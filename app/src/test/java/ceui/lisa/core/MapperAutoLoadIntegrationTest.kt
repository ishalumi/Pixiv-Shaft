package ceui.lisa.core

import ceui.lisa.http.NullCtrl
import ceui.lisa.model.ListIllust
import ceui.lisa.models.IllustsBean
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #729 集成测试：真实 Mapper 过滤 → 列表为空 → AutoLoadPolicy 自动翻页。
 *
 * 链路上能在纯 JVM 跑的真实生产类全部用真的：
 *   - [Mapper] 子类 [FirstPageIdMapper]：第一页只要 id > 0 就过滤（真实作品 id 永远 > 0，
 *     等价于第一页全军覆没），后续页原样放行
 *   - [ListIllust] / [IllustsBean]：真实响应模型
 *   - [NullCtrl]：真实回调链（next → success → must）
 *   - [AutoLoadPolicy]：被测的自动翻页策略
 *
 * 不能用真的的部分（以及为什么）：
 *   - RemoteRepo：BaseRepo 构造器调 Common.showLog → android.util.Log，JVM 上没 mock 会直接抛。
 *     所以 [FakePagedRepo] 把它 getFirstData/getNextData 的 RxJava 链
 *     （subscribeOn → map(mapper) → observeOn → subscribe）原样搬下来，
 *     线程调度换成 trampoline 让测试同步确定性执行。Mapper 仍然通过 .map() 生效，跟生产一致。
 *   - NetListFragment：Android Fragment，[FakeNetListFragment] 复刻它 fresh()/loadMore()/must()
 *     的控制流和 RecyclerView/暂无数据 的可见性切换。
 */
class MapperAutoLoadIntegrationTest {

    // ---------- 用户要求的过滤器：第一页 id > 0 全部过滤 ----------

    /**
     * 真实 [Mapper] 的子类。规则：第一页里只要 id > 0 就过滤掉，第二页起原样放行。
     *
     * 不调 super.apply()：父类的过滤规则要查 Room 屏蔽数据库（IllustNovelFilter），
     * JVM 上跑不了；这里复刻它"挑出要过滤的项 → 从 t.getList() 里 removeAll"的行为，
     * 只是把"已屏蔽"的判断换成"id > 0"。
     */
    private class FirstPageIdMapper : Mapper<ListIllust>() {
        var appliedPages = 0
            private set

        override fun apply(t: ListIllust): ListIllust {
            appliedPages++
            if (appliedPages == 1) {
                val dash = t.list.filter { it.id > 0 }
                t.list.removeAll(dash)
            }
            return t
        }
    }

    // ---------- 复刻 RemoteRepo 的 RxJava 链 ----------

    /**
     * 复刻 RemoteRepo.getFirstData/getNextData：
     * Observable → subscribeOn → map(mapper) → observeOn → subscribe(NullCtrl)。
     * 数据源换成内存分页，调度器换成 trampoline（同步执行）。
     */
    private class FakePagedRepo(
        private val pages: List<ListIllust>,
        val mapper: Mapper<ListIllust>,
    ) {
        var nextUrl: String = ""
        var requestCount = 0
            private set

        private var pageIndex = 0

        fun getFirstData(ctrl: NullCtrl<ListIllust>) {
            pageIndex = 0
            request(ctrl)
        }

        fun getNextData(ctrl: NullCtrl<ListIllust>) {
            request(ctrl)
        }

        private fun request(ctrl: NullCtrl<ListIllust>) {
            requestCount++
            val page = pages[pageIndex]
            pageIndex++
            Observable.just(page)
                .subscribeOn(Schedulers.trampoline()) // 生产代码是 Schedulers.newThread()
                .map(mapper)                          // ← 真实 Mapper 过滤发生在这里，跟 RemoteRepo 一致
                .observeOn(Schedulers.trampoline())   // 生产代码是 AndroidSchedulers.mainThread()
                .subscribe(ctrl)
        }
    }

    // ---------- 复刻 NetListFragment 的控制流 ----------

