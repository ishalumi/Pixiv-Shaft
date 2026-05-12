package ceui.pixiv.ui.recommend

import ceui.lisa.activities.Shaft
import ceui.lisa.core.RemoteRepo
import ceui.lisa.model.ListIllust
import ceui.lisa.model.ListNovel
import ceui.lisa.models.IllustsBean
import ceui.lisa.models.NovelBean
import ceui.lisa.network.ShaftApiV2Client
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * 站长推荐 - 插画/漫画 数据源。直接打 shaft-api-v2 的 trending/works 接口,按 bookmark_count desc。
 * 服务端 include_meta=1 时已经把没 payload 的 id 过滤掉了,所以这里每个 item.bean 都有值。
 *
 * 不分页 (next_url 永远空) —— 服务端单次返回 top-N 就是当前榜单全貌,再翻页没有语义。
 */
class TrendingWorksRepo(
    private val type: String,
    private val limitN: Int = 60,
) : RemoteRepo<ListIllust>() {

    override fun initApi(): Observable<ListIllust> {
        return Observable.fromCallable {
            val resp = runBlocking {
                ShaftApiV2Client.service.trendingWorks(
                    type = type,
                    window = "week",
                    limit = limitN,
                    sort = "bookmark",
                    includeMeta = 1,
                )
            }
            val gson = Shaft.sGson
            val illusts = resp.items.mapNotNull { item ->
                item.bean?.let { json ->
                    try { gson.fromJson(json, IllustsBean::class.java) }
                    catch (e: Throwable) {
                        Timber.tag("SiteRecmd").w(e, "skip malformed bean id=${item.target_id}")
                        null
                    }
                }
            }
            Timber.tag("SiteRecmd").d("trending type=$type returned=${resp.items.size} parsed=${illusts.size}")
            ListIllust().apply { setIllusts(illusts) }
        }.subscribeOn(Schedulers.io())
    }

    override fun initNextApi(): Observable<ListIllust> {
        return Observable.fromCallable {
            ListIllust().apply { setIllusts(emptyList()) }
        }.subscribeOn(Schedulers.io())
    }
}

/**
 * 同上,小说版。
 */
class TrendingNovelsRepo(
    private val limitN: Int = 60,
) : RemoteRepo<ListNovel>() {

    override fun initApi(): Observable<ListNovel> {
        return Observable.fromCallable {
            val resp = runBlocking {
                ShaftApiV2Client.service.trendingWorks(
                    type = "novel",
                    window = "week",
                    limit = limitN,
                    sort = "bookmark",
                    includeMeta = 1,
                )
            }
            val gson = Shaft.sGson
            val novels = resp.items.mapNotNull { item ->
                item.bean?.let { json ->
                    try { gson.fromJson(json, NovelBean::class.java) }
                    catch (e: Throwable) {
                        Timber.tag("SiteRecmd").w(e, "skip malformed novel bean id=${item.target_id}")
                        null
                    }
                }
            }
            Timber.tag("SiteRecmd").d("trending novel returned=${resp.items.size} parsed=${novels.size}")
            ListNovel().apply { setNovels(novels) }
        }.subscribeOn(Schedulers.io())
    }

    override fun initNextApi(): Observable<ListNovel> {
        return Observable.fromCallable {
            ListNovel().apply { setNovels(emptyList()) }
        }.subscribeOn(Schedulers.io())
    }
}
