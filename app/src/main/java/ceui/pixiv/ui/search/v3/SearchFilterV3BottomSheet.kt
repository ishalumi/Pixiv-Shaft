package ceui.pixiv.ui.search.v3

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.databinding.CellSearchFilterRowBinding
import ceui.lisa.databinding.DialogSearchFilterV3Binding
import ceui.loxia.Client
import ceui.loxia.ObjectType
import ceui.pixiv.ui.search.SearchViewModel
import ceui.pixiv.ui.search.SortType
import ceui.pixiv.utils.setOnClick
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * V3 Search Filter —— pixiv iOS 8.6.5「搜索条件」的 V3 投影。
 *
 * 视觉：top bar（取消/标题）+ 三段 settings card（A 段 检索范围/排序；
 * B 段 投稿期间/喜欢！数/工具|类型/语种 [+ novel 仅限原创/单词置换]；C 段 其他条件）+
 * 底部全宽 V3 primary pill「搜索」。
 *
 * 双模式（[ARG_LEGACY]）：
 *   - 默认 false：宿主是新版搜索 [ceui.pixiv.ui.search.SearchViewPagerFragment]；
 *     ViewModelStoreOwner = parentFragment.parentFragment。
 *   - true：宿主是 [ceui.lisa.activities.SearchActivity]；ViewModelStoreOwner = activity；
 *     [SearchFilterV3LegacyBridge] 把 SearchViewModel.illustFilter / novelFilter 翻译回老
 *     SearchModel，老 fragment 通过 nowGo 触发刷新。
 *
 * picker 走 FragmentResult API（[SimplePickerSheet] / [DurationPickerSheet] /
 * [OtherFilterSheet]）—— 跨 config change 不丢回调。监听器全部在 [onViewCreated]
 * 一次性注册，每次 view 重建都会重新登记，缓存中的 fragment result 自动派发。
 */
class SearchFilterV3BottomSheet : V3BottomSheetBase() {

    override val maxHeightFraction: Float = 0.92F

    private val isLegacy: Boolean
        get() = arguments?.getBoolean(ARG_LEGACY, false) ?: false

