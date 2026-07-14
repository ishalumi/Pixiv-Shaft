package ceui.lisa.fragments

import android.content.Intent
import androidx.core.view.isVisible
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.UActivity
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.CellHistoryUserBinding
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.HistoryEntry
import ceui.loxia.User
import ceui.pixiv.db.EntityType
import ceui.pixiv.db.GeneralEntity
import ceui.pixiv.db.RecordType
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.session.SessionManager
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Locale

// ── FeedItem 模型（原 HistoryUserHolder 的数据部分）。isSelectionMode/isSelected 由
//    FragmentHistoryUserList.syncSelection 通过 updateItems 回灌，键为 entity.id(uid)。──

data class HistoryUserFeedItem(
    val entity: GeneralEntity,
    val isSelectionMode: Boolean = false,
    val isSelected: Boolean = false,
) : FeedItem {
    override val feedKey: Any get() = entity.id
}

// ── FeedSource：远端 pixshaft("user") 优先，失败/未登录/未同意回退本地 general_table。
//    「用户」tab 无搜索(对齐旧 HistoryUserViewModel)。原 fetchPage 逻辑整体搬来。──

class HistoryUserFeedSource : FeedSource<String> {

    private val dao = AppDatabase.getAppDatabase(Shaft.getContext()).generalDao()
    private var forcedLocal = false

    private fun useRemote(): Boolean =
        SessionManager.loggedInUid > 0L &&
            Shaft.sSettings.isCloudHistorySync && Shaft.sSettings.isCloudHistoryConsentShown

    override suspend fun load(cursor: String?): FeedPage<String> = withContext(Dispatchers.IO) {
        if (cursor == null) forcedLocal = false
        if (useRemote() && !forcedLocal) {
            try {
                val resp = Client.pixshaft.listHistory(
                    SessionManager.loggedInUid, "user", null, cursor, PAGE_SIZE,
                )
                val mapped = resp.items.mapNotNull { remoteToEntity(it) }
                if (cursor == null && mapped.isEmpty()) {
                    forcedLocal = true
                } else {
                    return@withContext FeedPage(mapped.map { HistoryUserFeedItem(it) }, resp.nextCursor)
                }
            } catch (ex: Exception) {
                Timber.w(ex, "remote user-history unavailable, falling back to local DB")
                forcedLocal = true
            }
        }
        val offset = cursor?.toIntOrNull() ?: 0
        val entities = dao.getByRecordType(RecordType.VIEW_USER_HISTORY, offset, PAGE_SIZE)
        val next = if (entities.size >= PAGE_SIZE) (offset + entities.size).toString() else null
        FeedPage(entities.map { HistoryUserFeedItem(it) }, next)
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

    companion object {
        const val PAGE_SIZE = 30
    }
}

/** 删除用户浏览历史（本地 + 远端）。永远删本地，server 挂时回退本地不会「复活」。 */
suspend fun deleteUserHistoryEntities(entities: List<GeneralEntity>) = withContext(Dispatchers.IO) {
    val dao = AppDatabase.getAppDatabase(Shaft.getContext()).generalDao()
    val useRemote = SessionManager.loggedInUid > 0L &&
        Shaft.sSettings.isCloudHistorySync && Shaft.sSettings.isCloudHistoryConsentShown
    entities.forEach { entity ->
        dao.deleteByRecordTypeAndId(RecordType.VIEW_USER_HISTORY, entity.id)
        if (useRemote) {
            runCatching {
                Client.pixshaft.deleteHistory(SessionManager.loggedInUid, "user", entity.id)
            }.onFailure { Timber.w(it, "remote user-history delete failed (local deleted)") }
        }
    }
}

// ── Renderer（原 HistoryUserViewHolder 的绑定逻辑）。FragmentHistoryUserList 扩展。──

private fun userHistoryTimeFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

fun FragmentHistoryUserList.historyUserRenderer(): FeedRenderer<HistoryUserFeedItem, CellHistoryUserBinding> =
    feedRenderer(
        inflate = CellHistoryUserBinding::inflate,
        recycle = { Glide.with(it.binding.userAvatar).clear(it.binding.userAvatar) },
    ) { cell ->
        val binding = cell.binding
        val item = cell.item
        val entity = item.entity
        val context = binding.root.context

        val user = runCatching { Shaft.sGson.fromJson(entity.json, User::class.java) }.getOrNull()
        binding.userName.text = user?.name ?: "User #${entity.id}"
        binding.visitTime.text = userHistoryTimeFormat().format(entity.updatedTime)
        val avatarUrl = user?.profile_image_urls?.medium
        if (!avatarUrl.isNullOrEmpty()) {
            Glide.with(context).load(GlideUtil.getUrl(avatarUrl)).into(binding.userAvatar)
        }

        HistorySelectBadge.bind(binding.selectCheck, item.isSelectionMode, item.isSelected)
        binding.deleteItem.isVisible = !item.isSelectionMode

        binding.root.setOnClickListener {
            if (item.isSelectionMode) { toggleUserHistorySelect(entity); return@setOnClickListener }
            context.startActivity(Intent(context, UActivity::class.java).apply {
                putExtra(Params.USER_ID, entity.id.toInt())
            })
        }
        binding.root.setOnLongClickListener {
            if (item.isSelectionMode) return@setOnLongClickListener true
            confirmDeleteUserHistory(entity)
            true
        }
        binding.deleteItem.setOnClickListener { confirmDeleteUserHistory(entity) }
    }
