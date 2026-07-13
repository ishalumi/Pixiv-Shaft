package ceui.pixiv.ui.prime

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import com.blankj.utilcode.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 单个 Prime 标签的精选插画（feeds 框架版）。数据来自内置 assets JSON，一次性
 * 全量读出，没有分页也没有网络请求，因此不接 [FeedSource.loadFromCache] 本地
 * 优先缓存——数据本身就在本地，缓存一层纯属多余。
 *
 * 点击/收藏/长按菜单全部继承自 [IllustFeedFragment]：比旧版（单独发请求打开
 * 一张不带滑动的详情）多了同列表内的滑动翻页。
 */
class PrimeTagDetailFragment : IllustFeedFragment(R.layout.fragment_toolbar_feed) {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    override val feedViewModel by feedViewModels<String> {
        val path = requireArguments().getString(ARG_PATH).orEmpty()
        FeedSource { _ ->
            // gson 树转换 + 内容过滤逐条不便宜，和条目解析一起留在后台，不能漏到主线程
            // （main-safe 契约见 PixivFeedSource.load 的同款 withContext 用法）
            val items = withContext(Dispatchers.IO) {
                val json = Utils.getApp().assets.open(path).bufferedReader().use { it.readText() }
                val illusts = Shaft.sGson.fromJson(json, PrimeTagResult::class.java).resp.illusts
                illusts.mapNotNull { illust -> IllustFeedItem.from(illust) }
            }
            FeedPage(items, null)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.text = requireArguments().getString(ARG_NAME)
    }

    companion object {
        private const val ARG_NAME = "name"
        private const val ARG_PATH = "path"

        fun newInstance(name: String, path: String): PrimeTagDetailFragment {
            return PrimeTagDetailFragment().apply {
                arguments = bundleOf(ARG_NAME to name, ARG_PATH to path)
            }
        }
    }
}
