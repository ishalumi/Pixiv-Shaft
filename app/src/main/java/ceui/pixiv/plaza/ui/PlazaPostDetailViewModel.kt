package ceui.pixiv.plaza.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.R
import ceui.lisa.network.PlazaComment
import ceui.lisa.network.PlazaPost
import ceui.lisa.network.PlazaResult
import ceui.lisa.network.ShaftApiV2Client
import ceui.pixiv.session.SessionManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * 单帖详情 VM。
 *
 * 启动时 init 块先用 ShaftApiV2Client.cachedPlazaPost(id) 设给 state(从 feed 进来通常已缓存,
 * 避免空白闪烁),然后 launch GET /posts/:id 拉权威版本(同时带 viewer sig 拿
 * liked_by_viewer)。再 launch 第一页评论。
 *
 * 404 视为"已删除",显示 plaza_post_gone 占位。
 */
class PlazaPostDetailViewModel(private val postId: Long) : ViewModel() {

    data class UiState(
        val post: PlazaPost? = null,
        val isLoading: Boolean = false,
        val isGone: Boolean = false,
        val isDeleting: Boolean = false,
        // ── 评论 ───────────────────────────────────────────
        val comments: List<PlazaComment> = emptyList(),
        val commentsTotal: Int = 0,
        val commentsLoading: Boolean = false,
        val commentsNextBefore: Long? = null,
        val isSendingComment: Boolean = false,
        // ── 点赞 ───────────────────────────────────────────
        val isLiking: Boolean = false,
    )

    sealed class Event {
        data object DeletedAndClose : Event()
        data object CommentSent : Event()
        data class Toast(val message: String) : Event()
    }

