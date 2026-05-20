package ceui.pixiv.ui.upscale

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import ceui.lisa.R
import ceui.pixiv.ui.translate.MangaOcrRecognizer
import kotlin.math.hypot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

data class OcrTextRegion(
    val text: String,
    val cx: Float,
    val cy: Float,
    val width: Float,
    val height: Float,
    val angle: Float,
    val orientation: Int, // 0=horizontal, 1=vertical
    val prob: Float, // paddle detection 置信(框是否真是文本框)
    val corners: List<Pair<Float, Float>>,
    val recogConfidence: Float = 1f, // manga-ocr 重识别置信(模型自己对识别多确定)
)

object MangaOcr {

    /**
     * Paddle 检测置信下限。这里放宽到 0.3 兜底极端噪声,真正的小细分由
     * [OcrTextRegion.isMeaningfulJapanese] 在重识别后基于 prob + 字符数做。
     */
    private const val MIN_DETECTION_PROB = 0.3f

    /**
     * OCR 图片短边目标上限(严格约束:`finalShort <= MAX`)。
     * 2400 兼顾内存(<= ~25MB ARGB_8888)和精度(给 paddle 内部 -s 960 留 2.5x 余量)。
     */
    private const val MAX_INPUT_SHORT_SIDE = 2400

    /**
     * region 短边动态门槛:按输入图短边的 0.8% 估算"合理小字",下限 8 px。
     * 1920 → 15.4px, 960 → 7.7px(clamp 8), 4000 → 32px。
     */
    private fun minRegionShortSide(imageShortEdge: Int): Float =
        maxOf(imageShortEdge * 0.008f, 8f)

    /** 省略号 + 句末符号 + 括号引号,不计入"实际字符"。括号常被 manga-ocr 在噪声 crop 上幻读。 */
    private val PUNCTUATION_TO_IGNORE = setOf(
        '.', '…', '。', ',', '、', ' ', '\n', '\t',
        '!', '！', '?', '？', '~', '〜', '・',
        '〈', '〉', '《', '》', '「', '」', '『', '』', '【', '】',
        '(', ')', '（', '）', '[', ']', '［', '］',
        '"', '\'', '‘', '’', '“', '”',
    )

    /**
     * sub-region 级 manga-ocr 置信度门槛。
     * 真文字典型 0.6-0.95;模型在装饰/反光/小图标上 hallucinate 通常 < 0.3。
     * pre-merge 用这个 filter 把噪声 sub-region 剔除,避免它被 merge 进相邻真气泡。
     */
    private const val MIN_RECOG_CONFIDENCE = 0.3f

    /**
     * manga-ocr 在真气泡识别完后,常在末尾多吐一个括号字 hallucinate(实测「フォルネウス王子〉」「ヴァネッサ様!?〈」)。
     * 这些字符不会作为合法日文句末出现,**recognize 完直接 trim**。
     * 注意:不能 trim 全部 punctuation,!? 是合法句末。只 trim 真的不该出现的尾巴字符。
     */
    private val TRAILING_NOISE_CHARS = setOf(
        '〈', '〉', '《', '》', '「', '」', '『', '』', '【', '】',
        '(', ')', '（', '）', '[', ']', '［', '］', '〔', '〕',
        '"', '\'', '‘', '’', '“', '”',
    )

