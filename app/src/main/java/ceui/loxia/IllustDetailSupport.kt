package ceui.loxia

import ceui.lisa.http.Retro
import ceui.lisa.models.IllustsBean
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * issue #569: 详情页渲染 / 下载所需的字段是否齐全。
 *
 * 网页「按 Tag 筛选」等精简来源只给方图缩略图,缺分页图(meta_pages / meta_single_page),
 * 用它直接建多图分页或下原图会崩或残缺,需回 v1/illust/detail 拉完整版。
 * 头像缺失不在此判定内——已由 GlideUtil/IllustDownload 的空值兜底处理,不再单独触发整页重拉。
 */
fun IllustsBean.isFullDetail(): Boolean {
    return if (page_count <= 1) {
        meta_single_page?.original_image_url?.isNotEmpty() == true
    } else {
        (meta_pages?.size ?: 0) >= page_count
    }
}

/**
 * 回 v1/illust/detail 拉完整版,整体覆盖(isFullVersion)进 ObjectPool 并返回。
 * 作品已删 / 不可见 / 网络失败返回 null —— 此时不覆盖,保留池里已有数据,由调用方降级处理。
 */
suspend fun fetchFullIllustDetail(illustId: Long): IllustsBean? {
    return try {
        val fresh = Retro.getAppApi().getIllustByID(illustId).awaitFirstOrThrow().illust
        if (fresh != null && fresh.id != 0 && fresh.isVisible) {
            ObjectPool.update(fresh, isFullVersion = true)
            fresh.user?.let { ObjectPool.update(it) }
            fresh
        } else {
            null
        }
    } catch (e: Exception) {
        Timber.e(e, "fetchFullIllustDetail failed illustId=%d", illustId)
        null
    }
}

private suspend fun <T : Any> Observable<T>.awaitFirstOrThrow(): T =
    suspendCancellableCoroutine { cont ->
        val disposable = subscribeOn(Schedulers.io())
            .firstOrError()
            .subscribe(
                { cont.resume(it) },
                { cont.resumeWithException(it) }
            )
        cont.invokeOnCancellation { disposable.dispose() }
    }
