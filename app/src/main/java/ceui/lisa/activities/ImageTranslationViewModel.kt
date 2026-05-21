package ceui.lisa.activities

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.R
import ceui.lisa.utils.Common
import ceui.loxia.asLiveData
import ceui.pixiv.ui.translate.BubbleAreaFinder
import ceui.pixiv.ui.translate.ComicTextDetector
import ceui.pixiv.ui.translate.ComicTextDetectorModel
import ceui.pixiv.ui.translate.GoogleWebTranslator
import ceui.pixiv.ui.translate.MangaOcrModel
import ceui.pixiv.ui.translate.MangaOcrRecognizer
import ceui.pixiv.ui.translate.TextEraser
import ceui.pixiv.ui.translate.TextMask
import ceui.pixiv.ui.translate.TextRenderer
import ceui.pixiv.ui.translate.promptProxyNeededIfPossible
import ceui.pixiv.ui.upscale.MangaOcr
import ceui.pixiv.ui.upscale.OcrTextRegion
import ceui.pixiv.ui.upscale.scaledBy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * 二级详情「翻译漫画」一站式 pipeline:OCR → Google batch 翻译 → 译文回填到原图气泡位置 →
 * 把产物图路径喂回 [translatedPaths],由 FragmentImageDetail 替换显示。
 *
 * 设计:
 * - [running] 防重入 — 同一时间只跑一个 pipeline,UI 看着标志决定 toast 拦截
 * - [status] 单一来源驱动 overlay UI(文字 + 进度环);null 表示无任务,UI 隐藏 overlay
 * - [translatedPaths] pageIndex → 译图路径,Fragment 观察后切图
 * - 大图自动 downsample(短边 ≤ [MAX_RENDER_SHORT_SIDE])防 OOM,region 坐标等比缩放跟随
 * - 同一页二次翻译会把旧产物文件 delete 掉,避免 cacheDir 累积
 */
class ImageTranslationViewModel : ViewModel() {

    data class Status(
        val text: String,
        /** null = indeterminate 转圈,否则 0..100 */
        val progressPercent: Int? = null,
    )

    private val _running = MutableLiveData(false)
    val running: LiveData<Boolean> get() = _running.asLiveData()

    private val _status = MutableLiveData<Status?>(null)
    val status: LiveData<Status?> get() = _status.asLiveData()

    private val _translatedPaths = MutableLiveData<Map<Int, String>>(emptyMap())
    val translatedPaths: LiveData<Map<Int, String>> get() = _translatedPaths.asLiveData()

    /**
     * 启动 pipeline。已在跑就直接 return false,UI 自己决定要不要 toast。
     */
    fun start(
        context: Context,
        imageFile: File,
        pageIndex: Int,
        ocrModel: MangaOcrModel,
        ctdModel: ComicTextDetectorModel,
    ): Boolean {
        if (_running.value == true) return false
        _running.value = true
        val app = context.applicationContext
        viewModelScope.launch {
            try {
                runPipeline(app, imageFile, pageIndex, ocrModel, ctdModel)
            } catch (e: Exception) {
                Timber.e(e, "ImageTranslationVM: pipeline failed")
                Common.showToast(R.string.string_ai_manga_translate_failed)
            } finally {
                _status.postValue(null)
                _running.postValue(false)
            }
        }
        return true
    }

