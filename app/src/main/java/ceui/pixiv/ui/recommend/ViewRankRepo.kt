package ceui.pixiv.ui.recommend

import ceui.lisa.activities.Shaft
import ceui.lisa.core.RemoteRepo
import ceui.lisa.model.ListIllust
import ceui.lisa.models.IllustsBean
import ceui.lisa.network.ShaftApiV2
import ceui.lisa.network.ShaftApiV2Client
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * 全站浏览量榜 —— 打 shaft-api-v2 的 discover/most-viewed:单作按 pixiv 总浏览数排(含 R-18)。
 * item.bean 是完整 pixiv illust JSON,用 Shaft.sGson 解析成 IllustsBean 复用现成瀑布流渲染。
 * 热度 pill 取 bookmark_count(浏览数动辄百万,pill 放收藏数更可读)。分页跟随服务端 next_url。
 */
class ViewRankRepo(
    private val type: String = "illust",
    private val limitN: Int = 30,
) : RemoteRepo<ListIllust>() {

    override fun initApi(): Observable<ListIllust> {
        return Observable.fromCallable {
            buildListIllust(runBlocking { ShaftApiV2Client.service.mostViewed(type = type, limit = limitN) })
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
            buildListIllust(runBlocking { ShaftApiV2Client.service.mostViewedByUrl(url) })
        }.subscribeOn(Schedulers.io())
    }

    private fun buildListIllust(resp: ShaftApiV2.MostViewedResponse): ListIllust {
        val gson = Shaft.sGson
        val illusts = resp.items.mapNotNull { item ->
            item.bean?.let { json ->
                try {
                    gson.fromJson(json, IllustsBean::class.java).apply {
                        // 这是浏览量榜:角标显示浏览数(TrendingScoreFormat 支持 M,6457227→「6.5M」),
                        // 跟标题一致;显示 bookmark_count 会让人误以为是浏览数。
                        trendingScore = item.view_count.toFloat()
                        // payload 里的收藏态是上报者的,清零让用户以自己名义收藏。
                        setIs_bookmarked(false)
                    }
                } catch (e: Throwable) {
                    Timber.tag("ViewRank").w(e, "skip malformed bean id=${item.target_id}")
                    null
                }
            }
        }
        Timber.tag("ViewRank").d("most-viewed returned=${resp.items.size} parsed=${illusts.size} next=${resp.next_url != null}")
        return ListIllust().apply {
            setIllusts(illusts)
            setNext_url(resp.next_url ?: "")
        }
    }
}
