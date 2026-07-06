package ceui.pixiv.ui.search.v3

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import ceui.lisa.R
import ceui.lisa.utils.V3Palette
import ceui.pixiv.utils.screenHeight
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlin.math.roundToInt

/**
 * 4 个 V3 sheet（[SearchFilterV3BottomSheet] / [SimplePickerSheet] / [DurationPickerSheet]
 * / [OtherFilterSheet]）共享的 BottomSheet 基类。把重复的 onCreateDialog + onStart
 * （behavior 配置 + 透明背景）+ onViewCreated（systemBars insets）抽出来，子类只关心 layout 和业务。
 *
 * 子类在 [onViewCreated] 调 `super.onViewCreated(...)` 后再做自己的 binding 绑定。
 */
abstract class V3BottomSheetBase : BottomSheetDialogFragment() {

    /** 子类 lazy 来需要 palette 时拿 —— 受 `requireContext()` 限制，仅在 fragment attached 之后调。 */
    protected val palette by lazy { V3Palette.from(requireContext()) }

    /**
     * 默认 sheet 占屏高比例。子类可 override。
     * SimplePickerSheet 列表偏长 → 0.85；其余都行，保持 0.85 默认即可，主 sheet 给 0.92。
     */
    protected open val maxHeightFraction: Float = 0.85F

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        // 用 V3 专用 theme 把 edgeToEdge 打开 —— Material 会顺手 setDecorFitsSystemWindows=false
        // + nav bar 透明，dialog window 就能画到 gesture nav 底下。
        BottomSheetDialog(requireContext(), R.style.ThemeOverlay_V3_BottomSheetDialog)

    override fun onStart() {
        super.onStart()
        val sheet = dialog?.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet
        ) ?: return
        val behavior = BottomSheetBehavior.from(sheet)
        behavior.skipCollapsed = true
        behavior.maxHeight = (screenHeight * maxHeightFraction).roundToInt()
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        // 我们自己的 root 用 bg_v3_sheet_top（顶部圆角）画背景；把 Material 默认的擦掉，
        // 否则会盖掉圆角。
        sheet.background = ColorDrawable(Color.TRANSPARENT)
        // Material 在 edgeToEdge=true 时给 design_bottom_sheet 装了 inset listener，
        // 会把 nav inset pad 到 sheet 容器本身 → 我们的 root MATCH_PARENT 被压缩，root 的
        // V3 bg 就盖不到 nav bar 区域，scrim 透出来形成那条灰带。改成 noop：root 的 bg
        // 撑满到屏幕底，nav bar padding 由 [onViewCreated] 里对 root 的 listener 单独做。
        ViewCompat.setOnApplyWindowInsetsListener(sheet) { _, insets -> insets }
        sheet.setPadding(0, 0, 0, 0)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyThemeCardTint(view)
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    /**
     * 把布局里 `android:tag="v3_card"` 的 settings 卡片底色换成 [V3Palette.settingsCardBg]，
     * 让卡片底色隐约跟随主题色（XML 里的 bg_v3_settings_card 是固定中性底，切主题色不动）。
     * 每张卡片单独 new 一个 drawable，避免共享实例的 bounds/state 冲突。
     */
    private fun applyThemeCardTint(root: View) {
        val d = root.resources.displayMetrics.density
        val radius = 14f * d
        val stroke = (0.5f * d).roundToInt().coerceAtLeast(1)
        forEachTagged(root, V3_CARD_TAG) {
            it.background = palette.settingsCardBg(radius, stroke)
        }
    }

    private fun forEachTagged(v: View, tag: String, action: (View) -> Unit) {
        if (v.tag == tag) action(v)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) forEachTagged(v.getChildAt(i), tag, action)
        }
    }
}

/** 卡片 tag —— 与各 dialog_*.xml 里 settings 卡片的 `android:tag` 对应。 */
private const val V3_CARD_TAG = "v3_card"
