package ceui.pixiv.ui.bulk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Process
import ceui.lisa.activities.Shaft
import ceui.lisa.cache.Cache
import ceui.lisa.file.LegacyFile
import ceui.lisa.http.Retro
import ceui.lisa.models.FramesBean
import ceui.lisa.models.GifResponse
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.AnimatedGifEncoder
import ceui.lisa.utils.Params
import ceui.pixiv.download.DownloadsRegistry
import ceui.pixiv.download.config.DownloadItems
import ceui.pixiv.ui.bulk.QueueDownloadManager.UgoiraPhase
import com.blankj.utilcode.util.ZipUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Ugoira 单条全链路 suspend 任务：
 *   getGifPackage 拿 zip url + frame delays
 *   → 下载 zip 到 [LegacyFile.gifZipFile]（仅 internal cache）
 *   → 解压到 [LegacyFile.gifUnzipFolder]（仅 internal cache）
 *   → AnimatedGifEncoder **直接编进 V3 [DownloadsRegistry] 的 WriteHandle.stream**
 *
 * 设计要点：
 *
 *  - **不走 [ceui.lisa.file.OutPut.outPutGif]**：那个 helper 内部虽然也用 V3 facade，
 *    但会在批量场景里给每条 ugoira 弹 save_gif_success / save_gif_exists toast，
 *    几十条 ugoira 一起跑会刷屏。这里直接调 [DownloadsRegistry.downloads.open]
 *    拿 WriteHandle，跳过 toast。
 *
 *  - **不写 [LegacyFile.gifResultFile]** 中间文件：旧路径会编一份到 cache 再
 *    `outPutGif` 复制到用户目录，双写一次 IO。这里编码器输出流直接接 V3 WriteHandle，
 *    一次写盘到目标位置，按用户的 ugoira 命名预设落到对应的 [Bucket.Ugoira] 目录。
 *
 *  - **每一步 idempotent**：已下好的 zip / 已解压的 frames 不重做；
 *    [DownloadsRegistry.downloads.open] 在 OverwritePolicy.Skip + 已存在时返回 null，
 *    我们跳过不报错，把这条当 SUCCESS。冷启动把 DOWNLOADING 翻 PENDING 重跑时
 *    第二遍几乎瞬间完成。
 *
 *  - 任何一步出错就抛异常 —— [QueueDownloadManager.dispatchUgoira] 走 retry / FAILED
 *    路径，不要在这里吞错误。
 */
suspend fun downloadUgoira(
    illust: IllustsBean,
    onPhase: (UgoiraPhase) -> Unit = {},
) = withContext(Dispatchers.IO) {
    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
    val ctx = Shaft.getContext()
    val illustId = illust.id

    Timber.tag(TAG).i("[UGOIRA] start illust=$illustId title=${illust.title}")

    // 1) 元数据：zip url + frame delays。Cache 里如果已经有 GifResponse 直接复用。
    onPhase(UgoiraPhase.FETCH_META)
    val cached = runCatching {
        Cache.get().getModel(Params.ILLUST_ID + "_" + illustId, GifResponse::class.java)
    }.getOrNull()
    val resp: GifResponse = if (cached?.ugoira_metadata != null) {
        cached
    } else {
        val fetched = Retro.getAppApi().getGifPackage(illustId).awaitFirstSafe()
        runCatching { Cache.get().saveModel(Params.ILLUST_ID + "_" + illustId, fetched) }
        fetched
    }
    val zipUrl = resp.ugoira_metadata?.zip_urls?.medium
        ?: throw IllegalStateException("ugoira zip url missing for illust=$illustId")
    coroutineContext.ensureActive()

    // 2) 下载 zip（已存在且非空就跳过）—— internal cache，未来由 V3 cache 清理
    val zipFile = LegacyFile.gifZipFile(ctx, illust)
    if (!zipFile.isFile || zipFile.length() == 0L) {
        onPhase(UgoiraPhase.DOWNLOAD_ZIP)
        downloadZipTo(zipUrl, zipFile)
    } else {
        Timber.tag(TAG).i("[UGOIRA] zip already cached ($zipFile)")
    }
    coroutineContext.ensureActive()

    // 3) 解压。完整性按 frames.size 比对：上次进程死在解压途中，folder 里可能有
    //    不全的帧子集，第二次跑直接 skip 解压会编出残废 GIF。
    //    数对不上 → 清空重解；zip 在本地 cache，几十毫秒级别开销，便宜。
    val unzipFolder = LegacyFile.gifUnzipFolder(ctx, illust)
    val expectedFrameCount = resp.ugoira_metadata?.frames?.size ?: 0
    val onDiskFrameCount = unzipFolder.listFiles()?.count { it.isFile } ?: 0
    if (onDiskFrameCount == 0 || (expectedFrameCount > 0 && onDiskFrameCount != expectedFrameCount)) {
        if (onDiskFrameCount > 0) {
            // 删现有内容，但保留 folder 本身
            unzipFolder.listFiles()?.forEach { runCatching { it.delete() } }
            Timber.tag(TAG).w("[UGOIRA] frame count mismatch (had=$onDiskFrameCount expect=$expectedFrameCount), re-extracting")
        }
        onPhase(UgoiraPhase.EXTRACT)
        ZipUtils.unzipFile(zipFile, unzipFolder)
        Timber.tag(TAG).i("[UGOIRA] unzipped ${unzipFolder.listFiles()?.size ?: 0} frames")
    }
    coroutineContext.ensureActive()

    // 4) 直接编进 V3 WriteHandle —— 用户配置的 ugoira 命名模板 / 存储位置统一生效。
    onPhase(UgoiraPhase.ENCODE)
    val handle = DownloadsRegistry.downloads.open(DownloadItems.ugoira(illust))
    if (handle == null) {
        // OverwritePolicy.Skip + 目标已存在；当作完成
        Timber.tag(TAG).i("[UGOIRA] skip: target already exists illust=$illustId")
        return@withContext
    }
    try {
        BufferedOutputStream(handle.stream).use { bos ->
            encodeFramesToGif(unzipFolder, resp, bos)
        }
        handle.onFinish()
        Timber.tag(TAG).i("[UGOIRA] done illust=$illustId uri=${handle.uri}")
    } catch (t: Throwable) {
        // onAbort 让 backend 清掉部分写入的 .pending-NNNN 文件；不调用就会留 0 字节孤儿
        runCatching { handle.onAbort() }
        throw t
    }
}

