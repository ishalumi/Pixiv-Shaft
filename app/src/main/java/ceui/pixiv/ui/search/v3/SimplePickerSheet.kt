package ceui.pixiv.ui.search.v3

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isInvisible
import ceui.lisa.R
import ceui.lisa.databinding.CellSearchFilterCheckRowBinding
import ceui.lisa.databinding.DialogSearchFilterPickerBinding
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick

/**
 * 通用单选 picker —— [SearchFilterV3BottomSheet] 的 row 点击后弹这个。
 * UI 静态 [R.layout.dialog_search_filter_picker]：top bar + scroll + 单 settings card。
 * 唯一动态部分是 card 里 inflate 一组 [CellSearchFilterCheckRowBinding]。
 *
 * 结果通过 FragmentResult API 回传——避免 transient lambda 跨 config change 丢失。
 * 父 fragment 在 onViewCreated 注册 `setFragmentResultListener(requestKey, ...)`，picker 点选后：
 * `parentFragmentManager.setFragmentResult(requestKey, bundleOf(KEY_IDX to idx))` 然后 dismiss。
 */
class SimplePickerSheet : V3BottomSheetBase() {

    private var _binding: DialogSearchFilterPickerBinding? = null
    private val binding get() = _binding!!

    private val requestKey: String
        get() = requireArguments().getString(ARG_REQUEST_KEY).orEmpty()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogSearchFilterPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val args = requireArguments()
        binding.pickerTitle.text = args.getString(ARG_TITLE).orEmpty()
        binding.btnCancel.setTextColor(palette.textAccent)
        binding.btnCancel.setOnClick { dismissAllowingStateLoss() }

        val labels = args.getStringArrayList(ARG_LABELS) ?: arrayListOf()
        val selected = args.getInt(ARG_SELECTED, 0)
        renderItems(labels, selected)
    }

    private fun renderItems(labels: List<String>, selected: Int) {
        binding.pickerCard.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        labels.forEachIndexed { idx, label ->
            if (idx > 0) binding.pickerCard.addView(divider())
            val itemBinding = CellSearchFilterCheckRowBinding.inflate(
                inflater, binding.pickerCard, false,
            )
            itemBinding.checkLabel.text = label
            itemBinding.checkMark.isInvisible = idx != selected
            itemBinding.checkMark.setTextColor(palette.textAccent)
            itemBinding.root.setOnClick {
                parentFragmentManager.setFragmentResult(requestKey, bundleOf(KEY_IDX to idx))
                dismissAllowingStateLoss()
            }
            binding.pickerCard.addView(itemBinding.root)
        }
    }

    private fun divider(): View = View(requireContext()).apply {
        setBackgroundColor(resources.getColor(R.color.v3_border_2, null))
        layoutParams = android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1.ppppx,
        ).apply { marginStart = 18.ppppx }
    }

    companion object {
        /** 结果 Bundle 里选中项索引 key —— Int 类型。 */
        const val KEY_IDX = "idx"

        private const val ARG_REQUEST_KEY = "requestKey"
        private const val ARG_TITLE = "title"
        private const val ARG_LABELS = "labels"
        private const val ARG_SELECTED = "selected"

        fun newInstance(
            requestKey: String,
            title: String,
            labels: List<String>,
            selected: Int,
        ): SimplePickerSheet = SimplePickerSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_REQUEST_KEY, requestKey)
                putString(ARG_TITLE, title)
                putStringArrayList(ARG_LABELS, ArrayList(labels))
                putInt(ARG_SELECTED, selected)
            }
        }
    }
}
