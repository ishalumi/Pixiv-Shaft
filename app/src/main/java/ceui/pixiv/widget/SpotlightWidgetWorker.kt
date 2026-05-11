package ceui.pixiv.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
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
import ceui.lisa.http.Retro
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.pixiv.session.SessionManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class SpotlightWidgetWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val manager = AppWidgetManager.getInstance(context)
        val widgetIds = manager.getAppWidgetIds(
            ComponentName(context, SpotlightWidgetProvider::class.java)
        )
        if (widgetIds.isEmpty()) return Result.success()

        if (SessionManager.getBearerTokenOrEmpty().isEmpty()) {
            widgetIds.forEach {
                renderEmpty(manager, it, context.getString(R.string.v3_widget_login_required))
            }
            return Result.success()
        }

        val illust = try {
            withContext(Dispatchers.IO) {
                Retro.getAppApi().getRecmdIllust(true)
                    .blockingFirst()
                    ?.illusts
                    ?.shuffled()
                    ?.firstOrNull { !it.isR18File }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

        if (illust == null) {
            widgetIds.forEach {
                renderEmpty(manager, it, context.getString(R.string.v3_widget_failed))
            }
            return Result.retry()
        }

        for (widgetId in widgetIds) {
            renderIllust(manager, widgetId, illust)
        }
        return Result.success()
    }

    private fun renderEmpty(
        manager: AppWidgetManager,
        widgetId: Int,
        message: String,
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_v3_spotlight)
        views.setTextViewText(R.id.widget_title, message)
        views.setTextViewText(R.id.widget_author_name, "")
        views.setTextViewText(R.id.widget_bookmark_count, "—")
        views.setTextViewText(R.id.widget_view_count, "—")

        val openApp = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            context, widgetId * 31 + 3, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, openPi)
        manager.updateAppWidget(widgetId, views)
    }

    private suspend fun renderIllust(
        manager: AppWidgetManager,
        widgetId: Int,
        illust: IllustsBean,
    ) {
        val opts = manager.getAppWidgetOptions(widgetId)
        val density = context.resources.displayMetrics.density
        val widthDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 280)
        val heightDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 120)
        val widthPx = (widthDp * density).toInt().coerceAtLeast((220 * density).toInt())
        val heightPx = (heightDp * density).toInt().coerceAtLeast((100 * density).toInt())

        // Hard pixel cap: bitmap travels through the RemoteViews IPC, which has a
        // ~2 MB transaction limit. 540×540 ARGB_8888 ≈ 1.16 MB, leaving headroom
        // for avatar. Going by dp blew the budget on xxxhdpi devices.
        val coverWidthPx = (widthPx * 0.45f - 24 * density).toInt()
            .coerceIn(180, 540)
        val coverHeightPx = (heightPx - 24 * density).toInt()
            .coerceIn(180, 540)
        val coverRadiusPx = (20 * density).toInt()
        val avatarPx = (20 * density).toInt().coerceAtMost(72)

        val cover = withContext(Dispatchers.IO) {
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
        val avatarGlideUrl = illust.user?.let { GlideUtil.getHead(it) }
        val avatar = if (avatarGlideUrl != null) withContext(Dispatchers.IO) {
            try {
                Glide.with(context).asBitmap()
                    .load(avatarGlideUrl)
                    .apply(RequestOptions().transform(CircleCrop()))
                    .submit(avatarPx, avatarPx)
                    .get()
            } catch (e: Exception) {
                e.printStackTrace(); null
            }
        } else null

        val views = RemoteViews(context.packageName, R.layout.widget_v3_spotlight)
        views.setTextViewText(R.id.widget_title, illust.title.orEmpty())
        views.setTextViewText(R.id.widget_author_name, illust.user?.name.orEmpty())
        views.setTextViewText(R.id.widget_bookmark_count, formatCount(illust.total_bookmarks ?: 0))
        views.setTextViewText(R.id.widget_view_count, formatCount(illust.total_view ?: 0))
        cover?.let { views.setImageViewBitmap(R.id.widget_cover, it) }
        avatar?.let { views.setImageViewBitmap(R.id.widget_author_avatar, it) }

        val openIntent = Intent(context, VActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            val pageData = PageData(listOf(illust))
            Container.get().addPageToMap(pageData)
            putExtra(Params.POSITION, 0)
            putExtra(Params.PAGE_UUID, pageData.uuid)
        }
        val openPi = PendingIntent.getActivity(
            context, widgetId * 31 + 2,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, openPi)

        manager.updateAppWidget(widgetId, views)
    }

    private fun formatCount(value: Int): String {
        return when {
            value < 1000 -> value.toString()
            value < 10_000 -> String.format(Locale.US, "%.1fk", value / 1000f)
            value < 1_000_000 -> "${value / 1000}k"
            else -> String.format(Locale.US, "%.1fM", value / 1_000_000f)
        }
    }
}