    private suspend fun runPipeline(
        app: Context,
        imageFile: File,
        pageIndex: Int,
        ocrModel: MangaOcrModel,
        ctdModel: ComicTextDetectorModel,
    ) {
        // 1. 模型按需加载
        if (!MangaOcrRecognizer.isLoaded || !ComicTextDetector.isLoaded) {
            _status.postValue(Status(app.getString(R.string.string_ai_ocr_loading_model)))
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    MangaOcrRecognizer.loadModel(app, ocrModel)
                    ComicTextDetector.loadModel(app, ctdModel)
                }.onFailure { Timber.e(it, "loadModel failed") }.isSuccess
            }
            if (!loaded) {
                Common.showToast(R.string.string_ai_ocr_failed)
                return
            }
        }

        // 2. OCR
        val ocrResult = MangaOcr.recognize(app, imageFile) { stage, fraction ->
            val pct = if (fraction.isNaN()) null else (fraction * 100).toInt().coerceIn(0, 100)
            _status.postValue(Status(stage, pct))
        }
        val regions = ocrResult?.regions
        if (regions.isNullOrEmpty()) {
            Common.showToast(
                if (ocrResult == null) R.string.string_ai_ocr_failed
                else R.string.string_ai_ocr_empty
            )
            return
        }

        // 3. Google batch 翻译 — 一次 POST 打包全部 region,中途没有有意义的进度,
        //    所以只 post 一个 indeterminate 状态盖住几秒 HTTP 等待,不再每 chunk 闪 N/N
        _status.postValue(Status(app.getString(R.string.ocr_translating)))
        val translations = mutableMapOf<Int, String>()
        try {
            GoogleWebTranslator.translateBatch(
                inputs = regions.map { it.text },
                outputLang = "zh-CN",
                onItem = { i, zh -> translations[i] = zh },
            )
        } catch (e: Exception) {
            // Google Translate 在国内被墙,大概率是代理没开;给个明确提示别让用户当 app bug
            Timber.e(e, "GoogleWebTranslator.translateBatch failed")
            promptProxyNeededIfPossible()
            return
        }
        if (translations.isEmpty()) {
            // batch 走完了但一条没回 — 多半也是代理半通不通(per-item fallback 全失败)
            promptProxyNeededIfPossible()
            return
        }

        // 4. 回填
        _status.postValue(Status(app.getString(R.string.ocr_writeback_running)))
        val outFile = withContext(Dispatchers.IO) {
            runCatching {
                renderTranslated(app, imageFile, pageIndex, regions, translations, ocrResult.textMask, ocrResult.ocrSample)
            }.onFailure { Timber.e(it, "renderTranslated failed") }.getOrNull()
        }
        if (outFile == null) {
            Common.showToast(R.string.string_ai_manga_translate_failed)
            return
        }

        // 5. 发布新译图路径,并清掉同 page 的旧产物
        publishTranslated(pageIndex, outFile.absolutePath)
    }

    /**
     * 解码原图(短边自动降采样到 [MAX_RENDER_SHORT_SIDE] 以内防 OOM),
     * 然后按降采样比例同步缩放 region 坐标系再喂给 TextEraser/TextRenderer。
     *
     * 入参 [regions] 必须是"原图坐标系"的(契约见 [OcrTextRegion]),所以这里把它们除以
     * 本次 decode 用的 sample 即可对齐到 bitmap 像素。
     *
     * [textMask] 是 OCR 阶段拿到的像素级文本 mask,坐标系按 [ocrSample] 解出的 bitmap。
     * 如果 ocrSample 跟我们这里算的 renderSample 一致(常态:两边阈值同为 2400),
     * 直接传给 TextEraser;否则 mask 对不齐 → 丢掉走 fallback,不强行 resample。
     */
    private fun renderTranslated(
        app: Context,
        imageFile: File,
        pageIndex: Int,
        regions: List<OcrTextRegion>,
        translations: Map<Int, String>,
        textMask: TextMask?,
        ocrSample: Int,
    ): File {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "decode bounds failed" }

        val shortSide = minOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (shortSide / sample > MAX_RENDER_SHORT_SIDE) sample *= 2

        val original = BitmapFactory.decodeFile(
            imageFile.absolutePath,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = sample
            }
        ) ?: error("decode failed: ${imageFile.absolutePath}")

        Timber.d(
            "WriteBack: orig %dx%d sample=%d → bitmap %dx%d; %d regions, %d with translation",
            bounds.outWidth, bounds.outHeight, sample, original.width, original.height,
            regions.size, translations.size
        )
        if (regions.isNotEmpty()) {
            val r0 = regions[0]
            Timber.d(
                "WriteBack: region[0] (orig coords) cx=%.0f cy=%.0f w=%.0f h=%.0f orient=%d",
                r0.cx, r0.cy, r0.width, r0.height, r0.orientation
            )
        }

        try {
            val scaleFactor = 1f / sample
            val scaledRegions = if (sample == 1) regions else regions.map { it.scaledBy(scaleFactor) }
            if (sample > 1 && scaledRegions.isNotEmpty()) {
                val s0 = scaledRegions[0]
                Timber.d(
                    "WriteBack: scaledRegion[0] (bitmap coords) cx=%.0f cy=%.0f w=%.0f h=%.0f",
                    s0.cx, s0.cy, s0.width, s0.height
                )
            }
            // 只擦"有译文"的 region,失败项保留日文原貌
            val toErase = scaledRegions.filterIndexed { i, _ -> !translations[i].isNullOrBlank() }
            // mask 跟 OCR bitmap 一致;只有 ocrSample==renderSample 且 dim 也对得上才喂给 eraser
            val maskForEraser = textMask?.takeIf {
                ocrSample == sample && it.width == original.width && it.height == original.height
            }
            if (textMask != null && maskForEraser == null) {
                Timber.w(
                    "WriteBack: mask drop — ocrSample=%d renderSample=%d, maskDim=%dx%d bitmap=%dx%d",
                    ocrSample, sample, textMask.width, textMask.height, original.width, original.height
                )
            }
            val erased = TextEraser.eraseText(original, toErase, maskForEraser)
            try {
                val canvas = Canvas(erased)
                // 把每个有译文 region 的 corners 扩到气泡内部可写区域 —
                // OCR 框紧贴日文字符,远小于气泡,中文塞回去字号被压成蚂蚁;
                // 扩到气泡边界(BG 连通区域)后中文能用满整个气泡。
                val regionsForRender = scaledRegions.mapIndexed { i, region ->
                    if (translations[i].isNullOrBlank()) return@mapIndexed region
                    val bgColor = TextEraser.sampleBackgroundColor(erased, region)
                    val b = BubbleAreaFinder.expand(erased, region, bgColor)
                    region.copy(
                        corners = listOf(
                            b[0].toFloat() to b[1].toFloat(),
                            b[2].toFloat() to b[1].toFloat(),
                            b[2].toFloat() to b[3].toFloat(),
                            b[0].toFloat() to b[3].toFloat(),
                        )
                    )
                }
                TextRenderer.renderTranslations(canvas, regionsForRender, translations)
                val out = File(
                    app.cacheDir,
                    "manga_translated_p${pageIndex}_${System.currentTimeMillis()}.png"
                )
                FileOutputStream(out).use { erased.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Timber.d("WriteBack: saved → %s", out.absolutePath)
                return out
            } finally {
                erased.recycle()
            }
        } finally {
            original.recycle()
        }
    }

    private fun publishTranslated(pageIndex: Int, newPath: String) {
        val oldPath = _translatedPaths.value?.get(pageIndex)
        val nextMap = _translatedPaths.value.orEmpty().toMutableMap().apply {
            put(pageIndex, newPath)
        }
        _translatedPaths.postValue(nextMap)
        if (oldPath != null && oldPath != newPath) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { File(oldPath).delete() }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // VM 终结时把 cacheDir 里这次会话产生的译图全删掉
        val paths = _translatedPaths.value.orEmpty().values.toList()
        if (paths.isNotEmpty()) {
            // 不能用 viewModelScope(已 cancel),直接同步删
            paths.forEach { runCatching { File(it).delete() } }
        }
    }

    companion object {
        /** 回填渲染时图像短边的上限,2400 对齐 [MangaOcr.MAX_INPUT_SHORT_SIDE]。 */
        private const val MAX_RENDER_SHORT_SIDE = 2400
    }
}

