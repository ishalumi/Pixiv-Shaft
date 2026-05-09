package ceui.pixiv.ui.comments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ImageSpan
import android.util.LruCache
import ceui.lisa.utils.Emoji
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
        val matches = emojiPattern.findAll(raw).toList()
        if (matches.isEmpty()) return raw

        val spannable = SpannableString(raw)
        matches.forEach { match ->
            val asset = nameToAsset[match.groupValues[1]] ?: return@forEach
            val bitmap = loadBitmap(context, asset) ?: return@forEach
            // Wrap in a fresh BitmapDrawable per span: setBounds is per-instance state and
            // multiple spans must not share the same Drawable.
            val drawable = BitmapDrawable(context.resources, bitmap).apply {
                setBounds(0, 0, sizePx, sizePx)
            }
            spannable.setSpan(
                CenteredImageSpan(drawable),
                match.range.first,
                match.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return spannable
    }

    private fun loadBitmap(context: Context, asset: String): Bitmap? {
        bitmapCache.get(asset)?.let { return it }
        return try {
            context.assets.open(asset).use { BitmapFactory.decodeStream(it) }
        } catch (ex: Exception) {
            Timber.w(ex, "load comment emoji failed: %s", asset)
            null
        }?.also { bitmapCache.put(asset, it) }
    }

    private class CenteredImageSpan(private val centeredDrawable: Drawable) :
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
            return rect.right
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
