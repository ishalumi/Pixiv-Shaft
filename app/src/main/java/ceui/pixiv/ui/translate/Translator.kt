package ceui.pixiv.ui.translate

import kotlinx.coroutines.ensureActive
import timber.log.Timber
import kotlin.coroutines.coroutineContext

interface Translator {
    suspend fun translate(input: String, outputLang: String): String

    /**
     * 翻一批文本。默认按 translate() 逐条调,子类可覆写做真 batch
     * (比如 Google web 端点把多条 join 成单次 HTTP)。
     * - onItem: 单条完成后回调,用于 LiveData 增量推送
     * - onProgress: 每完成一段后回调 (done, total),用于按钮进度
     */
    suspend fun translateBatch(
        inputs: List<String>,
        outputLang: String,
        onItem: ((index: Int, translated: String) -> Unit)? = null,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): List<String> {
        val out = mutableListOf<String>()
        for ((i, text) in inputs.withIndex()) {
            coroutineContext.ensureActive()
            val zh = try {
                translate(text, outputLang)
            } catch (e: Exception) {
                Timber.e(e, "Translator: item %d failed", i)
                ""
            }
            out.add(zh)
            if (zh.isNotEmpty()) onItem?.invoke(i, zh)
            onProgress?.invoke(i + 1, inputs.size)
        }
        return out
    }
}
