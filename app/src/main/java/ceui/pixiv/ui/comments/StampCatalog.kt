package ceui.pixiv.ui.comments

import ceui.loxia.Client
import ceui.loxia.Stamp
import ceui.loxia.StampsResponse
import ceui.pixiv.ui.common.createResponseStore
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * 评论「表情贴图」目录:GET /v1/stamps 返回官方常驻的 40 个贴纸,小体量且不随人变化,
 * 进程内缓存一次即可,不必每次打开面板都重新请求。
 *
 * 请求失败不外抛——内部吞掉异常,退回磁盘持久化的上一次成功响应([ResponseStore],同
 * TrendingTagsDataSource 等已在用的收口);只有成功请求才写入内存/磁盘缓存,失败不会
 * 「毒化」内存缓存,下次调用(比如重新打开评论页)还会正常重试网络。
 */
object StampCatalog {
    private var cache: List<Stamp>? = null
    private val store = createResponseStore<StampsResponse>({ "stamps-catalog" })

    suspend fun get(): List<Stamp> {
        cache?.let { return it }
        return try {
            val response = Client.appApi.getStamps()
            store.writeToCache(response)
            response.stamps.also { cache = it }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            Timber.e(ex, "StampCatalog: fetch failed, falling back to disk cache")
            store.loadFromCache()?.stamps.orEmpty()
        }
    }
}
