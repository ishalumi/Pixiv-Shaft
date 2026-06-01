package ceui.lisa.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #729：屏蔽过多导致无作品显示。
 *
 * 机制：Mapper.apply() 在响应到达 NetListFragment 之前就把已屏蔽的作品从每页响应里删掉。
 * 屏蔽的 tag/画师/作品足够多时，搜索结果第一页（30 条）可能被整页过滤光 →
 * fresh() 隐藏 RecyclerView 显示"暂无数据" → 列表上没有条目就无法滑动 →
 * BaseAdapter.checkPreload()（要求非 IDLE 滑动状态）永远不触发 → loadMore() 永远不被调用 →
 * 即使 nextUrl 表明后面还有内容，用户也被卡死。
 *
 * 修复：每页加载成功后（NetListFragment 的 must(true) 回调里）咨询 [AutoLoadPolicy]，
 * 列表仍为空且还有下一页时自动追加加载，连续 [AutoLoadPolicy.MAX_AUTO_LOAD_TIMES] 次为上限。
 *
 * NetListFragment 本身耦合 Android Fragment / SmartRefreshLayout / RxJava，没法纯 JVM 跑。
 * 这里把它的 fresh()/loadMore()/must() 控制流原样搬成 [FakeNetList] 仿真器，
 * **被测对象是真实的生产类 [AutoLoadPolicy]**，仿真器只负责复刻调用时序：
 *
 *   fresh():    reset() → 请求第一页 → Mapper 过滤 → must(true) → shouldAutoLoad?
 *   loadMore(): 请求下一页 → Mapper 过滤 → must(true) → shouldAutoLoad? （递归链）
 */
class AutoLoadPolicyTest {

    // ---------- 仿真数据结构 ----------

    /** 一页响应：[total] 条作品里有 [blocked] 条会被 Mapper 过滤掉 */
    private data class Page(val total: Int, val blocked: Int) {
        val visibleAfterFilter: Int get() = total - blocked
    }

    /**
     * 复刻 NetListFragment 的控制流（fresh / loadMore / must），
     * 驱动真实的 AutoLoadPolicy。
     */
    private class FakeNetList(
        private val pages: List<Page>,
        private val policy: AutoLoadPolicy = AutoLoadPolicy(),
    ) {
        /** 过滤后实际显示在列表上的条目数（= NetListFragment.getCount()） */
        var visibleCount = 0
            private set

        /** 实际发出的网络请求数（防止修复方案无限请求 / 请求过多） */
        var requestCount = 0
            private set

        private var nextPageIndex = 0

        /** = mRemoteRepo.getNextUrl()：还有下一页则非空 */
        private fun nextUrl(): String =
            if (nextPageIndex < pages.size) "https://app-api.pixiv.net/v1/search/illust?offset=${nextPageIndex * 30}" else ""

        /** 复刻 NetListFragment.fresh()：清空列表、重置预算、请求第一页 */
        fun fresh() {
            visibleCount = 0
            policy.reset()
            loadOnePage()
            mustSuccess()
        }

        /** 复刻 NetListFragment.loadMore()：nextUrl 非空才请求 */
        fun loadMore() {
            if (nextUrl().isEmpty()) return
            loadOnePage()
            mustSuccess()
        }

        /** 请求一页 + Mapper.apply() 过滤 */
        private fun loadOnePage() {
            requestCount++
            val page = pages[nextPageIndex]
            nextPageIndex++
            visibleCount += page.visibleAfterFilter
        }

        /** 复刻 must(true)：加载成功后咨询 policy，决定是否自动追加加载 */
        private fun mustSuccess() {
            if (policy.shouldAutoLoad(visibleCount, nextUrl())) {
                loadMore()
            }
        }
    }

    // ---------- Issue #729 核心场景 ----------

    /**
     * 用户场景：搜索热门词，但屏蔽了霸榜的画师/tag → 前两页 30 条全被过滤光，
     * 第三页有 12 条幸存。
     *
     * 修复前：fresh() 之后列表为空，显示"暂无数据"，用户卡死。
     * 修复后：自动连续加载到第三页，用户直接看到 12 条作品。
     */
    @Test
    fun `issue 729 - first pages fully blocked, auto loads until content appears`() {
        val list = FakeNetList(
            listOf(
                Page(total = 30, blocked = 30),  // 第一页全被屏蔽
                Page(total = 30, blocked = 30),  // 第二页全被屏蔽
                Page(total = 30, blocked = 18),  // 第三页幸存 12 条
            )
        )

        list.fresh()

        assertEquals("用户应该直接看到第三页幸存的 12 条作品", 12, list.visibleCount)
        assertEquals("应该正好请求了 3 页（1 次 fresh + 2 次自动加载）", 3, list.requestCount)
    }

