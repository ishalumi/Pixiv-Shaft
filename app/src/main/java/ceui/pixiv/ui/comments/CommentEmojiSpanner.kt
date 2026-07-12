package ceui.pixiv.ui.comments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ImageSpan
import android.util.LruCache
import ceui.lisa.utils.Emoji
import kotlin.math.roundToInt
import timber.log.Timber

object CommentEmojiSpanner {

    private val emojiPattern = Regex("""\(([a-z][a-z0-9]*)\)""")

    private val nameToAsset: Map<String, String> by lazy {
        Emoji.getEmojis().associate { item ->
            item.name.trim('(', ')') to item.resource
        }
    }

    private val bitmapCache = LruCache<String, Bitmap>(64)

    fun format(context: Context, raw: String?, sizePx: Int): CharSequence {
        if (raw.isNullOrEmpty()) return raw.orEmpty()
        val spannable = SpannableString(raw)
        applySpans(context, spannable, sizePx)
        return spannable
    }

    /**
     * 就地在可变 [Spannable] 上加表情图 span,不改文字内容——供输入框实时渲染场景复用
     * (评论列表/预览卡走上面 [format] 的只读快照,输入框需要在 Editable 上原地刷新)。
     * 调用方应先 [clearSpans] 摘掉旧 span 再调这个,否则残留的旧 span 会跟新的叠在一起。
     */
    fun applySpans(context: Context, editable: Spannable, sizePx: Int) {
        val text = editable.toString()
        if (text.isEmpty()) return
        val matches = emojiPattern.findAll(text)
        // 连续表情((heaven)(heaven)(heaven) 这类)贴脸挤在一起,每个 span 尾部补 2dp 空白间距。
        val gapPx = (2 * context.resources.displayMetrics.density).roundToInt()
        matches.forEach { match ->
            val asset = nameToAsset[match.groupValues[1]] ?: return@forEach
            val bitmap = loadBitmap(context, asset) ?: return@forEach
            // Wrap in a fresh BitmapDrawable per span: setBounds is per-instance state and
            // multiple spans must not share the same Drawable.
            val drawable = BitmapDrawable(context.resources, bitmap).apply {
                setBounds(0, 0, sizePx, sizePx)
            }
            editable.setSpan(
                CenteredImageSpan(drawable, gapPx),
                match.range.first,
                match.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    /** 摘掉之前由 [applySpans] 加的表情图 span,原地编辑(如输入框每次改字后)前必须先调这个。 */
    fun clearSpans(editable: Spannable) {
        editable.getSpans(0, editable.length, CenteredImageSpan::class.java).forEach {
            editable.removeSpan(it)
        }
    }

    /** 按 asset 文件名(如 "101.png")取表情位图,带 LruCache,供选择器面板复用。 */
    fun loadBitmap(context: Context, asset: String): Bitmap? {
        bitmapCache.get(asset)?.let { return it }
        return try {
            context.assets.open(asset).use { BitmapFactory.decodeStream(it) }
        } catch (ex: Exception) {
            Timber.w(ex, "load comment emoji failed: %s", asset)
            null
        }?.also { bitmapCache.put(asset, it) }
    }

    private class CenteredImageSpan(private val centeredDrawable: Drawable, private val trailingGapPx: Int) :
        ImageSpan(centeredDrawable) {

        override fun getSize(
            paint: Paint,
            text: CharSequence?,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?,
        ): Int {
            val rect = centeredDrawable.bounds
            if (fm != null) {
                val fontMetrics = paint.fontMetricsInt
                val fontHeight = fontMetrics.descent - fontMetrics.ascent
                val drHeight = rect.height()
                val centerY = fontMetrics.ascent + fontHeight / 2
                val half = drHeight / 2
                fm.ascent = centerY - half
                fm.top = fm.ascent
                fm.descent = centerY + (drHeight - half)
                fm.bottom = fm.descent
            }
            // 图本身仍按 rect 尺寸绘制(见 draw()),多出的 trailingGapPx 只撑宽 span 占位,
            // 视觉上表现为这个表情和下一个字符/表情之间的空白间距。
            return rect.right + trailingGapPx
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint,
        ) {
            val rect = centeredDrawable.bounds
            canvas.save()
            val transY = top + (bottom - top - rect.height()) / 2f
            canvas.translate(x, transY)
            centeredDrawable.draw(canvas)
            canvas.restore()
        }
    }
}
