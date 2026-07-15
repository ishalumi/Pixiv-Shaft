package ceui.pixiv.ui.recommend

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import ceui.lisa.R
import ceui.lisa.databinding.ViewpagerWithTablayoutBinding
import ceui.pixiv.ui.common.viewBinding

/**
 * 站长推荐 — 从自建服务端 shaft-api-v2 拉当前周收藏趋势 top-N,三个 tab:
 *   插画 (type=illust) / 漫画 (type=manga) / 小说 (type=novel)
 * 服务端按 bookmark_count desc 返回,只回带 payload 的 id,直接渲染。
 */
class FragmentSiteRecommend : Fragment(R.layout.viewpager_with_tablayout) {

    private val binding by viewBinding(ViewpagerWithTablayoutBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.title = " "
        binding.toolbarTitle.text = getString(R.string.site_recommend)
        binding.toolbar.setNavigationOnClickListener { activity?.finish() }

        val titles = listOf(
            getString(R.string.type_illust),
            getString(R.string.type_manga),
            getString(R.string.type_novel),
        )
        // dataType 传 server 端的稳定 enum,不传 localized 的 R.string.type_*。
        // 否则系统语言切换后 ViewPager 从 SavedState 恢复出的旧 dataType 跟新的
        // getString(...) 对不上,漫画 tab 会落到 else 分支变成插画 tab。
        val fragments: List<Fragment> = listOf(
            HotWorksIllustFeedFragment.newInstance(
                HotWorksSource.TRENDING, HotWorksIllustFeedFragment.TYPE_ILLUST, null
            ),
            HotWorksIllustFeedFragment.newInstance(
                HotWorksSource.TRENDING, HotWorksIllustFeedFragment.TYPE_MANGA, null
            ),
            HotWorksNovelFeedFragment.newInstance(HotWorksSource.TRENDING, null),
        )

        binding.viewPager.adapter = object : FragmentPagerAdapter(
            childFragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT
        ) {
            override fun getItem(position: Int): Fragment = fragments[position]
            override fun getCount(): Int = titles.size
            override fun getPageTitle(position: Int): CharSequence = titles[position]
        }
        binding.tabLayout.setupWithViewPager(binding.viewPager)
    }
}
