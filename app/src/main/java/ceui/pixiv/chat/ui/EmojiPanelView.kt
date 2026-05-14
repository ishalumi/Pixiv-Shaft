package ceui.pixiv.chat.ui

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors

/**
 * A grid of tappable emoji characters sized to match the soft keyboard.
 *
 * Set [onEmojiClick] to receive the selected emoji string.
 */
class EmojiPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    var onEmojiClick: ((String) -> Unit)? = null

    init {
        setBackgroundColor(
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainer)
        )
        val rv = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 8)
            adapter = EmojiAdapter(EMOJIS) { emoji -> onEmojiClick?.invoke(emoji) }
            clipToPadding = false
            val pad = dp(8)
            setPadding(pad, pad, pad, pad)
        }
        addView(rv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    // ── Adapter ──────────────────────────────────────────────────────────

    private class EmojiAdapter(
        private val items: List<String>,
        private val onClick: (String) -> Unit,
    ) : RecyclerView.Adapter<EmojiAdapter.VH>() {

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, 44f, resources.displayMetrics
                    ).toInt()
                )
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                setBackgroundResource(
                    TypedValue().apply {
                        context.theme.resolveAttribute(
                            android.R.attr.selectableItemBackground, this, true
                        )
                    }.resourceId
                )
                isClickable = true
                isFocusable = true
            }
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val emoji = items[position]
            holder.tv.text = emoji
            holder.tv.setOnClickListener { onClick(emoji) }
        }

        class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
    }

    companion object {
        val EMOJIS = listOf(
            "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂",
            "🙂", "😊", "😇", "🥰", "😍", "🤩", "😘", "😗",
            "😋", "😛", "😜", "🤪", "😝", "🤑", "🤗", "🤭",
            "🤫", "🤔", "😐", "😑", "😶", "😏", "😒", "🙄",
            "😬", "😮‍💨", "🤥", "😌", "😔", "😪", "🤤", "😴",
            "😷", "🤒", "🤕", "🤢", "🤮", "🥵", "🥶", "🥴",
            "😵", "🤯", "🤠", "🥳", "🥺", "😢", "😭", "😤",
            "😠", "😡", "🤬", "💀", "💩", "🤡", "👹", "👻",
            "👍", "👎", "👏", "🙏", "🤝", "💪", "✌️", "🤞",
            "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍",
            "🔥", "⭐", "🌈", "☀️", "🌙", "🎉", "🎊", "✨",
        )
    }
}
