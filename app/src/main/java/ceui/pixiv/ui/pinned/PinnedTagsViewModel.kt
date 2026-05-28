package ceui.pixiv.ui.pinned

import androidx.lifecycle.viewModelScope
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.SearchEntity
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.pixiv.ui.common.HoldersViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「置顶标签」页 VM。
 * 数据：[ceui.lisa.database.SearchDao.getAllPinned] —— pinned=1 的全量 search_table 行,
 * 按 searchTime 倒序返回（最近置顶的排前）。
 */
class PinnedTagsViewModel : HoldersViewModel() {

    override suspend fun refreshImpl(hint: RefreshHint) {
        super.refreshImpl(hint)
        val items = withContext(Dispatchers.IO) {
            val dao = AppDatabase.getAppDatabase(Shaft.getContext()).searchDao()
            dao.getAllPinned()
                .filterNot { it.keyword.isNullOrBlank() }
                .map { PinnedTagItemHolder(it) }
        }
        _itemHolders.postValue(items)
        _refreshState.value = RefreshState.LOADED(
            hasContent = items.isNotEmpty(),
            hasNext = false,
        )
    }

    fun deleteOne(entity: SearchEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().deleteSearchEntity(entity)
            }
            refresh(RefreshHint.PullToRefresh)
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                AppDatabase.getAppDatabase(Shaft.getContext()).searchDao().deleteAllPinned()
            }
            refresh(RefreshHint.PullToRefresh)
        }
    }

    init {
        refresh(RefreshHint.InitialLoad)
    }
}
