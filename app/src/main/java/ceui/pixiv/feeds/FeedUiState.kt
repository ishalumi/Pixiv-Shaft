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
