package ceui.pixiv.ui.recommend

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import ceui.lisa.R
import ceui.lisa.databinding.ViewpagerWithTablayoutBinding
import ceui.pixiv.ui.common.viewBinding

/**
 * 当前最热 — 打自建服务端 shaft-api-v2 的 recent/works:「现在正在被人收藏的作品」,
 * 按最近一次 bookmark 事件倒序、server 端按作品去重的实时流,三个 tab:
 *   插画 (type=illust) / 漫画 (type=manga) / 小说 (type=novel)
 * 跟 [FragmentSiteRecommend](本月收藏 = 当前周收藏加权榜)数据源不同:这是「现在在被
 * 收藏的」实时流,无 score pill。
 */
class FragmentRecentRecommend : Fragment(R.layout.viewpager_with_tablayout) {

    private val binding by viewBinding(ViewpagerWithTablayoutBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.title = " "
        binding.toolbarTitle.text = getString(R.string.current_hot)
        binding.toolbar.setNavigationOnClickListener { activity?.finish() }

        val titles = listOf(
            getString(R.string.type_illust),
            getString(R.string.type_manga),
            getString(R.string.type_novel),
        )
        // dataType 传 server 端稳定 enum,不传 localized(理由同 FragmentSiteRecommend)。
        val fragments: List<Fragment> = listOf(
            FragmentRecentIllust.newInstance(FragmentTrendingIllust.TYPE_ILLUST),
            FragmentRecentIllust.newInstance(FragmentTrendingIllust.TYPE_MANGA),
            FragmentRecentNovel(),
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
