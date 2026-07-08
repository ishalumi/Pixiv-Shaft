package ceui.pixiv.ui.detail

import android.content.ActivityNotFoundException
import android.content.res.ColorStateList
import android.net.Uri
import android.widget.TextView
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.view.isVisible
import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellMangaSeriesHeroBinding
import ceui.lisa.databinding.CellMangaSeriesItemBinding
import ceui.lisa.databinding.CellMangaSeriesProfileBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.ShareIllust
import ceui.lisa.utils.V3Palette
import ceui.loxia.DateParse
import ceui.loxia.Illust
import ceui.loxia.NovelSeriesDetail
import ceui.loxia.ObjectPool
import ceui.loxia.ProgressImageButton
import ceui.loxia.ProgressIndicator
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.IllustCardActionReceiver
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.ui.novel.NovelSeriesHeaderActionReceiver
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide

/** 漫画系列总话数：优先 series_work_count（/v1/illust/series 用的字段），退回
 *  content_count。两者都是 0 表示接口没给，调用方应当整个不展示。 */
fun mangaSeriesEpisodeCount(series: NovelSeriesDetail): Int =
    series.series_work_count.takeIf { it > 0 } ?: series.content_count

// ── 1. Hero: 标题 + 收藏(观看清单) + Manga·N话 + 阅读最新一话 ────────────
//
// 漫画系列 detail 的 illust_series_detail 复用 NovelSeriesDetail（后端两套系列
// 字段一致）。字数相关字段(total_character_count)在漫画系列恒为 0，这里根本不
// 展示，所以只用 title / content_count / watchlist_added / user。

/**
 * @param latestIllustId 系列最新一话的 illust id，「阅读最新一话」按钮跳转用。
 * @param latestEpisodeIndex 最新一话在系列里的序号（1-based == content_count），
 *   仅用于按钮文案 "(#N)"。两者同时为 null 表示系列尚无章节，按钮不显示。
 */
class MangaSeriesHeroHolder(
    val series: NovelSeriesDetail,
    val latestIllustId: Long? = null,
    val latestEpisodeIndex: Int? = null,
) : ListItemHolder() {
    override fun getItemId(): Long = series.id

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        return other is MangaSeriesHeroHolder &&
                series.id == other.series.id &&
                series.watchlist_added == other.series.watchlist_added &&
                latestIllustId == other.latestIllustId &&
                latestEpisodeIndex == other.latestEpisodeIndex
    }
}

@ItemHolder(MangaSeriesHeroHolder::class)
class MangaSeriesHeroViewHolder(bd: CellMangaSeriesHeroBinding) :
    ListItemViewHolder<CellMangaSeriesHeroBinding, MangaSeriesHeroHolder>(bd) {

    override fun onBindViewHolder(holder: MangaSeriesHeroHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val series = holder.series

        binding.title.text = series.title ?: ""
        binding.title.setOnClick { Common.copy(it.context, series.title) }
        binding.title.setOnLongClickListener {
            Common.copy(it.context, series.title); true
        }

        // 收藏 = 漫画观看清单（postWatchlistManga add/delete，fragment 里实现）。
        binding.bookmark.setImageResource(
            if (series.watchlist_added == true) R.drawable.icon_liked else R.drawable.icon_not_liked
        )
        binding.bookmark.imageTintList = if (series.watchlist_added == true) {
            null
        } else {
            ColorStateList.valueOf(context.getColor(R.color.v3_text_3))
        }
        binding.bookmark.setOnClick { v ->
            v.findActionReceiverOrNull<NovelSeriesHeaderActionReceiver>()
                ?.onClickToggleWatchlist(v as ProgressImageButton)
        }

        // 话数：漫画系列走 series_work_count，退回 content_count。拿不到（==0）就只显示
        // 「Manga」，不显示误导性的「0话」。整条做成醒目的 V3 accent 胶囊。
        val parts = mutableListOf(context.getString(R.string.manga_series_meta_type))
        val episodeCount = mangaSeriesEpisodeCount(series)
        if (episodeCount > 0) {
            parts.add(context.getString(R.string.manga_series_episode_count, episodeCount))
        }
        val density = context.resources.displayMetrics.density
        val palette = V3Palette.from(context)
        binding.metaBadge.text = parts.joinToString("  ·  ")
        binding.metaBadge.background = palette.pillSecondary(999f, (1 * density).toInt())
        binding.metaBadge.setTextColor(palette.textAccent)

        val latestId = holder.latestIllustId
        val latestIdx = holder.latestEpisodeIndex
        if (latestId != null && latestIdx != null) {
            binding.readLatest.isVisible = true
            binding.readLatest.text =
                context.getString(R.string.read_latest_episode_with_num, latestIdx)
            binding.readLatest.setOnClick { v ->
                v.findActionReceiverOrNull<NovelSeriesHeaderActionReceiver>()
                    ?.onClickReadLatestEpisode(latestId)
            }
        } else {
            binding.readLatest.isVisible = false
            binding.readLatest.setOnClickListener(null)
        }
    }
}

// ── 2. Profile chips（作品档案）─────────────────────────────────────

class MangaSeriesProfileHolder(val series: NovelSeriesDetail) : ListItemHolder() {
    override fun getItemId(): Long = series.id + 2_000_000
}

