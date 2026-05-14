package ceui.pixiv.chat.base

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * Generic [ListAdapter] for data items.
 *
 * Pagination footer is managed by a separate [PagingFooterAdapter] composed
 * via [ConcatAdapter][androidx.recyclerview.widget.ConcatAdapter] — this
 * adapter only deals with data items, eliminating the notification race that
 * existed when both data and footer shared the same adapter.
 *
 * ## Subclass contract
 *
 * Implement [onCreateDataViewHolder] and [onBindDataViewHolder].
 *
 * ## Multi-viewType
 *
 * For lists with multiple item layouts, override [getDataItemViewType] and
 * dispatch on `viewType` in [onCreateDataViewHolder]:
 * ```kotlin
 * override fun getDataItemViewType(item: Message, position: Int) = when (item) {
 *     is TextMessage  -> TYPE_TEXT
 *     is ImageMessage -> TYPE_IMAGE
 * }
 *
 * override fun onCreateDataViewHolder(parent: ViewGroup, viewType: Int) = when (viewType) {
 *     TYPE_TEXT  -> TextVH(inflater.inflate(...))
 *     TYPE_IMAGE -> ImageVH(inflater.inflate(...))
 *     else -> error("Unknown viewType: $viewType")
 * }
 * ```
 *
 * @param T  the domain model type
 * @param VH the ViewHolder type for data items
 */
abstract class BaseListAdapter<T : Any, VH : RecyclerView.ViewHolder>(
    diffCallback: DiffUtil.ItemCallback<T>
) : ListAdapter<T, VH>(diffCallback) {

    companion object {
        /**
         * Build a [DiffUtil.ItemCallback] using a key selector.
         * Items with the same key are considered the same; content equality uses `equals`.
         */
        fun <T : Any, K> diffCallback(
            keySelector: (T) -> K
        ): DiffUtil.ItemCallback<T> = diffCallback(
            areItemsSame = { a, b -> keySelector(a) == keySelector(b) }
        )

        /**
         * Build a [DiffUtil.ItemCallback] by delegating
         * identity / content checks to the caller for the data type.
         */
        fun <T : Any> diffCallback(
            areItemsSame: (old: T, new: T) -> Boolean,
            areContentsSame: (old: T, new: T) -> Boolean = { a, b -> a == b }
        ): DiffUtil.ItemCallback<T> = object : DiffUtil.ItemCallback<T>() {
            override fun areItemsTheSame(oldItem: T, newItem: T): Boolean =
                areItemsSame(oldItem, newItem)

            override fun areContentsTheSame(oldItem: T, newItem: T): Boolean =
                areContentsSame(oldItem, newItem)
        }
    }

    // ── ViewType support ────────────────────────────────────────────────────

    /**
     * Return the view type for the data [item] at [position].
     * Override for multi-viewType lists. Default: `0` (single type).
     */
    open fun getDataItemViewType(item: T, position: Int): Int = 0

    // ── ListAdapter bridge ──────────────────────────────────────────────────

    final override fun getItemViewType(position: Int): Int =
        getDataItemViewType(getItem(position), position)

    final override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        onCreateDataViewHolder(parent, viewType)

    final override fun onBindViewHolder(holder: VH, position: Int) =
        onBindDataViewHolder(holder, getItem(position))

    // ── Abstract hooks for subclasses ────────────────────────────────────────

    /**
     * Create a ViewHolder for the given [viewType].
     * Single-viewType adapters can ignore the parameter.
     */
    abstract fun onCreateDataViewHolder(parent: ViewGroup, viewType: Int): VH

    abstract fun onBindDataViewHolder(holder: VH, item: T)
}
