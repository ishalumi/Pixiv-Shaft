package ceui.pixiv.ui.detail

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.lisa.feature.FeatureEntity
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.pixiv.db.discovery.DiscoveryPool
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.feeds.pixiv.replayNextUrl
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「相关作品」页（feeds 框架版，替代 legacy FragmentRelatedIllust + RelatedIllustRepo + IAdapter）。
 * TemplateActivity 宿主、自带 toolbar（fragment_toolbar_feed），卡片复用 [IllustFeedFragment] 的
 * 标准瀑布流插画卡。
 *
 * 与 legacy 对齐：
 * - toolbar 标题 = 作品标题 + 「的相关作品」；toolbar 菜单保留「收藏到精华」(local_save 去掉
 *   action_jump/action_add)，把当前列表存进 FeatureEntity 精华库；
 * - 每页过滤前整页喂 DiscoveryPool（对齐 RelatedIllustRepo.doOnNext）；
 * - 翻页门控：`isRelatedIllustNoLimit`（「相关作品无限下滑」）关时只出首页。legacy 的 hasNext
 *   只挡住下拉页脚、挡不住滚动预载（scroll-preload 绕过 hasNext 照常翻页），等于设置形同虚设；
 *   这里让设置在唯一的翻页路径上真正生效（对齐 [[feedback_settings_apply_everywhere]] 的一致性要求）。
 */
class RelatedIllustFeedFragment : IllustFeedFragment(R.layout.fragment_toolbar_feed) {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    // legacy 用 int ILLUST_ID（TemplateActivity 路由 getIntExtra、ArtworkDetailItem.RelatedHeader.illustId:Int）。
    private val illustId: Long by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getInt(Params.ILLUST_ID).toLong()
    }
    private val title: String by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(Params.ILLUST_TITLE).orEmpty()
    }

    override val feedViewModel by feedViewModels {
        // VM 只长期持有 source；RelatedIllustFeedSource 只吃一个 Long，天然零 Fragment 捕获。
        // （工厂 lambda 本身读 illustId 会闭包 this，但它只在建 VM 时跑一次、不被 VM 留存，无泄漏。）
        RelatedIllustFeedSource(illustId)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.text = title + getString(R.string.string_231)
        binding.toolbar.inflateMenu(R.menu.local_save)
        // 对齐 legacy：只留「收藏到精华」，去掉跳转/添加。
        binding.toolbar.menu.removeItem(R.id.action_jump)
        binding.toolbar.menu.removeItem(R.id.action_add)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_bookmark) {
                saveToFeatures()
                true
            } else {
                false
            }
        }
    }

    /** 把当前已加载的相关作品存进精华库（对齐 legacy action_bookmark）。序列化 + Room 写挪 IO 避免卡主线程。 */
    private fun saveToFeatures() {
        val beans = ArrayList(currentIllustItems().map { it.bean })
        val appCtx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val entity = FeatureEntity().apply {
                    uuid = "${illustId}相关作品"
                    dataType = "相关作品"
                    illustID = illustId.toInt()
                    illustTitle = title
                    illustJson = Common.cutToJson(beans)
                    dateTime = System.currentTimeMillis()
                }
                AppDatabase.getAppDatabase(appCtx).downloadDao().insertFeature(entity)
            }
            Common.showToast("已收藏到精华")
        }
    }

    companion object {
        @JvmStatic
        fun newInstance(id: Int, title: String?): RelatedIllustFeedFragment {
            return RelatedIllustFeedFragment().apply {
                arguments = Bundle().apply {
                    putInt(Params.ILLUST_ID, id)
                    putString(Params.ILLUST_TITLE, title)
                }
            }
        }
    }
}

/**
 * 相关作品数据源：v2/illust/related（suspend）。翻页 replayNextUrl；`isRelatedIllustNoLimit` 关时
 * 只出首页（nextCursor=null，对齐 legacy RelatedIllustRepo.hasNext）。每页过滤前整页喂 DiscoveryPool。
 */
class RelatedIllustFeedSource(private val illustId: Long) : FeedSource<String> {

    private val gson = Gson()

    override suspend fun load(cursor: String?): FeedPage<String> {
        val resp: IllustResponse = if (cursor == null) {
            Client.appApi.getRelatedIllusts(illustId)
        } else {
            replayNextUrl(gson, cursor, IllustResponse::class.java)
        }
        val items = withContext(Dispatchers.Default) {
            mapRelatedPage(resp.illusts, cursor == null, illustId)
        }
        // 翻页门控：「相关作品无限下滑」关时只出首页（nextCursor=null 让 loadMore 直接 return）。
        val next = if (Shaft.sSettings.isRelatedIllustNoLimit) {
            resp.next_url?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        return FeedPage(items, next)
    }

    companion object {
        /** 跑在 Default 线程、被 VM 长期持有，放伴生对象保证零捕获。 */
        private fun mapRelatedPage(
            illusts: List<Illust>,
            isFirstPage: Boolean,
            illustId: Long,
        ): List<FeedItem> {
            val pairs = illusts.mapNotNull { illust ->
                IllustFeedItem.beanOf(illust)?.let { bean -> illust to bean }
            }
            // 对齐 RelatedIllustRepo.doOnNext：过滤前整页喂 DiscoveryPool。
            DiscoveryPool.collect(
                pairs.map { it.second },
                if (isFirstPage) "related:$illustId" else "related_next:$illustId",
            )
            return pairs.mapNotNull { (illust, bean) -> IllustFeedItem.of(illust, bean) }
        }
    }
}