    /**
     * 复刻 NetListFragment：fresh()/loadMore()/must() 的控制流、
     * RecyclerView 和"暂无数据"的可见性切换、以及修复加上的 autoLoadIfAllBlocked()。
     *
     * [enableAutoLoadFix] = false 即修复前的行为，作为对照组。
     */
    private class FakeNetListFragment(
        private val repo: FakePagedRepo,
        private val enableAutoLoadFix: Boolean,
        val policy: AutoLoadPolicy = AutoLoadPolicy(),
    ) {
        /** = NetListFragment.allItems（过滤后实际显示的作品） */
        val allItems = mutableListOf<IllustsBean>()

        /** = mRecyclerView / emptyRela 的可见性 */
        var recyclerViewVisible = true
            private set
        var showingEmptyHint = false
            private set

        private var isLoading = false

        /** 复刻 NetListFragment.fresh() */
        fun fresh() {
            if (isLoading) return
            isLoading = true
            allItems.clear()
            policy.reset()
            repo.getFirstData(object : NullCtrl<ListIllust>() {
                override fun success(response: ListIllust) {
                    if (!response.list.isNullOrEmpty()) {
                        allItems.addAll(response.list)
                        recyclerViewVisible = true
                        showingEmptyHint = false
                    } else {
                        // 整页被过滤光：隐藏列表、显示"暂无数据"
                        recyclerViewVisible = false
                        showingEmptyHint = true
                    }
                    repo.nextUrl = response.nextUrl ?: ""
                }

                override fun must(isSuccess: Boolean) {
                    isLoading = false
                    if (enableAutoLoadFix && isSuccess) {
                        autoLoadIfAllBlocked()
                    }
                }
            })
        }

        /** 复刻 NetListFragment.loadMore() */
        fun loadMore() {
            if (repo.nextUrl.isEmpty()) return
            if (isLoading) return
            isLoading = true
            repo.getNextData(object : NullCtrl<ListIllust>() {
                override fun success(response: ListIllust) {
                    if (!response.list.isNullOrEmpty()) {
                        allItems.addAll(response.list)
                        // 复刻修复：自动加载找回内容后恢复列表显示 (#729)
                        if (!recyclerViewVisible) {
                            recyclerViewVisible = true
                            showingEmptyHint = false
                        }
                    }
                    repo.nextUrl = response.nextUrl ?: ""
                }

                override fun must(isSuccess: Boolean) {
                    isLoading = false
                    if (enableAutoLoadFix && isSuccess) {
                        autoLoadIfAllBlocked()
                    }
                }
            })
        }

        /** 复刻 NetListFragment.autoLoadIfAllBlocked() */
        private fun autoLoadIfAllBlocked() {
            if (policy.shouldAutoLoad(allItems.size, repo.nextUrl)) {
                loadMore()
            }
        }
    }

    // ---------- 测试数据 ----------

    /** 造一页响应：ids 指定每条作品的 id，nextUrl 指定下一页地址 */
    private fun page(ids: IntRange, nextUrl: String?): ListIllust {
        return ListIllust().apply {
            illusts = ids.map { id -> IllustsBean().apply { setId(id) } }.toMutableList()
            next_url = nextUrl
        }
    }

    // ---------- 测试 ----------

    /**
     * 用户验证场景：第一页 30 条作品 id 全部 > 0 → 被 FirstPageIdMapper 整页过滤 →
     * AutoLoadPolicy 应该自动翻到第二页，第二页的 30 条作品直接显示出来。
     */
    @Test
    fun `first page fully filtered by Mapper - AutoLoadPolicy auto turns to page 2`() {
        val mapper = FirstPageIdMapper()
        val repo = FakePagedRepo(
            pages = listOf(
                page(ids = 1..30, nextUrl = "https://next/page2"),   // 第一页：id 全 > 0，会被全过滤
                page(ids = 31..60, nextUrl = null),                   // 第二页：原样放行
            ),
            mapper = mapper,
        )
        val fragment = FakeNetListFragment(repo, enableAutoLoadFix = true)

        fragment.fresh()

        // Mapper 确实对两页都跑过（第一页过滤、第二页放行）
        assertEquals("Mapper 应该处理了 2 页响应", 2, mapper.appliedPages)
        // 自动翻页发生了：一共 2 次请求（1 次 fresh + 1 次自动 loadMore）
        assertEquals(2, repo.requestCount)
        assertEquals("policy 应该消耗了 1 次自动加载预算", 1, fragment.policy.autoLoadCount)
        // 用户最终看到第二页的 30 条作品
        assertEquals(30, fragment.allItems.size)
        assertEquals((31..60).toList(), fragment.allItems.map { it.id })
        // 列表可见，"暂无数据"被隐藏
        assertTrue(fragment.recyclerViewVisible)
        assertFalse(fragment.showingEmptyHint)
    }

