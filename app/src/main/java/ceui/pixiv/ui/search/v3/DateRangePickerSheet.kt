package ceui.pixiv.ui.search.v3

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import ceui.lisa.R
import ceui.lisa.databinding.CellSearchFilterRowBinding
import ceui.lisa.databinding.DialogSearchFilterDateRangeBinding
import ceui.pixiv.utils.setOnClick
import com.wdullaer.materialdatetimepicker.date.DatePickerDialog
import java.io.Serializable
import java.time.LocalDate
import java.util.Calendar

/**
 * 「指定期间」自定义起止日期 picker —— [DurationPickerSheet] 选了「指定期间」之后弹出。
 *
 * 沿用旧版 DurationPickerSheet 的日期流：选完起始日**自动续弹**结束日 picker（issue #718）。
 * 仅在结束日还没设、又是从起始日入口选的情况下才自动续；改起始日（end 已有值）不自动续。
 *
 * MaterialDatePicker.dateRangePicker 在本项目 QMUI/AppCompat 主题链下走不通，故继续走
 * wdullaer DatePickerDialog（其它地方都用这个）。
 *
 * draft 状态在 [onSaveInstanceState] 持久化，旋屏不丢；按「确定」才提交 Patch。
 */
class DateRangePickerSheet : V3BottomSheetBase() {

    /** picker 提交结果：startDate / endDate 任一为 null = 该方向不限。两端都 null = 取消「指定期间」 */
    data class Patch(
        val startDate: String?,
        val endDate: String?,
    ) : Serializable

    private var draftStart: String? = null
    private var draftEnd: String? = null

    private var _binding: DialogSearchFilterDateRangeBinding? = null
    private val binding get() = _binding!!

    private val requestKey: String
        get() = requireArguments().getString(ARG_REQUEST_KEY).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = savedInstanceState ?: requireArguments()
        @Suppress("DEPRECATION")
        val patch = source.getSerializable(KEY_DRAFT) as? Patch
            ?: @Suppress("DEPRECATION") (requireArguments().getSerializable(ARG_INITIAL) as? Patch)
        draftStart = patch?.startDate
        draftEnd = patch?.endDate
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable(KEY_DRAFT, Patch(draftStart, draftEnd))
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogSearchFilterDateRangeBinding.inflate(inflater, container, false)
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
            parentFragmentManager.setFragmentResult(
                requestKey,
                bundleOf(KEY_PATCH to Patch(draftStart, draftEnd)),
            )
            dismissAllowingStateLoss()
        }

        binding.rowDateStart.rowTitle.setText(R.string.search_filter_v3_date_start)
        binding.rowDateEnd.rowTitle.setText(R.string.search_filter_v3_date_end)
        binding.rowDateStart.rowValue.setTextColor(palette.textAccent)
        binding.rowDateEnd.rowValue.setTextColor(palette.textAccent)
        binding.rowDateStart.root.setOnClick { showDatePicker(isStart = true) }
        binding.rowDateEnd.root.setOnClick { showDatePicker(isStart = false) }
        binding.btnClearDates.setTextColor(palette.textAccent)
        binding.btnClearDates.setOnClick {
            draftStart = null
            draftEnd = null
            renderState()
        }

        renderState()
        // 首次进入若两端都还没值，自动弹起始日 picker，省一次点击
        if (savedInstanceState == null && draftStart == null && draftEnd == null) {
            showDatePicker(isStart = true)
        }
    }

    private fun renderState() {
        if (_binding == null) return
        bindDateRow(binding.rowDateStart, draftStart, R.string.search_filter_v3_date_start, R.string.search_filter_v3_date_unset)
        bindDateRow(binding.rowDateEnd,   draftEnd,   R.string.search_filter_v3_date_end,   R.string.search_filter_v3_date_unset)
        val hasDates = draftStart != null || draftEnd != null
        binding.clearDivider.isVisible = hasDates
        binding.btnClearDates.isVisible = hasDates
        // 「清除日期」行显隐变化会改变段尾归属，重套分段圆角
        applySegmentedCardStyle()
    }

    private fun bindDateRow(row: CellSearchFilterRowBinding, date: String?, titleRes: Int, unsetRes: Int) {
        row.rowTitle.setText(titleRes)
        row.rowValue.text = date ?: getString(unsetRes)
    }

    private fun showDatePicker(isStart: Boolean) {
        val current = if (isStart) draftStart else draftEnd
        val cal = Calendar.getInstance()
        if (!current.isNullOrEmpty()) {
            val parts = current.split("-")
            if (parts.size == 3) cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
        }
        // 记住「end 之前是不是空」—— 决定选完起始日要不要自动续弹结束 picker（issue #718）
        val endWasUnsetBefore = isStart && draftEnd == null
        val listener = DatePickerDialog.OnDateSetListener { _, year, month, day ->
            val picked = LocalDate.of(year, month + 1, day).toString()
            if (isStart) {
                draftStart = picked
                val end = draftEnd
                if (end != null && end < picked) draftEnd = null
            } else {
                draftEnd = picked
                val start = draftStart
                if (start != null && start > picked) draftStart = null
            }
            renderState()
            if (isStart && endWasUnsetBefore) {
                showDatePicker(isStart = false)
            }
        }
        val dpd = DatePickerDialog.newInstance(
            listener,
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH),
        )
        val start = Calendar.getInstance().apply { set(2007, 8, 10) }   // pixiv 上线日
        val now = Calendar.getInstance()
        dpd.setMinDate(start); dpd.setMaxDate(now)
        dpd.setThemeDark(resources.getBoolean(R.bool.is_night_mode))
        dpd.setAccentColor(palette.primary)
        dpd.show(parentFragmentManager, "DateRangePickerDate")
    }

    companion object {
        const val KEY_PATCH = "patch"

        private const val ARG_REQUEST_KEY = "requestKey"
        private const val ARG_INITIAL = "initial"
        private const val KEY_DRAFT = "draft"

        fun newInstance(
            requestKey: String,
            current: SearchFilterV3,
        ): DateRangePickerSheet = DateRangePickerSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_REQUEST_KEY, requestKey)
                putSerializable(ARG_INITIAL, Patch(current.startDate, current.endDate))
            }
        }
    }
}
