package ceui.pixiv.ui.notification

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import ceui.lisa.R
import ceui.lisa.databinding.ViewpagerWithTablayoutBinding
import ceui.pixiv.ui.common.ViewPagerFragment
import ceui.pixiv.ui.common.viewBinding

/**
 * 通知 + 公告 双 tab 宿主页。
 * [NotificationListFragment]/[InfoLatestFragment] 都已迁到 feeds 框架，用裸 fragment_feed
 * （本就没有 toolbar）——ViewPagerFragment 标记曾是给 PixivFragment.setUpToolbar 用来隐藏
 * 子页内部 toolbar 的，两个子页迁移后不再依赖这个判断，留着纯粹是无害的历史标记。
 */
class NotificationPagerFragment : Fragment(R.layout.viewpager_with_tablayout), ViewPagerFragment {

    private val binding by viewBinding(ViewpagerWithTablayoutBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.title = " "
        binding.toolbarTitle.text = getString(R.string.notifications_and_info)
        binding.toolbar.setNavigationOnClickListener { activity?.finish() }

        val tabs = listOf(
            getString(R.string.tab_notifications) to ::NotificationListFragment,
            getString(R.string.tab_info) to ::InfoLatestFragment,
        )

        val fragments: List<Fragment> = tabs.map { it.second() }

        binding.viewPager.adapter = object : FragmentPagerAdapter(
            childFragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT
        ) {
            override fun getItem(position: Int): Fragment = fragments[position]
            override fun getCount(): Int = tabs.size
            override fun getPageTitle(position: Int): CharSequence = tabs[position].first
        }
        binding.tabLayout.setupWithViewPager(binding.viewPager)
    }
}
