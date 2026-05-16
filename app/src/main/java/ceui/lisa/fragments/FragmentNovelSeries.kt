package ceui.lisa.fragments

import androidx.appcompat.widget.Toolbar
import androidx.databinding.ViewDataBinding
import ceui.lisa.R
import ceui.lisa.activities.BaseActivity
import ceui.lisa.adapters.BaseAdapter
import ceui.lisa.adapters.NovelSeriesAdapter
import ceui.lisa.core.BaseRepo
import ceui.lisa.databinding.FragmentBaseListBinding
import ceui.lisa.model.ListNovelSeries
import ceui.lisa.models.NovelSeriesItem
import ceui.lisa.repo.NovelSeriesRepo
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.pixiv.ui.novel.CrossSeriesDownloadOptionsSheet
import ceui.pixiv.ui.novel.reader.export.ExportFormat
import ceui.pixiv.ui.novel.reader.ui.ExportFormatCallback
import ceui.pixiv.ui.novel.reader.ui.ExportSheet
import ceui.pixiv.ui.task.CrossSeriesDownloadTask
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction

/**
 * 某作者「小说系列」总览页（注意：不是单个系列详情页 NovelSeriesFragment）。
 *
 * 新增能力：顶部 Toolbar 下载按钮，点击弹出 [CrossSeriesDownloadOptionsSheet]
 * 三选一——
 *   - 选择下载：多选系列，每个系列各自合并为独立文件
 *   - 全部下载：全部系列，每个各自合并为独立文件
 *   - 合并下载：全部系列合并为唯一一个文件
 */
class FragmentNovelSeries :
    NetListFragment<FragmentBaseListBinding, ListNovelSeries, NovelSeriesItem>(),
    ExportFormatCallback {

    override fun adapter(): BaseAdapter<*, out ViewDataBinding> {
        return NovelSeriesAdapter(allItems, mContext)
    }

    override fun repository(): BaseRepo {
        return NovelSeriesRepo(mActivity.intent.getIntExtra(Params.USER_ID, 0))
    }

    override fun getToolbarTitle(): String {
        return getString(R.string.string_257)
    }

    override fun initToolbar(toolbar: Toolbar) {
        super.initToolbar(toolbar)
        // 通过菜单加一个下载 icon，复用 FragmentNovelSeriesDetail 里同样的模式。
        toolbar.inflateMenu(R.menu.cross_series_download)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.cross_series_download) {
                onClickDownloadEntry()
                true
            } else {
                false
            }
        }
    }

    private fun onClickDownloadEntry() {
        if (!isAdded) return
        if (allItems.isNullOrEmpty()) {
            Common.showToast(getString(R.string.cross_series_no_series_loaded))
            return
        }
        val sheet = CrossSeriesDownloadOptionsSheet()
        sheet.configure { option ->
            when (option) {
                CrossSeriesDownloadOptionsSheet.Option.Pick -> showSeriesPicker()
                CrossSeriesDownloadOptionsSheet.Option.All -> runPerSeries(allItems.toList())
                CrossSeriesDownloadOptionsSheet.Option.Merge -> runMergeAll()
            }
        }
        sheet.show(childFragmentManager, CrossSeriesDownloadOptionsSheet.TAG)
    }

    /**
     * 多选对话框：QMUI MultiCheckableDialogBuilder。避免引入单独的
     * ActionMode 状态到 NovelSeriesAdapter，保持列表页自身不变。
     */
    private fun showSeriesPicker() {
        val list = allItems.toList()
        if (list.isEmpty()) {
            Common.showToast(getString(R.string.cross_series_no_series_loaded))
            return
        }
        val titles: Array<CharSequence> = list.map { it.title.orEmpty() as CharSequence }.toTypedArray()

        val builder = QMUIDialog.MultiCheckableDialogBuilder(mContext)
            .setTitle(getString(R.string.cross_series_pick_dialog_title))
            .setCheckedItems(intArrayOf())
        builder.addItems(titles) { _, _ -> /* multi-state auto-tracked */ }
        builder.addAction(getString(R.string.cross_series_pick_dialog_cancel)) { d, _ -> d.dismiss() }
        builder.addAction(getString(R.string.sure)) { d, _ ->
            val indexes = builder.checkedItemIndexes
            if (indexes == null || indexes.isEmpty()) {
                Common.showToast(getString(R.string.cross_series_pick_empty))
                return@addAction
            }
            val pickedSet = indexes.toHashSet()
            val picked = list.filterIndexed { idx, _ -> pickedSet.contains(idx) }
            d.dismiss()
            runPerSeries(picked)
        }
        builder.create().show()
    }

    /**
     * 用户在 CrossSeriesDownloadOptionsSheet 选完模式后，再弹 ExportSheet 选
     * 输出格式。把"要做什么"暂存到 [pendingMergeAction]，等 sheet 回调拿到
     * format 再真正启动 task。
     */
    private var pendingMergeAction: ((ExportFormat) -> Unit)? = null

    private fun runPerSeries(seriesList: List<NovelSeriesItem>) {
        if (!isAdded) return
        pendingMergeAction = { format -> startPerSeries(seriesList, format) }
        ExportSheet().show(childFragmentManager, ExportSheet.TAG)
    }

    private fun runMergeAll() {
        val list = allItems.toList()
        if (list.isEmpty()) {
            Common.showToast(getString(R.string.cross_series_no_series_loaded))
            return
        }
        if (!isAdded) return
        pendingMergeAction = { format -> startMergeAll(list, format) }
        ExportSheet().show(childFragmentManager, ExportSheet.TAG)
    }

    override fun onExportFormatChosen(format: ExportFormat) {
        pendingMergeAction?.invoke(format)
        pendingMergeAction = null
    }

    private fun startPerSeries(seriesList: List<NovelSeriesItem>, format: ExportFormat) {
        val act = activity as? BaseActivity<*> ?: return
        CrossSeriesDownloadTask.runPerSeries(
            activity = act,
            seriesList = seriesList,
            format = format,
        ) { _, failures ->
            if (!isAdded) return@runPerSeries
            if (failures.isEmpty()) return@runPerSeries
            val msg = failures.joinToString(separator = "\n") { f ->
                "《${f.seriesTitle}》— ${f.reason}"
            }
            QMUIDialog.MessageDialogBuilder(mContext)
                .setTitle(
                    getString(R.string.batch_download_some_failed, failures.size)
                )
                .setMessage(msg)
                .addAction(0, android.R.string.ok, QMUIDialogAction.ACTION_PROP_POSITIVE) { d, _ ->
                    d.dismiss()
                }
                .show()
        }
    }

    private fun startMergeAll(list: List<NovelSeriesItem>, format: ExportFormat) {
        val act = activity as? BaseActivity<*> ?: return
        // 作者 id / name：allItems 里任何一项的 user 都指向该作者本人。
        val firstUser = list.firstOrNull { it.user != null }?.user
        val authorId = firstUser?.id ?: mActivity.intent.getIntExtra(Params.USER_ID, 0)
        val authorName = firstUser?.name
        CrossSeriesDownloadTask.runAllMergedOne(
            activity = act,
            seriesList = list,
            authorName = authorName,
            authorId = authorId,
            format = format,
        ) { _, _ -> /* toast handled inside task */ }
    }
}
