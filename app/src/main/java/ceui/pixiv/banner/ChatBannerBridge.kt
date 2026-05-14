package ceui.pixiv.banner

import ceui.pixiv.chat.api.ChatFrame
import ceui.pixiv.chat.api.ChatFrameDecoder
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
 * Behaviour:
 *  - Filters out the user's own echo (`uid == SessionManager.loggedInUid`).
 *  - Uses `Replace` policy keyed by `dedupKey = "chat-<room>"` so a newer
 *    message in the same conversation supersedes the older banner instead
 *    of stacking.
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
        val body = msg.text?.takeIf { it.isNotBlank() }
            ?: msg.illustId?.let { "[illust:$it]" }
            ?: return null
        val sender = msg.displayName?.takeIf { it.isNotBlank() } ?: "uid ${msg.uid}"
        // 1v1 room id is a hashed pair → cannot reverse to peer uid. But the
        // sender (msg.uid, already filtered against self) IS the peer for 1v1,
        // so encode that directly. Global rooms drop the peer param.
        val deepLink = if (msg.room == "global") {
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

    companion object {
        private const val TAG = "Chat-Banner-Bridge"
    }
}
