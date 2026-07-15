package ceui.lisa.fragments

import android.content.Intent
import androidx.core.view.isVisible
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.activities.UActivity
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.IllustHistoryEntity
import ceui.lisa.databinding.CellHistoryIllustV3Binding
import ceui.lisa.databinding.CellHistoryNovelV3Binding
import ceui.lisa.models.IllustsBean
import ceui.lisa.models.NovelBean
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.HistoryEntry
import ceui.loxia.ObjectPool
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
import java.util.Locale

// ── FeedItem 模型（原 HistoryIllustHolder / HistoryNovelHolder 的数据部分）─────────────
// isSelectionMode / isSelected 由 FragmentHistoryList.syncSelection 通过 updateItems 回灌。

data class HistoryIllustFeedItem(
    val entity: IllustHistoryEntity,
    val illust: IllustsBean,
    val isSelectionMode: Boolean = false,
    val isSelected: Boolean = false,
) : FeedItem {
    override val feedKey: Any get() = entity.illustID
}

data class HistoryNovelFeedItem(
    val entity: IllustHistoryEntity,
    val novel: NovelBean,
    val isSelectionMode: Boolean = false,
    val isSelected: Boolean = false,
) : FeedItem {
    override val feedKey: Any get() = entity.illustID
}

// ── FeedSource：远端 pixshaft(按 uid) 优先，失败/未登录/未同意回退本地 DAO。搜索走单页。──
// 原 HistoryListViewModel 的 fetchPage / applySearch / remoteToEntity 逻辑整体搬来。
// searchVm 是 activity-scope（跨旋转存活），source 由 FeedViewModel 长期持有也安全。

class HistoryFeedSource(
    private val historyType: Int,
    private val searchVm: HistorySearchSharedViewModel,
) : FeedSource<String> {

    private val dao = AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao()
    private val serverType = if (historyType == 0) "illust,manga" else "novel"

    /** 远端这次会话已失败/为空 → 本会话后续翻页直接走本地，避免远端/本地混页。 */
    private var forcedLocal = false

    private fun useRemote(): Boolean =
        SessionManager.loggedInUid > 0L &&
            Shaft.sSettings.isCloudHistorySync && Shaft.sSettings.isCloudHistoryConsentShown

    override suspend fun load(cursor: String?): FeedPage<String> {
        val page = loadPage(cursor)
        // ObjectPool.store 是普通 map + setValue(见 ObjectPool),后台线程写会与主线程 get/update
        // 竞争撞 ConcurrentModificationException。load 由 FeedViewModel 的 viewModelScope(Main)调起,
        // 这里回主线程再喂池,详情页 ObjectPool.get 才拿得到最新 illust。
        if (historyType == 0) {
            withContext(Dispatchers.Main.immediate) {
                page.items.forEach { item ->
                    (item as? HistoryIllustFeedItem)?.let { ObjectPool.updateIllust(it.illust) }
                }
            }
        }
        return page
    }

    private suspend fun loadPage(cursor: String?): FeedPage<String> = withContext(Dispatchers.IO) {
        val query = searchVm.query.value?.trim().orEmpty().ifEmpty { null }
        if (query != null) {
            // 搜索：单页、无翻页（nextCursor = null）
            val entities = if (useRemote()) {
                try {
                    Client.pixshaft.listHistory(
                        SessionManager.loggedInUid, serverType, query, null, SEARCH_LIMIT,
                    ).items.mapNotNull { remoteToEntity(it) }
                } catch (ex: Exception) {
                    Timber.w(ex, "remote history search unavailable, falling back to local")
                    dao.searchViewHistoryByType(query, historyType)
                }
            } else {
                dao.searchViewHistoryByType(query, historyType)
            }
            return@withContext FeedPage(entities.toFeedItems(), null)
        }

        if (cursor == null) forcedLocal = false
        if (useRemote() && !forcedLocal) {
            try {
                val resp = Client.pixshaft.listHistory(
                    SessionManager.loggedInUid, serverType, null, cursor, PAGE_SIZE,
                )
                val mapped = resp.items.mapNotNull { remoteToEntity(it) }
                // 刚同意云同步时云端可能还是空的 → 回退本地，否则列表会突然刷成空白。
                if (cursor == null && mapped.isEmpty()) {
                    forcedLocal = true
                } else {
                    return@withContext FeedPage(mapped.toFeedItems(), resp.nextCursor)
                }
            } catch (ex: Exception) {
                Timber.w(ex, "remote history unavailable, falling back to local DB")
                forcedLocal = true
            }
        }
        // 本地分页：cursor = 已加载 offset（字符串编码）
        val offset = cursor?.toIntOrNull() ?: 0
        val entities = dao.getViewHistoryByType(historyType, PAGE_SIZE, offset)
        val next = if (entities.size >= PAGE_SIZE) (offset + entities.size).toString() else null
        FeedPage(entities.toFeedItems(), next)
    }

    /** 把远端条目映射回 illust_table 的 entity 形态，复用既有渲染。 */
    private fun remoteToEntity(entry: HistoryEntry): IllustHistoryEntity? {
        val payload = entry.payload ?: return null
        return IllustHistoryEntity().apply {
            illustID = entry.target_id.toInt()
            illustJson = Shaft.sGson.toJson(payload)
            time = entry.viewed_at
            type = historyType
        }
    }

    private fun List<IllustHistoryEntity>.toFeedItems(): List<FeedItem> = mapNotNull { entity ->
        if (historyType == 0) {
            val illust = Shaft.sGson.fromJson(entity.illustJson, IllustsBean::class.java)
                ?: return@mapNotNull null
            HistoryIllustFeedItem(entity, illust)
        } else {
            val novel = Shaft.sGson.fromJson(entity.illustJson, NovelBean::class.java)
                ?: return@mapNotNull null
            HistoryNovelFeedItem(entity, novel)
        }
    }

    companion object {
        const val PAGE_SIZE = 30
        const val SEARCH_LIMIT = 100
    }
}