@ItemHolder(MangaSeriesProfileHolder::class)
class MangaSeriesProfileViewHolder(bd: CellMangaSeriesProfileBinding) :
    ListItemViewHolder<CellMangaSeriesProfileBinding, MangaSeriesProfileHolder>(bd) {

    override fun onBindViewHolder(holder: MangaSeriesProfileHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val series = holder.series
        chip(binding.chipSeriesId, R.string.novel_chip_series_id,
            series.id.toString(), series.id.toString())
        val user = series.user
        if (user != null) {
            val name = user.name.orEmpty()
            chip(binding.chipAuthor, R.string.novel_chip_author, name, name)
            chip(binding.chipAuthorId, R.string.novel_chip_author_id,
                user.id.toString(), user.id.toString())
            openLinkChip(binding.chipUserLink, R.string.novel_chip_user_link,
                ShareIllust.USER_URL_Head + user.id)
        } else {
            binding.chipAuthor.isVisible = false
            binding.chipAuthorId.isVisible = false
            binding.chipUserLink.isVisible = false
        }
        val episodeCount = mangaSeriesEpisodeCount(series)
        if (episodeCount > 0) {
            chip(binding.chipContentCount, R.string.manga_series_chip_content_count,
                episodeCount.toString(), episodeCount.toString())
        } else {
            binding.chipContentCount.isVisible = false
        }
        // 漫画系列 web 链接需要作者 id：https://www.pixiv.net/user/{uid}/series/{sid}
        if (user != null) {
            val seriesUrl = "https://www.pixiv.net/user/${user.id}/series/${series.id}"
            linkChip(binding.chipSeriesLink, R.string.novel_chip_series_link, seriesUrl)
            openLinkChip(binding.chipOpenSeriesLink, R.string.novel_chip_open_series_link, seriesUrl)
        } else {
            binding.chipSeriesLink.isVisible = false
            binding.chipOpenSeriesLink.isVisible = false
        }
    }

    private fun chip(view: TextView, labelRes: Int, displayValue: String, copyValue: String) {
        view.text = context.getString(labelRes, displayValue)
        view.isVisible = true
        view.setOnClick { Common.copy(context, copyValue) }
    }

    private fun linkChip(view: TextView, labelRes: Int, url: String) {
        view.text = context.getString(labelRes)
        view.isVisible = true
        view.setOnClick { Common.copy(context, url) }
    }

    private fun openLinkChip(view: TextView, labelRes: Int, url: String) {
        view.text = context.getString(labelRes)
        view.isVisible = true
        view.setOnClick {
            try {
                CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
            } catch (_: ActivityNotFoundException) {
                Common.showToast("未找到浏览器")
            }
        }
    }
}

// ── 3. 单话卡片（标题优先，替代旧瀑布流缩略图）──────────────────────

class MangaSeriesItemHolder(
    val illust: Illust,
    var episodeIndex: Int,
) : ListItemHolder() {
    init {
        ObjectPool.update(illust)
        illust.user?.let { ObjectPool.update(it) }
    }

    override fun getItemId(): Long = illust.id

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        if (other !is MangaSeriesItemHolder) return false
        return illust.id == other.illust.id &&
                episodeIndex == other.episodeIndex &&
                illust.is_bookmarked == other.illust.is_bookmarked
    }
}

@ItemHolder(MangaSeriesItemHolder::class)
class MangaSeriesItemViewHolder(private val b: CellMangaSeriesItemBinding) :
    ListItemViewHolder<CellMangaSeriesItemBinding, MangaSeriesItemHolder>(b) {

    override fun onBindViewHolder(holder: MangaSeriesItemHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val illust = holder.illust

        val url = illust.image_urls?.let { it.square_medium ?: it.medium ?: it.large }
        Glide.with(context)
            .load(GlideUtil.getUrl(url))
            .placeholder(R.color.v3_surface_2)
            .error(R.color.v3_surface_2)
            .centerCrop()
            .into(b.cover)

        b.episodeIndex.text = "#${holder.episodeIndex}"

        val title = illust.title.orEmpty()
        b.title.isVisible = title.isNotBlank()
        b.title.text = title

        if (illust.page_count > 1) {
            b.pageCount.isVisible = true
            b.metaDot.isVisible = true
            b.pageCount.text = context.getString(R.string.manga_series_page_count, illust.page_count)
        } else {
            b.pageCount.isVisible = false
            b.metaDot.isVisible = false
        }
        b.publishDate.text = DateParse.getTimeAgo(context, illust.create_date)

        bindBookmark(illust)
        // 收藏状态实时刷新（点心后不用等重绑）。observe 挂在 fragment 生命周期上，
        // key 按 illust id 去重，随页面销毁清理。
        ObjectPool.get<Illust>(illust.id).observe(lifecycleOwner) { updated ->
            if (updated != null) bindBookmark(updated)
        }

        b.bookmarkBtn.setOnClick { sender ->
            sender.findActionReceiverOrNull<IllustCardActionReceiver>()
                ?.onClickBookmarkIllust(sender as ProgressIndicator, holder.illust.id)
        }
        b.root.setOnClick { sender ->
            sender.findActionReceiverOrNull<IllustCardActionReceiver>()
                ?.onClickIllustCard(holder.illust)
        }
    }

    private fun bindBookmark(illust: Illust) {
        val bookmarked = illust.is_bookmarked == true
        b.bookmarkBtn.setImageResource(
            if (bookmarked) R.drawable.icon_liked else R.drawable.icon_not_liked
        )
        b.bookmarkBtn.imageTintList = if (bookmarked) {
            null
        } else {
            ColorStateList.valueOf(context.getColor(R.color.v3_text_3))
        }
    }
}