    /**
     * Recognize text in a manga page.
     *
     * PaddleOCR 仅用于检测文本框;最终文本一律由 manga-ocr (ViT+GPT2) 重识别产出。
     * 调用前必须 [MangaOcrRecognizer.loadModel],否则直接返回 null。
     *
     * Progress 阶段: "检测文本框" 0→0.3,"识别 i/N" 0.3→1.0。
     */
    suspend fun recognize(
        context: Context,
        inputFile: File,
        onProgress: ((stage: String, fraction: Float) -> Unit)? = null
    ): List<OcrTextRegion>? = withContext(Dispatchers.IO) {
        if (!MangaOcrRecognizer.isLoaded) {
            Timber.e("MangaOcr: manga-ocr model not loaded, refuse to fall back to PaddleOCR")
            return@withContext null
        }
        var bitmap: Bitmap? = null
        var pngInput: File? = null
        var process: Process? = null
        try {
            val modelDir = ensureModelFiles(context)
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val executablePath = "$nativeDir/libocr_ncnn.so"

            // 先 inJustDecodeBounds 测原图,大图通过 inSampleSize 降采样防 OOM
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(inputFile.absolutePath, bounds)
            val origShort = minOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            var sample = 1
            while (origShort / sample > MAX_INPUT_SHORT_SIDE) sample *= 2

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            bitmap = BitmapFactory.decodeFile(inputFile.absolutePath, opts)
            if (bitmap == null) {
                Timber.e("MangaOcr: failed to decode input")
                return@withContext null
            }
            Timber.d("MangaOcr: orig ${bounds.outWidth}x${bounds.outHeight} sample=$sample → ${bitmap!!.width}x${bitmap!!.height}")

            pngInput = File(context.cacheDir, "ocr_input_${System.currentTimeMillis()}.png")
            FileOutputStream(pngInput).use { out ->
                bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            val pb = ProcessBuilder(
                executablePath,
                "-i", pngInput!!.absolutePath,
                "-m", modelDir.absolutePath,
                "-g", "0",
                "-s", "960"
            )
            pb.environment()["LD_LIBRARY_PATH"] = nativeDir
            pb.redirectErrorStream(false)

            process = pb.start()

            // 检测阶段没有可靠百分比 → 用 NaN 通知 caller 切 indeterminate ring
            onProgress?.invoke(context.getString(R.string.string_ai_ocr_detecting), Float.NaN)
            val stderrThread = Thread {
                process!!.errorStream.bufferedReader().forEachLine { line ->
                    Timber.d("MangaOcr: $line")
                }
            }
            stderrThread.start()

            val jsonOutput = process!!.inputStream.bufferedReader().readText()
            val exitCode = process!!.waitFor()
            stderrThread.join()

            Timber.d("MangaOcr exit=$exitCode, output=${jsonOutput.take(200)}")

            if (exitCode != 0 || jsonOutput.isBlank()) {
                Timber.e("MangaOcr failed: exit=$exitCode")
                return@withContext null
            }

            val arr = JSONArray(jsonOutput)
            val rawRegions = mutableListOf<OcrTextRegion>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val cornersArr = obj.getJSONArray("corners")
                val corners = (0 until cornersArr.length()).map { j ->
                    val pt = cornersArr.getJSONArray(j)
                    Pair(pt.getDouble(0).toFloat(), pt.getDouble(1).toFloat())
                }
                rawRegions.add(OcrTextRegion(
                    text = obj.getString("text"),
                    cx = obj.getDouble("cx").toFloat(),
                    cy = obj.getDouble("cy").toFloat(),
                    width = obj.getDouble("w").toFloat(),
                    height = obj.getDouble("h").toFloat(),
                    angle = obj.getDouble("angle").toFloat(),
                    orientation = obj.getInt("orientation"),
                    prob = obj.getDouble("prob").toFloat(),
                    corners = corners
                ))
            }

            val minShort = minRegionShortSide(minOf(bitmap!!.width, bitmap!!.height))
            val viableRegions = rawRegions.filter { r ->
                val short = minOf(r.width, r.height)
                // paddle 自己识别成单 latin/digit 字符 = dict 完全没匹配 = 不是日文文字。
                // 不送 manga-ocr,避免它在小 crop 上 hallucinate 高 conf 单字(c→し / 7→ひ / 0→、)
                val isPaddleSingleNonJapanese = r.text.length == 1 && r.text[0].let {
                    it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9'
                }
                r.prob >= MIN_DETECTION_PROB && short >= minShort && !isPaddleSingleNonJapanese
            }
            Timber.d(
                "MangaOcr: detection ${rawRegions.size} → viable ${viableRegions.size} " +
                    "(prob>=$MIN_DETECTION_PROB, short>=$minShort, drop single-latin); raw probs=${rawRegions.map { "%.2f".format(it.prob) }}"
            )

            val total = viableRegions.size
            val enhanced = viableRegions.mapIndexedNotNull { idx, region ->
                onProgress?.invoke(
                    context.getString(R.string.string_ai_ocr_recognizing, idx + 1, total),
                    idx.toFloat() / total.coerceAtLeast(1)
                )
                var cropped: Bitmap? = null
                try {
                    cropped = cropRegion(bitmap!!, region)
                    val result = MangaOcrRecognizer.recognize(cropped)
                    // B: trim 尾部噪声括号(manga-ocr 在真气泡末尾常 hallucinate 单括号字)
                    val trimmed = result.text.trimEnd { it in TRAILING_NOISE_CHARS }
                    Timber.d(
                        "MangaOcr: [${region.text}] → [${result.text}]" +
                            (if (trimmed != result.text) " trimmed→[$trimmed]" else "") +
                            " conf=%.2f".format(result.confidence)
                    )
                    if (trimmed.isBlank()) null
                    else region.copy(text = trimmed, recogConfidence = result.confidence)
                } catch (e: Exception) {
                    Timber.e(e, "MangaOcr: manga-ocr failed for region, dropping")
                    null
                } finally {
                    cropped?.recycle()
                }
            }
            onProgress?.invoke(context.getString(R.string.string_ai_ocr_done), 1f)

            // C3: pre-merge 噪声过滤 — 低置信 sub-region 必须不能混进 merge,
            // 否则被夹在合法气泡中间(「ヴァネッサ様!?〈どうなされました!?」的「〈」就是这么来的)
            val confident = enhanced.filter { it.recogConfidence >= MIN_RECOG_CONFIDENCE }
            Timber.d("MangaOcr: ${enhanced.size} → ${confident.size} after recog-confidence filter (>=$MIN_RECOG_CONFIDENCE)")
            groupRegions(confident)
        } catch (e: Exception) {
            Timber.e(e, "MangaOcr error")
            null
        } finally {
            // CancellationException / 异常路径都走这,native 资源必清
            bitmap?.recycle()
            pngInput?.delete()
            process?.destroyForcibly()
        }
    }

