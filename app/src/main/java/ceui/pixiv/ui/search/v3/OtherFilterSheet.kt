package ceui.pixiv.ui.search.v3

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.CellSearchFilterCheckRowBinding
import ceui.lisa.databinding.CellSearchFilterSwitchRowBinding
import ceui.lisa.databinding.DialogSearchFilterOtherBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.Local
import ceui.pixiv.ui.search.SpamNovelFilter
import ceui.pixiv.utils.setOnClick
import java.io.Serializable

/**
 * 「其他条件」子 sheet —— 收纳次要维度：AI 三选一（全部/屏蔽AI/仅看AI）+ 小说专属
 * （仅限原创 / 仅限单词置换）+ R-18 限制三选一。
 *
 * 小说专属两个 switch 仅在 isNovel = true 时显示；illust 模式整张卡片隐藏，结果回传时
 * 也固定 false。AI「全部 / 屏蔽AI」两档提交时把 [Shaft.sSettings.isDeleteAIIllust] 落成
 * false/true——与 [ceui.lisa.fragments.FragmentFilter] 历史行为对齐，避免设置项分裂；「仅看AI」
 * 是单次搜索的临时维度，提交时**不碰任何设置**（issue #909：只影响搜索、不持久化）。
 *
 * draft 状态在 [onSaveInstanceState] 持久化，旋屏不丢；结果走 FragmentResult API。
 */
class OtherFilterSheet : V3BottomSheetBase() {

    private var draftAiMode: AiMode = AiMode.All
    private var draftR18: R18Mode = R18Mode.All
    private var draftOriginalOnly: Boolean = false
    private var draftReplaceableOnly: Boolean = false
    /** illust-only;null = 「不限」。父 sheet 通过 args 注入初值 + 候选列表。 */
    private var draftTool: String? = null

    private val isNovel: Boolean
        get() = requireArguments().getBoolean(ARG_IS_NOVEL, false)

    /**
     * 实时读 parent sheet 持有的 SearchViewModel.searchOptions —— 不再用 args snapshot,
     * 这样即便用户进 sheet 时 /v1/search/options 还没拉到,options 一到位下一次点击就生效。
     * Cast 不上时回退空 list（自身被遗弃/单独显示时 fail-safe）。
     */
    private fun currentToolOptions(): List<String> =
        (parentFragment as? SearchFilterV3BottomSheet)
            ?.searchViewModel?.searchOptions?.value?.illust?.tool?.options.orEmpty()

