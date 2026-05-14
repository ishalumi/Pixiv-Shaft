package ceui.pixiv.chat.base.panel

import android.view.View
import android.widget.ImageView

/**
 * View contract for [BottomPanelCoordinator].
 *
 * Describes the Views the coordinator needs to manage keyboard ↔ panel
 * transitions. Any Fragment can implement this or construct an anonymous
 * instance from its binding.
 */
interface PanelHost {

    /** Root view that receives window insets and padding adjustments. */
    val panelRoot: View

    /** The bottom panel view (emoji, sticker, etc.) below the input bar. */
    val panelView: View

    /** Input view to focus when switching to keyboard. Null for voice-only panels. */
    val panelInputView: View?

    /**
     * The content area above the input bar (e.g. a RecyclerView or its
     * container). Tapping this view while keyboard or panel is open will
     * dismiss both. Null to disable tap-to-dismiss.
     */
    val panelContentView: View? get() = null

    /**
     * Toggle button that switches between keyboard and panel.
     * If provided, the coordinator automatically:
     * - Calls [BottomPanelCoordinator.toggle] on click
     * - Swaps between [panelToggleIconRes] and [keyboardToggleIconRes]
     *   on state changes
     * - Handles input-view click → switchToKeyboard when panel is open
     *
     * Null to handle toggle wiring manually.
     */
    val panelToggleButton: ImageView? get() = null

    /** Icon resource shown when tapping the button would open the panel. */
    val panelToggleIconRes: Int get() = 0

    /** Icon resource shown when tapping the button would open the keyboard. */
    val keyboardToggleIconRes: Int get() = 0

    /**
     * Called each frame during transitions so the host can keep content
     * anchored (e.g. `recyclerView.scrollToPosition(0)` for reverse layouts).
     */
    fun onAnchorContent() {}

    /** Called when the panel state changes so the host can update UI (e.g. toggle button icons). */
    fun onPanelStateChanged(state: PanelState) {}
}
