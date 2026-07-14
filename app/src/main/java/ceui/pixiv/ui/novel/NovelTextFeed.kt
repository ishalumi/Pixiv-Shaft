package ceui.pixiv.ui.novel

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.view.View
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.CellNovelActionsBinding
import ceui.lisa.databinding.CellNovelCaptionBinding
import ceui.lisa.databinding.CellNovelHeaderBinding
import ceui.lisa.databinding.CellNovelProfileBinding
import ceui.lisa.databinding.CellNovelTagsBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.lisa.utils.ShareIllust
import ceui.lisa.utils.V3Palette
import ceui.loxia.Client
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.Series
import ceui.loxia.User
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.ui.common.IllustCardActionReceiver
import ceui.pixiv.ui.common.NOVEL_URL_HEAD
import ceui.pixiv.ui.common.bindCopyChip
import ceui.pixiv.ui.common.bindCopyLinkChip
import ceui.pixiv.ui.common.bindOpenLinkChip
import ceui.pixiv.ui.common.NovelActionReceiver
import ceui.pixiv.ui.detail.SeriesAuthorFeedItem
import ceui.pixiv.ui.user.UserActionReceiver
import ceui.pixiv.utils.extractPixivId
import ceui.pixiv.utils.setOnClick
import timber.log.Timber
import java.text.NumberFormat

// ── 从旧 holder 文件迁来的公共契约（同包，FQN 不变，外部 import 无需改）──────

interface NovelSeriesActionReceiver {
    fun onClickNovelSeries(sender: View, series: Series)
}

interface NovelActionsReceiver {
    fun onClickShareNovel(sender: View, novelId: Long)
    fun onClickNovelComments(sender: View, novelId: Long)
    fun onClickDownloadNovel(sender: View, novelId: Long)
    fun onLongClickDownloadNovel(sender: View, novelId: Long)
}

internal fun openTagBookmarkForNovel(sender: View, novel: Novel) {
    val ctx = sender.context
    val tagNames = novel.tags.orEmpty().mapNotNull { it.name }.toTypedArray()
    val intent = Intent(ctx, TemplateActivity::class.java).apply {
        putExtra(TemplateActivity.EXTRA_FRAGMENT, "按标签收藏")
        putExtra(Params.ILLUST_ID, novel.id.toInt())
        putExtra(Params.DATA_TYPE, Params.TYPE_NOVEL)
        putExtra(Params.TAG_NAMES, tagNames)
    }
    ctx.startActivity(intent)
}

// ── FeedItem 模型（都以 novelId 为身份；实际小说数据由渲染器观察 ObjectPool 取）─────

data class NovelHeaderFeedItem(val novelId: Long) : FeedItem {
    override val feedKey: Any get() = novelId
}

data class NovelProfileFeedItem(val novelId: Long) : FeedItem {
    override val feedKey: Any get() = novelId
}

data class NovelActionsFeedItem(val novelId: Long) : FeedItem {
    override val feedKey: Any get() = novelId
}

data class NovelTagsFeedItem(val novelId: Long) : FeedItem {
    override val feedKey: Any get() = novelId
}

data class NovelCaptionFeedItem(val novelId: Long) : FeedItem {
    override val feedKey: Any get() = novelId
}

// ── FeedSource：单页，无分页（一篇小说的固定 6 张卡）──────────────────────

class NovelTextFeedSource(private val novelId: Long) : FeedSource<Int> {
    override suspend fun load(cursor: Int?): FeedPage<Int> {
        val novel = Client.appApi.getNovel(novelId).novel?.also {
            ObjectPool.update(it)
            it.user?.let { user -> ObjectPool.update(user) }
        }
        val items = mutableListOf<FeedItem>()
        items.add(NovelHeaderFeedItem(novelId))
        (novel?.user ?: ObjectPool.get<Novel>(novelId).value?.user)?.let { user: User ->
            items.add(SeriesAuthorFeedItem(user))
        }
        items.add(NovelProfileFeedItem(novelId))
        items.add(NovelActionsFeedItem(novelId))
        items.add(NovelTagsFeedItem(novelId))
        items.add(NovelCaptionFeedItem(novelId))
        return FeedPage(items, null)
    }
}

// ── Renderers（复用旧 cell XML，bind 逻辑对齐旧 ViewHolder；单实例条目安全 observe）──

