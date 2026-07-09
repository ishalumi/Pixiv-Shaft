package ceui.pixiv.ui.discovery

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.NullCtrl
import ceui.lisa.model.ListIllust
import ceui.lisa.models.IllustsBean
import ceui.lisa.repo.LatestIllustRepo
import ceui.pixiv.ui.prime.PrimeTagIndexItem
import ceui.pixiv.ui.recommend.RecentWorksRepo
import ceui.pixiv.ui.recommend.TrendingWorksRepo
import com.blankj.utilcode.util.Utils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 发现页(FragmentCenter)各内容货架的数据持有者。把侧边栏「发现」分组的内容直接铺进 tab:
 *   · [primeTags]   热度标签 —— 本地策展 asset(prime_index.json),离线秒开
 *   · [latest]      最新     —— [LatestIllustRepo] getNewWorks(全站新投稿)
 *   · [siteRecommend] 本月收藏 —— [TrendingWorksRepo] shaft-api-v2 周收藏榜(非 Lite)
 *   · [recentHot]   当前最热 —— [RecentWorksRepo] shaft-api-v2 实时收藏流(非 Lite)
 *
 * 每条货架只拉一页、截断到 [RAIL_LIMIT] 张;点「查看全部」跳原来的整页(零新后端)。
 * 数据存这里而非 Fragment 字段:tab 来回切不重拉,配置变更后货架还在。
 */
class DiscoverViewModel : ViewModel() {

    private val _primeTags = MutableLiveData<List<PrimeTagIndexItem>>()
    val primeTags: LiveData<List<PrimeTagIndexItem>> get() = _primeTags

    private val _latest = MutableLiveData<List<IllustsBean>>()
    val latest: LiveData<List<IllustsBean>> get() = _latest

    private val _siteRecommend = MutableLiveData<List<IllustsBean>>()
    val siteRecommend: LiveData<List<IllustsBean>> get() = _siteRecommend

    private val _recentHot = MutableLiveData<List<IllustsBean>>()
    val recentHot: LiveData<List<IllustsBean>> get() = _recentHot

    private var started = false

    /** 首次可见时懒加载;加载过就跳过(tab 来回切不重拉)。 */
    fun start(includeServerShelves: Boolean) {
        if (started) return
        started = true
        reload(includeServerShelves)
    }

    /** 下拉刷新 / 双击 tab forceRefresh:重拉全部货架。 */
    fun reload(includeServerShelves: Boolean) {
        loadTags()
        loadIllustRail(LatestIllustRepo("illust"), _latest)
        // 站长推荐(本月收藏)/ 当前最热走自建 shaft-api-v2,Lite 渠道不展示这两条。
        if (includeServerShelves) {
            loadIllustRail(TrendingWorksRepo("illust", RAIL_LIMIT), _siteRecommend)
            loadIllustRail(RecentWorksRepo("illust", RAIL_LIMIT), _recentHot)
        }
    }

    private fun loadIllustRail(
        repo: RemoteRepo<ListIllust>,
        live: MutableLiveData<List<IllustsBean>>
    ) {
        // getFirstData 内部 subscribeOn(newThread).observeOn(mainThread),回调回到主线程。
        repo.getFirstData(object : NullCtrl<ListIllust>() {
            override fun success(t: ListIllust) {
                // 过滤 user==null 的脏数据:RAdapter 直接取 user.name / 头像,null 会 NPE 崩。
                live.value = t.illusts?.filter { it.user != null }?.take(RAIL_LIMIT) ?: emptyList()
            }

            override fun nullSuccess() {
                live.value = emptyList()
            }

            override fun error(e: Throwable) {
                // 失败静默塌陷(不调 super,避免 ErrorCtrl 对后台货架弹 toast);发空→收起该货架。
                live.value = emptyList()
            }
        })
    }

    private fun loadTags() {
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                try {
                    val json = Utils.getApp().assets.open(INDEX_FILE)
                        .bufferedReader().use { it.readText() }
                    val type = object : TypeToken<List<PrimeTagIndexItem>>() {}.type
                    Gson().fromJson<List<PrimeTagIndexItem>>(json, type) ?: emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
            }
            // 随机展示:每次真正构建 fragment(新 VM)重新洗牌,不再永远头三个「超电磁炮/对魔忍…」。
            // tab 来回切 VM 不重建、started 门控住,所以不会一切换就换一批(符合用户要的口径)。
            _primeTags.value = items.shuffled().take(TAG_LIMIT)
        }
    }

    companion object {
        private const val INDEX_FILE = "pixiv_prime/prime_index.json"
        private const val RAIL_LIMIT = 12
        private const val TAG_LIMIT = 15
    }
}