    /** 输入 region 角点向外扩张比例(取真实气泡背景,不再用纯白 padding)。 */
    private const val CROP_EXPAND = 0.08f

    /**
     * 透视校正裁切 — 把 PaddleOCR 的旋转矩形拉回水平/竖直矩形。
     * 对应上游 [Quadrilateral.get_transformed_region] 的 cv2.findHomography + cv2.warpPerspective。
     *
     * 关键设计:
     *   1. 角点先 reorder 成几何 TL→TR→BR→BL,防止 paddle 对竖排按阅读方向输出 → warp 旋转 90° 错位
     *   2. 向外扩张 8% 取真实像素 padding(气泡背景),而不是 drawColor 白边 — 跟训练分布一致
     *   3. setPolyToPoly 返回 false(退化角点)时 fallback 到 AABB
     *   4. fallback 路径也加 4% padding,行为一致
     *   5. 不旋转 — manga-ocr 原生支持竖排日文,旋转 90° 反而跌精度
     */
    private fun cropRegion(bitmap: Bitmap, region: OcrTextRegion): Bitmap {
        val corners = region.corners
        if (corners.size < 4) return aabbCrop(bitmap, region)

        // #5: 强制几何 TL→TR→BR→BL 重排
        val canon = canonicalQuad(corners) ?: return aabbCrop(bitmap, region)

        // #2: 向外扩张取真实气泡上下文
        val (cxAvg, cyAvg) = canon.fold(0f to 0f) { acc, p -> acc.first + p.first to acc.second + p.second }
            .let { it.first / 4f to it.second / 4f }
        val expanded = canon.map { (x, y) ->
            val ex = (cxAvg + (x - cxAvg) * (1f + CROP_EXPAND)).coerceIn(0f, bitmap.width.toFloat())
            val ey = (cyAvg + (y - cyAvg) * (1f + CROP_EXPAND)).coerceIn(0f, bitmap.height.toFloat())
            ex to ey
        }
        val (ex0, ey0) = expanded[0]
        val (ex1, ey1) = expanded[1]
        val (ex2, ey2) = expanded[2]
        val (ex3, ey3) = expanded[3]

        val w = ((hypot(ex1 - ex0, ey1 - ey0) + hypot(ex2 - ex3, ey2 - ey3)) / 2f).toInt().coerceAtLeast(2)
        val h = ((hypot(ex3 - ex0, ey3 - ey0) + hypot(ex2 - ex1, ey2 - ey1)) / 2f).toInt().coerceAtLeast(2)

        val src = floatArrayOf(ex0, ey0, ex1, ey1, ex2, ey2, ex3, ey3)
        val dst = floatArrayOf(
            0f, 0f,
            w.toFloat(), 0f,
            w.toFloat(), h.toFloat(),
            0f, h.toFloat(),
        )

        val matrix = Matrix()
        // #1: setPolyToPoly false → 退化矩形,fallback 防止生成垃圾 crop
        if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) {
            Timber.w("MangaOcr: degenerate quad for region cx=${region.cx},cy=${region.cy}, falling back to AABB")
            return aabbCrop(bitmap, region)
        }

        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return output
    }

    /**
     * #5 canonical TL→TR→BR→BL:
     *   TL = argmin(x+y), BR = argmax(x+y), TR = argmax(x-y), BL = argmin(x-y)
     * 这套规则对任意旋转角度的四边形都给出几何意义上的角,与 paddle 是否按"阅读方向"输出无关。
     * 退化(两点重合)时返回 null,caller 走 AABB fallback。
     */
    private fun canonicalQuad(corners: List<Pair<Float, Float>>): List<Pair<Float, Float>>? {
        if (corners.size < 4) return null
        val sums = corners.map { it.first + it.second }
        val diffs = corners.map { it.first - it.second }
        val tlIdx = sums.indices.minByOrNull { sums[it] } ?: return null
        val brIdx = sums.indices.maxByOrNull { sums[it] } ?: return null
        val trIdx = diffs.indices.maxByOrNull { diffs[it] } ?: return null
        val blIdx = diffs.indices.minByOrNull { diffs[it] } ?: return null
        // 四个 idx 必须互不相同;否则四边形退化
        if (setOf(tlIdx, trIdx, brIdx, blIdx).size != 4) return null
        return listOf(corners[tlIdx], corners[trIdx], corners[brIdx], corners[blIdx])
    }

    /** AABB fallback crop,带 4% padding(以短边为基准),跟主路径行为一致。 */
    private fun aabbCrop(bitmap: Bitmap, region: OcrTextRegion): Bitmap {
        val pad = (minOf(region.width, region.height) * 0.04f).toInt().coerceAtLeast(2)
        val left = (region.cx - region.width / 2f - pad).toInt().coerceIn(0, bitmap.width - 1)
        val top = (region.cy - region.height / 2f - pad).toInt().coerceIn(0, bitmap.height - 1)
        val right = (region.cx + region.width / 2f + pad).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (region.cy + region.height / 2f + pad).toInt().coerceIn(top + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    private fun ensureModelFiles(context: Context): File {
        val modelDir = File(context.filesDir, "ocr-models/ppocrv5")
        val binFile = File(modelDir, "PP_OCRv5_mobile_det.ncnn.bin")
        if (binFile.exists()) return modelDir

        modelDir.mkdirs()
        val files = listOf(
            "PP_OCRv5_mobile_det.ncnn.param",
            "PP_OCRv5_mobile_det.ncnn.bin",
            "PP_OCRv5_mobile_rec.ncnn.param",
            "PP_OCRv5_mobile_rec.ncnn.bin"
        )
        for (name in files) {
            context.assets.open("models/ppocrv5/$name").use { input ->
                FileOutputStream(File(modelDir, name)).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return modelDir
    }

    /**
     * 合并 PaddleOCR 检测框为 region,算法委托 [MangaTextlineMerge] (上游 manga-image-translator)。
     * 这里只负责把上游返回的下标列表组装回 OcrTextRegion + 同 region 内文本拼接 + 噪声过滤。
     */
    private fun groupRegions(regions: List<OcrTextRegion>): List<OcrTextRegion> {
        if (regions.isEmpty()) return regions
        val grouped = MangaTextlineMerge.merge(regions)
        return grouped.map { indices ->
            val sorted = indices.map { regions[it] }
            val combinedText = sorted.joinToString("") { it.text }
            val first = sorted.first()
            val allCorners = sorted.flatMap { it.corners }
            val minX = allCorners.minOf { it.first }
            val minY = allCorners.minOf { it.second }
            val maxX = allCorners.maxOf { it.first }
            val maxY = allCorners.maxOf { it.second }
            val majorityOrientation = sorted.groupBy { it.orientation }
                .maxByOrNull { it.value.size }?.key ?: first.orientation
            OcrTextRegion(
                text = combinedText,
                cx = (minX + maxX) / 2,
                cy = (minY + maxY) / 2,
                width = maxX - minX,
                height = maxY - minY,
                angle = first.angle,
                orientation = majorityOrientation,
                prob = sorted.map { it.prob }.average().toFloat(),
                corners = listOf(
                    Pair(minX, minY), Pair(maxX, minY),
                    Pair(maxX, maxY), Pair(minX, maxY)
                ),
                recogConfidence = sorted.map { it.recogConfidence }.average().toFloat(),
            )
        }
            .filter { it.isMeaningfulJapanese() }
            .let { mangaReadingOrder(it) }
    }

    /**
     * 合法日文 region 判据:
     *   - 含至少一个日文/汉字字符
     *   - 去掉省略号/括号/标点后,实际字符数 >= 2
     *     或 == 1 且 (paddle 检测 prob >= 0.85 **且** manga-ocr 自身 conf >= 0.85)
     *
     * 单字门槛复合两个置信:实测 manga-ocr 在小噪声 crop 上会输出 0.8+ 高 conf 单字「し」「ひ」,
     * 仅靠 recogConfidence 拦不住。配合 paddle prob 双重把关:真单字气泡两个 prob 都 >= 0.85,
     * 幽灵 hallucinate 至少一个偏低。
     */
    private fun OcrTextRegion.isMeaningfulJapanese(): Boolean {
        val coreChars = text.count { it !in PUNCTUATION_TO_IGNORE }
        if (coreChars == 0) return false
        val hasJa = text.any { ch ->
            val block = Character.UnicodeBlock.of(ch) ?: return@any false
            block === Character.UnicodeBlock.HIRAGANA ||
                block === Character.UnicodeBlock.KATAKANA ||
                block === Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS ||
                block === Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block === Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                block === Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
                block === Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
        }
        if (!hasJa) return false
        return coreChars >= 2 || (prob >= 0.85f && recogConfidence >= 0.85f)
    }

    /**
     * 阅读顺序:cy 升序贪心分 row(下一个 region 的 cy 落在当前 row 累计纵向区间内就并入,
     * 否则开新 row),每个 row 内按 cx 右→左。
     *
     * 不解决跨 panel 顺序(那需要 panel detection),但比对称 rowBand 阈值稳。
     */
    private fun mangaReadingOrder(regions: List<OcrTextRegion>): List<OcrTextRegion> {
        if (regions.size <= 1) return regions
        val byCy = regions.sortedBy { it.cy }
        val rows = mutableListOf<MutableList<OcrTextRegion>>()
        for (r in byCy) {
            val cur = rows.lastOrNull()
            if (cur == null) {
                rows += mutableListOf(r)
                continue
            }
            // row 的纵向区间 = 所有成员 [cy - h/2, cy + h/2] 的并集底部
            val rowBottom = cur.maxOf { it.cy + it.height / 2f }
            if (r.cy - r.height / 2f < rowBottom) cur += r
            else rows += mutableListOf(r)
        }
        return rows.flatMap { row -> row.sortedByDescending { it.cx } }
    }
}
