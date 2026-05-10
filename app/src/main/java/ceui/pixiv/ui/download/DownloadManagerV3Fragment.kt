package ceui.pixiv.ui.download

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import ceui.lisa.R
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch

/**
 * V3 设计哲学的下载管理页：单页面 3 tab 容器。
 *
 * Tab 0: 批量队列  · count → 持久化 download_queue 中 PENDING+DOWNLOADING
 * Tab 1: 正在下载  · count → Manager.content 内存队列大小
 * Tab 2: 已完成    · count → download_queue 里 SUCCESS 的累积
 *
 * 数字直接追加到 tab 文字后面，避免单独占一行。
 */
class DownloadManagerV3Fragment : Fragment() {

    private val sharedVm: DownloadManagerSharedViewModel by activityViewModels()

    private var tabs: TabLayout? = null
    private var pager: ViewPager2? = null
    private var pageCallback: ViewPager2.OnPageChangeCallback? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_download_manager_v3, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.findViewById<Toolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        val pager = view.findViewById<ViewPager2>(R.id.viewPager).also { this.pager = it }
        tabs = view.findViewById<TabLayout>(R.id.tabLayout)

        pager.adapter = TabsAdapter(this)
        pager.offscreenPageLimit = 2

        TabLayoutMediator(tabs!!, pager) { tab, pos ->
            tab.text = baseLabel(pos)
        }.attach()

        // toolbar 最右侧的"导出"menu —— 只在批量队列 (pos 0) 和已完成 (pos 2)
        // 这两个有 illust 列表的 tab 显示，正在下载 (pos 1) 是瞬态进度页，
        // 没有稳定数据快照可导，直接隐藏避免误点。点击仅 emit 信号到
        // [DownloadManagerSharedViewModel.exportRequest]，由当前可见的子
        // fragment 自己拉数据 → [DownloadExportLinks.present]。
        toolbar.inflateMenu(R.menu.menu_download_manager)
        val exportItem = toolbar.menu.findItem(R.id.action_export)
        // 必须清 iconTintList — Toolbar 默认会拿 colorControlNormal 强行覆盖
        // menu icon 的颜色，把 vector 自带的 android:tint=v3_text_1 压成淡色
        // （浅色主题下跟白底融合看不见）。同 BulkSelectV3.refreshSelectToggleIcon
        // 末尾对 select_toggle 做的处理。
        exportItem?.iconTintList = null
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_export -> {
                    sharedVm.requestExport(pager.currentItem)
                    true
                }
                else -> false
            }
        }
        fun applyExportVisibility(pos: Int) {
            exportItem.isVisible = pos == 0 || pos == 2
        }
        applyExportVisibility(pager.currentItem)
        pageCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                applyExportVisibility(position)
            }
        }.also { pager.registerOnPageChangeCallback(it) }

        // 实时刷新 tab 文案末尾的数字
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                sharedVm.snapshots().collect { s ->
                    setTabCount(0, s.queuePending + s.queueDownloading)
                    setTabCount(1, s.activeCount)
                    setTabCount(2, s.queueSuccess)
                }
            }
        }
    }

    override fun onDestroyView() {
        pageCallback?.let { pager?.unregisterOnPageChangeCallback(it) }
        pageCallback = null
        pager = null
        tabs = null
        super.onDestroyView()
    }

    private fun baseLabel(pos: Int): String = when (pos) {
        0 -> getString(R.string.dlmgr_tab_queue)
        1 -> getString(R.string.dlmgr_tab_active)
        2 -> getString(R.string.dlmgr_tab_done)
        else -> ""
    }

    private fun setTabCount(pos: Int, count: Int) {
        val t = tabs?.getTabAt(pos) ?: return
        val base = baseLabel(pos)
        t.text = if (count > 0) "$base  $count" else base
    }

    private class TabsAdapter(host: Fragment) : FragmentStateAdapter(host) {
        override fun getItemCount(): Int = 3
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> QueueListV3Fragment()
            1 -> ActiveListV3Fragment()
            2 -> DoneListV3Fragment()
            else -> error("unreachable: $position")
        }
    }
}
