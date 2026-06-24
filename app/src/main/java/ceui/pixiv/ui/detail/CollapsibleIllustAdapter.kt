package ceui.pixiv.ui.detail

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.adapters.IllustAdapter
import ceui.lisa.adapters.ViewHolder
import ceui.lisa.databinding.RecyIllustDetailBinding
import ceui.lisa.models.IllustsBean

/**
 * V3-only IllustAdapter that hides all but the first [collapsedCount] pages of a
 * multi-page illust. Call [expand] to reveal the rest, [collapse] to fold back.
 *
 * The "展开剩余 X 张" CTA renders as a bottom scrim + glass pill overlaid on the
 * FIRST page's image itself — no separate adapter row needed.
 *
 * The collapse CTA is intentionally NOT placed on a list item: putting it at the
 * end would force users to scroll through everything they just opened, defeating
 * the purpose of the collapse. The host fragment owns a floating "收起" pill it
 * toggles via [onExpandedChanged].
 */
class CollapsibleIllustAdapter(
    activity: FragmentActivity,
    fragment: Fragment,
    private val illust: IllustsBean,
    maxHeight: Int,
    isForceOriginal: Boolean,
    private val collapsedCount: Int = DEFAULT_COLLAPSED,
    var onComicReaderClick: (() -> Unit)? = null,
    var onExpandedChanged: ((expanded: Boolean) -> Unit)? = null,
) : IllustAdapter(activity, fragment, illust, maxHeight, isForceOriginal) {

    private var expanded = false

    val totalPages: Int get() = illust.page_count
    val hiddenCount: Int get() = (totalPages - collapsedCount).coerceAtLeast(0)
    val isCollapsible: Boolean get() = totalPages > collapsedCount
    val isCollapsed: Boolean get() = !expanded && isCollapsible
    val isExpanded: Boolean get() = expanded && isCollapsible

    override fun getItemCount(): Int {
        val total = super.getItemCount()
        return if (expanded) total else minOf(total, collapsedCount)
    }

    fun expand() {
        if (expanded) return
        // 展开时再扫一遍下载库：覆盖「未展开时下载、随后展开」——此时第 2 张及之后
        // 才首次绑定，扫到本地文件就直读，不回 pixiv 重新下。
        scanLocalDownloads()
        val prev = itemCount
        expanded = true
        val added = itemCount - prev
        if (added > 0) notifyItemRangeInserted(prev, added)
        // No notifyItemChanged(0) here — the caller's fade-out on the scrim
        // would get clobbered. The next natural rebind will hide the overlay.
        onExpandedChanged?.invoke(true)
    }

    fun collapse() {
        if (!expanded) return
        val prev = itemCount
        expanded = false
        val removed = prev - itemCount
        if (removed > 0) notifyItemRangeRemoved(itemCount, removed)
        // Tell pos 0 to fade its expand CTA back IN (alpha 0 → 1), in sync
        // with the host fragment's collapse-pill fade-out.
        notifyItemChanged(0, PAYLOAD_OVERLAY_FADE_IN)
        onExpandedChanged?.invoke(false)
    }

    override fun onBindViewHolder(holder: ViewHolder<RecyIllustDetailBinding>, position: Int) {
        super.onBindViewHolder(holder, position)
        bindExpandOverlay(holder, position, fadeIn = false)
    }

    override fun onBindViewHolder(
        holder: ViewHolder<RecyIllustDetailBinding>,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        when {
            payloads.contains(PAYLOAD_OVERLAY_FADE_IN) ->
                bindExpandOverlay(holder, position, fadeIn = true)
            payloads.contains(PAYLOAD_OVERLAY_ONLY) ->
                bindExpandOverlay(holder, position, fadeIn = false)
            else -> super.onBindViewHolder(holder, position, payloads)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindExpandOverlay(
        holder: ViewHolder<RecyIllustDetailBinding>,
        position: Int,
        fadeIn: Boolean,
    ) {
        val views = overlayOf(holder) ?: return
        val overlay = views.overlay
        val pill = views.expandPill
        val label = views.expandLabel
        val comicPill = views.comicPill

        if (position != 0 || !isCollapsed) {
            overlay.animate().cancel()
            overlay.visibility = View.GONE
            overlay.alpha = 1f
            pill.animate().cancel()
            pill.scaleX = 1f
            pill.scaleY = 1f
            pill.setOnClickListener(null)
            pill.setOnTouchListener(null)
            comicPill.visibility = View.GONE
            comicPill.setOnClickListener(null)
            return
        }

        overlay.animate().cancel()
        overlay.visibility = View.VISIBLE
        if (fadeIn) {
            overlay.alpha = 0f
            overlay.animate().alpha(1f).setDuration(FADE_MS).start()
        } else {
            overlay.alpha = 1f
        }
        label.text = holder.itemView.context.getString(
            R.string.v3_expand_all_pages_title, hiddenCount
        )

        // Show comic reader pill for manga type
        val isManga = "manga" == illust.type
        comicPill.visibility = if (isManga && onComicReaderClick != null) View.VISIBLE else View.GONE
        if (isManga) {
            applyPillTouchFeedback(comicPill)
            comicPill.setOnClickListener { onComicReaderClick?.invoke() }
        }

        // Press-down feedback — scale on ACTION_DOWN, restore on release/cancel.
        // Return false so the click still fires normally via setOnClickListener.
        applyPillTouchFeedback(pill)
        pill.setOnClickListener {
            // Kick off the data change FIRST so onExpandedChanged(true) fires
            // before the fade — the host pill fades IN concurrently with this
            // scrim fading OUT, instead of after.
            expand()
            overlay.animate()
                .alpha(0f)
                .setDuration(FADE_MS)
                .withEndAction {
                    overlay.visibility = View.GONE
                    overlay.alpha = 1f
                }
                .start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun applyPillTouchFeedback(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(120)
                        .setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160)
                        .setInterpolator(DecelerateInterpolator()).start()
            }
            false
        }
    }

    private class OverlayViews(
        val overlay: FrameLayout,
        val expandPill: View,
        val expandLabel: TextView,
        val comicPill: View,
    )

    private fun overlayOf(holder: ViewHolder<RecyIllustDetailBinding>): OverlayViews? {
        val root = holder.itemView
        (root.getTag(TAG_OVERLAY) as? OverlayViews)?.let { return it }
        val overlay = root.findViewById<FrameLayout>(R.id.expand_overlay) ?: return null
        val pill = root.findViewById<View>(R.id.expand_pill) ?: return null
        val label = root.findViewById<TextView>(R.id.expand_label) ?: return null
        val comicPill = root.findViewById<View>(R.id.comic_reader_pill) ?: return null
        val views = OverlayViews(overlay, pill, label, comicPill)
        root.setTag(TAG_OVERLAY, views)
        return views
    }

    override fun onViewAttachedToWindow(holder: ViewHolder<RecyIllustDetailBinding>) {
        super.onViewAttachedToWindow(holder)
        val lp = holder.itemView.layoutParams
        if (lp is StaggeredGridLayoutManager.LayoutParams && !lp.isFullSpan) {
            lp.isFullSpan = true
            holder.itemView.layoutParams = lp
        }
    }

    companion object {
        /** How many pages to show before collapsing. */
        const val DEFAULT_COLLAPSED = 1

        /** 1P and 2P are always shown in full; 3P and up get collapsed. */
        fun shouldCollapse(pageCount: Int, collapsedCount: Int = DEFAULT_COLLAPSED): Boolean {
            return pageCount > 2
        }

        private val PAYLOAD_OVERLAY_ONLY = Any()
        private val PAYLOAD_OVERLAY_FADE_IN = Any()
        private val TAG_OVERLAY = R.id.expand_overlay
        private const val FADE_MS = 220L
    }
}