/**
 * 删除历史条目（本地 + 远端）。永远删本地（双写副本），server 挂时回退本地才不会「复活」。
 * 独立 suspend 函数：调用方（[FragmentHistoryList]）拿不到 FeedViewModel 内部的 source，直接调这里。
 */
suspend fun deleteHistoryEntities(historyType: Int, entities: List<IllustHistoryEntity>) =
    withContext(Dispatchers.IO) {
        val dao = AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao()
        val useRemote = SessionManager.loggedInUid > 0L &&
            Shaft.sSettings.isCloudHistorySync && Shaft.sSettings.isCloudHistoryConsentShown
        entities.forEach { entity ->
            dao.delete(entity)
            if (useRemote) {
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
    }

// ── Renderers（原 HistoryIllustViewHolder / HistoryNovelViewHolder 的绑定逻辑）──────────
// 定义成 FragmentHistoryList 扩展：renderer 只在 onCreateRenderers() 里 new，随 view 生死，
// 引用 fragment 的方法安全（不违反 FeedSource 的零捕获约定）。

fun FragmentHistoryList.historyIllustRenderer(): FeedRenderer<HistoryIllustFeedItem, CellHistoryIllustV3Binding> =
    feedRenderer(
        inflate = CellHistoryIllustV3Binding::inflate,
        recycle = { Glide.with(it.binding.illustImage).clear(it.binding.illustImage) },
    ) { cell ->
        val binding = cell.binding
        val item = cell.item
        val illust = item.illust
        val entity = item.entity
        val context = binding.root.context

        val screenWidth = context.resources.displayMetrics.widthPixels
        val itemWidth = (screenWidth - context.resources.getDimensionPixelSize(R.dimen.four_dp) * 6) / 2
        val imageHeight = if (illust.width > 0) {
            (itemWidth.toFloat() * illust.height / illust.width).toInt().coerceAtLeast(itemWidth / 2)
        } else itemWidth
        binding.illustImage.layoutParams = binding.illustImage.layoutParams.apply {
            width = itemWidth
            height = imageHeight
        }
        Glide.with(context).load(GlideUtil.getMediumImg(illust))
            .placeholder(R.color.v3_surface_2).into(binding.illustImage)
        binding.title.text = illust.title
        binding.author.text = illust.user?.name.orEmpty()
        binding.time.text = historyTimeFormat.format(entity.time)

        when {
            illust.isGif -> { binding.pSize.isVisible = true; binding.pSize.text = "GIF" }
            illust.page_count > 1 -> {
                binding.pSize.isVisible = true
                binding.pSize.text = String.format(Locale.getDefault(), "%dP", illust.page_count)
            }
            else -> binding.pSize.isVisible = false
        }

        HistorySelectBadge.bind(binding.selectCheck, item.isSelectionMode, item.isSelected)
        binding.deleteItem.isVisible = !item.isSelectionMode

        binding.root.setOnClickListener {
            if (item.isSelectionMode) { toggleHistorySelect(entity); return@setOnClickListener }
            openHistoryIllust(illust)
        }
        binding.root.setOnLongClickListener {
            if (item.isSelectionMode) return@setOnLongClickListener true
            confirmDeleteHistory(entity)
            true
        }
        binding.deleteItem.setOnClickListener { confirmDeleteHistory(entity) }
        binding.author.setOnClickListener {
            illust.user?.id?.let { uid -> openHistoryUser(uid) }
        }
    }

fun FragmentHistoryList.historyNovelRenderer(): FeedRenderer<HistoryNovelFeedItem, CellHistoryNovelV3Binding> =
    feedRenderer(
        inflate = CellHistoryNovelV3Binding::inflate,
        recycle = { Glide.with(it.binding.illustImage).clear(it.binding.illustImage) },
    ) { cell ->
        val binding = cell.binding
        val item = cell.item
        val novel = item.novel
        val entity = item.entity
        val context = binding.root.context

        Glide.with(context).load(GlideUtil.getUrl(novel.image_urls?.medium))
            .placeholder(R.color.v3_surface_2).into(binding.illustImage)
        binding.title.text = novel.title
        binding.author.text = novel.user?.name.orEmpty()
        binding.time.text = historyTimeFormat.format(entity.time)
        binding.badgeAi.isVisible = novel.isCreatedByAI()

        HistorySelectBadge.bind(binding.selectCheck, item.isSelectionMode, item.isSelected)
        binding.deleteItem.isVisible = !item.isSelectionMode

        binding.root.setOnClickListener {
            if (item.isSelectionMode) { toggleHistorySelect(entity); return@setOnClickListener }
            context.startActivity(Intent(context, TemplateActivity::class.java).apply {
                putExtra(Params.CONTENT, novel)
                putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说详情")
                putExtra("hideStatusBar", true)
            })
        }
        binding.root.setOnLongClickListener {
            if (item.isSelectionMode) return@setOnLongClickListener true
            confirmDeleteHistory(entity)
            true
        }
        binding.deleteItem.setOnClickListener { confirmDeleteHistory(entity) }
        binding.author.setOnClickListener {
            novel.user?.id?.let { uid -> openHistoryUser(uid) }
        }
    }
