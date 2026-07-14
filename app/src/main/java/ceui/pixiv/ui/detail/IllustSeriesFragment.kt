package ceui.pixiv.ui.detail

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.VActivity
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.http.Retro
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Params
import ceui.lisa.view.LinearItemDecorationNoLRTB
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.loxia.ProgressImageButton
import ceui.loxia.ProgressIndicator
import ceui.loxia.launchSuspend
import ceui.pixiv.events.EventReporter
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.feeds.updateItems
import ceui.pixiv.ui.common.IllustCardActionReceiver
import ceui.pixiv.ui.common.IllustIdActionReceiver
import ceui.pixiv.ui.novel.NovelSeriesHeaderActionReceiver
import ceui.pixiv.utils.ppppx
import ceui.pixiv.widgets.RateAppManager
import com.hjq.toast.ToastUtils
import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 漫画系列 V3 详情页（feeds 框架版）。整页镜像小说系列详情页的观感：全屏 hero（标题 +
 * 收藏 + 阅读最新一话）、作者卡、作品档案、简介、「作品列表」标题 + 标题优先的单话列表。
 *
 * 数据全部住在 [feedViewModel]（[IllustSeriesFeedSource]）；hero / 作者 / 档案 / 简介 /
 * 单话都是 [FeedItem]，各自的 [FeedRenderer] 在 [IllustSeriesFeed.kt]。列表项点击一律走
 * VActivity + PageData（TemplateActivity 无 NavHost，pushFragment 会崩）。
 *
 * 入口两条：① TemplateActivity "漫画系列详情"；② NavHost navigation_illust_series。
 * 都靠 arguments 里的 "series_id"(Long)。
 */
