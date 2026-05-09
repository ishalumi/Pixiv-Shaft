package ceui.pixiv.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class SpotlightWidgetProvider : AppWidgetProvider() {

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

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            triggerImmediate(context)
        }
    }

    override fun onDisabled(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PERIODIC)
    }

    private fun triggerImmediate(context: Context) {
        val request = OneTimeWorkRequestBuilder<SpotlightWidgetWorker>()
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
        val request = PeriodicWorkRequestBuilder<SpotlightWidgetWorker>(30, TimeUnit.MINUTES)
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
        const val ACTION_REFRESH = "ceui.pixiv.widget.action.SPOTLIGHT_REFRESH"
        const val WORK_NAME_PERIODIC = "v3_spotlight_widget_periodic"
        const val WORK_NAME_ONE_SHOT = "v3_spotlight_widget_one_shot"

        fun refreshPendingIntent(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, SpotlightWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            return PendingIntent.getBroadcast(
                context,
                appWidgetId * 31 + 1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