/**
 * OkHttp 直下 zip 到 [target]。pixiv 服务器要 Referer，否则 403。
 * 写到 .part 临时文件，完成后 rename —— 中途中断不会留 0 字节文件让下次跳过。
 */
private suspend fun downloadZipTo(url: String, target: File) {
    val req = Request.Builder()
        .url(url)
        .header("Referer", "https://app-api.pixiv.net/")
        .header("User-Agent", "PixivAndroidApp/5.0.234 (Android 11; Pixel)")
        .build()
    ugoiraHttpClient.newCall(req).execute().use { r ->
        if (!r.isSuccessful) {
            throw IllegalStateException("zip download HTTP ${r.code} url=$url")
        }
        val body = r.body ?: throw IllegalStateException("zip body null url=$url")
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".part")
        // Response.use 已经会关 body 流，body.byteStream() 不必再嵌套 use
        FileOutputStream(temp).use { out ->
            val input = body.byteStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            throw IllegalStateException("rename .part → ${target.name} failed")
        }
    }
}

/**
 * 把 [unzipFolder] 里 `001.png / 002.png ...` 帧文件按顺序编进 GIF，写到 [out]。
 * 帧延迟优先取 [GifResponse.ugoira_metadata.frames]（每帧独立），fallback 到
 * [GifResponse.getDelay]（单值），再 fallback 到默认 60ms。
 *
 * 直接 BitmapFactory.decodeFile + recycle —— 同步阻塞，谁调用谁负责进 IO 线程。
 * 100 帧的常见 ugoira 在 Pixel 上 ~1s 完成。
 *
 * **不持有/不关闭 [out]** —— 调用方负责（[BufferedOutputStream.use] / V3 WriteHandle
 * 的 onFinish 收尾）。这里调 [AnimatedGifEncoder.finish] 写出 GIF trailer 即可，
 * 不要再 close。
 */
private fun encodeFramesToGif(unzipFolder: File, resp: GifResponse, out: OutputStream) {
    val files = (unzipFolder.listFiles() ?: emptyArray())
        .filter { it.isFile }
        .sortedBy {
            // 文件名形如 "000123.png"；按数字部分排序，避开字典序
            it.nameWithoutExtension.toIntOrNull() ?: Int.MAX_VALUE
        }
    if (files.isEmpty()) throw IllegalStateException("no frames to encode in $unzipFolder")

    val frames: List<FramesBean>? = resp.ugoira_metadata?.frames
    // GifResponse.getDelay() 兜底返回 60，永远 > 0；这里直接用就行
    val fallbackDelayMs = resp.delay
    val perFrame = frames != null && frames.size == files.size

    val encoder = AnimatedGifEncoder()
    encoder.start(out)
    encoder.setRepeat(0) // 无限循环
    for ((i, f) in files.withIndex()) {
        val delay = if (perFrame) frames!![i].delay else fallbackDelayMs
        encoder.setDelay(delay)
        val bmp: Bitmap? = BitmapFactory.decodeFile(f.absolutePath)
        if (bmp != null) {
            encoder.addFrame(bmp)
            bmp.recycle()
        } else {
            Timber.tag(TAG).w("[UGOIRA] decode frame failed $f")
        }
    }
    encoder.finish()
}

private const val DEFAULT_DELAY_MS = 60
private const val TAG = "UgoiraTask"

private val ugoiraHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)  // 大 ugoira zip 几十 MB，慢网下读超时要松一点
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
}
