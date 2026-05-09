package ceui.pixiv.ui.search.v3

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import ceui.lisa.R
import ceui.lisa.databinding.CellSearchFilterCheckRowBinding
import ceui.lisa.databinding.CellSearchFilterRowBinding
import ceui.lisa.databinding.DialogSearchFilterDurationBinding
import ceui.pixiv.utils.setOnClick
import com.wdullaer.materialdatetimepicker.date.DatePickerDialog
import java.io.Serializable
import java.time.LocalDate
import java.util.Calendar

/**
 * 「投稿期间」picker —— relative duration（一日/一周/一月/半年/一年）+ 自定义起止日期。
 * 选 relative 清掉自定义日期，选自定义日期清掉 relative，互斥。
 *
 * 比 [SimplePickerSheet] 多个「确定」按钮 —— 日期选完不会立刻 dismiss，要让用户改完起止
 * 两个日期再 commit。draft 状态在 [onSaveInstanceState] 持久化，旋屏不丢。
 *
 * 结果走 FragmentResult API：
 *   parentFragmentManager.setFragmentResult(requestKey, bundle with KEY_PATCH = Patch)
 */
class DurationPickerSheet : V3BottomSheetBase() {

    private var draftDuration: SearchDuration? = null
    private var draftStart: String? = null
    private var draftEnd: String? = null

    /** 仅返回 picker 修改的 3 字段，其余维度不动。 */
    data class Patch(
        val duration: SearchDuration?,
        val startDate: String?,
        val endDate: String?,
    ) : Serializable

    private var _binding: DialogSearchFilterDurationBinding? = null
    private val binding get() = _binding!!

    private val requestKey: String
        get() = requireArguments().getString(ARG_REQUEST_KEY).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // savedInstanceState 优先（旋屏 / 进程死后重建）；首次进来用 ARG_INITIAL（外部传入）。
        val source = savedInstanceState ?: requireArguments()
        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        val patch = source.getSerializable(KEY_DRAFT) as? Patch
            ?: @Suppress("DEPRECATION") (requireArguments().getSerializable(ARG_INITIAL) as? Patch)
        draftDuration = patch?.duration
        draftStart = patch?.startDate
        draftEnd = patch?.endDate
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable(KEY_DRAFT, Patch(draftDuration, draftStart, draftEnd))
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogSearchFilterDurationBinding.inflate(inflater, container, false)
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
                bundleOf(KEY_PATCH to Patch(draftDuration, draftStart, draftEnd)),
            )
            dismissAllowingStateLoss()
        }

        // 6 个 relative 桶 —— 标签固定，select handler 一一绑定
        bindDurationRow(binding.rowDurationAll,      null,                    R.string.search_filter_v3_duration_all)
        bindDurationRow(binding.rowDurationDay,      SearchDuration.Day,      R.string.search_filter_v3_duration_day)
        bindDurationRow(binding.rowDurationWeek,     SearchDuration.Week,     R.string.search_filter_v3_duration_week)
        bindDurationRow(binding.rowDurationMonth,    SearchDuration.Month,    R.string.search_filter_v3_duration_month)
        bindDurationRow(binding.rowDurationHalfYear, SearchDuration.HalfYear, R.string.search_filter_v3_duration_half_year)
        bindDurationRow(binding.rowDurationYear,     SearchDuration.Year,     R.string.search_filter_v3_duration_year)

        // 自定义日期 row —— 这俩 row 复用 cell_search_filter_row（title + value + chevron）
        binding.rowDateStart.rowTitle.setText(R.string.search_filter_v3_date_start)
        binding.rowDateEnd.rowTitle.setText(R.string.search_filter_v3_date_end)
        binding.rowDateStart.rowValue.setTextColor(palette.textAccent)
        binding.rowDateEnd.rowValue.setTextColor(palette.textAccent)
        binding.rowDateStart.root.setOnClick { showDatePicker(true) }
        binding.rowDateEnd.root.setOnClick { showDatePicker(false) }
        binding.btnClearDates.setTextColor(palette.textAccent)
        binding.btnClearDates.setOnClick {
            draftStart = null
            draftEnd = null
            renderState()
        }

        renderState()
    }

    private fun bindDurationRow(row: CellSearchFilterCheckRowBinding, duration: SearchDuration?, labelRes: Int) {
        row.checkLabel.setText(labelRes)
        row.checkMark.setTextColor(palette.textAccent)
        row.root.setOnClick {
            draftDuration = duration
            draftStart = null
            draftEnd = null
            renderState()
        }
    }

    private fun renderState() {
        if (_binding == null) return
        val noCustom = draftStart == null && draftEnd == null
        binding.rowDurationAll.checkMark.isInvisible      = !(draftDuration == null && noCustom)
        binding.rowDurationDay.checkMark.isInvisible      = !(draftDuration == SearchDuration.Day && noCustom)
        binding.rowDurationWeek.checkMark.isInvisible     = !(draftDuration == SearchDuration.Week && noCustom)
        binding.rowDurationMonth.checkMark.isInvisible    = !(draftDuration == SearchDuration.Month && noCustom)
        binding.rowDurationHalfYear.checkMark.isInvisible = !(draftDuration == SearchDuration.HalfYear && noCustom)
        binding.rowDurationYear.checkMark.isInvisible     = !(draftDuration == SearchDuration.Year && noCustom)

        bindDateRow(binding.rowDateStart, draftStart, R.string.search_filter_v3_date_start, R.string.search_filter_v3_date_unset)
        bindDateRow(binding.rowDateEnd,   draftEnd,   R.string.search_filter_v3_date_end,   R.string.search_filter_v3_date_unset)

        val hasDates = !noCustom
        binding.clearDivider.isVisible = hasDates
        binding.btnClearDates.isVisible = hasDates
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
            // 选了具体日期，相对时间互斥清除
            draftDuration = null
            renderState()
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
        dpd.show(parentFragmentManager, "DurationPickerDate")
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
        ): DurationPickerSheet = DurationPickerSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_REQUEST_KEY, requestKey)
                putSerializable(ARG_INITIAL, Patch(current.duration, current.startDate, current.endDate))
            }
        }
    }
}