    private val searchViewModel: SearchViewModel by lazy {
        val owner: ViewModelStoreOwner = if (isLegacy) {
            requireActivity()
        } else {
            try { requireParentFragment().requireParentFragment() }
            catch (_: Throwable) { requireParentFragment() }
        }
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return SearchViewModel("") as T
            }
        }
        ViewModelProvider(owner, factory)[SearchViewModel::class.java]
    }

    private val objectType: String
        get() = arguments?.getString(ARG_OBJECT_TYPE) ?: ObjectType.ILLUST

    private val isNovel: Boolean get() = objectType == ObjectType.NOVEL

    private fun currentFilter(): SearchFilterV3 =
        if (isNovel) (searchViewModel.novelFilter.value ?: SearchFilterV3())
        else (searchViewModel.illustFilter.value ?: SearchFilterV3())

    private fun updateFilter(transform: (SearchFilterV3) -> SearchFilterV3) {
        val store = if (isNovel) searchViewModel.novelFilter else searchViewModel.illustFilter
        store.value = transform(currentFilter())
        renderRows()
    }

    private var _binding: DialogSearchFilterV3Binding? = null
    private val binding get() = _binding!!

    // 静态可枚举的 picker 候选；动态的（tool/genre/lang）由 VM.searchOptions 派生。
    private val sortList = listOf(
        SortType.POPULAR_PREVIEW,
        SortType.DATE_DESC,
        SortType.DATE_ASC,
        SortType.POPULAR_DESC,
        SortType.TRENDING_BUILTIN,
    )
    private val bookmarkList = BookmarkBucket.values().toList()
    private val keywordUsersList = KeywordUsersBucket.values().toList()
    private val targetList: List<SearchTarget>
        get() = if (isNovel) SearchTarget.forNovel() else SearchTarget.forIllust()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogSearchFilterV3Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // accent 色（取消按钮 / 行右侧值 / 搜索按钮）—— V3Palette 派生于主题 colorPrimary
        binding.btnCancel.setTextColor(palette.textAccent)
        binding.btnCancel.setOnClick { dismissAllowingStateLoss() }
        binding.btnSearch.background =
            palette.pillPrimary(999f * resources.displayMetrics.density)
        binding.btnSearch.setOnClick {
            triggerRefresh()
            dismissAllowingStateLoss()
        }
        listOf(
            binding.rowTarget,
            binding.rowSort,
            binding.rowDuration,
            binding.rowBookmark,
            binding.rowKeywordBookmark,
            binding.rowToolOrGenre,
            binding.rowLang,
            binding.rowOther,
        ).forEach { it.rowValue.setTextColor(palette.textAccent) }

        // 行点击路由
        binding.rowTarget.root.setOnClick { showTargetPicker() }
        binding.rowSort.root.setOnClick { showSortPicker() }
        binding.rowDuration.root.setOnClick { showDurationPicker() }
        binding.rowBookmark.root.setOnClick { showBookmarkPicker() }
        binding.rowKeywordBookmark.root.setOnClick { showKeywordBookmarkPicker() }
        binding.rowToolOrGenre.root.setOnClick {
            if (isNovel) showGenrePicker() else showToolPicker()
        }
        binding.rowLang.root.setOnClick { showLangPicker() }
        binding.rowOther.root.setOnClick { showOtherSheet() }

        // 语种行仅 novel 展示；illust/manga 不需要语种维度
        binding.dividerLang.isVisible = isNovel
        binding.rowLang.root.isVisible = isNovel

        // novel 专属两个开关行（illust 模式整段保持 GONE）
        if (isNovel) setupNovelSwitches()

        registerPickerListeners(viewLifecycleOwner)
        renderRows()
        ensureSearchOptionsLoaded()
    }

    private fun setupNovelSwitches() {
        binding.dividerOriginalOnly.isVisible = true
        binding.rowOriginalOnly.root.isVisible = true
        binding.dividerReplaceableOnly.isVisible = true
        binding.rowReplaceableOnly.root.isVisible = true
        binding.rowOriginalOnly.inlineSwitchTitle.setText(R.string.search_filter_v3_row_original_only)
        binding.rowReplaceableOnly.inlineSwitchTitle.setText(R.string.search_filter_v3_row_replaceable_only)
        val accentTint = ColorStateList.valueOf(palette.primary)
        binding.rowOriginalOnly.inlineSwitchToggle.thumbTintList = accentTint
        binding.rowReplaceableOnly.inlineSwitchToggle.thumbTintList = accentTint
        // 整行点击切换 switch（与 iOS 行为一致）；switch 自身的 listener 在 renderRows
        // 与 isChecked 同步——避免 set 触发回调的死循环。
        binding.rowOriginalOnly.root.setOnClick {
            binding.rowOriginalOnly.inlineSwitchToggle.toggle()
        }
        binding.rowReplaceableOnly.root.setOnClick {
            binding.rowReplaceableOnly.inlineSwitchToggle.toggle()
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Fragment result listeners —— picker 提交结果走这里。
    // ──────────────────────────────────────────────────────────────────

    private fun registerPickerListeners(lifecycleOwner: LifecycleOwner) {
        val fm = childFragmentManager

        fm.setFragmentResultListener(REQUEST_TARGET, lifecycleOwner) { _, bundle ->
            val idx = bundle.getInt(SimplePickerSheet.KEY_IDX)
            targetList.getOrNull(idx)?.let { tgt -> updateFilter { it.copy(searchTarget = tgt) } }
        }
        fm.setFragmentResultListener(REQUEST_SORT, lifecycleOwner) { _, bundle ->
            val idx = bundle.getInt(SimplePickerSheet.KEY_IDX)
            sortList.getOrNull(idx)?.let { s -> updateFilter { it.copy(sort = s) } }
        }
        fm.setFragmentResultListener(REQUEST_BOOKMARK, lifecycleOwner) { _, bundle ->
            val idx = bundle.getInt(SimplePickerSheet.KEY_IDX)
            bookmarkList.getOrNull(idx)?.let { b -> updateFilter { it.copy(bookmarkBucket = b) } }
        }
        fm.setFragmentResultListener(REQUEST_KEYWORD_BOOKMARK, lifecycleOwner) { _, bundle ->
            val idx = bundle.getInt(SimplePickerSheet.KEY_IDX)
            keywordUsersList.getOrNull(idx)?.let { b ->
                updateFilter { it.copy(keywordUsersBucket = b) }
            }
        }
        fm.setFragmentResultListener(REQUEST_TOOL, lifecycleOwner) { _, bundle ->
            val idx = bundle.getInt(SimplePickerSheet.KEY_IDX)
            val opts = searchViewModel.searchOptions.value?.illust?.tool?.options.orEmpty()
            // idx 0 = "不限"，1.. = opts[idx-1]
            updateFilter { it.copy(tool = if (idx == 0) null else opts.getOrNull(idx - 1)) }
        }
        fm.setFragmentResultListener(REQUEST_GENRE, lifecycleOwner) { _, bundle ->
            val idx = bundle.getInt(SimplePickerSheet.KEY_IDX)
            val opts = searchViewModel.searchOptions.value?.novel?.genre?.options.orEmpty()
            updateFilter { it.copy(genre = if (idx == 0) null else opts.getOrNull(idx - 1)?.id) }
        }
        fm.setFragmentResultListener(REQUEST_LANG, lifecycleOwner) { _, bundle ->
            val idx = bundle.getInt(SimplePickerSheet.KEY_IDX)
            val opts = if (isNovel) searchViewModel.searchOptions.value?.novel?.lang?.options.orEmpty()
                       else searchViewModel.searchOptions.value?.illust?.lang?.options.orEmpty()
            updateFilter { it.copy(lang = if (idx == 0) null else opts.getOrNull(idx - 1)?.code) }
        }
        fm.setFragmentResultListener(REQUEST_DURATION, lifecycleOwner) { _, bundle ->
            @Suppress("DEPRECATION")
            val patch = bundle.getSerializable(DurationPickerSheet.KEY_PATCH)
                    as? DurationPickerSheet.Patch ?: return@setFragmentResultListener
            updateFilter {
                it.copy(
                    duration = patch.duration,
                    startDate = patch.startDate,
                    endDate = patch.endDate,
                )
            }
        }
        fm.setFragmentResultListener(REQUEST_OTHER, lifecycleOwner) { _, bundle ->
            @Suppress("DEPRECATION")
            val patch = bundle.getSerializable(OtherFilterSheet.KEY_PATCH)
                    as? OtherFilterSheet.Patch ?: return@setFragmentResultListener
            updateFilter { it.copy(excludeAi = patch.excludeAi, r18Mode = patch.r18Mode) }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Row 文案绑定 —— 状态变化或拉到 search options 后调一次。
    // ──────────────────────────────────────────────────────────────────

    private fun renderRows() {
        if (_binding == null) return
        val filter = currentFilter()

        bindRow(binding.rowTarget, R.string.search_filter_v3_row_target,
            searchTargetLabel(filter.searchTarget))
        bindRow(binding.rowSort, R.string.search_filter_v3_row_sort, sortLabel(filter.sort))
        bindRow(binding.rowDuration, R.string.search_filter_v3_row_duration, durationSummary(filter))
        bindRow(binding.rowBookmark, R.string.search_filter_v3_row_bookmark, bookmarkLabel(filter.bookmarkBucket))
        bindRow(
            binding.rowKeywordBookmark,
            R.string.search_filter_v3_row_keyword_bookmark,
            keywordBookmarkLabel(filter.keywordUsersBucket),
        )
        if (isNovel) {
            bindRow(binding.rowToolOrGenre, R.string.search_filter_v3_row_genre, genreSummary(filter))
        } else {
            bindRow(binding.rowToolOrGenre, R.string.search_filter_v3_row_tool, toolSummary(filter))
        }
        bindRow(binding.rowLang, R.string.search_filter_v3_row_lang, langSummary(filter))

        bindRow(binding.rowOther, R.string.search_filter_v3_row_other, otherSummary(filter))

        // novel 专属开关 —— 同步 toggle 状态。Switch 的 isChecked 写入会触发 listener，
        // 所以先摘后装：避免 set → listener → updateFilter → renderRows 死循环。
        if (isNovel) {
            binding.rowOriginalOnly.inlineSwitchToggle.setOnCheckedChangeListener(null)
            binding.rowReplaceableOnly.inlineSwitchToggle.setOnCheckedChangeListener(null)
            binding.rowOriginalOnly.inlineSwitchToggle.isChecked = filter.isOriginalOnly
            binding.rowReplaceableOnly.inlineSwitchToggle.isChecked = filter.isReplaceableOnly
            binding.rowOriginalOnly.inlineSwitchToggle.setOnCheckedChangeListener { _, checked ->
                updateFilter { it.copy(isOriginalOnly = checked) }
            }
            binding.rowReplaceableOnly.inlineSwitchToggle.setOnCheckedChangeListener { _, checked ->
                updateFilter { it.copy(isReplaceableOnly = checked) }
            }
        }
    }

    private fun bindRow(row: CellSearchFilterRowBinding, titleRes: Int, value: String) {
        row.rowTitle.setText(titleRes)
        row.rowValue.text = value
    }

    private fun triggerRefresh() {
        val now = System.currentTimeMillis()
        if (isNovel) searchViewModel.triggerSearchNovelEvent(now)
        else searchViewModel.triggerSearchIllustMangaEvent(now)
        // sort 可能从 trending_builtin 之类 radio 没有的项变了——同步 radio 索引（仅新版用）
        val sortIndex = searchViewModel.sortToRadioIndex(currentFilter().sort)
        if (isNovel) searchViewModel.novelSelectedRadioTabIndex.value = sortIndex
        else searchViewModel.illustSelectedRadioTabIndex.value = sortIndex
    }

    // ──────────────────────────────────────────────────────────────────
    // Value summaries
    // ──────────────────────────────────────────────────────────────────

    private fun sortLabel(sort: String): String = getString(when (sort) {
        SortType.POPULAR_PREVIEW   -> R.string.search_filter_v3_sort_popular_preview
        SortType.DATE_DESC         -> R.string.search_filter_v3_sort_date_desc
        SortType.DATE_ASC          -> R.string.search_filter_v3_sort_date_asc
        SortType.POPULAR_DESC      -> R.string.search_filter_v3_sort_popular_desc
        SortType.TRENDING_BUILTIN  -> R.string.search_filter_v3_sort_trending
        else                       -> R.string.search_filter_v3_sort_popular_preview
    })

    private fun searchTargetLabel(target: SearchTarget): String = getString(when (target) {
        SearchTarget.PartialMatchForTags -> R.string.search_filter_v3_target_partial
        SearchTarget.ExactMatchForTags   -> R.string.search_filter_v3_target_exact
        SearchTarget.TitleAndCaption     -> R.string.search_filter_v3_target_title_caption
        SearchTarget.NovelText           -> R.string.search_filter_v3_target_novel_text
        SearchTarget.NovelKeyword        -> R.string.search_filter_v3_target_novel_keyword
    })

    private fun bookmarkLabel(bucket: BookmarkBucket): String =
        if (bucket == BookmarkBucket.None) getString(R.string.search_filter_v3_bookmark_unlimited_summary)
        else getString(R.string.search_filter_v3_bookmark_min, bucket.min)

    private fun keywordBookmarkLabel(bucket: KeywordUsersBucket): String =
        if (bucket == KeywordUsersBucket.None) getString(R.string.search_filter_v3_keyword_bookmark_off_summary)
        else "${bucket.min}users入り"

    private fun durationSummary(filter: SearchFilterV3): String {
        if (filter.startDate != null || filter.endDate != null) {
            return (filter.startDate ?: "—") + " → " + (filter.endDate ?: "—")
        }
        return getString(when (filter.duration) {
            null                    -> R.string.search_filter_v3_duration_all
            SearchDuration.Day      -> R.string.search_filter_v3_duration_day
            SearchDuration.Week     -> R.string.search_filter_v3_duration_week
            SearchDuration.Month    -> R.string.search_filter_v3_duration_month
            SearchDuration.HalfYear -> R.string.search_filter_v3_duration_half_year
            SearchDuration.Year     -> R.string.search_filter_v3_duration_year
        })
    }

    private fun toolSummary(filter: SearchFilterV3): String =
        filter.tool ?: getString(R.string.search_filter_v3_tool_all_summary)

    private fun genreSummary(filter: SearchFilterV3): String {
        val opts = searchViewModel.searchOptions.value?.novel?.genre?.options.orEmpty()
        val match = opts.firstOrNull { it.id == filter.genre }
        return match?.label ?: getString(R.string.search_filter_v3_genre_all_summary)
    }

    private fun langSummary(filter: SearchFilterV3): String {
        if (filter.lang == null) return getString(R.string.search_filter_v3_lang_all_summary)
        val opts = run {
            val resp = searchViewModel.searchOptions.value
            if (isNovel) resp?.novel?.lang?.options.orEmpty()
            else resp?.illust?.lang?.options.orEmpty()
        }
        return opts.firstOrNull { it.code == filter.lang }?.name ?: filter.lang!!
    }

    private fun otherSummary(filter: SearchFilterV3): String {
        val flags = mutableListOf<String>()
        if (filter.excludeAi) flags += getString(R.string.search_filter_v3_other_summary_no_ai)
        when (filter.r18Mode) {
            R18Mode.SafeOnly -> flags += getString(R.string.search_filter_v3_r18_safe)
            R18Mode.R18Only  -> flags += getString(R.string.search_filter_v3_r18_only)
            R18Mode.All -> Unit
        }
        return if (flags.isEmpty()) getString(R.string.search_filter_v3_other_summary_none)
        else flags.joinToString(" · ")
    }

    // ──────────────────────────────────────────────────────────────────
    // Picker show helpers —— 行点击后呼出
    // ──────────────────────────────────────────────────────────────────

    private fun showSimplePicker(
        requestKey: String,
        title: String,
        labels: List<String>,
        selected: Int,
    ) {
        SimplePickerSheet.newInstance(requestKey, title, labels, selected)
            .show(childFragmentManager, requestKey)
    }

    private fun showTargetPicker() {
        showSimplePicker(
            REQUEST_TARGET,
            getString(R.string.search_filter_v3_row_target),
            targetList.map(::searchTargetLabel),
            targetList.indexOf(currentFilter().searchTarget).coerceAtLeast(0),
        )
    }

    private fun showSortPicker() {
        showSimplePicker(
            REQUEST_SORT,
            getString(R.string.search_filter_v3_row_sort),
            sortList.map(::sortLabel),
            sortList.indexOf(currentFilter().sort).coerceAtLeast(0),
        )
    }

    private fun showBookmarkPicker() {
        showSimplePicker(
            REQUEST_BOOKMARK,
            getString(R.string.search_filter_v3_row_bookmark),
            bookmarkList.map(::bookmarkLabel),
            bookmarkList.indexOf(currentFilter().bookmarkBucket).coerceAtLeast(0),
        )
    }

    private fun showKeywordBookmarkPicker() {
        showSimplePicker(
            REQUEST_KEYWORD_BOOKMARK,
            getString(R.string.search_filter_v3_row_keyword_bookmark),
            keywordUsersList.map(::keywordBookmarkLabel),
            keywordUsersList.indexOf(currentFilter().keywordUsersBucket).coerceAtLeast(0),
        )
    }

    private fun showToolPicker() {
        val opts = searchViewModel.searchOptions.value?.illust?.tool?.options.orEmpty()
        if (opts.isEmpty()) { ensureSearchOptionsLoaded(); return }
        val labels = listOf(getString(R.string.search_filter_v3_tool_all_summary)) + opts
        val cur = currentFilter().tool
        val selected = if (cur == null) 0 else opts.indexOf(cur).let { if (it < 0) 0 else it + 1 }
        showSimplePicker(REQUEST_TOOL, getString(R.string.search_filter_v3_row_tool), labels, selected)
    }

    private fun showGenrePicker() {
        val opts = searchViewModel.searchOptions.value?.novel?.genre?.options.orEmpty()
        if (opts.isEmpty()) { ensureSearchOptionsLoaded(); return }
        val labels = listOf(getString(R.string.search_filter_v3_genre_all_summary)) + opts.map { it.label }
        val cur = currentFilter().genre
        val selected = if (cur == null) 0 else opts.indexOfFirst { it.id == cur }.let { if (it < 0) 0 else it + 1 }
        showSimplePicker(REQUEST_GENRE, getString(R.string.search_filter_v3_row_genre), labels, selected)
    }

    private fun showLangPicker() {
        val opts = if (isNovel) searchViewModel.searchOptions.value?.novel?.lang?.options.orEmpty()
                   else searchViewModel.searchOptions.value?.illust?.lang?.options.orEmpty()
        if (opts.isEmpty()) { ensureSearchOptionsLoaded(); return }
        val labels = listOf(getString(R.string.search_filter_v3_lang_all_summary)) + opts.map { it.name }
        val cur = currentFilter().lang
        val selected = if (cur == null) 0 else opts.indexOfFirst { it.code == cur }.let { if (it < 0) 0 else it + 1 }
        showSimplePicker(REQUEST_LANG, getString(R.string.search_filter_v3_row_lang), labels, selected)
    }

    private fun showDurationPicker() {
        DurationPickerSheet.newInstance(REQUEST_DURATION, currentFilter())
            .show(childFragmentManager, REQUEST_DURATION)
    }

    private fun showOtherSheet() {
        OtherFilterSheet.newInstance(REQUEST_OTHER, currentFilter())
            .show(childFragmentManager, REQUEST_OTHER)
    }

    // ──────────────────────────────────────────────────────────────────
    // /v1/search/options 拉取
    // ──────────────────────────────────────────────────────────────────

    private fun ensureSearchOptionsLoaded() {
        if (searchViewModel.searchOptions.value != null) return
        // /v1/search/options 实测响应与 word 无关，但服务端要求该参数非空。
        // 直接用用户的 keyword；空就传一个无害占位。
        val keyword = searchViewModel.tagList.value
            ?.firstOrNull()?.name?.takeIf { !it.isNullOrEmpty() }
            ?: PLACEHOLDER_KEYWORD
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = Client.appApi.searchOptions(word = keyword)
                searchViewModel.searchOptions.value = resp
                renderRows()
            } catch (ex: Exception) {
                Timber.tag(TAG).w(ex, "searchOptions failed")
            }
        }
    }

    companion object {
        private const val TAG = "SearchFilterV3"
        private const val ARG_OBJECT_TYPE = "objectType"
        private const val ARG_LEGACY = "legacy"

        // 拉 /v1/search/options 用的占位关键词。pixiv API 要求 word 非空但实际响应与之无关。
        private const val PLACEHOLDER_KEYWORD = "art"

        // FragmentResult request keys —— 在 onViewCreated 里登记 listener，picker setFragmentResult 命中。
        private const val REQUEST_TARGET   = "v3_filter_target"
        private const val REQUEST_SORT     = "v3_filter_sort"
        private const val REQUEST_BOOKMARK = "v3_filter_bookmark"
        private const val REQUEST_KEYWORD_BOOKMARK = "v3_filter_keyword_bookmark"
        private const val REQUEST_TOOL     = "v3_filter_tool"
        private const val REQUEST_GENRE    = "v3_filter_genre"
        private const val REQUEST_LANG     = "v3_filter_lang"
        private const val REQUEST_DURATION = "v3_filter_duration"
        private const val REQUEST_OTHER    = "v3_filter_other"

        @JvmStatic
        @JvmOverloads
        fun newInstance(objectType: String, legacy: Boolean = false): SearchFilterV3BottomSheet =
            SearchFilterV3BottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_OBJECT_TYPE, objectType)
                    putBoolean(ARG_LEGACY, legacy)
                }
            }
    }
}