fun novelHeaderRenderer(
    lifecycleOwner: LifecycleOwner,
): FeedRenderer<NovelHeaderFeedItem, CellNovelHeaderBinding> =
    feedRenderer(
        inflate = CellNovelHeaderBinding::inflate,
        fullSpan = true,
        create = { cell -> cell.binding.lifecycleOwner = lifecycleOwner },
    ) { cell ->
        val b = cell.binding
        val novelId = cell.item.novelId
        val ctx = b.root.context
        val liveNovel = ObjectPool.get<Novel>(novelId)
        b.novel = liveNovel
        b.bookmark.setOnClick {
            it.findActionReceiverOrNull<NovelActionReceiver>()?.onClickBookmarkNovel(it, novelId)
        }
        b.bookmark.setOnLongClickListener { sender ->
            val novel = liveNovel.value ?: return@setOnLongClickListener false
            openTagBookmarkForNovel(sender, novel); true
        }
        val d = ctx.resources.displayMetrics.density
        val palette = V3Palette.from(ctx)
        b.seriesStrip.background = palette.seriesStripBg(20f * d)
        b.seriesIcon.background = palette.seriesIconBg(10f * d)
        b.seriesName.setTextColor(palette.seriesStripText)
        b.seriesLabel.setTextColor(palette.seriesStripText)
        b.seriesChevron.setTextColor(palette.seriesStripText)
        b.seriesStrip.setOnClick { sender ->
            liveNovel.value?.series?.let { series ->
                sender.findActionReceiverOrNull<NovelSeriesActionReceiver>()
                    ?.onClickNovelSeries(sender, series)
            }
        }
        b.title.setOnClick { Common.copy(ctx, liveNovel.value?.title) }
        b.title.setOnLongClickListener { Common.copy(ctx, liveNovel.value?.title); true }
        liveNovel.observe(lifecycleOwner) { novel ->
            if (novel == null) return@observe
            b.bookmark.imageTintList = if (novel.is_bookmarked == true) null
                else ColorStateList.valueOf(ctx.getColor(R.color.v3_text_3))
            b.metaDate.text = novel.create_date?.replace('T', ' ')?.take(16).orEmpty()
            val isAi = novel.novel_ai_type == 2
            b.metaAi.isVisible = isAi
            b.metaDotAi.isVisible = isAi
            val wordCount = novel.text_length
            if (wordCount != null && wordCount > 0) {
                b.metaWordCount.text = ctx.getString(
                    R.string.novel_meta_word_count,
                    NumberFormat.getInstance().format(wordCount),
                )
                b.metaWordCount.isVisible = true
                b.metaDot2.isVisible = true
            } else {
                b.metaWordCount.isVisible = false
                b.metaDot2.isVisible = false
            }
        }
    }

fun novelProfileRenderer(
    lifecycleOwner: LifecycleOwner,
): FeedRenderer<NovelProfileFeedItem, CellNovelProfileBinding> =
    feedRenderer(
        inflate = CellNovelProfileBinding::inflate,
        fullSpan = true,
    ) { cell ->
        val b = cell.binding
        val fmt = NumberFormat.getInstance()

        ObjectPool.get<Novel>(cell.item.novelId).observe(lifecycleOwner) { novel ->
            if (novel == null) return@observe
            b.statViews.text = fmt.format(novel.total_view ?: 0)
            b.statBookmarks.text = fmt.format(novel.total_bookmarks ?: 0)

            b.chipNovelId.bindCopyChip(R.string.novel_chip_id, novel.id.toString(), novel.id.toString())
            novel.text_length?.let {
                b.chipTextLength.bindCopyChip(R.string.novel_chip_text_length, it.toString(), it.toString())
            } ?: run { b.chipTextLength.isVisible = false }
            novel.total_view?.let {
                b.chipTotalView.bindCopyChip(R.string.novel_chip_total_view, it.toString(), it.toString())
            } ?: run { b.chipTotalView.isVisible = false }
            novel.total_bookmarks?.let {
                b.chipTotalBookmarks.bindCopyChip(R.string.novel_chip_total_bookmarks, it.toString(), it.toString())
            } ?: run { b.chipTotalBookmarks.isVisible = false }
            novel.create_date?.let {
                val display = it.replace('T', ' ').take(16)
                b.chipCreateDate.bindCopyChip(R.string.novel_chip_create_date, display, it)
            } ?: run { b.chipCreateDate.isVisible = false }
            novel.user?.let { user ->
                val name = user.name.orEmpty()
                b.chipAuthor.bindCopyChip(R.string.novel_chip_author, name, name)
                b.chipAuthorId.bindCopyChip(R.string.novel_chip_author_id, user.id.toString(), user.id.toString())
                b.chipUserLink.bindOpenLinkChip(R.string.novel_chip_user_link, ShareIllust.USER_URL_Head + user.id)
            } ?: run {
                b.chipAuthor.isVisible = false
                b.chipAuthorId.isVisible = false
                b.chipUserLink.isVisible = false
            }
            val novelUrl = NOVEL_URL_HEAD + novel.id
            b.chipNovelLink.bindCopyLinkChip(R.string.novel_chip_novel_link, novelUrl)
            b.chipOpenNovelLink.bindOpenLinkChip(R.string.novel_chip_open_novel_link, novelUrl)
        }
    }

