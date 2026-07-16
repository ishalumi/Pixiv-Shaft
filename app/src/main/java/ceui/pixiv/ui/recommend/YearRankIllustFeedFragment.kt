package ceui.pixiv.ui.recommend

import android.os.Bundle
import ceui.lisa.models.IllustsBean
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.IllustFeedFragment

/**
 * 年代榜的**单个年份 tab**(feeds 框架版)。宿主是 [YearRankFragment] 的 ViewPager,所以
 * 用无参 [IllustFeedFragment](不带 toolbar —— toolbar 在宿主那儿),对齐
 * [HotWorksIllustFeedFragment] 的做法。
 *
 * ⚠️ `autoLoad = false` 不是可选的:年代榜有 20 个年份 tab,自动加载会在进页面那一刻
 * 把所有 tab 的首屏一起打出去。服务端读端点限流 120 req/min/IP,而 CN 运营商级 NAT 会把
 * 上百个真实用户压在同一个出口 IP 后面 —— 一次性打 20 枪会把整个 NAT 后的用户一起打成 429。
 * 宿主 ViewPager 用 BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT,配上这个才是「滑到哪年拉哪年」。
 */
class YearRankIllustFeedFragment : IllustFeedFragment() {

    private val year: String by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(ARG_YEAR).orEmpty()
    }

    override val feedViewModel by feedViewModels(autoLoad = false) {
        // 零捕获:只捕获局部值,不把 Fragment 钉进 VM。
        val year = year
        BookmarkRankFeedSource(year = year)
    }

    // shaft-api-v2 的 next_url 是 shaft 绝对 URL,不是 app-api illust nextUrl;别漏进详情页 pager
    // (getNextIllust 拿它当 @Url 请求会拿到 MostBookmarkedResponse 形状,解析成空 IllustResponse)。
    override val detailContinuationCursor: String? get() = null

    // 榜单 bean 是第三方上报快照:is_bookmarked 被 source 伪造成 false、user.is_followed 是
    // 上报者的——都不可信,喂池会把当前用户更新的收藏/关注态盖回去。同 WatchLaterFeedFragment 先例。
    override fun poolableBeansOf(item: FeedItem): List<IllustsBean> = emptyList()

    companion object {
        private const val ARG_YEAR = "year_rank_year"

        /** [year] 是 4 位年份字符串,服务端 enum 语义(不是展示文案),别本地化。 */
        @JvmStatic
        fun newInstance(year: String): YearRankIllustFeedFragment {
            return YearRankIllustFeedFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_YEAR, year)
                }
            }
        }
    }
}