    private val _state = MutableStateFlow(
        UiState(post = ShaftApiV2Client.cachedPlazaPost(postId), isLoading = true)
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events get() = _events.receiveAsFlow()

    init {
        viewModelScope.launch { refresh(initial = true) }
        viewModelScope.launch { loadCommentsInitial() }
        // 别处删了同一条帖子 -> 同步显示 gone(防止用户卡在已删除的 detail 页)。
        viewModelScope.launch {
            ShaftApiV2Client.plazaPostsDeleted.collect { deletedId ->
                if (deletedId == postId) {
                    _state.value = _state.value.copy(post = null, isGone = true, isLoading = false)
                }
            }
        }
    }

    private suspend fun refresh(initial: Boolean) {
        if (initial && _state.value.post == null) {
            _state.value = _state.value.copy(isLoading = true)
        }
        // 带 viewer sig 拿 liked_by_viewer。未登录 viewerUid=0 → server 返回不带这字段。
        val viewerUid = SessionManager.loggedInUid
        when (val r = ShaftApiV2Client.getPlazaPost(postId, viewerUid)) {
            is PlazaResult.Ok -> {
                _state.value = _state.value.copy(post = r.value, isLoading = false, isGone = false)
            }
            is PlazaResult.Err -> {
                if (r.code == "not_found" || r.httpStatus == 404) {
                    _state.value = _state.value.copy(post = null, isLoading = false, isGone = true)
                } else {
                    // 临时错保留 cache 版本不动,只关 loading。
                    _state.value = _state.value.copy(isLoading = false)
                }
            }
        }
    }

    // ── 点赞 ─────────────────────────────────────────────────────────

    /**
     * 切换点赞状态。乐观更新 UI(立刻翻转 + 调整 count),server 返回权威 count
     * 后再校正一次,失败时回滚到之前的状态。
     */
    fun toggleLike(context: Context) {
        val selfUid = SessionManager.loggedInUid
        if (selfUid <= 0L) {
            _events.trySend(Event.Toast(context.getString(R.string.plaza_login_required)))
            return
        }
        val current = _state.value.post ?: return
        if (_state.value.isLiking) return

        val wasLiked = current.liked_by_viewer == true
        val optimistic = current.copy(
            liked_by_viewer = !wasLiked,
            like_count = (current.like_count + if (wasLiked) -1 else 1).coerceAtLeast(0),
        )
        _state.value = _state.value.copy(post = optimistic, isLiking = true)

        viewModelScope.launch {
            val r = if (wasLiked) {
                ShaftApiV2Client.unlikePlazaPost(selfUid, postId)
            } else {
                ShaftApiV2Client.likePlazaPost(selfUid, postId)
            }
            when (r) {
                is PlazaResult.Ok -> {
                    // 用 server 返回的权威 count 校正。liked_by_viewer 维持乐观值
                    // (server 已经写入,跟乐观一致)。广播给 feed 让对应 cell 同步刷新。
                    _state.value.post?.let { p ->
                        val updated = p.copy(like_count = r.value.like_count)
                        _state.value = _state.value.copy(post = updated, isLiking = false)
                        ShaftApiV2Client.broadcastPostUpdated(updated)
                    } ?: run { _state.value = _state.value.copy(isLiking = false) }
                }
                is PlazaResult.Err -> {
                    // 回滚乐观更新
                    _state.value = _state.value.copy(post = current, isLiking = false)
                    val msg = mapErrMsg(context, r)
                    _events.trySend(Event.Toast(context.getString(R.string.plaza_like_failed, msg)))
                }
            }
        }
    }

    // ── 评论 ─────────────────────────────────────────────────────────

    private suspend fun loadCommentsInitial() {
        _state.value = _state.value.copy(commentsLoading = true)
        when (val r = ShaftApiV2Client.listPlazaComments(postId, limit = 20, before = null)) {
            is PlazaResult.Ok -> {
                _state.value = _state.value.copy(
                    comments = r.value.items,
                    commentsTotal = r.value.total,
                    commentsNextBefore = r.value.next_before,
                    commentsLoading = false,
                )
            }
            is PlazaResult.Err -> {
                _state.value = _state.value.copy(commentsLoading = false)
            }
        }
    }

    fun loadMoreComments() {
        val before = _state.value.commentsNextBefore ?: return
        if (_state.value.commentsLoading) return
        _state.value = _state.value.copy(commentsLoading = true)
        viewModelScope.launch {
            when (val r = ShaftApiV2Client.listPlazaComments(postId, limit = 20, before = before)) {
                is PlazaResult.Ok -> {
                    _state.value = _state.value.copy(
                        comments = _state.value.comments + r.value.items,
                        commentsTotal = r.value.total,
                        commentsNextBefore = r.value.next_before,
                        commentsLoading = false,
                    )
                }
                is PlazaResult.Err -> {
                    _state.value = _state.value.copy(commentsLoading = false)
                }
            }
        }
    }

    /**
     * 发评论。客户端做 trim + 300 cp 校验避免无谓 round trip,server 是权威。
     * 成功后插入到列表头(GET 是 ts desc,新的在前),并自增 total + post.comment_count。
     */
    fun postComment(context: Context, text: String) {
        val selfUid = SessionManager.loggedInUid
        if (selfUid <= 0L) {
            _events.trySend(Event.Toast(context.getString(R.string.plaza_login_required)))
            return
        }
        if (_state.value.isSendingComment) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            _events.trySend(Event.Toast(context.getString(R.string.plaza_post_empty)))
            return
        }
        val codePoints = trimmed.codePointCount(0, trimmed.length)
        if (codePoints > 300) {
            _events.trySend(Event.Toast(context.getString(R.string.plaza_comment_too_long)))
            return
        }
        _state.value = _state.value.copy(isSendingComment = true)
        viewModelScope.launch {
            val r = ShaftApiV2Client.createPlazaComment(selfUid, postId, trimmed)
            _state.value = _state.value.copy(isSendingComment = false)
            when (r) {
                is PlazaResult.Ok -> {
                    val newList = listOf(r.value) + _state.value.comments
                    val newPost = _state.value.post?.let {
                        it.copy(comment_count = it.comment_count + 1)
                    }
                    _state.value = _state.value.copy(
                        comments = newList,
                        commentsTotal = _state.value.commentsTotal + 1,
                        post = newPost,
                    )
                    // 同步给 feed 让 comment_count 跟着 +1。
                    newPost?.let { ShaftApiV2Client.broadcastPostUpdated(it) }
                    _events.trySend(Event.Toast(context.getString(R.string.plaza_comment_sent)))
                    _events.trySend(Event.CommentSent)
                }
                is PlazaResult.Err -> {
                    val msg = mapErrMsg(context, r)
                    _events.trySend(
                        Event.Toast(context.getString(R.string.plaza_comment_send_failed, msg))
                    )
                }
            }
        }
    }

    // ── 删帖 ─────────────────────────────────────────────────────────

    fun delete(context: Context, selfUid: Long) {
        val current = _state.value.post ?: return
        if (current.uid != selfUid) return
        if (_state.value.isDeleting) return
        _state.value = _state.value.copy(isDeleting = true)
        viewModelScope.launch {
            val r = ShaftApiV2Client.deletePlazaPost(selfUid, current.id)
            _state.value = _state.value.copy(isDeleting = false)
            val isGone = r is PlazaResult.Ok || (r is PlazaResult.Err && r.code == "not_found")
            if (isGone) {
                _events.trySend(Event.Toast(context.getString(R.string.plaza_post_deleted)))
                _events.trySend(Event.DeletedAndClose)
            } else if (r is PlazaResult.Err) {
                val msg = mapErrMsg(context, r)
                _events.trySend(
                    Event.Toast(context.getString(R.string.plaza_post_delete_failed, msg))
                )
            }
        }
    }

    private fun mapErrMsg(context: Context, err: PlazaResult.Err): String {
        return when (err.code) {
            "rate_limited" -> {
                val s = err.body?.retryAfterSeconds ?: 60
                context.getString(R.string.plaza_rate_limited, s.toInt())
            }
            "ts_skew" -> context.getString(R.string.plaza_ts_skew)
            "text_too_long" -> context.getString(R.string.plaza_comment_too_long)
            "empty_text" -> context.getString(R.string.plaza_post_empty)
            "network" -> context.getString(R.string.chat_error_network_unavailable)
            else -> err.code
        }
    }
}
