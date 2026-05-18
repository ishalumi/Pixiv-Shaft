package ceui.pixiv.ui.search.v3

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import ceui.lisa.databinding.DialogSearchFilterNumberRangeBinding
import ceui.pixiv.utils.setOnClick
import java.io.Serializable

/**
 * V3 通用「指定范围」输入器 —— 正文长度（指定文字数 / 指定单词数）+ 阅读预计用时（指定分钟数）
 * 三处「指定」Premium 选项共用。
 *
 * 状态语义：min/max 都是 nullable Int；任一为 null 表示该方向不限。两端都 null = 该维度未限制。
 * 父 fragment 透过 [Patch] 收回结果，自行决定 null/null 是否等价于「不限」清空。
 *
 * 单位文案 ([ARG_UNIT_LABEL]) 仅做行尾说明，参与不到状态里——单位是哪个维度的 picker 入口
 * 选 「指定」 时已经定下来了。
 */
class NumberRangeInputSheet : V3BottomSheetBase() {

    private var _binding: DialogSearchFilterNumberRangeBinding? = null
    private val binding get() = _binding!!

    /** picker 提交后透传的 4 字段；min/max 任一为 null = 不限。 */
    data class Patch(val min: Int?, val max: Int?) : Serializable

    private val requestKey: String
        get() = requireArguments().getString(ARG_REQUEST_KEY).orEmpty()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogSearchFilterNumberRangeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val args = requireArguments()
        binding.sheetTitle.text = args.getString(ARG_TITLE).orEmpty()
        binding.minUnit.text = args.getString(ARG_UNIT_LABEL).orEmpty()
        binding.maxUnit.text = args.getString(ARG_UNIT_LABEL).orEmpty()

        binding.btnCancel.setTextColor(palette.textAccent)
        binding.btnConfirm.setTextColor(palette.textAccent)

        // 首次进来用 ARG_INITIAL；savedInstanceState 不参与（系统会自己恢复 EditText 文本）
        if (savedInstanceState == null) {
            val initMin = args.getInt(ARG_INITIAL_MIN, -1).takeIf { it >= 0 }
            val initMax = args.getInt(ARG_INITIAL_MAX, -1).takeIf { it >= 0 }
            binding.minInput.setText(initMin?.toString().orEmpty())
            binding.maxInput.setText(initMax?.toString().orEmpty())
        }

        binding.btnCancel.setOnClick { dismissAllowingStateLoss() }
        binding.btnConfirm.setOnClick { commit() }
    }

    private fun commit() {
        val min = binding.minInput.text.toString().trim().toIntOrNull()
        val max = binding.maxInput.text.toString().trim().toIntOrNull()
        // 用户填了 min > max 时静默互换 —— 比直接拒绝友好
        val normalized = if (min != null && max != null && min > max) Patch(max, min) else Patch(min, max)
        parentFragmentManager.setFragmentResult(requestKey, bundleOf(KEY_PATCH to normalized))
        dismissAllowingStateLoss()
    }

    companion object {
        const val KEY_PATCH = "patch"

        private const val ARG_REQUEST_KEY = "requestKey"
        private const val ARG_TITLE = "title"
        private const val ARG_UNIT_LABEL = "unitLabel"
        private const val ARG_INITIAL_MIN = "initialMin"
        private const val ARG_INITIAL_MAX = "initialMax"

        /**
         * @param unitLabel 行尾单位说明（如「字」「词」「分钟」）；仅显示用，不参与提交。
         * @param initialMin / initialMax  起始填入的值；null 留空。
         */
        fun newInstance(
            requestKey: String,
            title: String,
            unitLabel: String,
            initialMin: Int?,
            initialMax: Int?,
        ): NumberRangeInputSheet = NumberRangeInputSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_REQUEST_KEY, requestKey)
                putString(ARG_TITLE, title)
                putString(ARG_UNIT_LABEL, unitLabel)
                // 负值占位代表 null（避免给可空 Int 写 Int extra 时被默认值覆盖）
                putInt(ARG_INITIAL_MIN, initialMin ?: -1)
                putInt(ARG_INITIAL_MAX, initialMax ?: -1)
            }
        }
    }
}
