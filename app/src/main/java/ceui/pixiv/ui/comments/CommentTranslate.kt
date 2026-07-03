package ceui.pixiv.ui.comments

import androidx.fragment.app.Fragment
import ceui.lisa.R
import ceui.lisa.utils.ClipBoardUtils
import ceui.lisa.utils.Common
import ceui.loxia.launchSuspend
import ceui.pixiv.ui.translate.GoogleWebTranslator
import ceui.pixiv.ui.translate.promptProxyNeededIfPossible
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * 长按评论「翻译成中文」:走项目里现成的谷歌网页翻译(日→中,[GoogleWebTranslator]),
 * 译文用 QMUIDialog 弹出、可一键复制;失败(多半是谷歌被墙没开代理)复用 [promptProxyNeededIfPossible]
 * 给出明确提示,别让用户当成 app 的 bug。弹窗挂 SkinManager 跟随日夜皮肤。
 */
fun Fragment.translateCommentToChinese(text: String?) {
    val src = text?.trim().orEmpty()
    if (src.isEmpty()) return
    val ctx = requireContext()
    Common.showToast(R.string.string_translating)
    launchSuspend {
        val zh = try {
            GoogleWebTranslator.translate(src, "zh")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "translate comment failed")
            promptProxyNeededIfPossible()
            return@launchSuspend
        }
        if (zh.isBlank()) {
            promptProxyNeededIfPossible()
            return@launchSuspend
        }
        QMUIDialog.MessageDialogBuilder(ctx)
            .setTitle(ctx.getString(R.string.comment_translate_to_zh))
            .setMessage(zh)
            .setSkinManager(QMUISkinManager.defaultInstance(ctx))
            .addAction(ctx.getString(R.string.string_120)) { dialog, _ ->
                ClipBoardUtils.putTextIntoClipboard(ctx, zh)
                dialog.dismiss()
            }
            .addAction(ctx.getString(R.string.sure)) { dialog, _ -> dialog.dismiss() }
            .show()
    }
}
