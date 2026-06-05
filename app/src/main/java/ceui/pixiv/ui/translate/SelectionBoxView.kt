package ceui.pixiv.ui.translate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 「圈选翻译」的橡皮筋框选层 —— 全屏透明 View,捕获单次拖拽画出一个矩形,松手把
 * **View 坐标系**下的 [RectF] 通过 [onSelected] 回吐给宿主([FragmentImageDetail]
 * 再换算到内容坐标)。本 View 不碰 zoomimage / 坐标换算,只管「画框 + 报框」。
 *
 * 交互约定:
 * - 只在 overlay 可见时挂在图片之上,把触摸从 ZoomImage / ViewPager 手里接走,
 *   所以正常翻页/缩放完全不受影响(overlay GONE 时不拦截任何事件)。
 * - DOWN 即 requestDisallowInterceptTouchEvent,防止横向拖拽被 ViewPager 当翻页吞掉。
 * - 拖动距离两个方向都小于 [minDragPx](≈一次误触)→ 视作取消,走 [onCancelled],
 *   宿主借此「点一下空白退出圈选」。
 */
class SelectionBoxView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 松手且框够大时回调,参数是 View 坐标系下已规整(left<right, top<bottom)的矩形。 */
    var onSelected: ((RectF) -> Unit)? = null

    /** 框太小(误触)或事件被取消时回调,宿主用来退出圈选模式。 */
    var onCancelled: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val minDragPx = 24f * density

    private var startX = 0f
    private var startY = 0f
    private var curX = 0f
    private var curY = 0f
    private var dragging = false

    private val dimPaint = Paint().apply { color = 0x99000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        pathEffect = DashPathEffect(floatArrayOf(10f * density, 6f * density), 0f)
    }

    /** 进入圈选模式前清空上一次的残留框,避免复用时闪一下旧矩形。 */
    fun reset() {
        dragging = false
        startX = 0f; startY = 0f; curX = 0f; curY = 0f
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 抢在 ViewPager / ZoomImage 之前锁住手势,否则横向拖拽会被当成翻页
                parent?.requestDisallowInterceptTouchEvent(true)
                startX = event.x; startY = event.y
                curX = event.x; curY = event.y
                dragging = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                curX = event.x; curY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) return false
                dragging = false
                curX = event.x; curY = event.y
                val rect = currentRect()
                parent?.requestDisallowInterceptTouchEvent(false)
                if (rect.width() >= minDragPx || rect.height() >= minDragPx) {
                    onSelected?.invoke(rect)
                } else {
                    onCancelled?.invoke()
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onCancelled?.invoke()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun currentRect(): RectF = RectF(
        min(startX, curX), min(startY, curY),
        max(startX, curX), max(startY, curY),
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 没在拖拽 → 完全透明,让用户看清要圈的内容
        if (!dragging) return
        // onDraw 每帧都跑,直接算出四边、不 new RectF,避免拖拽时每帧分配
        val left = min(startX, curX)
        val top = min(startY, curY)
        val right = max(startX, curX)
        val bottom = max(startY, curY)
        if (right - left < 1f && bottom - top < 1f) return
        val w = width.toFloat()
        val h = height.toFloat()
        // 框外四条带变暗,框内保持清晰
        canvas.drawRect(0f, 0f, w, top, dimPaint)
        canvas.drawRect(0f, bottom, w, h, dimPaint)
        canvas.drawRect(0f, top, left, bottom, dimPaint)
        canvas.drawRect(right, top, w, bottom, dimPaint)
        canvas.drawRect(left, top, right, bottom, borderPaint)
    }
}
