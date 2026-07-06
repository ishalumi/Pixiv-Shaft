package ceui.pixiv.ui.watchlater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction
import com.scwang.smart.refresh.header.MaterialHeader
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.adapters.IAdapter
import ceui.lisa.databinding.FragmentWatchLaterBinding
import ceui.lisa.helper.StaggeredManager
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Common
import ceui.lisa.utils.DensityUtil
import ceui.lisa.view.SpacesItemDecoration
import ceui.loxia.requireEntityWrapper
import ceui.pixiv.db.EntityWrapper
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.detail.showV3Menu
import ceui.pixiv.ui.slideshow.SlideshowLauncher
import ceui.pixiv.utils.setOnClick

/**
 * 「稍后再看」列表页。**必须用 legacy IAdapter**(recy_illust_stagger)渲染:它点击走
 * VActivity + Container,不依赖 NavController,能在非 NavHost 的 TemplateActivity 里正常
 * 打开详情;V3 的 IllustCardHolder 点击走 findNavController,在 TemplateActivity 里必崩。
 *
 * 数据来自 general_table(WATCH_LATER),存的是 ceui.loxia.Illust JSON,字段名与
 * IllustsBean 完全一致,直接反序列化成 IllustsBean 喂给 IAdapter。增删清空经 EntityWrapper
 * 发本地广播,本页收到后重新拉 DB 刷新(所以长按「移出」后能立刻消失)。
 */
class WatchLaterFragment : Fragment(R.layout.fragment_watch_later) {

    private val binding by viewBinding(FragmentWatchLaterBinding::bind)
    private val viewModel: WatchLaterViewModel by viewModels()

    // 只是 legacy IAdapter 的渲染缓冲(它构造时按引用持有一个 List),
    // 数据真源是 viewModel.items,这份靠 observer 同步过来。
    private val items = mutableListOf<IllustsBean>()
    private val adapter by lazy { IAdapter(items, requireContext()).apply { setUuid("watch_later") } }

    private val changeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            viewModel.reload()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding.toolbarLayout, binding.listView)
        binding.toolbarLayout.naviTitle.setText(R.string.watch_later)
        binding.toolbarLayout.naviMore.setOnClick { showActionMenu() }

        binding.listView.layoutManager =
            StaggeredManager(Shaft.sSettings.lineCount, androidx.recyclerview.widget.StaggeredGridLayoutManager.VERTICAL)
        binding.listView.addItemDecoration(SpacesItemDecoration(DensityUtil.dp2px(8f)))
        binding.listView.adapter = adapter

        binding.refreshLayout.setRefreshHeader(MaterialHeader(requireContext()))
        binding.refreshLayout.setEnableLoadMore(false)
        binding.refreshLayout.setOnRefreshListener {
            viewModel.reload { if (getView() != null) binding.refreshLayout.finishRefresh() }
        }

        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(changeReceiver, IntentFilter(EntityWrapper.ACTION_WATCH_LATER_CHANGED))

        viewModel.items.observe(viewLifecycleOwner) { beans ->
            items.clear()
            items.addAll(beans)
            adapter.notifyDataSetChanged()
            binding.emptyLayout.isVisible = beans.isEmpty()
        }
        viewModel.reload()
    }

    override fun onResume() {
        super.onResume()
        // 从详情返回后收藏状态可能变了,重拉一次让红心同步(本地查询,便宜)。
        viewModel.reload()
    }

    override fun onDestroyView() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(changeReceiver)
        super.onDestroyView()
    }

    private fun showActionMenu() {
        showV3Menu("WatchLaterMenu") {
            item(getString(R.string.watch_later_play_all), R.drawable.ic_baseline_play_arrow_24) {
                val current = viewModel.current
                if (current.isEmpty()) {
                    Common.showToast(R.string.watch_later_empty)
                } else {
                    SlideshowLauncher.launchFromIllustsBeans(requireContext(), ArrayList(current), 0, true)
                }
            }
            item(getString(R.string.watch_later_clear), R.drawable.ic_not_interested_black_24dp) {
                confirmClear()
            }
        }
    }

    private fun confirmClear() {
        val ctx = context ?: return
        if (viewModel.current.isEmpty()) {
            Common.showToast(R.string.watch_later_empty)
            return
        }
        // EntityWrapper 是 app 单例;提前抓好,弹窗动作异步触发时 fragment 可能已 detach。
        val entityWrapper = requireEntityWrapper()
        QMUIDialog.MessageDialogBuilder(ctx)
            .setTitle(R.string.watch_later)
            .setMessage(R.string.watch_later_clear_confirm)
            .setSkinManager(QMUISkinManager.defaultInstance(ctx))
            .addAction(R.string.string_142) { d, _ -> d.dismiss() }
            .addAction(0, R.string.watch_later_clear_ok, QMUIDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                d.dismiss()
                // clearWatchLater 会发广播触发 reload,不用手动清 items。
                entityWrapper.clearWatchLater(ctx.applicationContext)
                Common.showToast(R.string.watch_later_cleared)
            }
            .show()
    }
}
