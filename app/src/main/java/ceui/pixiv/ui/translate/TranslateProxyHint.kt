package ceui.pixiv.ui.translate

import android.app.Activity
import ceui.lisa.R
import com.blankj.utilcode.util.ActivityUtils
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction
import timber.log.Timber

/**
 * GoogleWebTranslator 走 translate.googleapis.com,国内必须有代理。
 * 翻译失败时弹一条 QMUIDialog 直接告诉用户「需要代理」— 避免只 toast 一句模糊的「翻译失败」
 * 让人以为是 app bug 反复重试。
 *
 * 拿当前 foreground Activity 用 [ActivityUtils.getTopActivity];AndroidUtilCode 内部维护
 * lifecycle callbacks,这里不用再自己注册。fallback:拿不到 activity / 已 finishing 就静默 —
 * 调用方仍会走原 toast 路径,不会缺少错误反馈。
 */
internal fun promptProxyNeededIfPossible() {
    val activity: Activity? = ActivityUtils.getTopActivity()
    if (activity == null || activity.isFinishing || activity.isDestroyed) {
        Timber.tag("TranslateProxyHint").w("no resumed activity, skip dialog")
        return
    }
    activity.runOnUiThread {
        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
        try {
            QMUIDialog.MessageDialogBuilder(activity)
                .setTitle(R.string.translate_proxy_required_title)
                .setMessage(R.string.translate_proxy_required_message)
                .setSkinManager(QMUISkinManager.defaultInstance(activity))
                .addAction(0, android.R.string.ok, QMUIDialogAction.ACTION_PROP_POSITIVE) { d, _ ->
                    d.dismiss()
                }
                .show()
        } catch (e: Exception) {
            Timber.tag("TranslateProxyHint").w(e, "show dialog failed")
        }
    }
}
