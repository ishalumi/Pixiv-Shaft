package ceui.pixiv.ui.watchlater

import android.content.Intent
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.database.AppDatabase
import ceui.lisa.utils.Local
import ceui.pixiv.db.RecordType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 冷启动提示:若「稍后再看」列表非空且设置开着,弹一次询问是否现在查看(issue 需求)。
 * 每次进程只弹一次([shownThisLaunch]);「不再提示」直接关掉设置项。
 */
object WatchLaterLaunchPrompt {

    private var shownThisLaunch = false

    fun showIfNeeded(activity: FragmentActivity) {
        if (shownThisLaunch) return
        if (!Shaft.sSettings.isRemindWatchLaterOnLaunch) return
        MainScope().launch {
            val count = withContext(Dispatchers.IO) {
                AppDatabase.getAppDatabase(activity.applicationContext).generalDao()
                    .getCountByRecordType(RecordType.WATCH_LATER)
            }
            if (count <= 0) return@launch
            if (activity.isFinishing || activity.isDestroyed) return@launch
            // 只在 activity 至少 STARTED 时弹,避免把弹窗甩到已退到后台的 activity 上。
            if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
            if (shownThisLaunch) return@launch
            shownThisLaunch = true
            showDialog(activity, count)
        }
    }

    private fun showDialog(activity: FragmentActivity, count: Int) {
        QMUIDialog.MessageDialogBuilder(activity)
            .setTitle(R.string.watch_later)
            .setMessage(activity.getString(R.string.watch_later_launch_prompt, count))
            .setSkinManager(QMUISkinManager.defaultInstance(activity))
            .addAction(
                0,
                activity.getString(R.string.watch_later_launch_never),
                QMUIDialogAction.ACTION_PROP_NEUTRAL
            ) { dialog, _ ->
                Shaft.sSettings.isRemindWatchLaterOnLaunch = false
                Local.setSettings(Shaft.sSettings)
                dialog.dismiss()
            }
            .addAction(
                0,
                activity.getString(R.string.watch_later_launch_view),
                QMUIDialogAction.ACTION_PROP_POSITIVE
            ) { dialog, _ ->
                dialog.dismiss()
                val intent = Intent(activity, TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "稍后再看")
                activity.startActivity(intent)
            }
            .show()
    }
}
