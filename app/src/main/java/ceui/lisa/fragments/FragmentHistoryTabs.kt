package ceui.lisa.fragments

import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuItemCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.databinding.ViewpagerWithTablayoutBinding
import ceui.pixiv.ui.common.viewBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tab wrapper for the browsing-history page.
 * Three tabs: 插画/漫画 (type=0) | 小说 (type=1) | 用户 (GeneralEntity)
 *
 * 顶部 toolbar 上挂一个 SearchView (R.menu.history_v3 复用,delete 项隐藏)。
 * 输入 debounce 200ms 后写入 [HistorySearchSharedViewModel.query]，三个子
 * tab 各自 collect 后切换数据源 (DAO LIKE)。
 */
class FragmentHistoryTabs : Fragment(R.layout.viewpager_with_tablayout) {

    private val binding by viewBinding(ViewpagerWithTablayoutBinding::bind)
    private val searchVm: HistorySearchSharedViewModel by activityViewModels()

    private var searchDebounceJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.title = " "
        binding.toolbarTitle.text = getString(R.string.view_history)
        binding.toolbar.setNavigationOnClickListener { activity?.finish() }

        val tabs = listOf(
            getString(R.string.string_246) to 0,    // 插画/漫画
            getString(R.string.string_237) to 1,    // 小说
            getString(R.string.tab_user) to -1,     // 用户
        )

        val fragments = tabs.map { (_, type) ->
            if (type >= 0) {
                FragmentHistoryList.newInstance(type)
            } else {
                FragmentHistoryUserList()
            }
        }

        binding.viewPager.adapter = object : FragmentPagerAdapter(
            childFragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT
        ) {
            override fun getItem(position: Int): Fragment = fragments[position]
            override fun getCount(): Int = tabs.size
            override fun getPageTitle(position: Int): CharSequence = tabs[position].first
        }
        binding.tabLayout.setupWithViewPager(binding.viewPager)

        setupSearchMenu()
    }

    /**
     * inflate R.menu.history_v3 到 toolbar，隐藏 delete (这里没"删全部"行为)，
     * SearchView 输入 debounce 200ms 后写入共享 query。
     */
    private fun setupSearchMenu() {
        binding.toolbar.inflateMenu(R.menu.history_v3)
        binding.toolbar.menu.findItem(R.id.action_delete)?.isVisible = false
        val searchItem: MenuItem = binding.toolbar.menu.findItem(R.id.action_search) ?: return
        val searchView = MenuItemCompat.getActionView(searchItem) as? SearchView ?: return
        searchView.queryHint = getString(R.string.history_search_hint)
        searchView.maxWidth = Int.MAX_VALUE
        // 展开搜索框后,优先用返回手势/键收起搜索框,不直接退出 activity。
        // callback.isEnabled 在 expand/collapse listener 里切。
        val backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (searchItem.isActionViewExpanded) searchItem.collapseActionView()
                isEnabled = false
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        // toolbar 背景 = ?attr/colorPrimary（用户主题色,中等亮度紫/蓝/绿等），
        // SearchView 默认 text/hint 是 ?attr/textColorPrimary/Hint，在浅色 day
        // theme 下解出来是黑/深灰,叠在彩色 toolbar 上完全看不清。强制白字 +
        // 70% 白 hint,跟周围的 navigationIcon (ic_arrow_back_white_shadow) 一致。
        searchView.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)?.apply {
            setTextColor(Color.WHITE)
            setHintTextColor(0xB3FFFFFF.toInt())
        }

        MenuItemCompat.setOnActionExpandListener(searchItem, object : MenuItemCompat.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                searchVm.setQuery("")
                backCallback.isEnabled = true
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                searchDebounceJob?.cancel()
                searchVm.setQuery(null)
                backCallback.isEnabled = false
                return true
            }
        })

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                searchVm.setQuery(query.orEmpty().trim())
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                searchDebounceJob?.cancel()
                searchDebounceJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(200)
                    searchVm.setQuery(newText.orEmpty().trim())
                }
                return true
            }
        })
    }
}
