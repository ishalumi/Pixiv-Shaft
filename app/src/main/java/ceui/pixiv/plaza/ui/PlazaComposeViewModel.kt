package ceui.pixiv.plaza.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.R
import ceui.pixiv.plaza.api.PlazaClient
import ceui.pixiv.plaza.api.PlazaResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * 发帖编辑器 ViewModel。
 *
 * 状态:已附 illust id 列表 (max 9) + 提交进行中 flag。文本字段不放
 * VM 里 (EditText 自管),只在 submit 时从 Fragment 拿。
 *
 * 提交时按 spec 校验 trim 后非空 + ≤500 code points + illust ≤ 9。
 * server 端会做权威校验,本地这层只是早失败避开 round trip。
 */
class PlazaComposeViewModel : ViewModel() {

    data class UiState(
        val attachedIllusts: List<Long> = emptyList(),
        val isSending: Boolean = false,
    )

    sealed class Event {
        data object Sent : Event()
        data class Toast(val message: String) : Event()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events get() = _events.receiveAsFlow()

    fun attachIllust(id: Long) {
        if (id <= 0L) return
        if (_state.value.attachedIllusts.contains(id)) return
        if (_state.value.attachedIllusts.size >= 9) return
        _state.value = _state.value.copy(
            attachedIllusts = _state.value.attachedIllusts + id
        )
    }

    fun removeIllust(id: Long) {
        _state.value = _state.value.copy(
            attachedIllusts = _state.value.attachedIllusts.filter { it != id }
        )
    }

    fun submit(context: Context, text: String, selfUid: Long) {
        if (_state.value.isSending) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            _events.trySend(Event.Toast(context.getString(R.string.plaza_post_empty)))
            return
        }
        // server 用 code points 计长(spec §3),Kotlin String.length 是 UTF-16 units —
        // 大多数情况一致,补充字符 (emoji 等) 1 个 code point 但 String.length=2,
        // 这里也按 code point 算,跟 server 对齐避开 false positive。
        val codePoints = trimmed.codePointCount(0, trimmed.length)
        if (codePoints > 500) {
            _events.trySend(Event.Toast(context.getString(R.string.plaza_post_too_long)))
            return
        }

        _state.value = _state.value.copy(isSending = true)
        viewModelScope.launch {
            val r = PlazaClient.createPost(
                uid = selfUid,
                text = trimmed,
                illust = _state.value.attachedIllusts,
            )
            _state.value = _state.value.copy(isSending = false)
            when (r) {
                is PlazaResult.Ok -> {
                    _events.trySend(Event.Toast(context.getString(R.string.plaza_post_sent)))
                    _events.trySend(Event.Sent)
                }
                is PlazaResult.Err -> {
                    val msg = mapErrorMessage(context, r)
                    _events.trySend(
                        Event.Toast(context.getString(R.string.plaza_post_send_failed, msg))
                    )
                }
            }
        }
    }

    private fun mapErrorMessage(context: Context, err: PlazaResult.Err): String {
        return when (err.code) {
            "rate_limited" -> {
                val s = err.body?.retryAfterSeconds ?: 60
                context.getString(R.string.plaza_rate_limited, s.toInt())
            }
            "ts_skew" -> context.getString(R.string.plaza_ts_skew)
            "text_too_long" -> context.getString(R.string.plaza_post_too_long)
            "empty_text" -> context.getString(R.string.plaza_post_empty)
            "network" -> context.getString(R.string.chat_error_network_unavailable)
            else -> err.code
        }
    }
}
