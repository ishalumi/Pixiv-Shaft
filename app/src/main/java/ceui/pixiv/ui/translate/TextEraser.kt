package ceui.pixiv.ui.translate

import android.graphics.Bitmap
import android.graphics.Color
import ceui.pixiv.ui.upscale.OcrTextRegion
import timber.log.Timber
import kotlin.math.abs

/**
 * Erases text from manga pages.
 *
 * 两条路径,**首选 [textMask] 路径**(CTD 模型直接吐的 text segmentation mask):
 *  - mask 路径:对每个 region AABB,在 mask 上读「这个像素是不是文字」,膨胀 1 次 → 覆盖背景色。
 *    擦除形状跟模型的 glyph segmentation 一致,贴字符轮廓不出方块。
 *  - fallback 路径(mask 为 null / 尺寸对不上):退回到颜色阈值,同 region 内
 *    与采样背景色单通道最大差 > [INK_THRESHOLD] 判为墨。粗糙但不至于崩。
 *
 * 两条路径都用同一个 [sampleBackgroundColor] 拿背景色作填色,差别只在「哪些像素被判为字」。
 */
object TextEraser {

    /** AABB 略外扩,兜底 region 框比 glyph 略小的情况。气泡内部留白通常 > 这个值,不会碰到黑边。 */
    private const val AABB_PAD_PX = 2

    /** fallback 颜色阈值。manga 黑字白底差 ~255,留宽容防底色噪点误判。 */
    private const val INK_THRESHOLD = 64

    /** 墨 mask 8 邻域膨胀次数,吃 glyph 抗锯齿灰边;>=2 会开始把相邻笔画粘成块,1 比较稳。 */
    private const val DILATE_PX = 1

    /**
     * Erase text from the original bitmap.
     *
     * @param textMask CTD 给的像素级文本 mask,必须跟 [original] 同 width/height;
     *  非 null 时走 mask 路径,null 时退回颜色阈值。调用方负责对齐校验。
     */
    fun eraseText(original: Bitmap, regions: List<OcrTextRegion>, textMask: TextMask? = null): Bitmap {
        val result = original.copy(Bitmap.Config.ARGB_8888, true)
        val maskOk = textMask != null &&
            textMask.width == original.width && textMask.height == original.height
        Timber.d(
            "TextEraser: bitmap %dx%d, %d regions, mode=%s",
            original.width, original.height, regions.size,
            if (maskOk) "mask" else "threshold-fallback"
        )
        for ((i, region) in regions.withIndex()) {
            val bgColor = sampleBackgroundColor(original, region)
            val erased = if (maskOk) {
                eraseRegionByMask(result, region, bgColor, textMask!!)
            } else {
                eraseRegionByThreshold(result, region, bgColor)
            }
            if (i < 2) {
                val xs = region.corners.map { it.first }
                val ys = region.corners.map { it.second }
                Timber.d(
                    "TextEraser: region[%d] AABB=[%.0f,%.0f,%.0f,%.0f] bg=#%06X ink=%d px",
                    i, xs.min(), ys.min(), xs.max(), ys.max(), bgColor and 0xFFFFFF, erased
                )
            }
        }
        return result
    }

