package ceui.pixiv.ui.common

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.TextView
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.activities.UActivity
import ceui.lisa.activities.VActivity
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.ProgressIndicator
import ceui.loxia.launchSuspend
import ceui.pixiv.events.EventReporter
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.RateAppManager
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 详情类 feeds 页(漫画系列 / 小说详情 / 小说系列)共用的小工具，收口三页里原本各写一遍的
 * 「作品档案」chip、经典 Intent 跳转、收藏切换与 Rx→suspend 桥接，避免复制粘贴。
 *
 * 这些页面都挂在 [TemplateActivity]（无 NavController），跳转一律走显式 Intent。
 */

// ── 作品档案 chip ────────────────────────────────────────────────────────

/** 复制型 chip：文案 `labelRes(display)`，点按复制 [copyValue]。 */
fun TextView.bindCopyChip(labelRes: Int, display: String, copyValue: String) {
    text = context.getString(labelRes, display)
    isVisible = true
    setOnClick { Common.copy(context, copyValue) }
}

/** 复制链接 chip：文案取 [labelRes]（无参），点按复制 [url]。 */
fun TextView.bindCopyLinkChip(labelRes: Int, url: String) {
    text = context.getString(labelRes)
    isVisible = true
    setOnClick { Common.copy(context, url) }
}

/** 打开链接 chip：文案取 [labelRes]，点按用 Custom Tab 打开 [url]。 */
fun TextView.bindOpenLinkChip(labelRes: Int, url: String) {
    text = context.getString(labelRes)
    isVisible = true
    setOnClick {
        try {
            CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
        } catch (_: ActivityNotFoundException) {
            Common.showToast("未找到浏览器")
        }
    }
}

// ── 经典（无 NavController）跳转 ──────────────────────────────────────────

fun Fragment.openUserActivity(userId: Long) {
    startActivity(Intent(requireContext(), UActivity::class.java).apply {
        putExtra(Params.USER_ID, userId.toInt())
    })
}

fun Fragment.openNovelDetail(novelId: Long) {
    startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
        putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说详情")
        putExtra(Params.NOVEL_ID, novelId)
    })
}

/** 把一组插画塞进 [VActivity] 查看器并定位到 [position]（经 [Container]/[PageData] 传递）。 */
fun Fragment.openIllustsInViewer(illusts: List<Illust>, position: Int) {
    if (!isAdded || illusts.isEmpty()) return
    val gson = Shaft.sGson
    val beans = illusts.map { gson.fromJson(gson.toJson(it), IllustsBean::class.java) }
    val uuid = UUID.randomUUID().toString()
    Container.get().addPageToMap(PageData(uuid, null, beans))
    startActivity(Intent(requireContext(), VActivity::class.java).apply {
        putExtra(Params.POSITION, position)
        putExtra(Params.PAGE_UUID, uuid)
    })
}

// ── 收藏切换（写穿 ObjectPool + 埋点）──────────────────────────────────────

fun Fragment.toggleIllustBookmark(sender: ProgressIndicator, illustId: Long) {
    launchSuspend(sender) {
        val illust = ObjectPool.get<Illust>(illustId).value
            ?: Client.appApi.getIllust(illustId).illust?.also { ObjectPool.update(it) }
            ?: return@launchSuspend
        // Pixiv 把漫画存成 type == "manga" 的 illust，按语义目标分开埋点。
        val target = if (illust.type == "manga") EventReporter.Target.MANGA else EventReporter.Target.ILLUST
        if (illust.is_bookmarked == true) {
            Client.appApi.removeBookmark(illustId)
            ObjectPool.update(illust.copy(is_bookmarked = false, total_bookmarks = illust.total_bookmarks?.minus(1)))
            EventReporter.report(EventReporter.Type.UNBOOKMARK, target, illustId, illust)
        } else {
            Client.appApi.postBookmark(illustId)
            RateAppManager.onUserEngaged()
            ObjectPool.update(illust.copy(is_bookmarked = true, total_bookmarks = illust.total_bookmarks?.plus(1)))
            EventReporter.report(EventReporter.Type.BOOKMARK, target, illustId, illust)
        }
    }
}

fun Fragment.toggleNovelBookmark(sender: ProgressIndicator, novelId: Long) {
    launchSuspend(sender) {
        val novel = ObjectPool.get<Novel>(novelId).value
            ?: Client.appApi.getNovel(novelId).novel?.also { ObjectPool.update(it) }
            ?: return@launchSuspend
        if (novel.is_bookmarked == true) {
            Client.appApi.removeNovelBookmark(novelId)
            ObjectPool.update(novel.copy(is_bookmarked = false, total_bookmarks = novel.total_bookmarks?.minus(1)))
            EventReporter.report(EventReporter.Type.UNBOOKMARK, EventReporter.Target.NOVEL, novelId, novel)
        } else {
            Client.appApi.addNovelBookmark(novelId, Params.TYPE_PUBLIC)
            RateAppManager.onUserEngaged()
            ObjectPool.update(novel.copy(is_bookmarked = true, total_bookmarks = novel.total_bookmarks?.plus(1)))
            EventReporter.report(EventReporter.Type.BOOKMARK, EventReporter.Target.NOVEL, novelId, novel)
        }
    }
}

// ── Rx2 Observable → suspend（项目不引 kotlinx-coroutines-rx2，统一收口这一份）──

suspend fun <T : Any> Observable<T>.awaitFirstValue(): T = suspendCancellableCoroutine { cont ->
    val disposable = subscribeOn(Schedulers.io())
        .firstOrError()
        .subscribe({ cont.resume(it) }, { cont.resumeWithException(it) })
    cont.invokeOnCancellation { disposable.dispose() }
}
