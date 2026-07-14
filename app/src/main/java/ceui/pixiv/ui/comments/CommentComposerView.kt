package ceui.pixiv.ui.comments

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.viewpager2.widget.ViewPager2
import ceui.lisa.R
import ceui.loxia.ProgressImageButton

/** Shared visual implementation of the Pixiv text/emoji/stamp comment composer. */
class CommentComposerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val inputBar: LinearLayout
    internal val commentInput: EditText
    internal val sendButton: ProgressImageButton
    internal val emojiToggle: ImageView
    internal val emojiPanel: LinearLayout
    internal val emojiPager: ViewPager2
    internal val tabKaomoji: TextView
    internal val tabStamp: TextView
    internal val panelDismiss: ImageView

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_comment_composer, this, true)
        inputBar = requireViewById(R.id.comment_input_bar)
        commentInput = requireViewById(R.id.comment_input)
        sendButton = requireViewById(R.id.send_button)
        emojiToggle = requireViewById(R.id.emoji_toggle)
        emojiPanel = requireViewById(R.id.emoji_panel_container)
        emojiPager = requireViewById(R.id.emoji_pager)
        tabKaomoji = requireViewById(R.id.tab_kaomoji)
        tabStamp = requireViewById(R.id.tab_stamp)
        panelDismiss = requireViewById(R.id.panel_dismiss)
    }

    internal fun applyPresentation(presentation: CommentComposerPresentation) {
        val overlay = presentation == CommentComposerPresentation.ON_DEMAND_OVERLAY
        isVisible = !overlay
        inputBar.elevation = if (overlay) OVERLAY_ELEVATION_DP * resources.displayMetrics.density else 0f
        inputBar.setBackgroundColor(if (overlay) context.getColor(R.color.v3_bg) else 0)
    }

    private companion object {
        const val OVERLAY_ELEVATION_DP = 12f
    }
}