class IllustSeriesFragment :
    FeedFragment(R.layout.fragment_v3_feed_list),
    NovelSeriesHeaderActionReceiver,
    IllustCardActionReceiver,
    IllustIdActionReceiver {

    private val seriesId: Long by lazy { arguments?.getLong(ARG_SERIES_ID, 0L) ?: 0L }

    override val feedViewModel by feedViewModels {
        val id = seriesId
        IllustSeriesFeedSource(id)
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> = listOf(
        mangaHeroRenderer(),
        seriesAuthorRenderer(),
        mangaProfileRenderer(),
        seriesCaptionRenderer(),
        seriesSectionLabelRenderer(),
        mangaEpisodeRenderer(),
    )

    override fun onListReady(listView: RecyclerView) {
        listView.clipToPadding = false
        listView.addItemDecoration(LinearItemDecorationNoLRTB(18.ppppx))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val density = resources.displayMetrics.density
        val listView = feedBinding.feedListView
        // Edge-to-edge：TemplateActivity 画到状态栏/刘海下面，列表首个 holder 清掉 systemBars.top
        // 再留一点呼吸位；底部让出导航栏 inset + 一点空隙。
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            listView.updatePadding(
                top = bars.top + (12 * density).toInt(),
                bottom = bars.bottom + (24 * density).toInt(),
            )
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    private fun loadedIllusts(): List<Illust> =
        feedViewModel.uiState.value.items.filterIsInstance<MangaEpisodeFeedItem>().map { it.illust }

    // ── 单话/最新话点击：打开整条系列的查看器并定位到这一话 ─────────────

    override fun onClickIllustCard(illust: Illust) = onClickIllust(illust.id)

    override fun visitIllustById(illustId: Long) = onClickIllust(illustId)

    override fun onClickIllust(illustId: Long) {
        val illusts = loadedIllusts()
        val pos = illusts.indexOfFirst { it.id == illustId }
        if (illusts.isEmpty() || pos < 0) {
            // 兜底：列表还没加载到这一话（理论上不会），单独拉取只放它一个。
            viewLifecycleOwner.lifecycleScope.launch {
                val illust = runCatching { Client.appApi.getIllust(illustId).illust }
                    .getOrNull() ?: return@launch
                openSeriesViewer(listOf(illust), 0)
            }
            return
        }
        openSeriesViewer(illusts, pos)
    }

    private fun openSeriesViewer(illusts: List<Illust>, position: Int) {
        if (!isAdded || illusts.isEmpty()) return
        val gson = Shaft.sGson
        val beans = illusts.map { gson.fromJson(gson.toJson(it), IllustsBean::class.java) }
        val uuid = UUID.randomUUID().toString()
        val pageData = PageData(uuid, null, beans)
        Container.get().addPageToMap(pageData)
        val intent = Intent(requireContext(), VActivity::class.java).apply {
            putExtra(Params.POSITION, position)
            putExtra(Params.PAGE_UUID, uuid)
        }
        startActivity(intent)
    }

    override fun onClickBookmarkIllust(sender: ProgressIndicator, illustId: Long) {
        launchSuspend(sender) {
            val illust = ObjectPool.get<Illust>(illustId).value
                ?: Client.appApi.getIllust(illustId).illust?.also { ObjectPool.update(it) }
            if (illust != null) {
                val targetType = if (illust.type == "manga") {
                    EventReporter.Target.MANGA
                } else {
                    EventReporter.Target.ILLUST
                }
                if (illust.is_bookmarked == true) {
                    Client.appApi.removeBookmark(illustId)
                    ObjectPool.update(
                        illust.copy(
                            is_bookmarked = false,
                            total_bookmarks = illust.total_bookmarks?.minus(1)
                        )
                    )
                    EventReporter.report(EventReporter.Type.UNBOOKMARK, targetType, illustId, illust)
                } else {
                    Client.appApi.postBookmark(illustId)
                    RateAppManager.onUserEngaged()
                    ObjectPool.update(
                        illust.copy(
                            is_bookmarked = true,
                            total_bookmarks = illust.total_bookmarks?.plus(1)
                        )
                    )
                    EventReporter.report(EventReporter.Type.BOOKMARK, targetType, illustId, illust)
                }
            }
        }
    }

    // ── NovelSeriesHeaderActionReceiver（hero 卡片复用小说那套接口）─────────

    override fun onClickToggleWatchlist(progressView: ProgressImageButton) {
        val hero = feedViewModel.uiState.value.items
            .filterIsInstance<MangaHeroFeedItem>().firstOrNull() ?: return
        progressView.showProgress()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val detail = hero.series
                val nowAdded = detail.watchlist_added == true
                val seriesIdInt = detail.id.toInt()
                val obs = if (nowAdded) {
                    Retro.getAppApi().postWatchlistMangaDelete(seriesIdInt)
                } else {
                    Retro.getAppApi().postWatchlistMangaAdd(seriesIdInt)
                }
                obs.awaitFirst()
                // 本地翻转 watchlist_added 并重发 hero 条目触发重绑收藏 icon。
                feedViewModel.updateItems<MangaHeroFeedItem> {
                    it.copy(series = it.series.copy(watchlist_added = !nowAdded))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isAdded) ToastUtils.show(getString(R.string.task_status_error))
            } finally {
                if (isAdded) progressView.hideProgress()
            }
        }
    }

    override fun onClickReadLatestEpisode(novelId: Long) {
        // 参数名沿用接口的 novelId，这里其实是最新一话的 illustId。跳转与列表点击一致。
        onClickIllust(novelId)
    }

    companion object {
        const val ARG_SERIES_ID = "series_id"

        fun newInstance(seriesId: Long): IllustSeriesFragment = IllustSeriesFragment().apply {
            arguments = Bundle().apply { putLong(ARG_SERIES_ID, seriesId) }
        }
    }
}

/** Bridge Rx2 Observable to suspend; cancellation disposes the subscription. */
private suspend fun <T : Any> Observable<T>.awaitFirst(): T = suspendCancellableCoroutine { cont ->
    val disposable: Disposable = subscribeOn(Schedulers.io())
        .firstOrError()
        .subscribe(
            { cont.resume(it) },
            { cont.resumeWithException(it) },
        )
    cont.invokeOnCancellation { disposable.dispose() }
}
