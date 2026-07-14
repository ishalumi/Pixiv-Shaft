package ceui.pixiv.chat.base.panel

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.LifecycleOwner

/**
 * Orchestrates smooth transitions between soft keyboard and a custom
 * bottom panel (emoji, sticker, voice, etc.).
 *
 * The coordinator owns the three-state machine ([PanelState]) and the
 * animations. It knows nothing about what the panel contains — only
 * the [PanelHost] contract and the panel [View].
 *
 * ## Usage
 *
 * ```kotlin
 * class MyChatFragment : Fragment(R.layout.fragment_chat) {
 *     private val binding by viewBinding(FragmentChatBinding::bind)
 *     private lateinit var panelCoordinator: BottomPanelCoordinator
 *
 *     override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
 *         super.onViewCreated(view, savedInstanceState)
 *         panelCoordinator = BottomPanelCoordinator(
 *             host = object : PanelHost {
 *                 override val panelRoot get() = binding.root
 *                 override val panelView get() = binding.emojiPanel
 *                 override val panelInputView get() = binding.etInput
 *                 override fun onAnchorContent() { binding.recyclerView.scrollToPosition(0) }
 *                 override fun onPanelStateChanged(state: PanelState) { updateIcon(state) }
 *             },
 *         )
 *         panelCoordinator.attach(this)
 *
 *         binding.btnEmoji.setOnClickListener { panelCoordinator.toggle() }
 *     }
 * }
 * ```
 */
