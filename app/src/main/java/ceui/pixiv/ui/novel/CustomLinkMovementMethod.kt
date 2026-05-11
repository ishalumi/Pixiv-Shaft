package ceui.pixiv.ui.novel

import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.view.MotionEvent
import android.widget.TextView
import timber.log.Timber
import kotlin.math.abs

class CustomLinkMovementMethod(private val onLinkClick: (String) -> Unit) : LinkMovementMethod() {

    private var isSliding = false
    private var startX = 0f
    private var startY = 0f
    private var pendingLink: String? = null

    // 标记最近一次按下是否落在链接上。供 OnClickListener 判断要不要触发复制等默认动作。
    // Why: TextView.onTouchEvent 先调用 super.onTouchEvent（在 ACTION_UP 时触发 performClick），
    // 再走 movementMethod，因此 OnClickListener 总是先于这里的回调执行。
    var wasLinkClicked: Boolean = false
        private set

    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = x
                startY = y
                isSliding = false
                pendingLink = findLinkAt(widget, buffer, x, y)
                wasLinkClicked = pendingLink != null
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = abs(x - startX)
                val deltaY = abs(y - startY)
                if (deltaX > 20 || deltaY > 20) {
                    isSliding = true
                    pendingLink = null
                    wasLinkClicked = false
                }
            }
            MotionEvent.ACTION_UP -> {
                // super.onTouchEvent 已在此之前完成 performClick，OnClickListener 这一刻已经读过
                // wasLinkClicked。在这里清掉，避免后续无 DOWN 的 performClick（如无障碍服务）读到残留状态。
                wasLinkClicked = false
                if (!isSliding) {
                    val link = pendingLink ?: findLinkAt(widget, buffer, x, y)
                    if (link != null) {
                        onLinkClick(link)
                        Timber.d("Link clicked: $link")
                    }
                }
                pendingLink = null
            }
            MotionEvent.ACTION_CANCEL -> {
                pendingLink = null
                wasLinkClicked = false
            }
        }

        return true
    }

    private fun findLinkAt(widget: TextView, buffer: Spannable, x: Float, y: Float): String? {
        val layout = widget.layout ?: return null
        val line = layout.getLineForVertical(y.toInt())
        val offset = layout.getOffsetForHorizontal(line, x)
        val spans = buffer.getSpans(offset, offset, URLSpan::class.java)
        return spans.firstOrNull()?.url
    }
}
