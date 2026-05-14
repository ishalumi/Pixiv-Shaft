package ceui.pixiv.chat.base.widget

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Base for Material 3 bottom sheets that:
 *  - Inflates a layout via the resource id constructor (mirrors the
 *    `Fragment(R.layout.xxx)` pattern used elsewhere in this project, so the
 *    existing [ceui.pixiv.chat.base.viewBinding] delegate works as-is).
 *  - Pads the content for the IME and the navigation bar (whichever is
 *    larger), so the sheet stays usable under edge-to-edge and when a
 *    soft-keyboard is open. Material already handles the status-bar inset on
 *    the sheet container itself.
 *  - Configures sensible defaults on [BottomSheetBehavior]: skipCollapsed,
 *    draggable, peekHeight, and an optional expanded-by-default state.
 *
 * Predictive back is provided automatically by [BottomSheetDialog] on
 * Android 14+ when the host application opts in via the manifest.
 *
 * ```kotlin
 * class MyBottomSheet : BaseBottomSheetDialogFragment(R.layout.sheet_my) {
 *     private val binding by viewBinding(SheetMyBinding::bind)
 *
 *     override val expandedByDefault = true
 *
 *     override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
 *         super.onViewCreated(view, savedInstanceState)
 *         binding.btnConfirm.setOnClickListener { dismiss() }
 *     }
 * }
 *
 * // Show it:
 * MyBottomSheet().show(parentFragmentManager, "my-sheet")
 * ```
 */
abstract class BaseBottomSheetDialogFragment(
    @LayoutRes private val contentLayoutId: Int,
) : BottomSheetDialogFragment() {

    /** Skip the half-expanded "collapsed" state. Default = true. */
    protected open val skipCollapsed: Boolean = true

    /** Whether the user can drag the sheet to dismiss it. */
    protected open val isDraggable: Boolean = true

    /**
     * Initial peek height in pixels. `null` (default) maps to
     * [BottomSheetBehavior.PEEK_HEIGHT_AUTO], letting Material pick a
     * sensible value (≈9/16 of the screen height).
     */
    protected open val peekHeightPx: Int? = null

    /** Open the sheet in [BottomSheetBehavior.STATE_EXPANDED] right away. */
    protected open val expandedByDefault: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(contentLayoutId, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Edge-to-edge: pad the content for whichever inset is taller —
        // the IME (when keyboard is up) or the navigation bar.
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.updatePadding(bottom = maxOf(ime, nav))
            insets
        }

        (dialog as? BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = this@BaseBottomSheetDialogFragment.skipCollapsed
            isDraggable = this@BaseBottomSheetDialogFragment.isDraggable
            peekHeight = peekHeightPx ?: BottomSheetBehavior.PEEK_HEIGHT_AUTO
            if (expandedByDefault) state = BottomSheetBehavior.STATE_EXPANDED
        }
    }
}
