package ceui.pixiv.chat.base

import android.view.View
import androidx.fragment.app.Fragment
import com.blankj.utilcode.util.BarUtils
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import ceui.lisa.R

/**
 * Trimmed Peanut helper — only [setupToolbar] is needed by chat. The
 * original Peanut version also exposed [`mainNavController`][androidx.navigation.NavController]
 * and `navigateWithAnim`, but those depend on a `R.id.nav_host_fragment`
 * single nav-host and a set of `R.anim.h_slide_*` anim resources that
 * Shaft does not have. Drop them; the chat fragment only uses
 * [setupToolbar] today.
 *
 * `showBack = true` pops the back-stack via the host activity's
 * [androidx.activity.OnBackPressedDispatcher] instead of a NavController.
 */
fun Fragment.setupToolbar(title: String, showBack: Boolean = false) {
    view?.findViewById<View>(R.id.toolbar_top_placeholder)?.apply {
        layoutParams = layoutParams.also { it.height = BarUtils.getStatusBarHeight() }
    }
    view?.findViewById<MaterialToolbar>(R.id.toolbar)?.apply {
        this.title = title
        if (showBack) {
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationIconTint(
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorOnPrimary
                )
            )
            setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        }
    }
}