class BottomPanelCoordinator(
    private val host: PanelHost,
    private val fallbackHeightDp: Int = 270,
    private val animDurationMs: Long = 250,
) {

    /** Current state (read-only for consumers). */
    var state: PanelState = PanelState.NONE
        private set

    private var savedKeyboardHeight: Int = 0
    private var navBarHeight: Int = 0
    private var panelAnimator: ValueAnimator? = null
    private var backCallback: OnBackPressedCallback? = null

    // ── Lifecycle ────────────────────────────────────────────────────────

    /**
     * Attach to a Fragment's view lifecycle. Sets up the
     * [WindowInsetsAnimationCompat] callback and back-press handler.
     * Automatically detaches on view destroy.
     */
    fun attach(fragment: Fragment) {
        setupImeInsets(host.panelRoot)
        setupTapToDismiss()
        setupToggleButton()

        val cb = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                hidePanel()
            }
        }
        backCallback = cb
        fragment.requireActivity().onBackPressedDispatcher
            .addCallback(fragment.viewLifecycleOwner, cb)

        fragment.viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                detach()
            }
        })
    }

    private fun detach() {
        cancelPanelAnimation()
        backCallback?.isEnabled = false
        backCallback = null
    }

    // ── Public API ───────────────────────────────────────────────────────

    /** Toggle between PANEL and the current state. */
    fun toggle() {
        when (state) {
            PanelState.NONE -> showPanel()
            PanelState.KEYBOARD -> switchToPanelFromKeyboard()
            PanelState.PANEL -> switchToKeyboard()
        }
    }

    /** Show the panel from NONE state (padding-based animation). */
    fun showPanel() {
        cancelPanelAnimation()
        val target = panelHeight()
        setState(PanelState.PANEL)
        val animator = ValueAnimator.ofInt(navBarHeight, navBarHeight + target).apply {
            duration = animDurationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                host.panelRoot.updatePadding(bottom = it.animatedValue as Int)
                host.onAnchorContent()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (panelAnimator !== animation || state != PanelState.PANEL) return
                    panelAnimator = null
                    host.panelView.layoutParams.height = target
                    host.panelView.isVisible = true
                    host.panelRoot.updatePadding(bottom = navBarHeight)
                }
            })
        }
        panelAnimator = animator
        animator.start()
    }

    /** Hide the panel (swap to padding, animate down). */
    fun hidePanel() {
        cancelPanelAnimation()
        setState(PanelState.NONE)
        val panelH = host.panelView.height
        if (panelH <= 0) {
            host.panelView.isVisible = false
            host.panelView.layoutParams.height = 0
            host.panelRoot.updatePadding(bottom = navBarHeight)
            return
        }
        host.panelView.isVisible = false
        host.panelView.layoutParams.height = 0
        host.panelRoot.updatePadding(bottom = navBarHeight + panelH)
        val animator = ValueAnimator.ofInt(navBarHeight + panelH, navBarHeight).apply {
            duration = animDurationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                host.panelRoot.updatePadding(bottom = it.animatedValue as Int)
                host.onAnchorContent()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (panelAnimator === animation) panelAnimator = null
                }
            })
        }
        panelAnimator = animator
        animator.start()
    }

    /** Seamless KEYBOARD → PANEL: show panel immediately, dismiss keyboard. */
    fun switchToPanelFromKeyboard() {
        cancelPanelAnimation()
        val height = panelHeight()
        val oldPad = host.panelRoot.paddingBottom
        val newPad = maxOf(oldPad - height, navBarHeight)
        host.panelView.layoutParams.height = height
        host.panelView.isVisible = true
        host.panelRoot.updatePadding(bottom = newPad)
        host.panelView.requestLayout()
        setState(PanelState.PANEL)
        hideKeyboard()
    }

    /** Seamless PANEL → KEYBOARD: keep panel during keyboard animation. */
    fun switchToKeyboard() {
        cancelPanelAnimation()
        setState(PanelState.KEYBOARD)
        showKeyboard()
    }

    /** Dismiss whatever is open (keyboard or panel) → NONE. */
    fun dismiss() {
        when (state) {
            PanelState.KEYBOARD -> {
                cancelPanelAnimation()
                hideKeyboard()
                host.panelInputView?.clearFocus()
            }
            PanelState.PANEL -> hidePanel()
            PanelState.NONE -> {}
        }
    }

    // ── IME insets ───────────────────────────────────────────────────────

    private fun setupImeInsets(root: View) {
        val insetTypes = WindowInsetsCompat.Type.ime() or
            WindowInsetsCompat.Type.navigationBars()
        var isImeAnimating = false

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            // 只在键盘不可见时采样导航栏高度:部分宿主(如 HyperOS)键盘弹起时会把 navigationBars
            // inset 也报成键盘高度,若照单全收会污染 navBarHeight——键盘→面板切换时
            // switchToPanelFromKeyboard 用 navBarHeight 当面板落位下限,会把面板顶高一整个键盘的
            // 高度、下方露出列表内容。键盘弹起期间保留上一次(键盘收起时)的正确导航栏高度即可。
            if (!imeVisible) {
                navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            }
            if (!isImeAnimating) {
                val bottom = if (state == PanelState.PANEL) navBarHeight
                    else insets.getInsets(insetTypes).bottom
                v.updatePadding(bottom = bottom)

                // Insets animation is optional: hardware keyboards, disabled system animations,
                // and some OEM IMEs can update visibility without invoking onEnd(). Keep the
                // public state canonical from the normal insets path as well. PANEL is excluded
                // because it deliberately remains authoritative while the IME is being dismissed.
                when {
                    imeVisible && !host.panelView.isVisible && state != PanelState.PANEL ->
                        setState(PanelState.KEYBOARD)
                    !imeVisible && !host.panelView.isVisible && state == PanelState.KEYBOARD ->
                        setState(PanelState.NONE)
                }
            }
            insets
        }

        ViewCompat.setWindowInsetsAnimationCallback(
            root,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                private var imeBottomBefore = 0

                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        isImeAnimating = true
                        imeBottomBefore = ViewCompat.getRootWindowInsets(root)
                            ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                    }
                }

                override fun onStart(
                    animation: WindowInsetsAnimationCompat,
                    bounds: WindowInsetsAnimationCompat.BoundsCompat,
                ): WindowInsetsAnimationCompat.BoundsCompat {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        val imeTarget = bounds.upperBound.bottom
                        if (imeTarget > navBarHeight) {
                            savedKeyboardHeight = imeTarget - navBarHeight
                        }
                        if (imeBottomBefore == 0 && !host.panelView.isVisible) {
                            host.onAnchorContent()
                        }
                    }
                    return bounds
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: List<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat {
                    val imeBottom = insets.getInsets(insetTypes).bottom
                    val bottom = if (host.panelView.isVisible) {
                        maxOf(imeBottom - savedKeyboardHeight, navBarHeight)
                    } else {
                        imeBottom
                    }
                    root.updatePadding(bottom = bottom)
                    host.onAnchorContent()
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        isImeAnimating = false
                        val rootInsets = ViewCompat.getRootWindowInsets(root)
                        val imeNow = rootInsets
                            ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                        // 键盘可见性:优先信任 isVisible(ime) 这个 canonical 标志位——`imeNow >
                        // navBarHeight` 只是尺寸启发式,某些宿主(如 HyperOS)键盘弹起时把 navigationBars
                        // inset 也报成键盘高度,尺寸比较会把开着的键盘误判成收起。OR 上 isVisible 只会让
                        //「键盘确实开着」更可靠地判成 true,不影响收起方向。
                        val imeVisibleFlag =
                            rootInsets?.isVisible(WindowInsetsCompat.Type.ime()) == true
                        val keyboardVisible = imeNow > navBarHeight || imeVisibleFlag

                        if (keyboardVisible && host.panelView.isVisible) {
                            host.panelView.isVisible = false
                            host.panelView.layoutParams.height = 0
                            setState(PanelState.KEYBOARD)
                        } else if (keyboardVisible) {
                            setState(PanelState.KEYBOARD)
                        } else if (!host.panelView.isVisible) {
                            setState(PanelState.NONE)
                        }

                        ViewCompat.requestApplyInsets(root)
                    }
                }
            },
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun setupToggleButton() {
        val btn = host.panelToggleButton ?: return
        btn.setOnClickListener { toggle() }
        // Tap input while panel is open → switch to keyboard
        host.panelInputView?.setOnClickListener {
            if (state == PanelState.PANEL) switchToKeyboard()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTapToDismiss() {
        val contentView = host.panelContentView ?: return
        if (contentView is RecyclerView) {
            // RecyclerView dispatches touches to child items first;
            // OnItemTouchListener intercepts before that.
            contentView.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    if (e.actionMasked == MotionEvent.ACTION_DOWN && state != PanelState.NONE) {
                        dismiss()
                    }
                    return false
                }
            })
        } else {
            contentView.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN && state != PanelState.NONE) {
                    dismiss()
                }
                false
            }
        }
    }

    private fun setState(new: PanelState) {
        if (state == new) return
        state = new
        backCallback?.isEnabled = new == PanelState.PANEL
        host.panelToggleButton?.setImageResource(
            if (new == PanelState.PANEL) host.keyboardToggleIconRes
            else host.panelToggleIconRes
        )
        host.onPanelStateChanged(new)
    }

    /** Cancel without allowing an obsolete animator's completion callback to commit stale UI. */
    private fun cancelPanelAnimation() {
        panelAnimator?.let { animator ->
            animator.removeAllListeners()
            animator.removeAllUpdateListeners()
            animator.cancel()
        }
        panelAnimator = null
    }

    private fun panelHeight(): Int =
        if (savedKeyboardHeight > 0) savedKeyboardHeight else dpToPx(fallbackHeightDp)

    private fun showKeyboard() {
        val input = host.panelInputView ?: return
        input.requestFocus()
        val imm = input.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val input = host.panelInputView ?: return
        val imm = input.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(),
            host.panelRoot.resources.displayMetrics,
        ).toInt()
}
