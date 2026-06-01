package ceui.pixiv.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 老版 2x2 / 2x1 随机推荐卡片（Play 用户点名要回来的那个）：
 * 整卡一张随机推荐作品，点卡片进详情，右下角刷新换一张。
 */
class RecommendCardWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        schedulePeriodic(context)
        triggerImmediate(context)
    }

    override fun onEnabled(context: Context) {
        schedulePeriodic(context)
        triggerImmediate(context)
    }

    // Intentionally NOT overriding onAppWidgetOptionsChanged: some launchers
    // (e.g. ColorOS) fire it 3–4× in rapid succession during initial relayout,
    // and triggering work with REPLACE on each turns the in-flight worker into
    // a thrash loop where none ever completes. Center-cropping handles resize.

    override fun onDisabled(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PERIODIC)
    }

    private fun triggerImmediate(context: Context) {
        val request = OneTimeWorkRequestBuilder<RecommendCardWidgetWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_ONE_SHOT,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<RecommendCardWidgetWorker>(30, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    companion object {
        const val WORK_NAME_PERIODIC = "v3_recommend_card_periodic"
        const val WORK_NAME_ONE_SHOT = "v3_recommend_card_one_shot"
    }
}
