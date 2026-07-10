package ceui.pixiv.feeds

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.viewbinding.ViewBinding

/**
 * 唯一的 Adapter 实现，页面不需要再写 Adapter 子类。
 *
 * 性能要点：
 * - [ListAdapter]：diff 在后台线程算，主线程只派发最小更新；
 * - [DiffUtil.ItemCallback.getChangePayload] 恒返回非空 → 内容变化复用同一个
 *   ViewHolder 原地重绑，杜绝默认 change 动画对图片列表造成的 crossfade 闪烁；
 * - viewType 就是 renderer 在注册表里的下标，O(1) 双向查找，无 hashCode 碰撞风险；
 * - 触底预取在 onBind 里判断（与 LayoutManager 实现解耦），去重交给
 *   [FeedViewModel.loadMore] 的防重入。
 */
class FeedAdapter(
    renderers: List<FeedRenderer<out FeedItem, out ViewBinding>>,
    private val prefetchDistance: Int = 6,
    private val onNearEnd: (() -> Unit)? = null,
) : ListAdapter<FeedItem, FeedCell<FeedItem, ViewBinding>>(
    FeedDiff(renderers.associateBy { it.itemClass })
) {

    @Suppress("UNCHECKED_CAST")
    private val renderers = renderers as List<FeedRenderer<FeedItem, ViewBinding>>

    private val viewTypeOf: Map<Class<*>, Int> =
        renderers.withIndex().associate { (index, renderer) -> renderer.itemClass to index }

    init {
        require(viewTypeOf.size == renderers.size) {
            "同一种 FeedItem 类型注册了多个 FeedRenderer"
        }
    }

    override fun getItemViewType(position: Int): Int {
        val item = getItem(position)
        return viewTypeOf[item.javaClass]
            ?: error("没有为 ${item.javaClass.name} 注册 FeedRenderer")
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): FeedCell<FeedItem, ViewBinding> {
        val renderer = renderers[viewType]
        val binding = renderer.inflate(LayoutInflater.from(parent.context), parent, false)
        val cell = FeedCell<FeedItem, ViewBinding>(binding)
        renderer.onCreate(cell)
        return cell
    }

    override fun onBindViewHolder(cell: FeedCell<FeedItem, ViewBinding>, position: Int) {
        bindInternal(cell, position, emptyList())
    }

    override fun onBindViewHolder(
        cell: FeedCell<FeedItem, ViewBinding>,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        bindInternal(cell, position, payloads)
    }

    private fun bindInternal(
        cell: FeedCell<FeedItem, ViewBinding>,
        position: Int,
        payloads: List<Any>,
    ) {
        val renderer = renderers[cell.itemViewType]
        cell.attach(getItem(position))
        if (renderer.fullSpan) {
            (cell.itemView.layoutParams as? StaggeredGridLayoutManager.LayoutParams)
                ?.isFullSpan = true
        }
        if (payloads.isEmpty()) {
            renderer.onBind(cell)
        } else {
            renderer.onBind(cell, payloads)
        }
        if (onNearEnd != null && position >= itemCount - prefetchDistance) {
            onNearEnd.invoke()
        }
    }

    override fun onViewRecycled(cell: FeedCell<FeedItem, ViewBinding>) {
        renderers[cell.itemViewType].onRecycled(cell)
    }

    /** 给 GridLayoutManager 的 SpanSizeLookup 用。 */
    fun spanSizeAt(position: Int, spanCount: Int): Int {
        if (position !in 0 until itemCount) return 1
        return renderers[getItemViewType(position)].spanSize(spanCount)
    }
}

/** 兜底 payload：告诉 RecyclerView「复用原 holder 全量重绑」，而不是播放 change 动画。 */
private val FULL_REBIND = Any()

private class FeedDiff(
    private val renderersByClass: Map<Class<*>, FeedRenderer<out FeedItem, out ViewBinding>>,
) : DiffUtil.ItemCallback<FeedItem>() {

    override fun areItemsTheSame(oldItem: FeedItem, newItem: FeedItem): Boolean {
        return oldItem.javaClass == newItem.javaClass && oldItem.feedKey == newItem.feedKey
    }

    override fun areContentsTheSame(oldItem: FeedItem, newItem: FeedItem): Boolean {
        return oldItem == newItem
    }

    override fun getChangePayload(oldItem: FeedItem, newItem: FeedItem): Any {
        @Suppress("UNCHECKED_CAST")
        val renderer = renderersByClass[oldItem.javaClass] as? FeedRenderer<FeedItem, ViewBinding>
        return renderer?.changePayload(oldItem, newItem) ?: FULL_REBIND
    }
}
