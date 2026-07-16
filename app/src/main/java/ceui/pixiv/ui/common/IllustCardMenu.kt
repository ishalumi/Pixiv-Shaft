package ceui.pixiv.ui.common

import android.content.Intent
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.dialogs.MuteDialog
import ceui.lisa.download.IllustDownload
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.loxia.requireEntityWrapper
import ceui.pixiv.ui.bulk.BulkSelectStorage
import ceui.pixiv.ui.detail.showV3Menu
import ceui.pixiv.ui.slideshow.SlideshowLauncher

/**
 * 插画卡长按菜单：屏蔽 / 相关评论 / 批量下载 / 单作品下载 / 幻灯片 / 稍后再看。
 *
 * 从 [IllustFeedFragment] 搬出来单独放一个文件：菜单是一组独立的动作编排，跟「列表怎么加载」
 * 和「卡片怎么画」都无关，挤在基类里只是让那个类更长。
 *
 * 各动作里的整表快照（`currentIllustItems()`）都在 lambda 内部取 —— 只在真的点了那一项时才
 * 复制列表，展开菜单本身零成本。
 */
internal fun IllustFeedFragment.showCardMenu(item: IllustFeedItem) {
    val bean = item.bean
    val entityWrapper = requireEntityWrapper()
    val inWatchLater = entityWrapper.isInWatchLater(item.illust.id)
    showV3Menu("IllustFeedCardMenu") {
        item(getString(R.string.string_111), R.drawable.ic_not_interested_black_24dp) {
            MuteDialog.newInstance(bean).show(childFragmentManager, "MuteDialog")
        }
        item(getString(R.string.string_112), R.drawable.ic_baseline_comment_24) {
            startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
                putExtra(TemplateActivity.EXTRA_FRAGMENT, "相关评论")
                putExtra(Params.ILLUST_ID, bean.id)
                putExtra(Params.ILLUST_TITLE, bean.title)
            })
        }
        item(getString(R.string.string_113), R.drawable.ic_file_download_black_24dp) {
            // 批量下载：整个列表交给 V3 多选页勾选（对齐 legacy IAdapter popup / MultiDownload）
            val beans = currentIllustItems().map { it.bean }
            if (beans.isNotEmpty()) {
                BulkSelectStorage.put(beans)
                startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
                    putExtra(TemplateActivity.EXTRA_FRAGMENT, "批量选择")
                })
            }
        }
        item(getString(R.string.string_339), R.drawable.ic_file_download_black_24dp) {
            IllustDownload.downloadIllustAllPages(bean)
            if (Shaft.sSettings.isAutoPostLikeWhenDownload() && !bean.isIs_bookmarked) {
                PixivOperate.postLikeDefaultStarType(bean)
            }
        }
        item(getString(R.string.slideshow_play), R.drawable.ic_baseline_play_arrow_24) {
            val beans = currentIllustItems().map { it.bean }
            val position = beans.indexOfFirst { it.id == bean.id }.coerceAtLeast(0)
            SlideshowLauncher.launchFromIllustsBeans(
                requireContext(), ArrayList(beans), position, true,
            )
        }
        val watchLaterLabel = getString(
            if (inWatchLater) R.string.watch_later_remove else R.string.watch_later_add
        )
        item(watchLaterLabel, R.drawable.ic_watch_later_24) {
            val appContext = requireContext().applicationContext
            if (inWatchLater) {
                entityWrapper.removeFromWatchLater(appContext, item.illust.id)
                Common.showToast(R.string.watch_later_removed)
            } else {
                entityWrapper.addToWatchLater(appContext, item.illust)
                Common.showToast(R.string.watch_later_added)
            }
        }
    }
}
