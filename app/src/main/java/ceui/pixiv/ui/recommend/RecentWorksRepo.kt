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
 * 当前最热 - 插画/漫画 数据源。打 shaft-api-v2 的 recent/works 接口:「现在正在被人
 * 收藏的作品」,按最近一次 bookmark 事件倒序、server 端已按作品去重(每个作品只出一次)。
 *
 * 跟 [TrendingWorksRepo](本月收藏 = 当前周收藏加权榜)的区别只有两点:
 *   1. 接口走 recent 而非 trending(实时流 vs 周期快照);
 *   2. 不装饰 trendingScore —— 这是时间流不是加权榜,不露左上角 score pill。
 * 分页协议同 trending(跟随 server 的 next_url)。
 */
class RecentWorksRepo(
    private val type: String,
    private val limitN: Int = 60,
) : RemoteRepo<ListIllust>() {

    override fun initApi(): Observable<ListIllust> {
        return Observable.fromCallable {
            val resp = runBlocking {
                ShaftApiV2Client.service.recentWorks(type = type, limit = limitN)
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
            val resp = runBlocking { ShaftApiV2Client.service.recentWorksByUrl(url) }
            buildListIllust(resp)
        }.subscribeOn(Schedulers.io())
    }

    private fun buildListIllust(resp: ShaftApiV2.RecentWorksResponse): ListIllust {
        val gson = Shaft.sGson
        val illusts = resp.items.mapNotNull { item ->
            item.bean?.let { json ->
                try {
                    gson.fromJson(json, IllustsBean::class.java).apply {
                        // 当前最热是时间流,不设 trendingScore → score pill 自动 GONE。
                        // payload 里的 is_bookmarked 是上报者当时的收藏态,跟当前用户无关,
                        // 一律清成 false,让用户能以自己名义点收藏。
                        setIs_bookmarked(false)
                    }
                } catch (e: Throwable) {
                    Timber.tag("RecentHot").w(e, "skip malformed bean id=${item.target_id}")
                    null
                }
            }
        }
        Timber.tag("RecentHot").d(
            "recent type=$type offset=${resp.offset} returned=${resp.items.size} parsed=${illusts.size} next=${resp.next_url != null}"
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
class RecentNovelsRepo(
    private val limitN: Int = 60,
) : RemoteRepo<ListNovel>() {

    override fun initApi(): Observable<ListNovel> {
        return Observable.fromCallable {
            val resp = runBlocking {
                ShaftApiV2Client.service.recentWorks(type = "novel", limit = limitN)
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
            val resp = runBlocking { ShaftApiV2Client.service.recentWorksByUrl(url) }
            buildListNovel(resp)
        }.subscribeOn(Schedulers.io())
    }

    private fun buildListNovel(resp: ShaftApiV2.RecentWorksResponse): ListNovel {
        val gson = Shaft.sGson
        val novels = resp.items.mapNotNull { item ->
            item.bean?.let { json ->
                try {
                    gson.fromJson(json, NovelBean::class.java).apply {
                        // 同上,时间流不设 trendingScore;清掉上报者收藏态。
                        setIs_bookmarked(false)
                    }
                } catch (e: Throwable) {
                    Timber.tag("RecentHot").w(e, "skip malformed novel bean id=${item.target_id}")
                    null
                }
            }
        }
        Timber.tag("RecentHot").d(
            "recent novel offset=${resp.offset} returned=${resp.items.size} parsed=${novels.size} next=${resp.next_url != null}"
        )
        return ListNovel().apply {
            setNovels(novels)
            setNext_url(resp.next_url ?: "")
        }
    }
}