    /**
     * 正常用户（没屏蔽什么东西）：第一页就有内容 → 完全不触发自动加载，
     * 行为和修复前一模一样，零额外请求。
     */
    @Test
    fun `normal case - first page has content, no auto load at all`() {
        val list = FakeNetList(
            listOf(
                Page(total = 30, blocked = 0),
                Page(total = 30, blocked = 0),
            )
        )

        list.fresh()

        assertEquals(30, list.visibleCount)
        assertEquals("第一页有内容就不该多发任何请求", 1, list.requestCount)
    }

    /**
     * 真·搜索无结果：第一页就是空的且没有 nextUrl →
     * 不能自动加载（没有下一页可加载），保持显示"暂无数据"。
     */
    @Test
    fun `genuinely empty result - no nextUrl, no auto load`() {
        val list = FakeNetList(
            listOf(
                Page(total = 0, blocked = 0),  // 唯一一页，空的，之后没有 nextUrl
            )
        )

        list.fresh()

        assertEquals(0, list.visibleCount)
        assertEquals("没有下一页时不该发起额外请求", 1, list.requestCount)
    }

    /**
     * 极端场景：用户屏蔽得太狠，连续 100 页全被过滤光 →
     * 自动加载必须在 MAX_AUTO_LOAD_TIMES 次后停下来，
     * 不能无限请求把 Pixiv API 打挂（也耗用户流量）。
     */
    @Test
    fun `all pages blocked - stops at MAX_AUTO_LOAD_TIMES, never hammers the API`() {
        val list = FakeNetList(
            List(100) { Page(total = 30, blocked = 30) }  // 100 页全军覆没
        )

        list.fresh()

        assertEquals(0, list.visibleCount)
        assertEquals(
            "1 次 fresh + 最多 ${AutoLoadPolicy.MAX_AUTO_LOAD_TIMES} 次自动加载",
            1 + AutoLoadPolicy.MAX_AUTO_LOAD_TIMES,
            list.requestCount
        )
    }

    /**
     * 部分屏蔽但有幸存者：第一页 30 条只剩 1 条 →
     * 有内容显示就不自动加载（剩下的交给正常的滑动预加载），
     * policy 只负责救"完全为空"这种卡死场景。
     */
    @Test
    fun `partially blocked page with survivors - normal scroll takes over, no auto load`() {
        val list = FakeNetList(
            listOf(
                Page(total = 30, blocked = 29),  // 幸存 1 条
                Page(total = 30, blocked = 0),
            )
        )

        list.fresh()

        assertEquals(1, list.visibleCount)
        assertEquals("有 1 条可见就不该自动加载", 1, list.requestCount)
    }

    /**
     * 预算按"刷新"为单位：第一轮把 MAX 次预算用光后，
     * 用户下拉刷新（fresh）→ 预算重置，可以再次自动加载。
     */
    @Test
    fun `budget resets on every fresh - second refresh can auto load again`() {
        val policy = AutoLoadPolicy()

        // 第一轮：手动把预算耗光
        repeat(AutoLoadPolicy.MAX_AUTO_LOAD_TIMES) {
            assertTrue(policy.shouldAutoLoad(0, "next-url"))
        }
        assertFalse("预算用光后不该再自动加载", policy.shouldAutoLoad(0, "next-url"))

        // 用户下拉刷新 → fresh() 调用 reset()
        policy.reset()

        assertTrue("刷新后预算应该恢复", policy.shouldAutoLoad(0, "next-url"))
    }

    // ---------- AutoLoadPolicy 单点行为 ----------

    @Test
    fun `policy - visible items present means no auto load and no budget consumed`() {
        val policy = AutoLoadPolicy()

        assertFalse(policy.shouldAutoLoad(1, "next-url"))
        assertFalse(policy.shouldAutoLoad(30, "next-url"))
        assertEquals("没触发自动加载就不该消耗预算", 0, policy.autoLoadCount)
    }

    @Test
    fun `policy - null or empty nextUrl means no auto load`() {
        val policy = AutoLoadPolicy()

        assertFalse(policy.shouldAutoLoad(0, null))
        assertFalse(policy.shouldAutoLoad(0, ""))
        assertEquals(0, policy.autoLoadCount)
    }

    @Test
    fun `policy - budget consumed only when auto load actually triggers`() {
        val policy = AutoLoadPolicy()

        assertTrue(policy.shouldAutoLoad(0, "next-url"))
        assertEquals(1, policy.autoLoadCount)

        // 中间穿插不触发的调用，预算不动
        assertFalse(policy.shouldAutoLoad(5, "next-url"))
        assertFalse(policy.shouldAutoLoad(0, ""))
        assertEquals(1, policy.autoLoadCount)

        assertTrue(policy.shouldAutoLoad(0, "next-url"))
        assertEquals(2, policy.autoLoadCount)
    }
}
