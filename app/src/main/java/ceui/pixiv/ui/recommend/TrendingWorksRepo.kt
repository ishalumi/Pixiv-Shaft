package ceui.pixiv.ui.recommend

import ceui.lisa.activities.Shaft
import ceui.lisa.core.RemoteRepo
import ceui.lisa.model.ListIllust
import ceui.lisa.model.ListNovel
import ceui.lisa.models.IllustsBean
import ceui.lisa.models.NovelBean
import ceui.lisa.network.ShaftApiV2
import ceui.lisa.network.ShaftApiV2Client
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * 站长推荐 - 插画/漫画 数据源。直接打 shaft-api-v2 的 trending/works 接口,按 bookmark_count desc。
 * 服务端 include_meta=1 时已经把没 payload 的 id 过滤掉了,所以这里每个 item.bean 都有值。
 *
 * 分页协议:首屏 offset=0,后续调 [ShaftApiV2.trendingWorksByUrl] 喂服务端给的 `next_url`
 * (pixiv 协议:绝对 URL,原 query 参数全保留,只改 offset),next_url=null 即榜单到底。
 * NetListFragment 会把 [ListIllust.getNextUrl] 同步回 [RemoteRepo.setNextUrl],所以
 * [initNextApi] 只需要读 [getNextUrl] 即可。
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
            buildListIllust(resp)
        }.subscribeOn(Schedulers.io())
    }

    override fun initNextApi(): Observable<ListIllust> {
        val url = nextUrl
        if (url.isNullOrEmpty()) {
            return Observable.fromCallable {
                ListIllust().apply { setIllusts(emptyList()) }
            }.subscribeOn(Schedulers.io())
        }
        return Observable.fromCallable {
            val resp = runBlocking { ShaftApiV2Client.service.trendingWorksByUrl(url) }
            buildListIllust(resp)
        }.subscribeOn(Schedulers.io())
    }

    private fun buildListIllust(resp: ShaftApiV2.TrendingWorksResponse): ListIllust {
        val gson = Shaft.sGson
        val illusts = resp.items.mapNotNull { item ->
            item.bean?.let { json ->
                try {
                    gson.fromJson(json, IllustsBean::class.java).apply {
                        // 把 server 的 trending score 装饰到 bean 上,IAdapter 渲染时
                        // 读这个字段决定是否露出左上角的 score pill。transient 保证不
                        // 串进 Gson 序列化 / Java Serializable 的输出。
                        trendingScore = item.score.toFloat()
                    }
                }
                catch (e: Throwable) {
                    Timber.tag("SiteRecmd").w(e, "skip malformed bean id=${item.target_id}")
                    null
                }
            }
        }
        Timber.tag("SiteRecmd").d(
            "trending type=$type offset=${resp.offset} returned=${resp.items.size} parsed=${illusts.size} next=${resp.next_url != null}"
        )
        return ListIllust().apply {
            setIllusts(illusts)
            setNext_url(resp.next_url ?: "")
        }
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
            buildListNovel(resp)
        }.subscribeOn(Schedulers.io())
    }

    override fun initNextApi(): Observable<ListNovel> {
        val url = nextUrl
        if (url.isNullOrEmpty()) {
            return Observable.fromCallable {
                ListNovel().apply { setNovels(emptyList()) }
            }.subscribeOn(Schedulers.io())
        }
        return Observable.fromCallable {
            val resp = runBlocking { ShaftApiV2Client.service.trendingWorksByUrl(url) }
            buildListNovel(resp)
        }.subscribeOn(Schedulers.io())
    }

    private fun buildListNovel(resp: ShaftApiV2.TrendingWorksResponse): ListNovel {
        val gson = Shaft.sGson
        val novels = resp.items.mapNotNull { item ->
            item.bean?.let { json ->
                try {
                    gson.fromJson(json, NovelBean::class.java).apply {
                        // 同上,把 trending score 装饰到 NovelBean,NAdapter 渲染时读。
                        trendingScore = item.score.toFloat()
                    }
                }
                catch (e: Throwable) {
                    Timber.tag("SiteRecmd").w(e, "skip malformed novel bean id=${item.target_id}")
                    null
                }
            }
        }
        Timber.tag("SiteRecmd").d(
            "trending novel offset=${resp.offset} returned=${resp.items.size} parsed=${novels.size} next=${resp.next_url != null}"
        )
        return ListNovel().apply {
            setNovels(novels)
            setNext_url(resp.next_url ?: "")
        }
    }
}
