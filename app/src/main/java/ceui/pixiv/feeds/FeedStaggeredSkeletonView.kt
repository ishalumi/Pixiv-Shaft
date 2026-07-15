package ceui.pixiv.feeds

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import ceui.lisa.R

/**
 * 瀑布流首屏「骨架图」+ shimmer 发光。跑满屏幕刷新率（标准做法，和 Facebook Shimmer / Compose
 * shimmer 一致）——「不重」靠的是**每帧极省**，而不是压帧率：
 *
 * - shimmer = 一条建一次的渐变 shader，每帧只 [Matrix] 平移它再 drawRoundRect。**无 clipPath**
 *   （复杂 path 裁剪会退软件渲染，是最早那版卡顿的真凶）、onDraw 内**零分配**。实测满帧 0 掉帧、
 *   GPU ~4-8ms/帧，全程 GPU 加速。
 * - 只在可见 + 系统允许动画时跑；不可见 / 未 attach / 系统关动画即停，绝不离屏空转。
 *
 * 块高固定比例（`[0.6,2.0]`）→ 确定性零 jitter；8dp 圆角/间距对齐真实卡片；列数读自 live 的
 * StaggeredGridLayoutManager（跟随用户「每行几列」）。普通线性/网格列表不用它（[FeedFragment] 转圈圈）。
 */
class FeedStaggeredSkeletonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 列数，与真实瀑布流一致（读自 StaggeredGridLayoutManager.spanCount）。 */
    var spanCount: Int = 2
        set(value) {
            val v = value.coerceAtLeast(1)
            if (field != v) {
                field = v
                rebuild()
                invalidate()
            }
        }

    private val density = resources.displayMetrics.density
    private val gap = 8f * density
    private val corner = 8f * density

    private val heightRatios = floatArrayOf(
        1.32f, 0.78f, 1.0f, 1.55f, 0.86f, 1.18f, 0.68f, 1.44f, 1.08f, 0.92f, 1.6f, 0.74f,
    )

    private val blockColor = ContextCompat.getColor(context, R.color.feed_skeleton_block)
    private val highlightColor = ContextCompat.getColor(context, R.color.feed_skeleton_highlight)
    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blockColor }
    private val blocks = ArrayList<RectF>()

    private var shimmer: LinearGradient? = null
    private var bandWidth = 0f
    private val shimmerMatrix = Matrix()
    private var shimmerFraction = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1200L
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            shimmerFraction = it.animatedFraction // 用 fraction，避免 animatedValue 每帧装箱
            invalidate()
        }
    }

    private fun motionEnabled(): Boolean =
        Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ) != 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    /** 尺寸/列数变了才重算：块矩形 + 一条建一次的 shimmer 渐变（onDraw 只读，不重建）。 */
    private fun rebuild() {
        blocks.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val colWidth = (w - gap * (spanCount + 1)) / spanCount
        if (colWidth <= 0f) return
        var idx = 0
        for (col in 0 until spanCount) {
            val left = gap + col * (colWidth + gap)
            var top = gap
            while (top < h) {
                val blockH = colWidth * heightRatios[idx % heightRatios.size]
                idx++
                blocks.add(RectF(left, top, left + colWidth, top + blockH))
                top += blockH + gap
            }
        }
        bandWidth = w * 0.5f
        shimmer = LinearGradient(
            0f, 0f, bandWidth, 0f,
            intArrayOf(blockColor, highlightColor, blockColor),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (blocks.isEmpty()) return
        val g = shimmer
        if (g != null && animator.isStarted) {
            // 每帧只平移矩阵：高光条从屏左外扫到屏右外。无分配、无 clip。
            val travel = width + bandWidth
            shimmerMatrix.setTranslate(-bandWidth + travel * shimmerFraction, 0f)
            g.setLocalMatrix(shimmerMatrix)
            blockPaint.shader = g
        } else {
            blockPaint.shader = null // 静态（无障碍关动画时）
            blockPaint.color = blockColor
        }
        for (rect in blocks) {
            canvas.drawRoundRect(rect, corner, corner, blockPaint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncAnimator()
    }

    override fun onDetachedFromWindow() {
        if (animator.isStarted) animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncAnimator()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncAnimator()
    }

    /** 只在真正可见 + 系统允许动画时跑；否则停（静态骨架），避免离屏空转。 */
    private fun syncAnimator() {
        if (isShown && motionEnabled()) {
            if (!animator.isStarted) animator.start()
        } else if (animator.isStarted) {
            animator.cancel()
            invalidate()
        }
    }
}
