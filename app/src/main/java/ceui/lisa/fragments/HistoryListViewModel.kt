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
import ceui.loxia.Client
import ceui.loxia.HistoryEntry
import ceui.loxia.ObjectPool
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.ListItemHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 浏览历史「插画/漫画」「小说」tab 的 VM。
 *
 * 数据源已从本地 illust_table 迁移到远端 pixshaft-api（按观看者 uid）。内部仍以
 * [IllustHistoryEntity] 为载体——远端条目映射回合成的 entity，下游 buildHolders /
 * Holder / UI 完全不变。未登录（uid<=0）回退本地 DAO，保证不崩。
 */
class HistoryListViewModel(private val historyType: Int) : ViewModel() {

    private val dao = AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao()

    // historyType: 0 = 插画/漫画, 1 = 小说
    private val serverType = if (historyType == 0) "illust,manga" else "novel"

    private val _holders = MutableLiveData<List<ListItemHolder>>(emptyList())
    val holders: LiveData<List<ListItemHolder>> = _holders

    private val _isEmpty = MutableLiveData(false)
    val isEmpty: LiveData<Boolean> = _isEmpty

    private val rawItems = mutableListOf<IllustHistoryEntity>()
    private val allIllusts = mutableListOf<IllustsBean>()
    private var onDeleteCallback: ((IllustHistoryEntity) -> Unit)? = null

    /** 远端 keyset 翻页游标；null = 没有更多。 */
    private var nextCursor: String? = null

    /** 远端这次会话已失败过 → 本会话后续翻页直接走本地,避免远端/本地混页。 */
    private var forcedLocal = false

    /** 当前搜索 query；null/空串 = 走默认 paginated 列表，非空 = 走搜索结果。 */
    private var searchQuery: String? = null

    // 关掉云同步、或还没弹过同意框(同意框现在挪到进历史页才弹)时,连读取也走本地:
    // 不把 uid 发给 pixshaft,且能显示本地/导入的历史 (issue #889)
    private fun useRemote(): Boolean =
        SessionManager.loggedInUid > 0L &&
            Shaft.sSettings.isCloudHistorySync && Shaft.sSettings.isCloudHistoryConsentShown

    fun setDeleteCallback(cb: (IllustHistoryEntity) -> Unit) {
        onDeleteCallback = cb
    }

    /** 把远端条目映射回 illust_table 的 entity 形态，复用既有 buildHolders。 */
    private fun remoteToEntity(entry: HistoryEntry): IllustHistoryEntity? {
        val payload = entry.payload ?: return null
        return IllustHistoryEntity().apply {
            illustID = entry.target_id.toInt()
            illustJson = Shaft.sGson.toJson(payload)
            time = entry.viewed_at
            type = historyType
        }
    }

    private suspend fun fetchPage(reset: Boolean): List<IllustHistoryEntity> = withContext(Dispatchers.IO) {
        if (reset) forcedLocal = false
        if (useRemote() && !forcedLocal) {
            try {
                val cursor = if (reset) null else nextCursor
                val resp = Client.pixshaft.listHistory(
                    SessionManager.loggedInUid, serverType, null, cursor, PAGE_SIZE,
                )
                nextCursor = resp.nextCursor
                return@withContext resp.items.mapNotNull { remoteToEntity(it) }
            } catch (ex: Exception) {
                // 远端挂了/超时 → 本会话退回本地 DAO,行为等同上个版本(本地一直在双写)。
                Timber.w(ex, "remote history unavailable, falling back to local DB")
                forcedLocal = true
            }
        }
        val offset = if (reset) 0 else rawItems.size
        dao.getViewHistoryByType(historyType, PAGE_SIZE, offset)
    }

    fun loadFirst(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val data = fetchPage(reset = true)
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
            // 远端：没有游标就别再请求（否则 before=null 会把第一页重复拉回来）。
            if (useRemote() && nextCursor == null) {
                onDone()
                return@launch
            }
            val data = fetchPage(reset = false)
            if (data.isNotEmpty()) {
                rawItems.addAll(data)
                appendIllusts(data)
                _holders.value = buildHolders()
            }
            onDone()
        }
    }

    /**
     * null/空串 → 回到默认 paginated 列表；非空 → 按当前 [historyType] 搜索。
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
                if (useRemote()) {
                    try {
                        Client.pixshaft.listHistory(
                            SessionManager.loggedInUid, serverType, normalized, null, SEARCH_LIMIT,
                        ).items.mapNotNull { remoteToEntity(it) }
                    } catch (ex: Exception) {
                        // 远端搜索挂了 → 退回本地 LIKE 搜索(同上个版本)。
                        Timber.w(ex, "remote history search unavailable, falling back to local")
                        dao.searchViewHistoryByType(normalized, historyType)
                    }
                } else {
                    dao.searchViewHistoryByType(normalized, historyType)
                }
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
            withContext(Dispatchers.IO) {
                // 永远删本地(双写的副本),server 挂时回退本地才不会"复活"。
                dao.delete(entity)
                if (useRemote()) {
                    val tt = if (historyType == 1) {
                        "novel"
                    } else {
                        val ib = runCatching {
                            Shaft.sGson.fromJson(entity.illustJson, IllustsBean::class.java)
                        }.getOrNull()
                        if (ib?.type == "manga") "manga" else "illust"
                    }
                    runCatching {
                        Client.pixshaft.deleteHistory(SessionManager.loggedInUid, tt, entity.illustID.toLong())
                    }.onFailure { Timber.w(it, "remote history delete failed (local deleted)") }
                }
            }
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
        private const val SEARCH_LIMIT = 100

        fun factory(type: Int) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HistoryListViewModel(type) as T
        }
    }
}
