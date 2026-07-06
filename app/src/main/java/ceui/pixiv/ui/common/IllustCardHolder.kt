package ceui.pixiv.ui.common

import android.view.View
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellIllustCardBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUrlChild
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.loxia.ProgressIndicator
import ceui.loxia.Series
import ceui.loxia.findActionReceiverOrNull
import ceui.loxia.requireEntityWrapper
import ceui.pixiv.ui.detail.showV3Menu
import ceui.pixiv.ui.watchlater.WatchLaterActionReceiver
import ceui.pixiv.ui.slideshow.SlideshowLauncher
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.screenWidth
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import timber.log.Timber
import kotlin.math.roundToInt

class IllustCardHolder(val illust: Illust, val isBlocked: Boolean = false) : ListItemHolder() {

    init {
        ObjectPool.update(illust)
        illust.user?.let {
            ObjectPool.update(it)
        }
    }

    override fun areItemsTheSame(other: ListItemHolder): Boolean {
        return illust.id == (other as? IllustCardHolder)?.illust?.id
    }

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        return illust == (other as? IllustCardHolder)?.illust
    }
}

interface IllustCardActionReceiver {
    fun onClickIllustCard(illust: Illust)
    fun onClickBookmarkIllust(sender: ProgressIndicator, illustId: Long)
    fun visitIllustById(illustId: Long)
}

interface IllustSeriesActionReceiver {
    fun onClickIllustSeries(sender: View, series: Series)
}

interface IllustIdActionReceiver {
    fun onClickIllust(illustId: Long)
}

@ItemHolder(IllustCardHolder::class)
class IllustCardViewHolder(bd: CellIllustCardBinding) :
    ListItemViewHolder<CellIllustCardBinding, IllustCardHolder>(bd) {

    override fun onBindViewHolder(holder: IllustCardHolder, position: Int) {
        super.onBindViewHolder(holder, position)

        binding.illust = ObjectPool.get<Illust>(holder.illust.id)

        val itemWidth = ((screenWidth - 12.ppppx) / 2F).roundToInt()
        Timber.d("dsaadssw22 ${holder.illust.height}, ${holder.illust.width}")
        val itemHeight =
            (itemWidth * holder.illust.height / holder.illust.width.toFloat()).roundToInt()
        binding.image.updateLayoutParams {
            width = itemWidth
            height = itemHeight
        }

        if (holder.illust.page_count > 1) {
            binding.pSize.isVisible = true
            binding.pSize.text = "${holder.illust.page_count}P"
        } else {
            binding.pSize.isVisible = false
        }

        Glide.with(binding.root.context)
            .load(GlideUrlChild(holder.illust.image_urls?.large))
            .placeholder(R.drawable.bg_loading_placeholder)
            .into(binding.image)
        binding.image.setOnClick {
            it.findActionReceiverOrNull<IllustCardActionReceiver>()
                ?.onClickIllustCard(holder.illust)
        }
        binding.image.setOnLongClickListener { v ->
            showSlideshowMenu(v, holder.illust)
            true
        }
        binding.bookmark.setOnClick {
            it.findActionReceiverOrNull<IllustCardActionReceiver>()
                ?.onClickBookmarkIllust(it, holder.illust.id)
        }
    }

    /**
     * Long-press menu for V3 waterfall cards. Plays the entire visible list as a slideshow,
     * starting from the long-pressed card. The visible list is read off the parent
     * RecyclerView's CommonAdapter so this works for any list without per-fragment changes.
     */
    private fun showSlideshowMenu(anchor: View, illust: Illust) {
        val fragment = anchor.findActionReceiverOrNull<androidx.fragment.app.Fragment>() ?: return
        val entityWrapper = fragment.requireEntityWrapper()
        val inWatchLater = entityWrapper.isInWatchLater(illust.id)
        fragment.showV3Menu("V3CardMenu") {
            item(
                anchor.context.getString(R.string.slideshow_play),
                R.drawable.ic_baseline_play_arrow_24
            ) {
                val list = collectVisibleIllusts(anchor)
                val startIndex = list.indexOfFirst { it.id == illust.id }.coerceAtLeast(0)
                if (list.isEmpty()) {
                    SlideshowLauncher.launchFromIllusts(anchor.context, listOf(illust), 0, true)
                } else {
                    SlideshowLauncher.launchFromIllusts(anchor.context, list, startIndex, true)
                }
            }
            item(
                anchor.context.getString(
                    if (inWatchLater) R.string.watch_later_remove else R.string.watch_later_add
                ),
                R.drawable.ic_watch_later_24
            ) {
                if (inWatchLater) {
                    entityWrapper.removeFromWatchLater(anchor.context, illust.id)
                    anchor.findActionReceiverOrNull<WatchLaterActionReceiver>()
                        ?.onWatchLaterRemoved(illust.id)
                    Common.showToast(R.string.watch_later_removed)
                } else {
                    entityWrapper.addToWatchLater(anchor.context, illust)
                    Common.showToast(R.string.watch_later_added)
                }
            }
        }
    }

    private fun collectVisibleIllusts(anchor: View): List<Illust> {
        var p: View? = anchor
        while (p != null && p !is RecyclerView) {
            p = p.parent as? View
        }
        val rv = p as? RecyclerView ?: return emptyList()
        val adapter = rv.adapter as? ListAdapter<*, *> ?: return emptyList()
        return adapter.currentList.filterIsInstance<IllustCardHolder>().map { it.illust }
    }
}
