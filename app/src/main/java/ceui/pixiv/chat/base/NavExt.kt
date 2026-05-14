package ceui.pixiv.chat.base

import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import ceui.lisa.R

/**
 * Wires the classic Shaft toolbar (`chat_layout_toolbar.xml`):
 *   - sets the title text on the centered inner TextView
 *   - hides the nav icon when [showBack] is false
 *   - routes the back arrow through the host activity's
 *     [androidx.activity.OnBackPressedDispatcher] when [showBack] is true,
 *     mirroring FragmentSettings' `toolbar.setNavigationOnClickListener`
 *     pattern (but going through the dispatcher so the activity can
 *     intercept back if needed — e.g. closing an open emoji panel before
 *     popping the fragment).
 *
 * The 1v1 chat's subtitle (`tv_subtitle`) and any peer-avatar / overflow
 * affordances are bound separately by the fragment — they depend on
 * per-screen data the helper has no business fetching.
 */
fun Fragment.setupToolbar(title: String, showBack: Boolean = false) {
    val v = view ?: return
    v.findViewById<TextView>(R.id.tv_title)?.text = title
    val toolbar = v.findViewById<Toolbar>(R.id.app_bar_layout) ?: return
    if (showBack) {
        toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    } else {
        toolbar.navigationIcon = null
    }
}