    /**
     * 对照组（修复前的行为）：同样的数据、同样的 Mapper，但 must() 里不接 AutoLoadPolicy →
     * 用户卡死在"暂无数据"，第二页明明有内容却永远不会被请求。
     *
     * 这条测试钉死 issue #729 的症状本身：如果它失败了（比如 NetListFragment 以后
     * 换了别的方式修复），说明对照前提变了，上面那条测试也要跟着重新审视。
     */
    @Test
    fun `control group without the fix - user is stuck on empty page forever`() {
        val repo = FakePagedRepo(
            pages = listOf(
                page(ids = 1..30, nextUrl = "https://next/page2"),
                page(ids = 31..60, nextUrl = null),
            ),
            mapper = FirstPageIdMapper(),
        )
        val fragment = FakeNetListFragment(repo, enableAutoLoadFix = false)

        fragment.fresh()

        // 修复前：只发了 1 次请求，第二页永远不会被加载
        assertEquals(1, repo.requestCount)
        assertEquals(0, fragment.allItems.size)
        // 卡死在"暂无数据"
        assertFalse(fragment.recyclerViewVisible)
        assertTrue(fragment.showingEmptyHint)
    }

    /**
     * 第一页被全过滤但确实没有下一页：不该自动翻页，正常显示"暂无数据"。
     */
    @Test
    fun `first page fully filtered but no next page - shows empty hint, no extra request`() {
        val repo = FakePagedRepo(
            pages = listOf(
                page(ids = 1..30, nextUrl = null),  // 全过滤且没有下一页
            ),
            mapper = FirstPageIdMapper(),
        )
        val fragment = FakeNetListFragment(repo, enableAutoLoadFix = true)

        fragment.fresh()

        assertEquals(1, repo.requestCount)
        assertEquals(0, fragment.allItems.size)
        assertTrue(fragment.showingEmptyHint)
    }

    // ---------- 回归测试：筛选有结果 / 正常翻页，行为必须和修复前完全一致 ----------

    /** 部分过滤的 Mapper：每页过滤掉 id 为偶数的作品，留下奇数 id（每页都有幸存者） */
    private class EvenIdMapper : Mapper<ListIllust>() {
        override fun apply(t: ListIllust): ListIllust {
            t.list.removeAll(t.list.filter { it.id % 2 == 0 })
            return t
        }
    }

    /**
     * 筛选有结果（部分过滤）：第一页 30 条过滤后剩 15 条 →
     * 正常显示这 15 条，不自动翻页、不多发请求、不消耗自动加载预算。
     */
    @Test
    fun `partial filter leaves results - shows them, no auto load, no budget consumed`() {
        val repo = FakePagedRepo(
            pages = listOf(
                page(ids = 1..30, nextUrl = "https://next/page2"),
                page(ids = 31..60, nextUrl = null),
            ),
            mapper = EvenIdMapper(),
        )
        val fragment = FakeNetListFragment(repo, enableAutoLoadFix = true)

        fragment.fresh()

        // 只发 1 次请求，显示第一页过滤后的 15 条奇数 id
        assertEquals(1, repo.requestCount)
        assertEquals((1..30 step 2).toList(), fragment.allItems.map { it.id })
        assertTrue(fragment.recyclerViewVisible)
        assertFalse(fragment.showingEmptyHint)
        // 自动加载预算一点没动
        assertEquals(0, fragment.policy.autoLoadCount)
    }

    /**
     * 正常翻页全流程：第一页有结果 → 用户滑动触发 loadMore → 第二页（部分过滤）正常追加 →
     * 翻到最后一页后再 loadMore 不发请求。
     *
     * 每一步都只发对应的 1 次请求，数据顺序正确，预算始终为 0。
     */
    @Test
    fun `normal pagination - manual loadMore appends pages, last page stops cleanly`() {
        val repo = FakePagedRepo(
            pages = listOf(
                page(ids = 1..30, nextUrl = "https://next/page2"),
                page(ids = 31..60, nextUrl = "https://next/page3"),
                page(ids = 61..90, nextUrl = null),  // 最后一页
            ),
            mapper = EvenIdMapper(),
        )
        val fragment = FakeNetListFragment(repo, enableAutoLoadFix = true)

        // 第一页
        fragment.fresh()
        assertEquals(1, repo.requestCount)
        assertEquals(15, fragment.allItems.size)

        // 用户滑动翻第二页
        fragment.loadMore()
        assertEquals(2, repo.requestCount)
        assertEquals(30, fragment.allItems.size)
        // 顺序正确：第一页的奇数 id 在前，第二页的奇数 id 在后
        assertEquals((1..60 step 2).toList(), fragment.allItems.map { it.id })

        // 翻到最后一页
        fragment.loadMore()
        assertEquals(3, repo.requestCount)
        assertEquals(45, fragment.allItems.size)

        // 已经没有下一页了，再 loadMore 不发请求
        fragment.loadMore()
        assertEquals(3, repo.requestCount)

        // 全程不消耗自动加载预算，列表始终可见
        assertEquals(0, fragment.policy.autoLoadCount)
        assertTrue(fragment.recyclerViewVisible)
        assertFalse(fragment.showingEmptyHint)
    }

