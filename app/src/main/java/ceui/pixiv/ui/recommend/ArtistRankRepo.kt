package ceui.pixiv.ui.recommend

import ceui.lisa.activities.Shaft
import ceui.lisa.core.RemoteRepo
import ceui.lisa.model.ListUser
import ceui.lisa.models.IllustsBean
import ceui.lisa.models.UserBean
import ceui.lisa.models.UserPreviewsBean
import ceui.lisa.network.ShaftApiV2
import ceui.lisa.network.ShaftApiV2Client
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * 画师收藏总榜 —— 打 shaft-api-v2 的 discover/artists:按画师全部作品的 pixiv 总收藏数求和排名
 * (含 R-18)。服务端回 pixiv user_previews 形状,这里用 Shaft.sGson 把每个 user / illusts 反序列化
 * 成 UserBean / IllustsBean(和 TrendingWorksRepo 一致,不走 Retrofit 默认 Gson),拼成 [ListUser],
 * 复用现成「画师 + 3 预览图」列表(UAdapter)。分页跟随服务端 next_url。
 *
 * 清零关注/收藏态:payload 里的 is_followed / is_bookmarked 是上报榜单那个客户端当时的状态,跟
 * 当前用户无关,全清成 false 让用户能以自己名义关注/收藏(同 Trending/Recent repo)。
 */
class ArtistRankRepo : RemoteRepo<ListUser>() {

    override fun initApi(): Observable<ListUser> {
        return Observable.fromCallable {
            buildListUser(runBlocking { ShaftApiV2Client.service.discoverArtists() })
        }.subscribeOn(Schedulers.io())
    }

    override fun initNextApi(): Observable<ListUser> {
        val url = nextUrl
        if (url.isNullOrEmpty()) {
            return Observable.fromCallable {
                ListUser().apply { setUser_previews(emptyList()) }
            }.subscribeOn(Schedulers.io())
        }
        return Observable.fromCallable {
            buildListUser(runBlocking { ShaftApiV2Client.service.discoverArtistsByUrl(url) })
        }.subscribeOn(Schedulers.io())
    }

    private fun buildListUser(resp: ShaftApiV2.ArtistRankResponse): ListUser {
        val gson = Shaft.sGson
        val previews = resp.user_previews.mapNotNull { item ->
            val user = item.user?.let {
                try { gson.fromJson(it, UserBean::class.java) } catch (e: Throwable) { null }
            } ?: return@mapNotNull null
            user.setIs_followed(false)
            val illusts = item.illusts.mapNotNull { j ->
                try {
                    gson.fromJson(j, IllustsBean::class.java).apply { setIs_bookmarked(false) }
                } catch (e: Throwable) {
                    null
                }
            }
            // novels 必须给空列表、不能留 null:UAdapter 在 illusts 不足 3 张时会读
            // getNovels().subList(...) 补位,null 会 NPE 崩(画师榜里 1~2 个爆款的画师就 <3 张)。
            UserPreviewsBean().apply {
                setUser(user)
                setIllusts(illusts)
                setNovels(emptyList())
            }
        }
        Timber.tag("ArtistRank").d("artists parsed=${previews.size} next=${resp.next_url != null}")
        return ListUser().apply {
            setUser_previews(previews)
            setNext_url(resp.next_url ?: "")
        }
    }
}