fun novelActionsRenderer(): FeedRenderer<NovelActionsFeedItem, CellNovelActionsBinding> =
    feedRenderer(
        inflate = CellNovelActionsBinding::inflate,
        fullSpan = true,
    ) { cell ->
        val b = cell.binding
        val novelId = cell.item.novelId
        b.btnShare.setOnClick {
            it.findActionReceiverOrNull<NovelActionsReceiver>()?.onClickShareNovel(it, novelId)
        }
        b.btnComments.setOnClick {
            it.findActionReceiverOrNull<NovelActionsReceiver>()?.onClickNovelComments(it, novelId)
        }
        b.btnDownload.setOnClick {
            it.findActionReceiverOrNull<NovelActionsReceiver>()?.onClickDownloadNovel(it, novelId)
        }
        b.btnDownload.setOnLongClickListener { sender ->
            sender.findActionReceiverOrNull<NovelActionsReceiver>()?.onLongClickDownloadNovel(sender, novelId)
            true
        }
    }

fun novelTagsRenderer(
    lifecycleOwner: LifecycleOwner,
): FeedRenderer<NovelTagsFeedItem, CellNovelTagsBinding> =
    feedRenderer(
        inflate = CellNovelTagsBinding::inflate,
        fullSpan = true,
    ) { cell ->
        val b = cell.binding
        b.tagsFlow.searchIndex = 1 // novels tab in SearchActivity
        ObjectPool.get<Novel>(cell.item.novelId).observe(lifecycleOwner) { novel ->
            b.tagsFlow.setTags(novel?.tags.orEmpty())
        }
    }

fun novelCaptionRenderer(
    lifecycleOwner: LifecycleOwner,
): FeedRenderer<NovelCaptionFeedItem, CellNovelCaptionBinding> =
    feedRenderer(
        inflate = CellNovelCaptionBinding::inflate,
        fullSpan = true,
        create = { cell -> cell.binding.lifecycleOwner = lifecycleOwner },
    ) { cell ->
        val b = cell.binding
        val ctx = b.root.context
        val liveNovel = ObjectPool.get<Novel>(cell.item.novelId)
        b.novel = liveNovel
        liveNovel.observe(lifecycleOwner) { novel ->
            val rawCaption = novel.caption.orEmpty()
            val hasCaption = rawCaption.isNotEmpty()
            val normalizedCaption = rawCaption.replace("\r\n", "\n").replace("\n", "<br/>")
            if (hasCaption) {
                b.caption.isVisible = true
                val linkHandler = CustomLinkMovementMethod { link ->
                    val info = extractPixivId(link)
                    when (info.type) {
                        "novels" -> info.value.toLongOrNull()?.let { id ->
                            b.caption.findActionReceiverOrNull<NovelActionReceiver>()?.visitNovelById(id)
                        }
                        "illusts" -> info.value.toLongOrNull()?.let { id ->
                            b.caption.findActionReceiverOrNull<IllustCardActionReceiver>()?.visitIllustById(id)
                        }
                        "users" -> info.value.toLongOrNull()?.let { id ->
                            b.caption.findActionReceiverOrNull<UserActionReceiver>()?.onClickUser(id)
                        }
                        else -> {
                            val uri = runCatching { Uri.parse(link) }.getOrNull()
                            val scheme = uri?.scheme?.lowercase()
                            if (uri != null && (scheme == "http" || scheme == "https")) {
                                runCatching {
                                    b.caption.context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                }
                            }
                        }
                    }
                    Timber.d("caption link clicked: $info")
                }
                b.caption.movementMethod = linkHandler
                b.caption.text = HtmlCompat.fromHtml(normalizedCaption, HtmlCompat.FROM_HTML_MODE_COMPACT)
                b.caption.setOnClick {
                    if (linkHandler.wasLinkClicked) return@setOnClick
                    val plain = HtmlCompat.fromHtml(normalizedCaption, HtmlCompat.FROM_HTML_MODE_COMPACT)
                        .toString().trim()
                    Common.copy(ctx, plain)
                }
            } else {
                b.caption.isVisible = false
            }
        }
    }
