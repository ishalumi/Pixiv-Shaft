package ceui.pixiv.feeds

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private data class Row(val id: Int, val text: String = "") : FeedItem {
        override val feedKey: Any get() = id
    }

    /** 页码游标的假数据源：pages[cursor] 为一页，翻到最后一页 nextCursor 为 null。 */
    private class FakeSource(
        private val pages: List<List<Row>>,
        var failOn: Int? = null,
    ) : FeedSource<Int> {
        var loadCount = 0

        override suspend fun load(cursor: Int?): FeedPage<Int> {
            loadCount++
            val index = cursor ?: 0
            if (failOn == index) throw RuntimeException("boom at page $index")
            val next = (index + 1).takeIf { it < pages.size }
            return FeedPage(pages[index], next)
        }
    }

    @Test
    fun `refresh loads first page`() = runTest(dispatcher) {
        val vm = FeedViewModel(FakeSource(listOf(listOf(Row(1), Row(2)), listOf(Row(3)))))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf(Row(1), Row(2)), state.items)
        assertEquals(LoadState.Idle, state.refresh)
        assertTrue(state.hasLoadedOnce)
        assertFalse(state.reachedEnd)
    }

    @Test
    fun `loadMore appends dedups and reaches end`() = runTest(dispatcher) {
        // 第二页和首页有重叠（翻页窗口漂移），Row(2) 不应重复出现
        val vm = FeedViewModel(FakeSource(listOf(listOf(Row(1), Row(2)), listOf(Row(2), Row(3)))))
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf(Row(1), Row(2), Row(3)), state.items)
        assertTrue(state.reachedEnd)

        // 到底之后 loadMore 是 no-op
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(3, vm.uiState.value.items.size)
    }

    @Test
    fun `append error stops auto load until retry`() = runTest(dispatcher) {
        val source = FakeSource(listOf(listOf(Row(1)), listOf(Row(2))), failOn = 1)
        val vm = FeedViewModel(source)
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.append is LoadState.Error)

        // 出错后滚动继续触发 loadMore 不应发请求
        val countAfterError = source.loadCount
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(countAfterError, source.loadCount)

        // 用户点重试后恢复
        source.failOn = null
        vm.retryAppend()
        advanceUntilIdle()
        assertEquals(listOf(Row(1), Row(2)), vm.uiState.value.items)
        assertEquals(LoadState.Idle, vm.uiState.value.append)
    }

    @Test
    fun `first load error then retry recovers`() = runTest(dispatcher) {
        val source = FakeSource(listOf(listOf(Row(1))), failOn = 0)
        val vm = FeedViewModel(source)
        advanceUntilIdle()

        val errored = vm.uiState.value
        assertTrue(errored.refresh is LoadState.Error)
        assertTrue(errored.showFullscreenError)
        assertFalse(errored.hasLoadedOnce)

        source.failOn = null
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf(Row(1)), vm.uiState.value.items)
    }

    @Test
    fun `refresh wins over in-flight append`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val slowSecondPage = object : FeedSource<Int> {
            override suspend fun load(cursor: Int?): FeedPage<Int> {
                if (cursor == null) return FeedPage(listOf(Row(1)), 1)
                gate.await() // 第二页挂起，模拟慢请求
                return FeedPage(listOf(Row(99)), null)
            }
        }
        val vm = FeedViewModel(slowSecondPage)
        advanceUntilIdle()

        vm.loadMore() // 挂在 gate 上
        dispatcher.scheduler.runCurrent()
        vm.refresh() // 应取消 append
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf(Row(1)), state.items) // Row(99) 被取消，没进列表
        assertEquals(LoadState.Idle, state.append)
    }

    @Test
    fun `loadMore chases past pages with zero new items`() = runTest(dispatcher) {
        // 页1 后跟一整页重复（服务端翻页窗口漂移/整页被过滤的形态），再跟真正的新页
        val source = FakeSource(
            listOf(
                listOf(Row(1), Row(2)),
                listOf(Row(1), Row(2)),
                listOf(Row(3)),
            )
        )
        val vm = FeedViewModel(source)
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf(Row(1), Row(2), Row(3)), state.items)
        assertTrue(state.reachedEnd)
        assertEquals(LoadState.Idle, state.append)
    }

    @Test
    fun `loadMore gives up after hop cap and marks end`() = runTest(dispatcher) {
        // 首页之后全是重复页且游标不断，追载必须有限步内收敛
        val source = FakeSource(List(12) { listOf(Row(1)) })
        val vm = FeedViewModel(source)
        advanceUntilIdle()
        val countAfterRefresh = source.loadCount

        vm.loadMore()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf(Row(1)), state.items)
        assertTrue(state.reachedEnd)
        assertEquals(LoadState.Idle, state.append)
        // 追载止步于上限（5 跳），不会翻完全部 12 页
        assertEquals(countAfterRefresh + 5, source.loadCount)
    }

    @Test
    fun `refresh chases past fully-filtered first pages`() = runTest(dispatcher) {
        // 首页整页被 mapper 滤空（#729 场景），refresh 必须继续向后翻到有内容
        val vm = FeedViewModel(
            FakeSource(listOf(emptyList(), emptyList(), listOf(Row(1))))
        )
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf(Row(1)), state.items)
        assertTrue(state.reachedEnd)
        assertTrue(state.hasLoadedOnce)
        assertEquals(LoadState.Idle, state.refresh)
    }

    @Test
    fun `refresh marks end when every page is empty within hop cap`() = runTest(dispatcher) {
        val source = FakeSource(List(12) { emptyList<Row>() })
        val vm = FeedViewModel(source)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.items.isEmpty())
        assertTrue(state.reachedEnd)
        assertTrue(state.showEmptyState)
        // 首页 + 5 跳追载后收手
        assertEquals(6, source.loadCount)
    }

    @Test
    fun `adoptCursor cancels in-flight append so stale cursor cannot clobber handover`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val source = object : FeedSource<Int> {
            override suspend fun load(cursor: Int?): FeedPage<Int> {
                if (cursor == null) return FeedPage(listOf(Row(1)), 1)
                if (cursor == 1) {
                    gate.await() // 旧游标的追加挂起在网络上
                    return FeedPage(listOf(Row(2)), 2)
                }
                return FeedPage(listOf(Row(99)), null)
            }
        }
        val vm = FeedViewModel(source)
        advanceUntilIdle()

        vm.loadMore() // 基于游标 1，挂起
        dispatcher.scheduler.runCurrent()
        vm.adoptCursor(5) // 详情页已替列表翻到游标 5
        gate.complete(Unit) // 旧追加此刻恢复，必须已被取消
        advanceUntilIdle()

        assertEquals(LoadState.Idle, vm.uiState.value.append)
        assertEquals(listOf(Row(1)), vm.uiState.value.items) // Row(2) 未混入

        vm.loadMore() // 应从交接来的游标 5 继续
        advanceUntilIdle()
        assertEquals(listOf(Row(1), Row(99)), vm.uiState.value.items)
        assertTrue(vm.uiState.value.reachedEnd)
    }

    @Test
    fun `adoptCursor hands over external pagination progress`() = runTest(dispatcher) {
        val vm = FeedViewModel(FakeSource(listOf(listOf(Row(1)), listOf(Row(2)), listOf(Row(3)))))
        advanceUntilIdle()

        // 外部组件（详情页 pager）已经替列表翻掉了第 2 页
        vm.adoptCursor(2)
        vm.loadMore()
        advanceUntilIdle()

        assertEquals(listOf(Row(1), Row(3)), vm.uiState.value.items)
        assertTrue(vm.uiState.value.reachedEnd)
    }

    @Test
    fun `appendItems dedups against list and within batch without touching source`() = runTest(dispatcher) {
        val source = FakeSource(listOf(listOf(Row(1), Row(2))))
        val vm = FeedViewModel(source)
        advanceUntilIdle()
        val countAfterRefresh = source.loadCount

        // Row(2) 与列表重复、Row(3) 在入参内部重复，都只保留一份；且不发任何网络请求
        vm.appendItems(listOf(Row(2), Row(3), Row(3), Row(4)))

        assertEquals(listOf(Row(1), Row(2), Row(3), Row(4)), vm.uiState.value.items)
        assertEquals(countAfterRefresh, source.loadCount)

        // 全部重复 / 空入参都是 no-op
        vm.appendItems(listOf(Row(1), Row(4)))
        vm.appendItems(emptyList())
        assertEquals(4, vm.uiState.value.items.size)
    }

    @Test
    fun `loadMore with zero new items keeps the items list instance`() = runTest(dispatcher) {
        // 整页重复时不该造等价新列表：下游按 identity 跳过的扫描（合池等）依赖实例不变
        val vm = FeedViewModel(FakeSource(listOf(listOf(Row(1)), listOf(Row(1)))))
        advanceUntilIdle()
        val before = vm.uiState.value.items

        vm.loadMore()
        advanceUntilIdle()

        assertSame(before, vm.uiState.value.items)
        assertTrue(vm.uiState.value.reachedEnd)
    }

    @Test
    fun `shouldNotifyRefreshError fires once per error instance`() = runTest(dispatcher) {
        val vm = FeedViewModel(FakeSource(listOf(listOf(Row(1)))))
        advanceUntilIdle()

        val error = LoadState.Error(RuntimeException("boom"))
        assertTrue(vm.shouldNotifyRefreshError(error))
        // 视图重建（旋转）后重新 render 同一份状态，不该重复提示
        assertFalse(vm.shouldNotifyRefreshError(error))
        // 新一次失败是新 Error 实例，恢复提示
        assertTrue(vm.shouldNotifyRefreshError(LoadState.Error(RuntimeException("boom2"))))
    }

    @Test
    fun `updateItems mutates in place`() = runTest(dispatcher) {
        val vm = FeedViewModel(FakeSource(listOf(listOf(Row(1, "a"), Row(2, "b")))))
        advanceUntilIdle()

        vm.updateItems(Row::class.java) { row ->
            if (row.id == 2) row.copy(text = "liked") else row
        }
        assertEquals(listOf(Row(1, "a"), Row(2, "liked")), vm.uiState.value.items)

        vm.removeItems { it.feedKey == 1 }
        assertEquals(listOf(Row(2, "liked")), vm.uiState.value.items)
    }
}