    /**
     * 正常翻页之后下拉刷新：回到第一页重新开始，预算重置，行为和首次进入一致。
     */
    @Test
    fun `normal pagination then refresh - starts over from page 1`() {
        val repo = FakePagedRepo(
            pages = listOf(
                page(ids = 1..30, nextUrl = "https://next/page2"),
                page(ids = 31..60, nextUrl = null),
            ),
            mapper = EvenIdMapper(),
        )
        val fragment = FakeNetListFragment(repo, enableAutoLoadFix = true)

        // 翻完两页
        fragment.fresh()
        fragment.loadMore()
        assertEquals(30, fragment.allItems.size)
        assertEquals(2, repo.requestCount)

        // 下拉刷新：清空重来，只显示第一页
        fragment.fresh()
        assertEquals(3, repo.requestCount)
        assertEquals((1..30 step 2).toList(), fragment.allItems.map { it.id })
        assertEquals(0, fragment.policy.autoLoadCount)
    }

    /**
     * 完全不过滤（没屏蔽任何东西的用户）：跟修复前的行为完全一致 —
     * 真实 Mapper 父类行为里"没有命中任何屏蔽规则"等价于原样放行。
     */
    @Test
    fun `no filtering at all - identical to pre-fix behavior`() {
        // 原样放行的 Mapper（什么都不过滤）
        val passThroughMapper = object : Mapper<ListIllust>() {
            override fun apply(t: ListIllust): ListIllust = t
        }
        val repo = FakePagedRepo(
            pages = listOf(
                page(ids = 1..30, nextUrl = "https://next/page2"),
                page(ids = 31..60, nextUrl = null),
            ),
            mapper = passThroughMapper,
        )
        val fixedFragment = FakeNetListFragment(repo, enableAutoLoadFix = true)

        fixedFragment.fresh()
        fixedFragment.loadMore()

        // 对照组：同样数据、关掉修复
        val controlRepo = FakePagedRepo(
            pages = listOf(
                page(ids = 1..30, nextUrl = "https://next/page2"),
                page(ids = 31..60, nextUrl = null),
            ),
            mapper = object : Mapper<ListIllust>() {
                override fun apply(t: ListIllust): ListIllust = t
            },
        )
        val controlFragment = FakeNetListFragment(controlRepo, enableAutoLoadFix = false)
        controlFragment.fresh()
        controlFragment.loadMore()

        // 修复前后：请求次数、可见条目、顺序完全一致
        assertEquals(controlRepo.requestCount, repo.requestCount)
        assertEquals(controlFragment.allItems.map { it.id }, fixedFragment.allItems.map { it.id })
        assertEquals(60, fixedFragment.allItems.size)
    }

    /**
     * 连续每一页都被全过滤（Mapper 规则改成所有页 id > 0 都过滤）：
     * 自动翻页必须在 MAX_AUTO_LOAD_TIMES 次后停下，不能无限请求。
     */
    @Test
    fun `every page fully filtered - auto load stops at the cap`() {
        // 所有页都过滤，不只第一页
        val allPagesMapper = object : Mapper<ListIllust>() {
            override fun apply(t: ListIllust): ListIllust {
                t.list.removeAll(t.list.filter { it.id > 0 })
                return t
            }
        }
        val totalPages = AutoLoadPolicy.MAX_AUTO_LOAD_TIMES + 10
        val repo = FakePagedRepo(
            pages = List(totalPages) { index ->
                val isLast = index == totalPages - 1
                page(ids = (index * 30 + 1)..(index * 30 + 30), nextUrl = if (isLast) null else "https://next/page${index + 2}")
            },
            mapper = allPagesMapper,
        )
        val fragment = FakeNetListFragment(repo, enableAutoLoadFix = true)

        fragment.fresh()

        assertEquals(
            "1 次 fresh + 最多 ${AutoLoadPolicy.MAX_AUTO_LOAD_TIMES} 次自动加载",
            1 + AutoLoadPolicy.MAX_AUTO_LOAD_TIMES,
            repo.requestCount
        )
        assertEquals(0, fragment.allItems.size)
        assertTrue(fragment.showingEmptyHint)
    }
}