    /**
     * Mask 路径:模型 mask 已经是 glyph segmentation,这里只做 AABB 裁剪 + 1 次膨胀。
     * 返回擦除的像素数。
     */
    private fun eraseRegionByMask(
        bmp: Bitmap, region: OcrTextRegion, bgColor: Int, textMask: TextMask,
    ): Int {
        val aabb = regionAabb(region, bmp.width, bmp.height) ?: return 0
        val x0 = aabb[0]; val y0 = aabb[1]; val x1 = aabb[2]; val y1 = aabb[3]
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        if (w <= 1 || h <= 1) return 0

        val maskData = textMask.data
        val maskW = textMask.width
        var local = BooleanArray(w * h)
        for (dy in 0 until h) {
            val srcRow = (y0 + dy) * maskW + x0
            val dstRow = dy * w
            for (dx in 0 until w) {
                if (maskData[srcRow + dx].toInt() != 0) local[dstRow + dx] = true
            }
        }
        repeat(DILATE_PX) { local = dilate8(local, w, h) }

        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, x0, y0, w, h)
        var painted = 0
        for (idx in local.indices) {
            if (local[idx]) { pixels[idx] = bgColor; painted++ }
        }
        bmp.setPixels(pixels, 0, w, x0, y0, w, h)
        return painted
    }

    /**
     * Fallback 路径:在 AABB 内做 color threshold + dilate。质量比 mask 路径差,
     * 只在 CTD 没吐 mask / mask 尺寸对不上时启用。
     */
    private fun eraseRegionByThreshold(bmp: Bitmap, region: OcrTextRegion, bgColor: Int): Int {
        val aabb = regionAabb(region, bmp.width, bmp.height) ?: return 0
        val x0 = aabb[0]; val y0 = aabb[1]; val x1 = aabb[2]; val y1 = aabb[3]
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        if (w <= 1 || h <= 1) return 0

        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, x0, y0, w, h)

        val bgR = Color.red(bgColor)
        val bgG = Color.green(bgColor)
        val bgB = Color.blue(bgColor)

        var mask = BooleanArray(w * h)
        for (idx in pixels.indices) {
            val p = pixels[idx]
            val dr = abs(Color.red(p) - bgR)
            val dg = abs(Color.green(p) - bgG)
            val db = abs(Color.blue(p) - bgB)
            if (maxOf(dr, dg, db) > INK_THRESHOLD) mask[idx] = true
        }
        repeat(DILATE_PX) { mask = dilate8(mask, w, h) }

        var painted = 0
        for (idx in mask.indices) {
            if (mask[idx]) { pixels[idx] = bgColor; painted++ }
        }
        bmp.setPixels(pixels, 0, w, x0, y0, w, h)
        return painted
    }

    /**
     * 算 region 的 clamped AABB(已外扩 [AABB_PAD_PX])。corners 缺失或框面积 0 返回 null。
     */
    private fun regionAabb(region: OcrTextRegion, W: Int, H: Int): IntArray? {
        val corners = region.corners
        if (corners.size < 4) return null
        val xs = corners.map { it.first }
        val ys = corners.map { it.second }
        val x0 = (xs.min().toInt() - AABB_PAD_PX).coerceIn(0, W - 1)
        val y0 = (ys.min().toInt() - AABB_PAD_PX).coerceIn(0, H - 1)
        val x1 = (xs.max().toInt() + AABB_PAD_PX).coerceIn(0, W - 1)
        val y1 = (ys.max().toInt() + AABB_PAD_PX).coerceIn(0, H - 1)
        return intArrayOf(x0, y0, x1, y1)
    }

    /** 一遍 8 邻域膨胀,边界外当 false 处理。 */
    private fun dilate8(src: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            val rowStart = y * w
            for (x in 0 until w) {
                val idx = rowStart + x
                if (src[idx]) { out[idx] = true; continue }
                // 任一 8 邻居为 true → 自己变 true
                val yMin = if (y > 0) y - 1 else y
                val yMax = if (y < h - 1) y + 1 else y
                val xMin = if (x > 0) x - 1 else x
                val xMax = if (x < w - 1) x + 1 else x
                var hit = false
                outer@ for (yy in yMin..yMax) {
                    val rr = yy * w
                    for (xx in xMin..xMax) {
                        if (src[rr + xx]) { hit = true; break@outer }
                    }
                }
                if (hit) out[idx] = true
            }
        }
        return out
    }

    /**
     * Sample the dominant background color around a text region by reading pixels
     * along the border just outside the bounding box.
     *
     * For typical manga speech bubbles this returns white/light gray.
     *
     * `internal` 给 [BubbleAreaFinder] 复用 — 同一份采样逻辑保证擦除色和扩展判定色一致。
     */
    internal fun sampleBackgroundColor(bitmap: Bitmap, region: OcrTextRegion): Int {
        val corners = region.corners
        if (corners.size < 4) return Color.WHITE

        val xs = corners.map { it.first }
        val ys = corners.map { it.second }
        val minX = xs.min().toInt()
        val maxX = xs.max().toInt()
        val minY = ys.min().toInt()
        val maxY = ys.max().toInt()

        val sampleMargin = 6
        val w = bitmap.width
        val h = bitmap.height

        val pixels = mutableListOf<Int>()

        // Sample border pixels around the region (outside the text area)
        // Top edge
        val topY = (minY - sampleMargin).coerceIn(0, h - 1)
        for (x in minX..maxX step 2) {
            val sx = x.coerceIn(0, w - 1)
            pixels.add(bitmap.getPixel(sx, topY))
        }
        // Bottom edge
        val bottomY = (maxY + sampleMargin).coerceIn(0, h - 1)
        for (x in minX..maxX step 2) {
            val sx = x.coerceIn(0, w - 1)
            pixels.add(bitmap.getPixel(sx, bottomY))
        }
        // Left edge
        val leftX = (minX - sampleMargin).coerceIn(0, w - 1)
        for (y in minY..maxY step 2) {
            val sy = y.coerceIn(0, h - 1)
            pixels.add(bitmap.getPixel(leftX, sy))
        }
        // Right edge
        val rightX = (maxX + sampleMargin).coerceIn(0, w - 1)
        for (y in minY..maxY step 2) {
            val sy = y.coerceIn(0, h - 1)
            pixels.add(bitmap.getPixel(rightX, sy))
        }

        if (pixels.isEmpty()) return Color.WHITE

        // Find the most common color (mode) via simple bucketing
        val colorCounts = mutableMapOf<Int, Int>()
        for (pixel in pixels) {
            // Quantize to reduce noise: round each channel to nearest 8
            val quantized = quantizeColor(pixel)
            colorCounts[quantized] = (colorCounts[quantized] ?: 0) + 1
        }
        val dominantQuantized = colorCounts.maxByOrNull { it.value }?.key ?: Color.WHITE

        // Return the average of all pixels matching the dominant quantized color
        val matchingPixels = pixels.filter { quantizeColor(it) == dominantQuantized }
        return averageColor(matchingPixels)
    }

    private fun quantizeColor(color: Int): Int {
        val r = (Color.red(color) / 16) * 16
        val g = (Color.green(color) / 16) * 16
        val b = (Color.blue(color) / 16) * 16
        return Color.rgb(r, g, b)
    }

    private fun averageColor(pixels: List<Int>): Int {
        if (pixels.isEmpty()) return Color.WHITE
        var rSum = 0L; var gSum = 0L; var bSum = 0L
        for (p in pixels) {
            rSum += Color.red(p)
            gSum += Color.green(p)
            bSum += Color.blue(p)
        }
        val n = pixels.size
        return Color.rgb((rSum / n).toInt(), (gSum / n).toInt(), (bSum / n).toInt())
    }
}
