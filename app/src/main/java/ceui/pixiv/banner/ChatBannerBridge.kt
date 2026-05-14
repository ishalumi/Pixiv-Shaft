package ceui.pixiv.banner

import ceui.lisa.activities.TemplateActivity
import ceui.pixiv.chat.api.ChatFrame
import ceui.pixiv.chat.api.ChatFrameDecoder
import ceui.pixiv.chat.api.ChatThreadId
import ceui.pixiv.chat.api.ShaftChatGateway
import ceui.pixiv.session.SessionManager
import ceui.pixiv.websocket.IncomingMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

/**
 * Bridges every inbound chat [ChatFrame.Msg] from [ShaftChatGateway.incoming]
 * into a [BannerRequest.Text] on the [BannerManager].
 *
 * Suppression rules:
 *  - User's own echo (`uid == SessionManager.loggedInUid`) — pointless to
 *    banner a message you just sent.
 *  - Foreground activity is already showing the same chat room — the user
 *    is reading the conversation, an overlay would be redundant and obscure
 *    the very content they want to see.
 *
 * Newer messages in the same room use `Replace` (dedupKey="chat-<room>") so
 * they supersede the previous banner instead of stacking.
 */
class ChatBannerBridge(
    private val bannerManager: BannerManager,
    private val scope: CoroutineScope,
) {

    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            ShaftChatGateway.incoming
                .filterIsInstance<IncomingMessage.Text>()
                .map { ChatFrameDecoder.decode(it.text) }
                .filterIsInstance<ChatFrame.Msg>()
                .mapNotNull { toBannerRequest(it) }
                .collect { bannerManager.enqueue(it) }
        }
        Timber.tag(TAG).i("ChatBannerBridge started")
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun toBannerRequest(msg: ChatFrame.Msg): BannerRequest.Text? {
        val selfUid = SessionManager.loggedInUid
        if (selfUid != 0L && msg.uid == selfUid) return null
        if (isViewingRoom(msg.room, selfUid)) {
            Timber.tag(TAG).d("suppress banner: foreground is room=%s", msg.room)
            return null
        }
        val body = msg.text?.takeIf { it.isNotBlank() }
            ?: msg.illustId?.let { "[illust:$it]" }
            ?: return null
        val sender = msg.displayName?.takeIf { it.isNotBlank() } ?: "uid ${msg.uid}"
        // 1v1 room id is a hashed pair → cannot reverse to peer uid. But the
        // sender (msg.uid, already filtered against self) IS the peer for 1v1,
        // so encode that directly. Global rooms drop the peer param.
        val deepLink = if (msg.room == ChatThreadId.ROOM_GLOBAL) {
            "shaft://chat?room=global"
        } else {
            "shaft://chat?peer=${msg.uid}"
        }
        return BannerRequest.Text(
            id = UUID.randomUUID().toString(),
            title = sender,
            message = body,
            dedupKey = "chat-${msg.room}",
            priority = BannerPriority.NORMAL,
            category = BannerCategory.Chat,
            policy = BannerDisplayPolicy.Replace,
            autoDismissMillis = 4000L,
            deepLink = deepLink,
            metadata = mapOf(
                "room" to msg.room,
                "uid" to msg.uid.toString(),
            ),
        )
    }

    /**
     * Is the user already looking at the chat room that produced [msgRoom]?
     *
     * The chat fragment lives inside [TemplateActivity] with
     * `EXTRA_FRAGMENT="聊天室"` (+ `EXTRA_CHAT_PEER_UID` for 1v1). Reconstruct
     * the active room id from those extras the same way `ChatListViewModel`
     * does — global is literal "global", 1v1 is
     * `ChatThreadId.oneOnOneThreadId(self, peer)`.
     */
    private fun isViewingRoom(msgRoom: String, selfUid: Long): Boolean {
        val activity = InAppBanners.currentActivity() ?: return false
        if (activity !is TemplateActivity) return false
        val intent = activity.intent ?: return false
        if (intent.getStringExtra(TemplateActivity.EXTRA_FRAGMENT) != "聊天室") return false
        val peerUid = intent.getLongExtra(TemplateActivity.EXTRA_CHAT_PEER_UID, 0L)
        val activeRoom = if (peerUid > 0L) {
            if (selfUid == 0L || selfUid == peerUid) return false
            runCatching { ChatThreadId.oneOnOneThreadId(selfUid, peerUid) }.getOrNull()
                ?: return false
        } else {
            ChatThreadId.ROOM_GLOBAL
        }
        return activeRoom == msgRoom
    }

    companion object {
        private const val TAG = "Chat-Banner-Bridge"
    }
}
