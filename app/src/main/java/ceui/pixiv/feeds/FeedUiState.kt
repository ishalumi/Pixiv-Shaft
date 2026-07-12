package ceui.pixiv.feeds

/**
 * 整个列表页面的单一不可变状态（unidirectional data flow）。
 *
 * ViewModel 只发射这个对象，Fragment 是纯渲染函数：任何 UI 结论
 * （全屏 loading / 全屏错误 / 空态 / 底部追加条）都是它的派生值，
 * 不允许 View 层自己攒状态。
 */
data class FeedUiState(
    /** 当前已加载的全部条目（不含框架自动追加的 footer）。 */
    val items: List<FeedItem> = emptyList(),
    /** 首屏加载 / 下拉刷新的状态。 */
    val refresh: LoadState = LoadState.Idle,
    /** 向后翻页的状态。 */
    val append: LoadState = LoadState.Idle,
    /** 是否已翻到底（没有下一页游标）。 */
    val reachedEnd: Boolean = false,
    /** 是否成功完成过至少一次刷新，用于区分「首屏加载」和「后续刷新」。 */
    val hasLoadedOnce: Boolean = false,
    /**
     * 当前展示的是磁盘缓存的旧首屏、且网络刷新仍在进行（本地优先）。
     * 刷新结束即转 false：成功=已被新数据替换；失败=不再是「更新中」（内容仍在，靠
     * `refresh is Error && items 非空` 表达「显示旧数据 + 刷新失败」）。
     * 默认无额外视觉（render 靠 hasLoadedOnce + refresh=Loading 已得到「内容 + 顶部刷新圈」），
     * 想显式加「更新中」胶囊的页面直接绑它即可（严格等价「显示缓存 ∧ 刷新中」，不会误亮）。
     */
    val showingCache: Boolean = false,
    /**
     * 结构版本号：items 发生「非纯追加」变化（refresh 整代替换、[FeedViewModel.mutateItems]
     * 的默认结构性编辑）时自增；[FeedViewModel.loadMore] / [FeedViewModel.appendItems] 的纯尾部
     * 追加不推进它——那两条路径保证旧前缀元素引用不变（`existing + fresh`）。
     *
     * 增量消费方（如 IllustFeedPoolSync）借此判断：版本没变 + 列表变长，说明只是追加，
     * 只需扫描新增的尾部；版本变了就必须假设任意位置的条目实例都可能换了，全量重扫。
     */
    val structureVersion: Int = 0,
) {

    /** 首屏还没出过数据时的全屏加载态。 */
    val showFullscreenLoading: Boolean
        get() = refresh is LoadState.Loading && !hasLoadedOnce

    /** 页面上没有任何内容可以展示时的全屏错误态（点击整体重试）。 */
    val showFullscreenError: Boolean
        get() = refresh is LoadState.Error && items.isEmpty()

    /** 成功加载过、但确实没有内容的空态。 */
    val showEmptyState: Boolean
        get() = refresh is LoadState.Idle && hasLoadedOnce && items.isEmpty()
}
