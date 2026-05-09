package ceui.pixiv.ui.search.v3

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isInvisible
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.CellSearchFilterCheckRowBinding
import ceui.lisa.databinding.DialogSearchFilterOtherBinding
import ceui.lisa.utils.Local
import ceui.pixiv.utils.setOnClick
import java.io.Serializable

/**
 * 「其他条件」子 sheet —— 收纳次要维度：屏蔽 AI 开关 + R-18 限制三选一。
 *
 * AI switch 提交时同时落盘 [Shaft.sSettings.isDeleteAIIllust]——与
 * [ceui.lisa.fragments.FragmentFilter] 历史行为对齐，避免设置项分裂。
 *
 * draft 状态在 [onSaveInstanceState] 持久化，旋屏不丢；结果走 FragmentResult API。
 */
class OtherFilterSheet : V3BottomSheetBase() {

    private var draftExcludeAi: Boolean = false
    private var draftR18: R18Mode = R18Mode.All

    data class Patch(val excludeAi: Boolean, val r18Mode: R18Mode) : Serializable

    private var _binding: DialogSearchFilterOtherBinding? = null
    private val binding get() = _binding!!

    private val requestKey: String
        get() = requireArguments().getString(ARG_REQUEST_KEY).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = savedInstanceState ?: requireArguments()
        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        val patch = source.getSerializable(KEY_DRAFT) as? Patch
            ?: @Suppress("DEPRECATION") (requireArguments().getSerializable(ARG_INITIAL) as? Patch)
        draftExcludeAi = patch?.excludeAi ?: false
        draftR18 = patch?.r18Mode ?: R18Mode.All
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable(KEY_DRAFT, Patch(draftExcludeAi, draftR18))
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogSearchFilterOtherBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnCancel.setTextColor(palette.textAccent)
        binding.btnConfirm.setTextColor(palette.textAccent)
        binding.btnCancel.setOnClick { dismissAllowingStateLoss() }
        binding.btnConfirm.setOnClick {
            // AI 开关同步落盘到全局设置（与 FragmentFilter 历史行为一致），让其它入口也跟随
            if (Shaft.sSettings.isDeleteAIIllust != draftExcludeAi) {
                Shaft.sSettings.isDeleteAIIllust = draftExcludeAi
                Local.setSettings(Shaft.sSettings)
            }
            parentFragmentManager.setFragmentResult(
                requestKey,
                bundleOf(KEY_PATCH to Patch(draftExcludeAi, draftR18)),
            )
            dismissAllowingStateLoss()
        }

        // AI section
        binding.rowAi.switchTitle.setText(R.string.search_filter_v3_ai_exclude)
        binding.rowAi.switchSubtitle.setText(R.string.search_filter_v3_ai_exclude_desc)
        binding.rowAi.switchToggle.thumbTintList = ColorStateList.valueOf(palette.primary)
        binding.rowAi.switchToggle.isChecked = draftExcludeAi
        binding.rowAi.switchToggle.setOnCheckedChangeListener { _, checked ->
            draftExcludeAi = checked
        }

        // R-18 三选一
        bindR18Row(binding.rowR18All,  R18Mode.All,      R.string.search_filter_v3_r18_all)
        bindR18Row(binding.rowR18Safe, R18Mode.SafeOnly, R.string.search_filter_v3_r18_safe)
        bindR18Row(binding.rowR18Only, R18Mode.R18Only,  R.string.search_filter_v3_r18_only)
        renderR18Marks()
    }

    private fun bindR18Row(row: CellSearchFilterCheckRowBinding, mode: R18Mode, labelRes: Int) {
        row.checkLabel.setText(labelRes)
        row.checkMark.setTextColor(palette.textAccent)
        row.root.setOnClick {
            draftR18 = mode
            renderR18Marks()
        }
    }

    private fun renderR18Marks() {
        binding.rowR18All.checkMark.isInvisible  = draftR18 != R18Mode.All
        binding.rowR18Safe.checkMark.isInvisible = draftR18 != R18Mode.SafeOnly
        binding.rowR18Only.checkMark.isInvisible = draftR18 != R18Mode.R18Only
    }

    companion object {
        /** 结果 Bundle 里 [Patch] 的 key。 */
        const val KEY_PATCH = "patch"

        private const val ARG_REQUEST_KEY = "requestKey"
        private const val ARG_INITIAL = "initial"
        private const val KEY_DRAFT = "draft"

        fun newInstance(
            requestKey: String,
            current: SearchFilterV3,
        ): OtherFilterSheet = OtherFilterSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_REQUEST_KEY, requestKey)
                putSerializable(ARG_INITIAL, Patch(current.excludeAi, current.r18Mode))
            }
        }
    }
}
