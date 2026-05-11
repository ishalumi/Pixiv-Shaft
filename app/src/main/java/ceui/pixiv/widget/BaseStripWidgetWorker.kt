package ceui.pixiv.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ceui.lisa.R
import ceui.lisa.activities.MainActivity
import ceui.lisa.activities.VActivity
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.pixiv.session.SessionManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders three illust thumbnails in a horizontal strip. Subclasses pick the
 * data source (daily ranking, recommendations, …); the layout, sizing, and
 * RemoteViews IPC handling live here.
 */
abstract class BaseStripWidgetWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    protected abstract val providerClass: Class<out AppWidgetProvider>

    /** Fetch up to three illusts. Return null to signal transient failure (worker will retry). */
    protected abstract suspend fun fetchIllusts(): List<IllustsBean>?

    override suspend fun doWork(): Result {
        val manager = AppWidgetManager.getInstance(context)
        val widgetIds = manager.getAppWidgetIds(ComponentName(context, providerClass))
        if (widgetIds.isEmpty()) return Result.success()

        if (SessionManager.getBearerTokenOrEmpty().isEmpty()) {
            widgetIds.forEach { renderPlaceholder(manager, it) }
            return Result.success()
        }

        val illusts = try {
            fetchIllusts()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
        if (illusts.isNullOrEmpty()) {
            widgetIds.forEach { renderPlaceholder(manager, it) }
            return Result.retry()
        }

        for (widgetId in widgetIds) {
            renderStrip(manager, widgetId, illusts.take(3))
        }
        return Result.success()
    }

    private fun renderPlaceholder(manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_v3_strip)
        val openApp = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            context, widgetId * 41 + 7, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, openPi)
        manager.updateAppWidget(widgetId, views)
    }

    private suspend fun renderStrip(
        manager: AppWidgetManager,
        widgetId: Int,
        illusts: List<IllustsBean>,
    ) {
        val opts = manager.getAppWidgetOptions(widgetId)
        val density = context.resources.displayMetrics.density
        val widthDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 320)
        val heightDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 130)
        val widthPx = (widthDp * density).toInt().coerceAtLeast((250 * density).toInt())
        val heightPx = (heightDp * density).toInt().coerceAtLeast((100 * density).toInt())

        // Strip layout: 12dp padding × 2 + 2dp gap × 2 = 28dp. Divide rest by 3.
        // Hard pixel cap so the three bitmaps together don't blow the 2 MB IPC.
        val coverWidthPx = ((widthPx - 28 * density) / 3f).toInt()
            .coerceIn(120, 360)
        val coverHeightPx = (heightPx - 24 * density).toInt()
            .coerceIn(120, 360)
        val coverRadiusPx = (16 * density).toInt()

        val views = RemoteViews(context.packageName, R.layout.widget_v3_strip)
        val slots = listOf(R.id.widget_cover_0, R.id.widget_cover_1, R.id.widget_cover_2)

        for (index in slots.indices) {
            val viewId = slots[index]
            val illust = illusts.getOrNull(index)
            if (illust == null) {
                // Fewer than three illusts; leave the slot with its placeholder background.
                continue
            }
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    Glide.with(context).asBitmap()
                        .load(GlideUtil.getLargeImage(illust))
                        .apply(
                            RequestOptions().transform(
                                CenterCrop(), RoundedCorners(coverRadiusPx)
                            )
                        )
                        .submit(coverWidthPx, coverHeightPx)
                        .get()
                } catch (e: Exception) {
                    e.printStackTrace(); null
                }
            }
            bitmap?.let { views.setImageViewBitmap(viewId, it) }

            val openIntent = Intent(context, VActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                val pageData = PageData(listOf(illust))
                Container.get().addPageToMap(pageData)
                putExtra(Params.POSITION, 0)
                putExtra(Params.PAGE_UUID, pageData.uuid)
            }
            val pi = PendingIntent.getActivity(
                context,
                widgetId * 41 + index,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(viewId, pi)
        }
        manager.updateAppWidget(widgetId, views)
    }
}
