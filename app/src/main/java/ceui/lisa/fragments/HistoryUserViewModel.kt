package ceui.lisa.fragments

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.loxia.Client
import ceui.loxia.HistoryEntry
import ceui.pixiv.db.EntityType
import ceui.pixiv.db.GeneralEntity
import ceui.pixiv.db.RecordType
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.ListItemHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 浏览历史「用户」tab 的 VM。数据源从本地 general_table 迁移到远端 pixshaft-api。
 * 远端条目映射回合成的 [GeneralEntity]（json = ceui.loxia.User），下游 Holder/UI 不变。
 * 未登录回退本地 DAO。
 */
class HistoryUserViewModel : ViewModel() {

    private val dao = AppDatabase.getAppDatabase(Shaft.getContext()).generalDao()

    private val _holders = MutableLiveData<List<ListItemHolder>>(emptyList())
    val holders: LiveData<List<ListItemHolder>> = _holders

    private val _isEmpty = MutableLiveData(false)
    val isEmpty: LiveData<Boolean> = _isEmpty

    private val rawItems = mutableListOf<GeneralEntity>()
    private var onDeleteCallback: ((GeneralEntity) -> Unit)? = null

    // —— 多选删除 —— 选中态以 entity.id(用户 uid)为键。
    private val _selectionMode = MutableLiveData(false)
    val selectionMode: LiveData<Boolean> = _selectionMode
    private val selectedIds = linkedSetOf<Long>()
    private val _selectedCount = MutableLiveData(0)
    val selectedCount: LiveData<Int> = _selectedCount

    private var nextCursor: String? = null
    private var forcedLocal = false

    // 关掉云同步、或还没弹过同意框(同意框现在挪到进历史页才弹)时,连读取也走本地:
    // 不把 uid 发给 pixshaft,且能显示本地/导入的历史 (issue #889)
    private fun useRemote(): Boolean =
        SessionManager.loggedInUid > 0L &&
            Shaft.sSettings.isCloudHistorySync && Shaft.sSettings.isCloudHistoryConsentShown

    fun setDeleteCallback(cb: (GeneralEntity) -> Unit) {
        onDeleteCallback = cb
    }

    private fun remoteToEntity(entry: HistoryEntry): GeneralEntity? {
        val payload = entry.payload ?: return null
        return GeneralEntity(
            entry.target_id,
            Shaft.sGson.toJson(payload),
            EntityType.USER,
            RecordType.VIEW_USER_HISTORY,
            entry.viewed_at,
        )
    }

    private suspend fun fetchPage(reset: Boolean): List<GeneralEntity> = withContext(Dispatchers.IO) {
        if (reset) forcedLocal = false
        if (useRemote() && !forcedLocal) {
            try {
                val cursor = if (reset) null else nextCursor
                val resp = Client.pixshaft.listHistory(
                    SessionManager.loggedInUid, "user", null, cursor, PAGE_SIZE,
                )
                nextCursor = resp.nextCursor
                val mapped = resp.items.mapNotNull { remoteToEntity(it) }
                // 刚同意云同步时云端可能还是空的(同意前的浏览没上传过)→ 回退本地,
                // 否则列表会从本地历史突然刷成空白。
                if (reset && mapped.isEmpty()) {
                    forcedLocal = true
                } else {
                    return@withContext mapped
                }
            } catch (ex: Exception) {
                // 远端挂了 → 退回本地 general_table(同上个版本)。
                Timber.w(ex, "remote user-history unavailable, falling back to local DB")
                forcedLocal = true
            }
        }
        val offset = if (reset) 0 else rawItems.size
        dao.getByRecordType(RecordType.VIEW_USER_HISTORY, offset, PAGE_SIZE)
    }

    fun loadFirst(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val data = fetchPage(reset = true)
            rawItems.clear()
            rawItems.addAll(data)
            _holders.value = buildHolders()
            _isEmpty.value = rawItems.isEmpty()
            onDone()
        }
    }

    fun loadMore(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            // forcedLocal 时说明已回退本地分页,不能被这条挡掉。
            if (useRemote() && !forcedLocal && nextCursor == null) {
                onDone()
                return@launch
            }
            val data = fetchPage(reset = false)
            if (data.isNotEmpty()) {
                rawItems.addAll(data)
                _holders.value = buildHolders()
            }
            onDone()
        }
    }

    fun delete(entity: GeneralEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // 永远删本地,server 挂时回退本地不会"复活"。
                dao.deleteByRecordTypeAndId(RecordType.VIEW_USER_HISTORY, entity.id)
                if (useRemote()) {
                    runCatching {
                        Client.pixshaft.deleteHistory(SessionManager.loggedInUid, "user", entity.id)
                    }.onFailure { Timber.w(it, "remote user-history delete failed (local deleted)") }
                }
            }
            rawItems.removeAll { it.id == entity.id }
            _holders.value = buildHolders()
            _isEmpty.value = rawItems.isEmpty()
        }
    }

    // —— 多选删除 ——

    fun enterSelectionMode() {
        if (_selectionMode.value == true) return
        selectedIds.clear()
        _selectedCount.value = 0
        _selectionMode.value = true
        _holders.value = buildHolders()
    }

    fun exitSelectionMode() {
        if (_selectionMode.value != true) return
        selectedIds.clear()
        _selectedCount.value = 0
        _selectionMode.value = false
        _holders.value = buildHolders()
    }

    fun toggleSelect(id: Long) {
        if (!selectedIds.add(id)) selectedIds.remove(id)
        _selectedCount.value = selectedIds.size
        _holders.value = buildHolders()
    }

    fun isAllSelected(): Boolean =
        rawItems.isNotEmpty() && rawItems.all { selectedIds.contains(it.id) }

    fun toggleSelectAll() {
        val allSelected = isAllSelected()
        selectedIds.clear()
        if (!allSelected) rawItems.forEach { selectedIds.add(it.id) }
        _selectedCount.value = selectedIds.size
        _holders.value = buildHolders()
    }

    fun deleteSelected(onComplete: (Int) -> Unit) {
        val targets = rawItems.filter { selectedIds.contains(it.id) }
        if (targets.isEmpty()) {
            onComplete(0)
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                targets.forEach { entity ->
                    dao.deleteByRecordTypeAndId(RecordType.VIEW_USER_HISTORY, entity.id)
                    if (useRemote()) {
                        runCatching {
                            Client.pixshaft.deleteHistory(SessionManager.loggedInUid, "user", entity.id)
                        }.onFailure { Timber.w(it, "remote user-history batch-delete failed (local deleted)") }
                    }
                }
            }
            val deletedIds = targets.map { it.id }.toSet()
            rawItems.removeAll { it.id in deletedIds }
            selectedIds.clear()
            _selectedCount.value = 0
            _selectionMode.value = false
            _holders.value = buildHolders()
            _isEmpty.value = rawItems.isEmpty()
            onComplete(deletedIds.size)
        }
    }

    private fun buildHolders(): List<ListItemHolder> {
        val selecting = _selectionMode.value == true
        val deleteHandler: (GeneralEntity) -> Unit = { entity ->
            onDeleteCallback?.invoke(entity)
        }
        val toggleHandler: (GeneralEntity) -> Unit = { entity ->
            toggleSelect(entity.id)
        }
        return rawItems.map { entity ->
            HistoryUserHolder(entity, deleteHandler, selecting, selectedIds.contains(entity.id), toggleHandler)
        }
    }

    companion object {
        private const val PAGE_SIZE = 30
    }
}
