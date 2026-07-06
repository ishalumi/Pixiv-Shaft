package ceui.pixiv.ui.detail

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.adapters.IAdapter
import ceui.lisa.databinding.FragmentArtworkV3Binding
import ceui.lisa.dialogs.MuteDialog
import ceui.lisa.download.IllustDownload
import ceui.lisa.fragments.BaseFragment
import ceui.lisa.fragments.FragmentIllustArgs
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Common
import ceui.lisa.utils.Dev
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.utils.ShareIllust
import ceui.loxia.ObjectPool
import ceui.loxia.isFullDetail
import ceui.loxia.requireNetworkStateManager
import ceui.loxia.threadSafeArgs
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import ceui.pixiv.ui.share.shareFirstImage
import ceui.pixiv.ui.task.PageLoadRetryController
import ceui.pixiv.ui.task.renderImageLoadStatusBanner
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.scwang.smart.refresh.header.MaterialHeader
import timber.log.Timber

class ArtworkV3Fragment : BaseFragment<FragmentArtworkV3Binding>() {

    private val safeArgs by threadSafeArgs<FragmentIllustArgs>()

    private val viewModel by viewModels<ArtworkV3ViewModel> {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return ArtworkV3ViewModel(safeArgs.illustId.toLong()) as T
            }
        }
    }

    private var illustAdapter: ceui.lisa.adapters.IllustAdapter? = null
    private lateinit var headerAdapter: ArtworkDetailAdapter
    private lateinit var relatedAdapter: IAdapter
    private lateinit var loadingFooter: LoadingFooterAdapter
    private val relatedList = mutableListOf<IllustsBean>()
    private var pendingHeaderItems: List<ArtworkDetailItem>? = null

    private lateinit var retryController: PageLoadRetryController

    override fun initLayout() {
        mLayoutID = R.layout.fragment_artwork_v3
    }

    override fun initView() {
        val illustId = safeArgs.illustId.toLong()

        retryController = PageLoadRetryController(
            lifecycleOwner = viewLifecycleOwner,
            networkStateManager = requireNetworkStateManager(),
            urlAtIndex = { idx ->
                val illust = ObjectPool.get<IllustsBean>(illustId).value ?: return@PageLoadRetryController null
                if (idx < 0 || idx >= illust.page_count) return@PageLoadRetryController null
                val resolution = if (Shaft.sSettings.isShowOriginalPreviewImage)
                    Params.IMAGE_RESOLUTION_ORIGINAL
                else
                    Params.IMAGE_RESOLUTION_LARGE
                IllustDownload.getUrl(illust, idx, resolution)
            },
            totalPages = { ObjectPool.get<IllustsBean>(illustId).value?.page_count ?: 0 },
            onSummaryChanged = { loaded, total, failed ->
                renderImageLoadStatusBanner(
                    baseBind.pageStatusRow, baseBind.pageStatusText,
                    loaded, total, failed,
                )
            },
            onRetryAt = { idx -> illustAdapter?.notifyItemChanged(idx) },
        )
        baseBind.pageStatusRetry.setOnClickListener { retryController.retryAllFailed() }

        headerAdapter = ArtworkDetailAdapter(this)
        headerAdapter.onCommentsVisible = { viewModel.loadComments() }
        headerAdapter.onAuthorWorksVisible = { viewModel.loadAuthorWorks() }
        headerAdapter.onRelatedVisible = { viewModel.loadRelated() }

        relatedAdapter = IAdapter(relatedList, mContext).apply {
            setUuid("artwork_v3_related_$illustId")
        }
        loadingFooter = LoadingFooterAdapter()

        val concatConfig = ConcatAdapter.Config.Builder().setIsolateViewTypes(true).build()
        val concatAdapter = ConcatAdapter(
            concatConfig,
            headerAdapter,
            relatedAdapter,
            loadingFooter
        )

        // Honour the global "列数" setting (FragmentSettings → 首页/作者页推荐流列数)。
        // Hardcoded 2 here ignored the user's preference — see issue #851.
        val spanCount = Shaft.sSettings.lineCount.coerceAtLeast(1)
        val layoutManager = StaggeredGridLayoutManager(spanCount, StaggeredGridLayoutManager.VERTICAL)
        // Disable span-shuffle on gap — the header has many fullSpan items above an
        // N-column grid; default MOVE_ITEMS_BETWEEN_SPANS causes jank as items
        // re-layout during scroll.
        layoutManager.gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
        baseBind.recyclerView.layoutManager = layoutManager
        baseBind.recyclerView.adapter = concatAdapter
        baseBind.recyclerView.addItemDecoration(RelatedOnlySpaceDecoration(4.ppppx, spanCount))
        // Header items are all fullSpan — DefaultItemAnimator's change animation on
        // notifyItemChanged (fired when ObjectPool pushes the updated UserBean after
        // returning from UActivity) scrambles SGLM's fullSpan tracking and makes the
        // Artist card snap flush with its upper neighbor. No useful animation to lose.
        baseBind.recyclerView.itemAnimator = null

        // Infinite scroll trigger near list end. Buffer is per-span so it must
        // match the layout's spanCount, not be hardcoded to 2.
        baseBind.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            private val lastVisiblePositions = IntArray(spanCount)

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) {
                    val lm = recyclerView.layoutManager as StaggeredGridLayoutManager
                    lm.findLastVisibleItemPositions(lastVisiblePositions)
                    var lastVisible = RecyclerView.NO_POSITION
                    for (p in lastVisiblePositions) if (p > lastVisible) lastVisible = p
                    if (lastVisible >= lm.itemCount - 4 && viewModel.hasMoreRelated) {
                        viewModel.loadMoreRelated()
                    }
                }
            }
        })

        // Hide/show floating action bar on scroll
        baseBind.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 8) hideFabBar() else if (dy < -8) showFabBar()
            }
        })

        // 手动下拉刷新:只重新拉取 illust 详情本身(标题/标签/收藏数等),
        // 评论、作者其他作品、相关作品等区块不动。Header 跟随 V3 主题色。
        baseBind.refreshLayout.setRefreshHeader(MaterialHeader(requireContext()).apply {
            setColorSchemeColors(headerAdapter.palette.textAccent)
        })
        baseBind.refreshLayout.setOnRefreshListener { viewModel.refreshIllustDetail() }
        baseBind.loadingFullDetail.setIndicatorColor(headerAdapter.palette.textAccent)
        viewModel.isRefreshingDetail.observe(viewLifecycleOwner) { refreshing ->
            if (refreshing == false) {
                baseBind.refreshLayout.finishRefresh()
            }
            // 居中转圈圈只在「还没有内容」时显示——即数据不完整、VM 正回 API 拉完整版的初始阶段。
            // 手动下拉刷新时内容已在(illustAdapter != null),只走 SmartRefreshLayout 自带的下拉转圈,
            // 不再叠一个居中圈。用 SmartRefreshLayout 程序化 autoRefresh 会导致转圈停不下来,故弃用。
            baseBind.loadingFullDetail.isVisible = refreshing == true && illustAdapter == null
        }

        setupNavBar(illustId)
        handleSystemInsets()

        Timber.tag("V3MultiP").d(
            "[Fragment.initView] illustId=$illustId, " +
                "displayMetrics=${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}, " +
                "density=${resources.displayMetrics.density}, " +
                "concatHasIsolateViewTypes=true, sglmGapHandling=NONE"
        )

        // When illust data arrives, insert IllustAdapter at the front. For heavy
        // multi-page works (e.g. 50P manga) collapse all but the first few pages so
        // the reader can reach tags / comments / related works without a huge scroll;
        // the ExpandPagesAdapter card sits right after and reveals the rest on tap.
        var observerEmissionCount = 0
        ObjectPool.get<IllustsBean>(illustId).observe(viewLifecycleOwner) { illust ->
            observerEmissionCount++
            if (illust == null) {
                Timber.tag("V3MultiP").w("[Fragment.observe] emission #$observerEmissionCount: illust=NULL, illustId=$illustId")
                return@observe
            }
            // 精简/网页来源 bean 缺分页图(meta_pages/meta_single_page),建多图分页会残缺。
            // 等 VM 回 API 拉到完整版再次 fire 时才建;拉取失败/已删时(detailFetchFailed)放行,
            // 用现有数据降级建封面,避免整页空白。(issue #569)
            if (!illust.isFullDetail() && viewModel.detailFetchFailed.value != true) {
                Timber.tag("V3MultiP").d("[Fragment.observe] incomplete bean, wait for full detail (illustId=$illustId)")
                return@observe
            }
            val metaPagesInfo = try {
                val mp = illust.meta_pages
                if (mp == null) "null" else "size=${mp.size}"
            } catch (e: Throwable) { "throws ${e.javaClass.simpleName}" }
            val metaSingleInfo = try {
                if (illust.meta_single_page == null) "null"
                else "original=${illust.meta_single_page.original_image_url?.take(80) ?: "null"}"
            } catch (e: Throwable) { "throws ${e.javaClass.simpleName}" }
            Timber.tag("V3MultiP").d(
                "[Fragment.observe] emission #$observerEmissionCount, " +
                    "illustId=$illustId, page_count=${illust.page_count}, " +
                    "width=${illust.width}, height=${illust.height}, " +
                    "meta_pages=$metaPagesInfo, meta_single_page=$metaSingleInfo, " +
                    "image_urls.large=${illust.image_urls?.large?.take(80) ?: "null"}, " +
                    "adapterAlreadyCreated=${illustAdapter != null}"
            )
            if (illustAdapter == null) {
                // Dump the full illust JSON once per page open — for debugging
                // server-side shape changes / missing fields. Tag: V3IllustJson.
                runCatching {
                    val json = Shaft.sGson.toJson(illust)
                    // Logcat truncates each line around 4k chars; chunk so multi-page
                    // works with long tag arrays still show in full.
                    json.chunked(3500).forEachIndexed { idx, chunk ->
                        Timber.tag("V3IllustJson").d("[$illustId p${idx + 1}/${(json.length + 3499) / 3500}] $chunk")
                    }
                }.onFailure { Timber.tag("V3IllustJson").w(it, "toJson failed for illustId=$illustId") }

                // Use 70% of screen height as max — not full screen, so single-page
                // images don't stretch to fill the entire viewport
                val maxHeight = (resources.displayMetrics.heightPixels * 0.7f).toInt()
                val willCollapse = CollapsibleIllustAdapter.shouldCollapse(illust.page_count)
                Timber.tag("V3MultiP").d(
                    "[Fragment.createAdapter] page_count=${illust.page_count}, " +
                        "maxHeight=$maxHeight, willCollapse=$willCollapse, " +
                        "adapterClass=${if (willCollapse) "CollapsibleIllustAdapter" else "IllustAdapter(anon)"}"
                )
                val comicReaderLauncher: () -> Unit = {
                    val intent = Intent(requireContext(), ceui.lisa.activities.TemplateActivity::class.java).apply {
                        putExtra(ceui.lisa.activities.TemplateActivity.EXTRA_FRAGMENT, "漫画阅读")
                        putExtra(Params.ILLUST_ID, illustId)
                    }
                    startActivity(intent)
                }
                val adapter = if (willCollapse) {
                    CollapsibleIllustAdapter(
                        mActivity,
                        this@ArtworkV3Fragment,
                        illust,
                        maxHeight,
                        false,
                        onComicReaderClick = comicReaderLauncher,
                        onExpandedChanged = { isExpanded ->
                            // Cross-fade with the adapter's scrim animation:
                            //  - expand: scrim alpha 1→0 on pos 0, this pill 0→1
                            //  - collapse: scrim alpha 0→1 on pos 0, this pill 1→0
                            val pill = baseBind.collapsePill
                            pill.animate().cancel()
                            if (isExpanded) {
                                pill.alpha = 0f
                                pill.visibility = View.VISIBLE
                                pill.animate().alpha(1f).setDuration(220).start()
                            } else {
                                pill.animate().alpha(0f).setDuration(220).withEndAction {
                                    pill.visibility = View.GONE
                                    pill.alpha = 1f
                                }.start()
                            }
                        },
                    )
                } else {
                    object : ceui.lisa.adapters.IllustAdapter(
                        mActivity, this@ArtworkV3Fragment, illust, maxHeight, false
                    ) {
                        override fun onBindViewHolder(holder: ceui.lisa.adapters.ViewHolder<ceui.lisa.databinding.RecyIllustDetailBinding>, position: Int) {
                            super.onBindViewHolder(holder, position)
                            // 进入 V3 漫画阅读器（多 P 才有意义；单 P 走旧的全屏看图）
                            if (illust.page_count > 1) {
                                holder.baseBind.illust.setOnClickListener {
                                    val intent = Intent(requireContext(), ceui.lisa.activities.TemplateActivity::class.java).apply {
                                        putExtra(ceui.lisa.activities.TemplateActivity.EXTRA_FRAGMENT, "漫画阅读")
                                        putExtra(Params.ILLUST_ID, illustId)
                                    }
                                    startActivity(intent)
                                }
                            }
                        }
                        override fun onViewAttachedToWindow(holder: ceui.lisa.adapters.ViewHolder<ceui.lisa.databinding.RecyIllustDetailBinding>) {
                            super.onViewAttachedToWindow(holder)
                            val lp = holder.itemView.layoutParams
                            if (lp is StaggeredGridLayoutManager.LayoutParams && !lp.isFullSpan) {
                                lp.isFullSpan = true
                                holder.itemView.layoutParams = lp
                            }
                            Timber.tag("V3MultiP").d(
                                "[Fragment.IllustAdapter.onAttach] pos=${holder.bindingAdapterPosition}, " +
                                    "itemView=${holder.itemView.width}x${holder.itemView.height}, " +
                                    "isFullSpan=${(lp as? StaggeredGridLayoutManager.LayoutParams)?.isFullSpan}"
                            )
                        }
                    }
                }
                illustAdapter = adapter
                if (adapter is CollapsibleIllustAdapter) {
                    baseBind.collapsePill.setOnClickListener {
                        adapter.collapse()
                        // Snap to the top after the data shrinks so the user
                        // doesn't get stranded in the now-empty space below.
                        val lm = baseBind.recyclerView.layoutManager
                        if (lm is StaggeredGridLayoutManager) {
                            lm.scrollToPositionWithOffset(0, 0)
                        } else {
                            baseBind.recyclerView.scrollToPosition(0)
                        }
                    }
                }
                adapter.setPageStatusListener { position, status ->
                    retryController.reportStatus(position, status)
                }
                retryController.refresh()
                val adapterCount = adapter.itemCount
                Timber.tag("V3MultiP").d(
                    "[Fragment.addAdapter] inserting at index 0, " +
                        "adapter.itemCount=$adapterCount, concatBeforeInsert=${concatAdapter.itemCount}"
                )
                concatAdapter.addAdapter(0, adapter)
                Timber.tag("V3MultiP").d(
                    "[Fragment.addAdapter] after insert, concatItemCount=${concatAdapter.itemCount}, " +
                        "rvIsComputingLayout=${baseBind.recyclerView.isComputingLayout}, " +
                        "rvIsAttached=${baseBind.recyclerView.isAttachedToWindow}"
                )
            }
        }

        viewModel.headerItems.observe(viewLifecycleOwner) { items ->
            headerAdapter.submitItems(items)
        }

        viewModel.relatedIllusts.observe(viewLifecycleOwner) { illusts ->
            // Sync nextUrl so IAdapter's click handler can build PageData for VActivity
            relatedAdapter.setNextUrl(viewModel.relatedNextUrl)
            // Post to avoid notifying adapter during RecyclerView layout/scroll
            baseBind.recyclerView.post {
                val oldSize = relatedList.size
                if (illusts.size > oldSize) {
                    relatedList.addAll(illusts.subList(oldSize, illusts.size))
                    relatedAdapter.notifyItemRangeInserted(oldSize, illusts.size - oldSize)
                } else if (illusts.size != oldSize) {
                    relatedList.clear()
                    relatedList.addAll(illusts)
                    relatedAdapter.notifyDataSetChanged()
                }
            }
        }

        viewModel.isLoadingRelated.observe(viewLifecycleOwner) { loading ->
            if (loading) loadingFooter.show() else loadingFooter.hide()
        }

        // 悬浮操作胶囊底色跟随主题色（原 bg_v3_fab_bar 固定 #CC1A1A2E,切主题色不动）——
        // 同 settings 卡片 tint,保留悬浮半透明。
        baseBind.fabBar.background = headerAdapter.palette.floatingPillBg(
            999f * resources.displayMetrics.density
        )

        viewModel.isBookmarked.observe(viewLifecycleOwner) { bookmarked ->
            baseBind.fabBookmark.imageTintList = android.content.res.ColorStateList.valueOf(
                if (bookmarked) mContext.getColor(R.color.has_bookmarked)
                else android.graphics.Color.WHITE
            )
        }

    }


    override fun onResume() {
        super.onResume()
        viewModel.refreshDownloadFab()
    }

    private fun renderDownloadFab(state: DownloadFab) {
        if (view == null) return
        when (state) {
            DownloadFab.Idle ->
                paintFab(R.drawable.ic_file_download_black_24dp, Color.WHITE)
            DownloadFab.Done ->
                paintFab(R.drawable.ic_file_download_done_24dp, mContext.getColor(R.color.has_downloaded))
            is DownloadFab.Downloading -> {
                baseBind.fabDownload.visibility = View.INVISIBLE
                baseBind.fabDownloadProgress.visibility = View.VISIBLE
                baseBind.fabDownloadProgress.setProgressCompat(state.percent, true)
            }
        }
    }

    private fun paintFab(@DrawableRes iconRes: Int, @ColorInt tint: Int) {
        baseBind.fabDownloadProgress.visibility = View.GONE
        baseBind.fabDownload.visibility = View.VISIBLE
        baseBind.fabDownload.setImageResource(iconRes)
        baseBind.fabDownload.imageTintList = ColorStateList.valueOf(tint)
    }

    private var fabShown = true

    private fun hideFabBar() {
        if (!fabShown) return
        fabShown = false
        baseBind.fabBar.animate().translationY(baseBind.fabBar.height + 100f)
            .alpha(0f).setDuration(200).start()
    }

    private fun showFabBar() {
        if (fabShown) return
        fabShown = true
        baseBind.fabBar.animate().translationY(0f).alpha(1f).setDuration(200).start()
    }

    private fun handleSystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(baseBind.toolbar) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(v.paddingLeft, insets.top, v.paddingRight, v.paddingBottom)
            windowInsets
        }
        ViewCompat.setOnApplyWindowInsetsListener(baseBind.fabBar) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val lp = v.layoutParams as FrameLayout.LayoutParams
            lp.bottomMargin = insets.bottom + 24.ppppx
            v.layoutParams = lp
            windowInsets
        }
        // RV 自己 match_parent 延伸到屏幕最底(背景填满 safe area),滚动时通过
        // clipToPadding=false + bottomPadding=navBar inset 让最后一个 item 停在
        // 系统导航栏之上,不被遮挡。
        baseBind.recyclerView.clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(baseBind.recyclerView) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, insets.bottom)
            windowInsets
        }
        // Pin the floating "收起" pill just below the top overlay column
        // (toolbar + optional retry banner). Driving the margin from the
        // column's actual bottom keeps the pill clear of the banner when it
        // appears — see issue #881.
        val pillGap = 8.ppppx
        baseBind.topOverlayColumn.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom == oldBottom) return@addOnLayoutChangeListener
            val pill = baseBind.collapsePill
            val lp = pill.layoutParams as FrameLayout.LayoutParams
            val target = bottom + pillGap
            if (lp.topMargin != target) {
                lp.topMargin = target
                pill.layoutParams = lp
            }
            // 下拉刷新的转圈圈也从 toolbar(+重试横幅)之下开始,
            // 不顶着透明状态栏/toolbar 区域。
            baseBind.refreshLayout.setHeaderInsetStartPx(bottom)
        }
    }

    private fun setupNavBar(illustId: Long) {
        baseBind.toolbar.setNavigationOnClickListener { mActivity.finish() }

        // Apply download/bookmark order preference
        if (!Shaft.sSettings.isArtworkV3FabDownloadOnLeft) {
            val bar = baseBind.fabBar
            val download = baseBind.fabDownloadContainer
            val bookmark = baseBind.fabBookmark
            bar.removeView(download)
            bar.removeView(bookmark)
            bar.addView(bookmark, 0)
            bar.addView(download)
        }

        // Floating action bar — downloads go through the shared TaskPool so
        // the Glide cache warmed here gives 二级详情页 instant display.
        baseBind.fabDownloadContainer.setOnClick {
            val illust = ObjectPool.get<IllustsBean>(illustId).value ?: return@setOnClick
            viewModel.triggerDownload()
            if (Shaft.sSettings.isAutoPostLikeWhenDownload && !illust.isIs_bookmarked) {
                // 下载触发的自动收藏也要立刻点亮收藏 FAB,否则要退出重进才显示已收藏
                // (V3 页无 LIKED_ILLUST 接收器,FAB 仅靠 ObjectPool 重发刷新)。同用户主动收藏路径。
                baseBind.fabBookmark.imageTintList = android.content.res.ColorStateList.valueOf(
                    mContext.getColor(R.color.has_bookmarked)
                )
                PixivOperate.postLikeDefaultStarType(illust)
            }
        }
        baseBind.fabDownloadContainer.setOnLongClickListener {
            val illust = ObjectPool.get<IllustsBean>(illustId).value ?: return@setOnLongClickListener true
            val baseAct = mActivity as? ceui.lisa.activities.BaseActivity<*>
            val resNames = arrayOf(
                getString(R.string.resolution_original),
                getString(R.string.resolution_large),
                getString(R.string.resolution_medium),
                getString(R.string.resolution_square_medium)
            )
            val resValues = arrayOf(
                Params.IMAGE_RESOLUTION_ORIGINAL,
                Params.IMAGE_RESOLUTION_LARGE,
                Params.IMAGE_RESOLUTION_MEDIUM,
                Params.IMAGE_RESOLUTION_SQUARE_MEDIUM
            )
            QMUIDialog.MenuDialogBuilder(mContext)
                .addItems(resNames) { dialog, which ->
                    if (illust.page_count == 1) {
                        IllustDownload.downloadIllustFirstPageWithResolution(illust, resValues[which], baseAct)
                    } else {
                        IllustDownload.downloadIllustAllPagesWithResolution(illust, resValues[which], baseAct)
                    }
                    viewModel.refreshDownloadFab()
                    dialog.dismiss()
                }
                .show()
            true
        }
        viewModel.downloadFabState.observe(viewLifecycleOwner) { renderDownloadFab(it) }

        // 监听 Manager 下载完成广播，刷新 FAB 状态。
        // 轮询期间不干扰（轮询自己会检测队列清空并设 Done）。
        val downloadFinishReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!viewModel.isPollingProgress) {
                    viewModel.refreshDownloadFab()
                }
            }
        }
        LocalBroadcastManager.getInstance(mContext).registerReceiver(
            downloadFinishReceiver, IntentFilter(Params.DOWNLOAD_FINISH)
        )
        viewLifecycleOwner.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                LocalBroadcastManager.getInstance(mContext).unregisterReceiver(downloadFinishReceiver)
            }
        })

        baseBind.fabBookmark.setOnClick {
            val illust = ObjectPool.get<IllustsBean>(illustId).value ?: return@setOnClick
            // Optimistic UI: toggle icon immediately
            val willBookmark = !illust.isIs_bookmarked
            baseBind.fabBookmark.imageTintList = android.content.res.ColorStateList.valueOf(
                if (willBookmark) mContext.getColor(R.color.has_bookmarked) else android.graphics.Color.WHITE
            )
            PixivOperate.postLikeDefaultStarType(illust)
            // 收藏后自动下载只在用户主动收藏(非取消)时触发,避免和"下载时自动收藏"循环联动(issue #880)。
            if (willBookmark && Shaft.sSettings.isAutoDownloadAfterStar) {
                IllustDownload.downloadIllustAllPages(illust)
            }
        }

        baseBind.fabBookmark.setOnLongClickListener {
            val illust = ObjectPool.get<IllustsBean>(illustId).value ?: return@setOnLongClickListener true
            val intent = Intent(mContext, ceui.lisa.activities.TemplateActivity::class.java).apply {
                putExtra(Params.ILLUST_ID, illust.id)
                putExtra(Params.DATA_TYPE, Params.TYPE_ILLUST)
                putExtra(Params.TAG_NAMES, illust.tagNames)
                putExtra(ceui.lisa.activities.TemplateActivity.EXTRA_FRAGMENT, "按标签收藏")
            }
            startActivity(intent)
            true
        }

        // More menu
        baseBind.navMore.setOnClick {
            val illust = ObjectPool.get<IllustsBean>(illustId).value ?: return@setOnClick
            showV3Menu {
                item(getString(R.string.share), R.drawable.ic_share_black_24dp) {
                    object : ShareIllust(mContext, illust) {
                        override fun onPrepare() {}
                    }.execute()
                }
                item(getString(R.string.string_454), R.drawable.ic_share_black_24dp) {
                    shareFirstImage(illust)
                }
                item(getString(R.string.string_355_2), R.drawable.ic_baseline_launch_24) {
                    Common.copy(mContext, ShareIllust.URL_Head + illust.id)
                }
                item(getString(R.string.string_1), R.drawable.ic_baseline_settings_24) {
                    MuteDialog.newInstance(illust)
                        .show(this@ArtworkV3Fragment.childFragmentManager, "MuteDialog")
                }
                item(getString(R.string.string_355), R.drawable.ic_visibility_off_black_24dp) {
                    PixivOperate.muteIllust(illust)
                }
                item(getString(R.string.flag_post), R.drawable.ic_baseline_remove_red_eye_24) {
                    val intent = android.content.Intent(
                        mContext,
                        ceui.lisa.activities.TemplateActivity::class.java
                    )
                    intent.putExtra(
                        ceui.lisa.activities.TemplateActivity.EXTRA_FRAGMENT,
                        "举报插画"
                    )
                    intent.putExtra(ceui.loxia.flag.FlagDescFragment.FlagObjectIdKey, illust.id)
                    intent.putExtra(
                        ceui.loxia.flag.FlagDescFragment.FlagObjectTypeKey,
                        ceui.lisa.models.ObjectSpec.POST
                    )
                    startActivity(intent)
                }
                item(getString(R.string.string_ai_upscale), R.drawable.ic_upscale_add_photo) {
                    ceui.pixiv.ui.upscale.ModelPickerDialog.pickOrUseDefault(
                        this@ArtworkV3Fragment.childFragmentManager
                    ) { model ->
                        // AI upscale requires IllustAiHelper
                    }
                }
                if (Dev.showPlazaShareInArtwork) {
                    item(
                        getString(R.string.plaza_share_illust_to_plaza),
                        R.drawable.ic_plaza_forum_24,
                    ) {
                        val intent = Intent(mContext, ceui.lisa.activities.TemplateActivity::class.java)
                        intent.putExtra(
                            ceui.lisa.activities.TemplateActivity.EXTRA_FRAGMENT, "发帖"
                        )
                        intent.putExtra(
                            ceui.pixiv.plaza.ui.PlazaComposeFragment.ARG_PREFILL_ILLUST_ID,
                            illust.id.toLong(),
                        )
                        startActivity(intent)
                    }
                }
            }
        }
    }


    override fun vertical() {}

    /**
     * Spacing decoration that only applies to non-fullSpan items (IAdapter's related cards).
     * Header items and loading footer are fullSpan and get zero offset.
     *
     * Distributes [space] gutters evenly for any [spanCount] >= 1: full gutter
     * outside the leftmost / rightmost columns, half gutter on the inner sides.
     * For spanCount=2 this matches the previous hardcoded behavior; for 3+ it
     * keeps the middle columns from collapsing against either neighbor.
     */
    private class RelatedOnlySpaceDecoration(
        private val space: Int,
        private val spanCount: Int,
    ) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val lp = view.layoutParams
            if (lp !is StaggeredGridLayoutManager.LayoutParams || lp.isFullSpan) return

            outRect.bottom = space
            val spanIndex = lp.spanIndex
            outRect.left = if (spanIndex == 0) space else space / 2
            outRect.right = if (spanIndex == spanCount - 1) space else space / 2
        }
    }

    companion object {
        @JvmStatic
        fun newInstance(illustId: Int): ArtworkV3Fragment {
            return ArtworkV3Fragment().apply {
                arguments = Bundle().apply { putInt("illust_id", illustId) }
            }
        }

        @JvmStatic
        fun newInstance(illustId: Long): ArtworkV3Fragment = newInstance(illustId.toInt())
    }
}
