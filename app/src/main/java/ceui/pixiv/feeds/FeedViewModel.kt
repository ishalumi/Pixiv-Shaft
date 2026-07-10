package ceui.pixiv.feeds

import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 列表状态机。数据全部住在这里，Fragment 不持有任何数据（进程内配置变更零重载）。
 *
 * 并发规则（全部由本类保证，UI 层随便调不会坏）：
 * - refresh 永远赢：会取消进行中的任何加载；
 * - loadMore 单飞（single-flight）：Loading 中重入直接忽略；
 * - append 出错后不再被滚动自动触发，只能由 [retryAppend]（用户点击）恢复，避免错误风暴；
 * - 追加页按身份去重，服务端翻页窗口漂移不会产生重复条目破坏 DiffUtil。
 */
class FeedViewModel<Cursor : Any>(
    private val source: FeedSource<Cursor>,
    autoLoad: Boolean = true,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    private var nextCursor: Cursor? = null
    private var refreshJob: Job? = null
    private var appendJob: Job? = null

    /** 当前翻页游标快照，用于把分页上下文交接给外部组件（如详情页 pager 续读）。 */
    val currentCursor: Cursor?
        get() = nextCursor

    init {
        if (autoLoad) {
            refresh()
        }
    }

    /**
     * 加载第一页。已有内容会保留在屏幕上直到新数据到达（不闪白屏）。
     *
     * 首页同样有空页追载：mapper 把第一页整页滤空（重口味屏蔽设置 + #729 场景）时，
     * 继续向后翻，直到拿到可展示内容或确定到底——否则空列表上没有任何 onBind，
     * loadMore 的预取信号永远不会点火。
     */
    fun refresh() {
        refreshJob?.cancel()
        appendJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(refresh = LoadState.Loading, append = LoadState.Idle) }
            try {
                var page = source.load(null)
                nextCursor = page.nextCursor
                var items = page.items.dedupByIdentity()
                var emptyHops = 0
                while (items.isEmpty() && page.nextCursor != null && emptyHops < MAX_EMPTY_PAGE_HOPS) {
                    page = source.load(page.nextCursor)
                    nextCursor = page.nextCursor
                    items = page.items.dedupByIdentity()
                    emptyHops++
                }
                val hopCapExhausted = items.isEmpty() && emptyHops >= MAX_EMPTY_PAGE_HOPS
                _uiState.update {
                    it.copy(
                        items = items,
                        refresh = LoadState.Idle,
                        reachedEnd = nextCursor == null || hopCapExhausted,
                        hasLoadedOnce = true,
                    )
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Throwable) {
                Timber.e(ex)
                _uiState.update { it.copy(refresh = LoadState.Error(ex)) }
            }
        }
    }

    /**
     * 加载下一页。滚动接近底部时可高频调用，内部自带防重入。
     *
     * 预取由 onBind 驱动：如果某一页去重后零新条目（空页 / 整页被过滤或与已有内容重叠），
     * 列表不变、DiffUtil 零派发、onBind 不再发生，预取信号就断了。所以这里主动追载：
     * 只要拿到的页没有贡献新条目且还有游标，就继续取下一页（上限 [MAX_EMPTY_PAGE_HOPS]，
     * 触顶视为到底），保证一次 loadMore 结束后要么有新内容、要么确定到底。
     */
    fun loadMore() {
        val current = _uiState.value
        if (!current.hasLoadedOnce || current.reachedEnd) return
        if (current.refresh is LoadState.Loading) return
        // Loading 防重入；Error 停手等用户点重试
        if (current.append !is LoadState.Idle) return
        val firstCursor = nextCursor ?: return
        appendJob = viewModelScope.launch {
            _uiState.update { it.copy(append = LoadState.Loading) }
            try {
                var cursor = firstCursor
                var emptyHops = 0
                while (true) {
                    val page = source.load(cursor)
                    nextCursor = page.nextCursor
                    // VM 的全部状态变更都在主线程串行发生，先读快照再算增量是安全的
                    val seen = _uiState.value.items.mapTo(HashSet()) { it.identity }
                    val fresh = page.items.filter { seen.add(it.identity) }
                    _uiState.update { state ->
                        state.copy(
                            items = state.items + fresh,
                            reachedEnd = page.nextCursor == null,
                        )
                    }
                    val next = page.nextCursor
                    if (fresh.isNotEmpty() || next == null) break
                    if (++emptyHops >= MAX_EMPTY_PAGE_HOPS) {
                        Timber.w("feeds: 连续 %d 页零新条目，视为到底", emptyHops)
                        _uiState.update { it.copy(reachedEnd = true) }
                        break
                    }
                    cursor = next
                }
                _uiState.update { it.copy(append = LoadState.Idle) }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Throwable) {
                Timber.e(ex)
                _uiState.update { it.copy(append = LoadState.Error(ex)) }
            }
        }
    }

    /**
     * 外部组件（如详情页 pager）替这张列表继续翻过页之后，把最新游标交还回来，
     * 让列表接着它的进度翻，而不是拿旧游标重拉一遍已看过的页。
     *
     * 会取消在飞的追加：那次追加基于旧游标，让它跑完会把 nextCursor 回写成
     * 旧页的游标，悄悄覆盖掉刚交接来的进度。refresh 不受影响（refresh 永远赢）。
     */
    fun adoptCursor(cursor: Cursor?) {
        appendJob?.cancel()
        nextCursor = cursor
        _uiState.update { it.copy(append = LoadState.Idle, reachedEnd = cursor == null) }
    }

    /** 追加失败后的手动重试入口（footer 点击）。 */
    fun retryAppend() {
        if (_uiState.value.append is LoadState.Error) {
            _uiState.update { it.copy(append = LoadState.Idle) }
            loadMore()
        }
    }

    /**
     * 整表编辑的通用入口：替换 / 插入 / 删除都可以由此表达。
     * 传入函数必须是纯函数（不要在里面做 IO）。
     */
    fun mutateItems(edit: (List<FeedItem>) -> List<FeedItem>) {
        _uiState.update { it.copy(items = edit(it.items)) }
    }

    /**
     * 把外部拿到的条目直接追加进列表（详情页 pager 替列表续拉的页等），
     * 纯内存操作不触发网络。身份去重由本方法保证（对全表 + 入参内部同时去重），
     * 调用方不需要自己维护「列表内身份唯一」这个 DiffUtil 前置不变量。
     * 通常与 [adoptCursor] 配套使用：先追加数据，再交接游标。
     */
    fun appendItems(newItems: List<FeedItem>) {
        if (newItems.isEmpty()) return
        mutateItems { existing ->
            val seen = existing.mapTo(HashSet()) { it.identity }
            val fresh = newItems.filter { seen.add(it.identity) }
            if (fresh.isEmpty()) existing else existing + fresh
        }
    }

    /** 就地更新某一类条目（点赞 / 收藏切换等），不触发网络重载。 */
    fun <T : FeedItem> updateItems(itemClass: Class<T>, transform: (T) -> T) {
        mutateItems { list ->
            list.map { item ->
                if (itemClass.isInstance(item)) transform(itemClass.cast(item)) else item
            }
        }
    }

    fun removeItems(predicate: (FeedItem) -> Boolean) {
        mutateItems { list -> list.filterNot(predicate) }
    }

    companion object {
        /** 连续零新条目页的追载上限，防止异常数据导致无限翻页。 */
        private const val MAX_EMPTY_PAGE_HOPS = 5
    }
}

