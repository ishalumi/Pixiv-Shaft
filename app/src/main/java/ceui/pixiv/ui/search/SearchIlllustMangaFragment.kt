package ceui.pixiv.ui.search

import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.ObjectType
import ceui.loxia.RefreshHint
import ceui.loxia.observeEvent
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.ui.search.v3.SearchFilterV3
import ceui.pixiv.ui.search.v3.SearchFilterV3BottomSheet
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.DialogViewModel


class SearchIlllustMangaFragment : PixivFragment(R.layout.fragment_pixiv_list) {

    private val searchViewModel by viewModels<SearchViewModel>(ownerProducer = { requireParentFragment() })
    private val dialogViewModel by activityViewModels<DialogViewModel>()
    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val viewModel by pixivListViewModel({ Pair(searchViewModel, dialogViewModel) }) { (vm, dialogVM) ->
        SearchIllustMangaDataSource {
            val count = dialogVM.chosenUsersYoriCount.value
            vm.buildSearchConfig(count, ObjectType.ILLUST)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel)
        binding.radioTab.setTabs(listOf(
            "热度预览",
            "从新到旧",
            "从旧到新",
            "热度排序",
        ))
        binding.radioTab.setItemCickListener { index ->
            searchViewModel.illustSelectedRadioTabIndex.value = index
            // radio_tab → V3 filter sort 单向同步（保留 radio_tab 作为快捷切换）
            val newSort = searchViewModel.radioIndexToSort(index)
            val cur = searchViewModel.illustFilter.value ?: SearchFilterV3()
            if (cur.sort != newSort) {
                searchViewModel.illustFilter.value = cur.copy(sort = newSort)
            }
            val now = System.currentTimeMillis()
            searchViewModel.triggerSearchIllustMangaEvent(now)
        }
        searchViewModel.searchIllustMangaEvent.observeEvent(viewLifecycleOwner) {
            viewModel.refresh(RefreshHint.InitialLoad)
        }
        searchViewModel.illustSelectedRadioTabIndex.observe(viewLifecycleOwner) { index ->
            binding.radioTab.selectTab(index)
        }
        // V3 filter 改了非 radio 维度也更新右上「筛选」入口的徽标。
        searchViewModel.illustFilter.observe(viewLifecycleOwner) { filter ->
            val count = filter?.activeCount(isNovel = false) ?: 0
            binding.usersYori.text = if (count > 0)
                getString(R.string.search_filter_v3_entry_with_count, count)
            else getString(R.string.search_filter_v3_entry)
            // 保持 radio_tab 与 filter.sort 同步（V3 sheet 改了 sort 也要回写）
            val sortIdx = searchViewModel.sortToRadioIndex(filter?.sort ?: SortType.POPULAR_PREVIEW)
            if (searchViewModel.illustSelectedRadioTabIndex.value != sortIdx) {
                searchViewModel.illustSelectedRadioTabIndex.value = sortIdx
            }
        }
        // 入口常驻（旧版仅在 date 排序下显示——V3 没有这个限制，所有排序都能加 filter）
        binding.usersYori.visibility = View.VISIBLE
        binding.usersYori.setOnClick {
            SearchFilterV3BottomSheet.newInstance(ObjectType.ILLUST)
                .show(childFragmentManager, "SearchFilterV3IllustSheet")
        }
    }
}
