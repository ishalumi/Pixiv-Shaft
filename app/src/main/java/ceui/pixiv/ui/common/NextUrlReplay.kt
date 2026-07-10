package ceui.pixiv.ui.common

import ceui.loxia.Client
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * pixiv nextUrl 翻页协议的唯一实现：GET 原始 JSON，用首页响应的运行时类型重放反序列化。
 * [DataSource.loadMoreImpl] 与 ceui.pixiv.feeds.PixivFeedSource 共用，协议修复只改这一处。
 */
suspend fun <T> replayNextUrl(gson: Gson, nextUrl: String, responseClass: Class<T>): T {
    return withContext(Dispatchers.IO) {
        val responseJson = Client.appApi.generalGet(nextUrl).string()
        gson.fromJson(responseJson, responseClass)
    }
}
