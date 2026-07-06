package ceui.pixiv.ui.watchlater

import android.os.Bundle
import android.view.View
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction
import ceui.lisa.R
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.utils.Common
import ceui.loxia.requireEntityWrapper
import ceui.pixiv.db.RecordType
import ceui.pixiv.ui.common.IllustCardHolder
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.detail.showV3Menu
import ceui.pixiv.ui.history.HistoryViewModel
import ceui.pixiv.ui.slideshow.SlideshowLauncher
import ceui.pixiv.utils.setOnClick

/**
 * 「稍后再看」列表页。复用浏览历史那一套(general_table + IllustCardHolder + HistoryViewModel),
 * 只是 recordType = WATCH_LATER。列表 UI 与浏览历史一致(issue 要求「参考历史记录 UI」)。
 * 单条移除走卡片长按菜单的「移出稍后再看」,通过 [WatchLaterActionReceiver] 即时刷新本页。
 */
class WatchLaterFragment : PixivFragment(R.layout.fragment_pixiv_list), WatchLaterActionReceiver {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val viewModel by constructVM({
        AppDatabase.getAppDatabase(requireContext())
    }) { database ->
        HistoryViewModel(database, RecordType.WATCH_LATER)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel, ListMode.STAGGERED_GRID)

        // setUpRefreshState 里 setUpToolbar 把 naviMore 设成了 wiggle,这里覆盖成本页的操作菜单。
        binding.toolbarLayout.naviTitle.setText(R.string.watch_later)
        binding.toolbarLayout.naviMore.setOnClick { showActionMenu() }
    }
    // 不在 onResume 里 refresh:refresh 会把 _offset 归零只拉第一页,分页列表(>30)返回本页
    // 时会塌回前 30 条 + 丢滚动位置。本页每次都是新开的 TemplateActivity(init 已 load),
    // 同页移除走 removeHolderById 即时更新,他处新增靠下拉刷新 —— 与浏览历史页保持一致。

    private fun showActionMenu() {
        showV3Menu("WatchLaterMenu") {
            item(getString(R.string.watch_later_play_all), R.drawable.ic_baseline_play_arrow_24) {
                playAll()
            }
            item(getString(R.string.watch_later_clear), R.drawable.ic_not_interested_black_24dp) {
                confirmClear()
            }
        }
    }

    private fun playAll() {
        val illusts = viewModel.holders.value.orEmpty()
            .filterIsInstance<IllustCardHolder>()
            .map { it.illust }
        if (illusts.isEmpty()) {
            Common.showToast(R.string.watch_later_empty)
            return
        }
        SlideshowLauncher.launchFromIllusts(requireContext(), illusts, 0, true)
    }

    private fun confirmClear() {
        val ctx = context ?: return
        if (viewModel.holders.value.orEmpty().isEmpty()) {
            Common.showToast(R.string.watch_later_empty)
            return
        }
        // 提前抓好 entityWrapper(EntityWrapper 是 app 单例):弹窗动作是异步触发的,
        // 那时 fragment 可能已 detach,再调 requireEntityWrapper()->requireActivity() 会崩。
        val entityWrapper = requireEntityWrapper()
        QMUIDialog.MessageDialogBuilder(ctx)
            .setTitle(R.string.watch_later)
            .setMessage(R.string.watch_later_clear_confirm)
            .setSkinManager(QMUISkinManager.defaultInstance(ctx))
            .addAction(R.string.string_142) { dialog, _ -> dialog.dismiss() }
            .addAction(0, R.string.watch_later_clear_ok, QMUIDialogAction.ACTION_PROP_NEGATIVE) { dialog, _ ->
                dialog.dismiss()
                entityWrapper.clearWatchLater(ctx.applicationContext)
                viewModel.clearHolders()
                Common.showToast(R.string.watch_later_cleared)
            }
            .show()
    }

    override fun onWatchLaterRemoved(illustId: Long) {
        viewModel.removeHolderById(illustId)
    }
}
