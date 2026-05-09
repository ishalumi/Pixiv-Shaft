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
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.ui.search.v3.SearchFilterV3
import ceui.pixiv.ui.search.v3.SearchFilterV3BottomSheet
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.DialogViewModel

class SearchNovelFragment : PixivFragment(R.layout.fragment_pixiv_list) {
    private val searchViewModel by viewModels<SearchViewModel>(ownerProducer = { requireParentFragment() })
    private val dialogViewModel by activityViewModels<DialogViewModel>()
    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val viewModel by pixivListViewModel({ Pair(searchViewModel, dialogViewModel) }) { (vm, dialogVM) ->
        SearchNovelDataSource {
            val count = dialogVM.chosenUsersYoriCount.value
            vm.buildSearchConfig(count, ObjectType.NOVEL)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel, ListMode.VERTICAL)
        binding.radioTab.setTabs(listOf(
            "热度预览",
            "从新到旧",
            "从旧到新",
            "热度排序",
        ))
        binding.radioTab.setItemCickListener { index ->
            searchViewModel.novelSelectedRadioTabIndex.value = index
            val newSort = searchViewModel.radioIndexToSort(index)
            val cur = searchViewModel.novelFilter.value ?: SearchFilterV3()
            if (cur.sort != newSort) {
                searchViewModel.novelFilter.value = cur.copy(sort = newSort)
            }
            val now = System.currentTimeMillis()
            searchViewModel.triggerSearchNovelEvent(now)
        }
        searchViewModel.searchNovelEvent.observeEvent(viewLifecycleOwner) {
            viewModel.refresh(RefreshHint.InitialLoad)
        }
        searchViewModel.novelSelectedRadioTabIndex.observe(viewLifecycleOwner) { index ->
            binding.radioTab.selectTab(index)
        }
        searchViewModel.novelFilter.observe(viewLifecycleOwner) { filter ->
            val count = filter?.activeCount(isNovel = true) ?: 0
            binding.usersYori.text = if (count > 0)
                getString(R.string.search_filter_v3_entry_with_count, count)
            else getString(R.string.search_filter_v3_entry)
            val sortIdx = searchViewModel.sortToRadioIndex(filter?.sort ?: SortType.POPULAR_PREVIEW)
            if (searchViewModel.novelSelectedRadioTabIndex.value != sortIdx) {
                searchViewModel.novelSelectedRadioTabIndex.value = sortIdx
            }
        }
        binding.usersYori.visibility = View.VISIBLE
        binding.usersYori.setOnClick {
            SearchFilterV3BottomSheet.newInstance(ObjectType.NOVEL)
                .show(childFragmentManager, "SearchFilterV3NovelSheet")
        }
    }
}
