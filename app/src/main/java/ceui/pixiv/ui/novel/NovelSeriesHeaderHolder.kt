package ceui.pixiv.ui.novel

import android.content.res.ColorStateList
import android.widget.TextView
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellNovelSeriesCaptionBinding
import ceui.lisa.databinding.CellNovelSeriesHeroBinding
import ceui.lisa.databinding.CellNovelSeriesProfileBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.ShareIllust
import ceui.loxia.NovelSeriesDetail
import ceui.loxia.ProgressImageButton
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.utils.setOnClick
import java.text.NumberFormat

// ── 1. Hero: title + bookmark + meta ────────────────────────────────

/**
 * @param latestNovelId 系列最新一话的 novel id，点击"读最新一话"按钮跳转用。
 * @param latestNovelChapterIndex 最新一话在系列里的章节序号（1-based，等同于
 *   `novel_series_detail.content_count`），仅用于按钮文案 "(#N)"。
 *   两者同时为 null 表示系列尚未发布任何章节，按钮不显示。
 */
class NovelSeriesHeroHolder(
    val series: NovelSeriesDetail,
    val latestNovelId: Long? = null,
    val latestNovelChapterIndex: Int? = null,
) : ListItemHolder() {
    override fun getItemId(): Long = series.id

    // CommonAdapter 默认 areContentsTheSame == areItemsTheSame，会按 itemId 判等。
    // 切换 watchlist 后系列 id 没变，DiffUtil 会跳过重绑——书签图标不会重画。
    // 这里把变化字段（watchlist_added / latest 章节）显式参与判等。
    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        return other is NovelSeriesHeroHolder &&
                series.id == other.series.id &&
                series.watchlist_added == other.series.watchlist_added &&
                latestNovelId == other.latestNovelId &&
                latestNovelChapterIndex == other.latestNovelChapterIndex
    }
}

@ItemHolder(NovelSeriesHeroHolder::class)
class NovelSeriesHeroViewHolder(bd: CellNovelSeriesHeroBinding) :
    ListItemViewHolder<CellNovelSeriesHeroBinding, NovelSeriesHeroHolder>(bd) {

    override fun onBindViewHolder(holder: NovelSeriesHeroHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val series = holder.series
        val fmt = NumberFormat.getInstance()

        binding.title.text = series.title ?: ""
        binding.title.setOnClick { Common.copy(it.context, series.title) }

        // Bookmark — tap to toggle watchlist. 旧页 FragmentNovelSeriesDetail 用
        // 文字按钮"加入观看清单 / 已加入"做这件事；新页统一用图标按钮，靠 tint
        // + 实心/线性图标区分已加 / 未加状态。
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

        // Meta line
        binding.metaContentCount.text =
            context.getString(R.string.novel_meta_chapter_count, series.content_count)
        if (series.total_character_count > 0) {
            binding.metaCharCount.text = context.getString(
                R.string.novel_meta_word_count,
                fmt.format(series.total_character_count),
            )
            binding.metaCharCount.isVisible = true
            binding.metaDot2.isVisible = true
        } else {
            binding.metaCharCount.isVisible = false
            binding.metaDot2.isVisible = false
        }

        // 系列已发布章节时显示"阅读最新一话(#N)"。
        val latestId = holder.latestNovelId
        val latestIdx = holder.latestNovelChapterIndex
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

// ── 2. Caption ──────────────────────────────────────────────────────

class NovelSeriesCaptionHolder(val series: NovelSeriesDetail) : ListItemHolder() {
    override fun getItemId(): Long = series.id + 1_000_000
}

@ItemHolder(NovelSeriesCaptionHolder::class)
class NovelSeriesCaptionViewHolder(bd: CellNovelSeriesCaptionBinding) :
    ListItemViewHolder<CellNovelSeriesCaptionBinding, NovelSeriesCaptionHolder>(bd) {

    override fun onBindViewHolder(holder: NovelSeriesCaptionHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val rawCaption = holder.series.caption.orEmpty()
        if (rawCaption.isNotEmpty()) {
            binding.caption.isVisible = true
            val normalized = rawCaption.replace("\r\n", "\n").replace("\n", "<br/>")
            binding.caption.text =
                HtmlCompat.fromHtml(normalized, HtmlCompat.FROM_HTML_MODE_COMPACT)
            binding.caption.setOnClick {
                val plain = HtmlCompat.fromHtml(normalized, HtmlCompat.FROM_HTML_MODE_COMPACT)
                    .toString().trim()
                Common.copy(it.context, plain)
            }
        } else {
            binding.caption.isVisible = false
        }
    }
}

// ── 3. Profile chips ────────────────────────────────────────────────

class NovelSeriesProfileHolder(val series: NovelSeriesDetail) : ListItemHolder() {
    override fun getItemId(): Long = series.id + 2_000_000
}

@ItemHolder(NovelSeriesProfileHolder::class)
class NovelSeriesProfileViewHolder(bd: CellNovelSeriesProfileBinding) :
    ListItemViewHolder<CellNovelSeriesProfileBinding, NovelSeriesProfileHolder>(bd) {

    override fun onBindViewHolder(holder: NovelSeriesProfileHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        bindInfoChips(holder.series)
    }

    private fun bindInfoChips(series: NovelSeriesDetail) {
        chip(binding.chipSeriesId, R.string.novel_chip_series_id,
            series.id.toString(), series.id.toString())
        series.user?.let { user ->
            val name = user.name.orEmpty()
            chip(binding.chipAuthor, R.string.novel_chip_author, name, name)
            chip(binding.chipAuthorId, R.string.novel_chip_author_id,
                user.id.toString(), user.id.toString())
            linkChip(binding.chipUserLink, R.string.novel_chip_user_link,
                ShareIllust.USER_URL_Head + user.id)
        } ?: run {
            binding.chipAuthor.isVisible = false
            binding.chipAuthorId.isVisible = false
            binding.chipUserLink.isVisible = false
        }
        if (series.content_count > 0) {
            chip(binding.chipContentCount, R.string.novel_chip_series_content_count,
                series.content_count.toString(), series.content_count.toString())
        } else {
            binding.chipContentCount.isVisible = false
        }
        if (series.total_character_count > 0) {
            chip(binding.chipCharCount, R.string.novel_chip_series_char_count,
                series.total_character_count.toString(), series.total_character_count.toString())
        } else {
            binding.chipCharCount.isVisible = false
        }
        linkChip(binding.chipSeriesLink, R.string.novel_chip_series_link,
            "https://www.pixiv.net/novel/series/${series.id}")
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
}

interface NovelSeriesHeaderActionReceiver {
    /**
     * 书签按钮被点击。receiver 拿权威状态从 ViewModel 自己读，并负责在网络
     * 请求期间调用 progressView.showProgress / hideProgress 防止重复点击。
     */
    fun onClickToggleWatchlist(progressView: ProgressImageButton)

    /** "阅读最新一话(#N)" 被点击；novelId 由 holder 自己拿到的最新一话 id。 */
    fun onClickReadLatestEpisode(novelId: Long)
}
