package ceui.pixiv.chat.base

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.databinding.ChatItemListErrorBinding
import ceui.lisa.databinding.ChatItemListLoadingBinding

/**
 * Standalone adapter managing 0 or 1 footer item for pagination state.
 *
 * Intended to be composed with a [BaseListAdapter] via
 * [ConcatAdapter][androidx.recyclerview.widget.ConcatAdapter]:
 * ```
 * recyclerView.adapter = ConcatAdapter(dataAdapter, footerAdapter)
 * ```
 *
 * Each adapter has its own independent item count, so footer notifications
 * never conflict with the data adapter's DiffUtil dispatches.
 */
class PagingFooterAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var onRetry: (() -> Unit)? = null

    private var state: PagingState = PagingState.Idle

    /** Update the footer based on [PagingState]. */
    fun setPagingState(newState: PagingState) {
        if (newState == state) return
        val wasVisible = isVisible(state)
        val nowVisible = isVisible(newState)
        state = newState
        when {
            !wasVisible && nowVisible -> notifyItemInserted(0)
            wasVisible && !nowVisible -> notifyItemRemoved(0)
            wasVisible && nowVisible  -> notifyItemChanged(0)
        }
    }

    override fun getItemCount(): Int = if (isVisible(state)) 1 else 0

    override fun getItemViewType(position: Int): Int = when (state) {
        is PagingState.LoadingMore -> TYPE_LOADING
        is PagingState.Error -> TYPE_ERROR
        else -> TYPE_LOADING // unreachable, guarded by getItemCount
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_ERROR -> ErrorViewHolder(
                ChatItemListErrorBinding.inflate(inflater, parent, false)
            )
            else -> FooterViewHolder(
                ChatItemListLoadingBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is ErrorViewHolder && state is PagingState.Error) {
            val error = (state as PagingState.Error).error
            holder.binding.tvErrorMessage.text = error.toUserMessage(holder.itemView.context)
            holder.binding.btnRetry.apply {
                visibility = if (error.isRetryable) View.VISIBLE else View.GONE
                setOnClickListener { onRetry?.invoke() }
            }
        }
    }

    private fun isVisible(s: PagingState): Boolean =
        s is PagingState.LoadingMore || s is PagingState.Error

    private class FooterViewHolder(
        binding: ChatItemListLoadingBinding
    ) : RecyclerView.ViewHolder(binding.root)

    private class ErrorViewHolder(
        val binding: ChatItemListErrorBinding
    ) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private const val TYPE_LOADING = 1
        private const val TYPE_ERROR = 2
    }
}
