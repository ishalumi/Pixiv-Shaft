package ceui.pixiv.plaza.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.R
import ceui.lisa.network.ShaftApiV2Client
import ceui.lisa.network.PlazaPost
import ceui.lisa.network.PlazaResult
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
 * 避免空白闪烁),然后 launch GET /posts/:id 拉权威版本(可能补全 illust meta /
 * 修订 display_name 等)。
 *
 * 404 视为"已删除",显示 plaza_post_gone 占位。
 */
class PlazaPostDetailViewModel(private val postId: Long) : ViewModel() {

    data class UiState(
        val post: PlazaPost? = null,
        val isLoading: Boolean = false,
        val isGone: Boolean = false,
        val isDeleting: Boolean = false,
    )

    sealed class Event {
        data object DeletedAndClose : Event()
        data class Toast(val message: String) : Event()
    }

    private val _state = MutableStateFlow(UiState(post = ShaftApiV2Client.cachedPlazaPost(postId), isLoading = true))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events get() = _events.receiveAsFlow()

    init {
        viewModelScope.launch { refresh(initial = true) }
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
        when (val r = ShaftApiV2Client.getPlazaPost(postId)) {
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
                // ShaftApiV2Client 已经广播 plazaPostsDeleted,feed 自动 filter。这里只需 close detail。
                _events.trySend(Event.Toast(context.getString(R.string.plaza_post_deleted)))
                _events.trySend(Event.DeletedAndClose)
            } else if (r is PlazaResult.Err) {
                val msg = when (r.code) {
                    "rate_limited" -> {
                        val s = r.body?.retryAfterSeconds ?: 60
                        context.getString(R.string.plaza_rate_limited, s.toInt())
                    }
                    "ts_skew" -> context.getString(R.string.plaza_ts_skew)
                    "network" -> context.getString(R.string.chat_error_network_unavailable)
                    else -> r.code
                }
                _events.trySend(
                    Event.Toast(context.getString(R.string.plaza_post_delete_failed, msg))
                )
            }
        }
    }
}
