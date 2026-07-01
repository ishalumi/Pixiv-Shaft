package ceui.pixiv.imageloader

import android.os.SystemClock
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.GlideUrlChild
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.jessyan.progressmanager.ProgressListener
import me.jessyan.progressmanager.ProgressManager
import me.jessyan.progressmanager.body.ProgressInfo
import timber.log.Timber
import java.io.File

/**
 * [ImageFetcher] 的默认实现:复用 Glide 抓取(自带磁盘缓存 + 通过 [GlideUrlChild] 带上 Pixiv referer/UA 头),
 * 进度复用 jessyan [ProgressManager](Glide 的 OkHttp 客户端已在 [Shaft] 里被它包过)。
 *
 * 设计要点:
 * - 进度监听按 url 挂在 ProgressManager 的 WeakHashMap 上;url 串不再被强引用时,entry 连同监听器一起被 GC 回收,
 *   无需手动移除,也就不会像 ui/task 那样堆积监听器。
 * - 阻塞的 `FutureTarget.get()` 放进可中断的 [runInterruptible];协程取消能真正打断下载、
 *   `finally` 里 `cancel(true)` 释放 Glide 请求与线程,而不是像 ui/task 那样一路 `.get()` 干等到底。
 *
 * 日志:`fetch DONE` 会打出 `source`(MEMORY_CACHE/DATA_DISK_CACHE/RESOURCE_DISK_CACHE/REMOTE)与耗时,
 * 这是判断「B/C 是否命中共享缓存 vs 真的走了一次网络」最直接的信号。
 */
object GlideImageFetcher : ImageFetcher {

    override suspend fun fetch(url: String, onProgress: (Int) -> Unit): File {
        val shortUrl = url.substringAfterLast('/')

        ProgressManager.getInstance().addResponseListener(url, object : ProgressListener {
            override fun onProgress(progressInfo: ProgressInfo) {
                if (!progressInfo.isFinish) onProgress(progressInfo.percent.coerceIn(0, 99))
            }

            override fun onError(id: Long, ex: Exception) = Unit
        })

        // 捕获 Glide 命中的数据源,仅用于日志分析(缓存命中 vs 走网络)。
        var dataSource: DataSource? = null
        val future = Glide.with(Shaft.getContext())
            .asFile()
            .load(GlideUrlChild(url))
            .listener(object : RequestListener<File> {
                override fun onLoadFailed(
                    e: GlideException?, model: Any?, target: Target<File>, isFirstResource: Boolean
                ): Boolean = false

                override fun onResourceReady(
                    resource: File, model: Any, target: Target<File>?,
                    source: DataSource, isFirstResource: Boolean
                ): Boolean {
                    dataSource = source
                    return false
                }
            })
            .submit()

        val startMs = SystemClock.elapsedRealtime()
        return withContext(Dispatchers.IO) {
            try {
                val file = runInterruptible { future.get() }
                val elapsed = SystemClock.elapsedRealtime() - startMs
                Timber.d("[ImgV3] fetch DONE url=$shortUrl source=${dataSource?.name} ms=$elapsed size=${file.length()}")
                file
            } finally {
                if (!future.isDone) future.cancel(true)
            }
        }
    }
}
