package ceui.pixiv.ui.search.v3

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
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
        BottomSheetDialog(requireContext(), theme)

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
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }
}
