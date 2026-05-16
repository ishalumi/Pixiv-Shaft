package ceui.lisa.fragments

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 在 [FragmentHistoryTabs] 容器和它的子 tab ([FragmentHistoryList] /
 * [FragmentHistoryUserList]) 之间共享浏览历史搜索的当前 query。activityViewModels
 * 范围 — host fragment 跟所有 ViewPager 子 fragment 都 attach 到同一个 Activity。
 *
 * null = 未进入搜索；""=展开 SearchView 但未输入；非空 = 真实关键词。子 tab 内部
 * 用 flatMapLatest / collect 切换数据源。
 */
class HistorySearchSharedViewModel : ViewModel() {

    private val _query = MutableStateFlow<String?>(null)
    val query: StateFlow<String?> get() = _query

    fun setQuery(q: String?) {
        _query.value = q
    }
}