/** [FeedViewModel.updateItems] 的 reified 便捷版：`viewModel.updateItems<IllustFeedItem> { ... }`。 */
inline fun <reified T : FeedItem> FeedViewModel<*>.updateItems(noinline transform: (T) -> T) {
    updateItems(T::class.java, transform)
}

/**
 * 在 Fragment 作用域内创建 / 复用 [FeedViewModel]，用法对齐 `by viewModels()`：
 *
 * ```
 * override val feedViewModel by feedViewModels {
 *     PixivFeedSource({ Client.appApi.getWalkthroughWorks() }) { resp, _ -> ... }
 * }
 * ```
 *
 * 同一个 Fragment 需要多个列表 VM 时用 [key] 区分。
 */
fun <Cursor : Any> Fragment.feedViewModels(
    key: String? = null,
    sourceProvider: () -> FeedSource<Cursor>,
): Lazy<FeedViewModel<Cursor>> = lazy(LazyThreadSafetyMode.NONE) {
    val factory = viewModelFactory {
        initializer { FeedViewModel(sourceProvider()) }
    }
    @Suppress("UNCHECKED_CAST")
    ViewModelProvider(this, factory)
        .get(key ?: FeedViewModel::class.java.name, FeedViewModel::class.java) as FeedViewModel<Cursor>
}