    data class Patch(
        val aiMode: AiMode,
        val r18Mode: R18Mode,
        val isOriginalOnly: Boolean,
        val isReplaceableOnly: Boolean,
        val tool: String?,
    ) : Serializable

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
        draftAiMode = patch?.aiMode ?: AiMode.All
        draftR18 = patch?.r18Mode ?: R18Mode.All
        draftOriginalOnly = patch?.isOriginalOnly ?: false
        draftReplaceableOnly = patch?.isReplaceableOnly ?: false
        draftTool = patch?.tool
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable(KEY_DRAFT,
            Patch(draftAiMode, draftR18, draftOriginalOnly, draftReplaceableOnly, draftTool))
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
            // 只有「全部 / 屏蔽 AI」与全局 isDeleteAIIllust 联动落盘（屏蔽 AI 本就是全局设置，
            // 与 FragmentFilter 历史行为一致）。「仅看 AI」是搜索单次的临时维度——绝不碰全局设置：
            // 否则既会被持久化，又会顺带改掉首页等其它列表的 AI 屏蔽。issue #909 要求只影响搜索
            // 结果且不持久化，所以这里整段对 OnlyAi 跳过。
            if (draftAiMode != AiMode.OnlyAi) {
                val globalExclude = draftAiMode == AiMode.ExcludeAi
                if (Shaft.sSettings.isDeleteAIIllust != globalExclude) {
                    Shaft.sSettings.isDeleteAIIllust = globalExclude
                    Local.setSettings(Shaft.sSettings)
                }
            }
            // 小说专属 switch：illust 模式下卡片整体隐藏，强制 false 防止状态串味儿
            val originalOnly = if (isNovel) draftOriginalOnly else false
            val replaceableOnly = if (isNovel) draftReplaceableOnly else false
            // 制图工具同理：novel 模式整张卡片隐藏，强制清 null
            val tool = if (isNovel) null else draftTool
            parentFragmentManager.setFragmentResult(
                requestKey,
                bundleOf(KEY_PATCH to Patch(draftAiMode, draftR18, originalOnly, replaceableOnly, tool)),
            )
            dismissAllowingStateLoss()
        }

        // AI section —— 三选一：全部 / 屏蔽 AI / 仅看 AI
        bindAiRow(binding.rowAiAll,     AiMode.All,       R.string.search_filter_v3_ai_all)
        bindAiRow(binding.rowAiExclude, AiMode.ExcludeAi, R.string.search_filter_v3_ai_exclude)
        bindAiRow(binding.rowAiOnly,    AiMode.OnlyAi,    R.string.search_filter_v3_ai_only)
        renderAiMarks()

        // 制图工具（illust/manga 专属）—— novel 模式整张卡片隐藏
        binding.illustToolSpace.isVisible = !isNovel
        binding.illustToolCard.isVisible = !isNovel
        if (!isNovel) {
            binding.rowTool.rowValue.setTextColor(palette.textAccent)
            renderToolRow()
            binding.rowTool.root.setOnClick { showToolPicker() }
            childFragmentManager.setFragmentResultListener(REQUEST_TOOL_PICKER, this) { _, bundle ->
                val idx = bundle.getInt(SimplePickerSheet.KEY_IDX)
                // idx 0 = "不限"，1.. = currentToolOptions()[idx-1]
                // 读 picker 展示时的同一份 options(此刻 options 必非空,picker 是从 currentToolOptions
                // build 出来的);用 currentToolOptions() 比缓存 args 更不容易过期
                val opts = currentToolOptions()
                draftTool = if (idx == 0) null else opts.getOrNull(idx - 1)
                renderToolRow()
            }
        }

        // 小说专属：仅限原创 / 仅限单词置换
        binding.novelSectionSpace.isVisible = isNovel
        binding.novelSectionCard.isVisible = isNovel
        if (isNovel) {
            bindNovelSwitch(
                binding.rowOriginalOnly,
                R.string.search_filter_v3_row_original_only,
                draftOriginalOnly,
            ) { draftOriginalOnly = it }
            bindNovelSwitch(
                binding.rowReplaceableOnly,
                R.string.search_filter_v3_row_replaceable_only,
                draftReplaceableOnly,
            ) { draftReplaceableOnly = it }
        }

        // isha: 小说垃圾过滤（仅 novel 可见；全局 Settings，立即落盘）
        binding.spamSectionSpace.isVisible = isNovel
        binding.spamSectionCard.isVisible = isNovel
        if (isNovel) {
            bindSpamSection()
        }

        // R-18 三选一
        bindR18Row(binding.rowR18All,  R18Mode.All,      R.string.search_filter_v3_r18_all)
        bindR18Row(binding.rowR18Safe, R18Mode.SafeOnly, R.string.search_filter_v3_r18_safe)
        bindR18Row(binding.rowR18Only, R18Mode.R18Only,  R.string.search_filter_v3_r18_only)
        renderR18Marks()
    }

    private fun bindSpamSection() {
        val s = Shaft.sSettings
        val row = binding.rowSpamEnable
        row.switchTitle.setText(R.string.search_filter_v3_spam_enable)
        row.switchSubtitle.setText(R.string.search_filter_v3_spam_enable_desc)
        row.switchSubtitle.isVisible = true
        row.switchToggle.thumbTintList = ColorStateList.valueOf(palette.primary)
        row.switchToggle.isChecked = s.isNovelSpamFilterEnabled
        row.switchToggle.setOnCheckedChangeListener { _, checked ->
            s.isNovelSpamFilterEnabled = checked
            Local.setSettings(s)
            renderSpamEditRow()
        }
        binding.rowSpamEdit.rowValue.setTextColor(palette.textAccent)
        renderSpamEditRow()
        binding.rowSpamEdit.root.setOnClick { showSpamKeywordsEditor() }
    }

    private fun renderSpamEditRow() {
        binding.rowSpamEdit.rowTitle.setText(R.string.search_filter_v3_spam_edit)
        val n = SpamNovelFilter.extraContentCount() + SpamNovelFilter.extraAuthorCount()
        binding.rowSpamEdit.rowValue.text =
            if (n == 0) getString(R.string.search_filter_v3_spam_edit_hint)
            else getString(R.string.search_filter_v3_spam_count, n)
        binding.rowSpamEdit.root.isEnabled = Shaft.sSettings.isNovelSpamFilterEnabled
        binding.rowSpamEdit.root.alpha =
            if (Shaft.sSettings.isNovelSpamFilterEnabled) 1f else 0.45f
    }

    /** 弹窗编辑额外内容/作者屏蔽词（一行一个），立即写入 Settings。 */
    private fun showSpamKeywordsEditor() {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val hint = TextView(ctx).apply {
            text = getString(R.string.search_filter_v3_spam_edit_hint)
            textSize = 12f
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        val contentLabel = TextView(ctx).apply {
            text = getString(R.string.search_filter_v3_spam_content_label)
            textSize = 13f
            setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
        }
        val contentEdit = EditText(ctx).apply {
            minLines = 5
            maxLines = 10
            setText(Shaft.sSettings.novelSpamContentExtra)
            setHint("teach.link\n传送门\n...")
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        val authorLabel = TextView(ctx).apply {
            text = getString(R.string.search_filter_v3_spam_author_label)
            textSize = 13f
            setPadding(0, (12 * density).toInt(), 0, (4 * density).toInt())
        }
        val authorEdit = EditText(ctx).apply {
            minLines = 4
            maxLines = 8
            setText(Shaft.sSettings.novelSpamAuthorExtra)
            setHint("dxtyjmg\ndrthbv\n...")
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        container.addView(hint)
        container.addView(contentLabel)
        container.addView(contentEdit)
        container.addView(authorLabel)
        container.addView(authorEdit)

        AlertDialog.Builder(ctx)
            .setTitle(R.string.search_filter_v3_spam_edit)
            .setView(container)
            .setNegativeButton(R.string.string_cancel, null)
            .setPositiveButton(R.string.search_filter_v3_confirm) { _, _ ->
                Shaft.sSettings.novelSpamContentExtra = contentEdit.text?.toString().orEmpty()
                Shaft.sSettings.novelSpamAuthorExtra = authorEdit.text?.toString().orEmpty()
                Local.setSettings(Shaft.sSettings)
                renderSpamEditRow()
                Common.showToast(getString(R.string.search_filter_v3_spam_saved))
            }
            .show()
    }

    private fun renderToolRow() {
        binding.rowTool.rowTitle.setText(R.string.search_filter_v3_row_tool)
        binding.rowTool.rowValue.text =
            draftTool ?: getString(R.string.search_filter_v3_tool_all_summary)
    }

    private fun showToolPicker() {
        val opts = currentToolOptions()
        if (opts.isEmpty()) {
            // /v1/search/options 还没回 —— 重启一次拉取 + toast 让用户稍后再试,
            // 不再像旧版那样一声不吭地吃掉点击
            (parentFragment as? SearchFilterV3BottomSheet)?.ensureSearchOptionsLoaded()
            Common.showToast(getString(R.string.search_filter_v3_tool_loading))
            return
        }
        val labels = listOf(getString(R.string.search_filter_v3_tool_all_summary)) + opts
        val selected = draftTool?.let {
            val idx = opts.indexOf(it)
            if (idx < 0) 0 else idx + 1
        } ?: 0
        SimplePickerSheet.newInstance(
            REQUEST_TOOL_PICKER,
            getString(R.string.search_filter_v3_row_tool),
            labels,
            selected,
        ).show(childFragmentManager, REQUEST_TOOL_PICKER)
    }

    private fun bindNovelSwitch(
        row: CellSearchFilterSwitchRowBinding,
        titleRes: Int,
        initial: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        row.switchTitle.setText(titleRes)
        // 这两行 iOS 没有副标题，隐藏掉 cell_search_filter_switch_row 自带的 subtitle 槽位
        row.switchSubtitle.isVisible = false
        row.switchToggle.thumbTintList = ColorStateList.valueOf(palette.primary)
        row.switchToggle.isChecked = initial
        row.switchToggle.setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    private fun bindAiRow(row: CellSearchFilterCheckRowBinding, mode: AiMode, labelRes: Int) {
        row.checkLabel.setText(labelRes)
        row.checkMark.setTextColor(palette.textAccent)
        row.root.setOnClick {
            draftAiMode = mode
            renderAiMarks()
        }
    }

    private fun renderAiMarks() {
        binding.rowAiAll.checkMark.isInvisible     = draftAiMode != AiMode.All
        binding.rowAiExclude.checkMark.isInvisible = draftAiMode != AiMode.ExcludeAi
        binding.rowAiOnly.checkMark.isInvisible    = draftAiMode != AiMode.OnlyAi
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
        private const val ARG_IS_NOVEL = "isNovel"
        private const val KEY_DRAFT = "draft"

        // 内部 picker 的 request key,与父 sheet REQUEST_OTHER 互不重叠
        private const val REQUEST_TOOL_PICKER = "v3_other_tool_picker"

        fun newInstance(
            requestKey: String,
            current: SearchFilterV3,
            isNovel: Boolean,
        ): OtherFilterSheet = OtherFilterSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_REQUEST_KEY, requestKey)
                putBoolean(ARG_IS_NOVEL, isNovel)
                putSerializable(ARG_INITIAL, Patch(
                    current.aiMode,
                    current.r18Mode,
                    current.isOriginalOnly,
                    current.isReplaceableOnly,
                    current.tool,
                ))
            }
        }
    }
}
