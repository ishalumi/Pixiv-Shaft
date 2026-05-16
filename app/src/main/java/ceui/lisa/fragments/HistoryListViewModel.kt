package ceui.lisa.fragments

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.IllustHistoryEntity
import ceui.lisa.models.IllustsBean
import ceui.lisa.models.NovelBean
import ceui.loxia.ObjectPool
import ceui.pixiv.ui.common.ListItemHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryListViewModel(private val historyType: Int) : ViewModel() {

    private val dao = AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao()

    private val _holders = MutableLiveData<List<ListItemHolder>>(emptyList())
    val holders: LiveData<List<ListItemHolder>> = _holders

    private val _isEmpty = MutableLiveData(false)
    val isEmpty: LiveData<Boolean> = _isEmpty

    private val rawItems = mutableListOf<IllustHistoryEntity>()
    private val allIllusts = mutableListOf<IllustsBean>()
    private var onDeleteCallback: ((IllustHistoryEntity) -> Unit)? = null

    /** 当前搜索 query；null/空串 = 走默认 paginated 列表，非空 = 走 LIKE 结果。 */
    private var searchQuery: String? = null

    fun setDeleteCallback(cb: (IllustHistoryEntity) -> Unit) {
        onDeleteCallback = cb
    }

    fun loadFirst(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                dao.getViewHistoryByType(historyType, PAGE_SIZE, 0)
            }
            rawItems.clear()
            rawItems.addAll(data)
            rebuildIllusts()
            _holders.value = buildHolders()
            _isEmpty.value = rawItems.isEmpty()
            onDone()
        }
    }

    fun loadMore(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                dao.getViewHistoryByType(historyType, PAGE_SIZE, rawItems.size)
            }
            if (data.isNotEmpty()) {
                rawItems.addAll(data)
                appendIllusts(data)
                _holders.value = buildHolders()
            }
            onDone()
        }
    }

    /**
     * 由 host fragment 监听 [HistorySearchSharedViewModel.query] 后转发：
     *   null/空串 → 回到默认 paginated 列表
     *   非空 → 走 DAO LIKE，按当前 [historyType] 过滤
     * 同一 query 不重复请求。
     */
    fun applySearch(query: String?) {
        val normalized = query?.trim().orEmpty().ifEmpty { null }
        if (searchQuery == normalized) return
        searchQuery = normalized
        if (normalized == null) {
            loadFirst()
            return
        }
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                dao.searchViewHistoryByType(normalized, historyType)
            }
            rawItems.clear()
            rawItems.addAll(data)
            rebuildIllusts()
            _holders.value = buildHolders()
            _isEmpty.value = rawItems.isEmpty()
        }
    }

    fun delete(entity: IllustHistoryEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dao.delete(entity) }
            rawItems.removeAll { it.illustID == entity.illustID && it.type == entity.type }
            if (entity.type == 0) allIllusts.removeAll { it.id == entity.illustID }
            _holders.value = buildHolders()
            _isEmpty.value = rawItems.isEmpty()
        }
    }

    private fun buildHolders(): List<ListItemHolder> {
        val deleteHandler: (IllustHistoryEntity) -> Unit = { entity ->
            onDeleteCallback?.invoke(entity)
        }
        return rawItems.mapNotNull { entity ->
            if (historyType == 0) {
                val illust = Shaft.sGson.fromJson(entity.illustJson, IllustsBean::class.java) ?: return@mapNotNull null
                HistoryIllustHolder(entity, illust, { allIllusts.toList() }, deleteHandler)
            } else {
                val novel = Shaft.sGson.fromJson(entity.illustJson, NovelBean::class.java) ?: return@mapNotNull null
                HistoryNovelHolder(entity, novel, deleteHandler)
            }
        }
    }

    private fun rebuildIllusts() {
        allIllusts.clear()
        if (historyType != 0) return
        rawItems.mapNotNull { Shaft.sGson.fromJson(it.illustJson, IllustsBean::class.java) }
            .forEach { ObjectPool.updateIllust(it); allIllusts.add(it) }
    }

    private fun appendIllusts(newItems: List<IllustHistoryEntity>) {
        if (historyType != 0) return
        newItems.mapNotNull { Shaft.sGson.fromJson(it.illustJson, IllustsBean::class.java) }
            .forEach { ObjectPool.updateIllust(it); allIllusts.add(it) }
    }

    companion object {
        private const val PAGE_SIZE = 30

        fun factory(type: Int) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HistoryListViewModel(type) as T
        }
    }
}
