package ceui.pixiv.chat.base

import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.blankj.utilcode.util.BarUtils
import ceui.lisa.R

/**
 * Wires the custom chat toolbar (see `chat_layout_toolbar.xml`):
 *  - reserves the status-bar inset on `toolbar_top_placeholder`
 *  - sets the title text
 *  - shows / hides the framed circular back button and routes its
 *    click through the host activity's [androidx.activity.OnBackPressedDispatcher]
 *
 * The peer avatar / subtitle / more button are bound separately by the
 * fragment (they depend on data the toolbar helper has no business
 * fetching).
 */
fun Fragment.setupToolbar(title: String, showBack: Boolean = false) {
    view?.findViewById<View>(R.id.toolbar_top_placeholder)?.apply {
        layoutParams = layoutParams.also { it.height = BarUtils.getStatusBarHeight() }
    }
    view?.findViewById<TextView>(R.id.tv_title)?.text = title
    view?.findViewById<ImageButton>(R.id.btn_back)?.apply {
        visibility = if (showBack) View.VISIBLE else View.GONE
        if (showBack) {
            setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        }
    }
}
