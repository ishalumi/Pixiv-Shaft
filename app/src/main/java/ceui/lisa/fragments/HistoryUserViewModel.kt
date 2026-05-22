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

    private var nextCursor: String? = null

    private fun useRemote(): Boolean = SessionManager.loggedInUid > 0L

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
        if (useRemote()) {
            try {
                val cursor = if (reset) null else nextCursor
                val resp = Client.pixshaft.listHistory(
                    SessionManager.loggedInUid, "user", null, cursor, PAGE_SIZE,
                )
                nextCursor = resp.nextCursor
                resp.items.mapNotNull { remoteToEntity(it) }
            } catch (ex: Exception) {
                Timber.e(ex, "remote user-history list failed")
                emptyList()
            }
        } else {
            val offset = if (reset) 0 else rawItems.size
            dao.getByRecordType(RecordType.VIEW_USER_HISTORY, offset, PAGE_SIZE)
        }
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
            if (useRemote() && nextCursor == null) {
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
            if (useRemote()) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        Client.pixshaft.deleteHistory(SessionManager.loggedInUid, "user", entity.id)
                    }.onFailure { Timber.e(it, "remote user-history delete failed") }
                }
            } else {
                withContext(Dispatchers.IO) {
                    dao.deleteByRecordTypeAndId(RecordType.VIEW_USER_HISTORY, entity.id)
                }
            }
            rawItems.removeAll { it.id == entity.id }
            _holders.value = buildHolders()
            _isEmpty.value = rawItems.isEmpty()
        }
    }

    private fun buildHolders(): List<ListItemHolder> {
        val deleteHandler: (GeneralEntity) -> Unit = { entity ->
            onDeleteCallback?.invoke(entity)
        }
        return rawItems.map { HistoryUserHolder(it, deleteHandler) }
    }

    companion object {
        private const val PAGE_SIZE = 30
    }
}
