package ceui.pixiv.ui.recommend

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.lifecycle.lifecycleScope
import androidx.viewpager.widget.ViewPager
import ceui.lisa.R
import ceui.lisa.databinding.FragmentYearRankBinding
import ceui.lisa.network.ShaftApiV2Client
import ceui.lisa.utils.Common
import ceui.pixiv.ui.common.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 年代榜 — 打自建服务端 shaft-api-v2 的 discover/most-bookmarked?year=YYYY。
 * 每个年份一个 tab(2007–2026,20 个),左右滑即穿越年代;单个 tab 是
 * [YearRankIllustFeedFragment](feeds 框架版插画瀑布流,热度 pill 显 pixiv 总收藏数)。
 *
 * 年份 tab 不硬编码,拉 discover/years 拿(服务端按年份降序回,带每年作品数)。所以比
 * 「当前最热」多一个加载态:拿到年份之前没有 tab 可建。
 *
 * ⚠️ 分布极度倾斜:2026 年占 56%,2007 年整年只有 61 个作品。所以 tab 标题带上作品数
 * (「2015 · 1.5k」),否则用户点进 2007 看到一屏就到底,会以为是 bug。
 *
 * 用 [FragmentStatePagerAdapter] 而非 FragmentPagerAdapter 的理由同 [FragmentRecentRecommend]:
 * 后者 destroyItem 只 detach、instantiateItem 按 tag 复用,20 个 tab 会攒下一堆 detached
 * fragment;State 版是真 remove + 按内部列表管理。BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT
 * 配上子 fragment 的 `autoLoad = false` 才是「滑到哪年拉哪年」—— 见 [YearRankIllustFeedFragment]。
 */
class YearRankFragment : Fragment(R.layout.fragment_year_rank) {

    private val binding by viewBinding(FragmentYearRankBinding::bind)

    // 跨配置变更/进程死亡要存。不能在 onSaveInstanceState 里读 binding(view 那时可能已销毁),
    // 所以 tab 位置用 listener 同步进字段(同 FragmentRecentRecommend)。
    private var currentYearPos: Int = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        savedInstanceState?.let {
            currentYearPos = it.getInt(KEY_YEAR_POS, 0)
        }

        binding.toolbar.title = " "
        binding.toolbarTitle.text = getString(R.string.year_rank_title)
        binding.toolbar.setNavigationOnClickListener { activity?.finish() }

        // 一次性挂在 ViewPager(view 本身不随数据重建),记录 tab 位置。
        binding.viewPager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) { currentYearPos = position }
        })

        loadYears()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_YEAR_POS, currentYearPos)
    }

    /**
     * 拉年份列表并建 tab。失败不留白屏 —— 提示 + 关页面,让用户重进重试(年代榜没有
     * 局部重试的意义:一个年份都没有就什么都渲染不了)。
     *
     * ⚠️ CancellationException 必须原样抛回,不能混进下面那个 catch:它是协程的**控制流**,
     * 不是加载失败。viewLifecycleOwner 的 scope 在 onDestroyView 取消,吞掉它会有两个后果 ——
     *   1. 用户在响应回来之前转屏/返回,页面会自己 finish() 并飘一条假的「加载失败」toast;
     *   2. 真崩溃(竞态):响应在 OkHttp 线程 resume 后要 post 回主 looper,若这条 post 执行前
     *      Job 已取消,会在**下一个** tick 用 CancellationException resume —— 那时 mView 已是
     *      null,binding 走 requireView() 直接抛 IllegalStateException,且没有 handler → 进程崩。
     * 同仓 FeedViewModel 的 refresh()/loadMore() 也都显式 rethrow,这是本项目既有约定。
     */
    private fun loadYears() {
        binding.yearsLoading.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val buckets = try {
                ShaftApiV2Client.service.discoverYears(type = TYPE_ILLUST).years
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.tag("YearRank").w(e, "discoverYears failed")
                binding.yearsLoading.visibility = View.GONE
                Common.showToast(getString(R.string.year_rank_load_failed))
                activity?.finish()
                return@launch
            }
            binding.yearsLoading.visibility = View.GONE
            if (buckets.isEmpty()) {
                Common.showToast(getString(R.string.year_rank_load_failed))
                activity?.finish()
                return@launch
            }
            bindTabs(buckets.map { it.year to it.count })
        }
    }

    private fun bindTabs(years: List<Pair<String, Int>>) {
        // tab 标题带作品数,让「2007 只有 61 个」这件事在点进去之前就看得见。
        val titles = years.map { (year, count) -> "$year · ${formatCount(count)}" }
        // 只捕获年份字符串,**不要**预建 Fragment 列表塞给 getItem。getItem 的契约是 create:
        // FSPA 只在 mFragments[position] == null(即该页从没建过、或已被 destroyItem 清掉)时
        // 才调它,调了就是要一个**新**实例。
        //
        // 预建列表会死得很难看,而且离屏一页就必然触发(offscreenPageLimit 默认 1):滑到第 3 个
        // tab 时 ViewPager 对 position 0 调 destroyItem → FSPA remove 掉它 → 走
        // FragmentStateManager.clearNonConfigState → ViewModelStore.clear() → FeedViewModel
        // .onCleared() → **viewModelScope 被取消**。滑回去时 getItem(0) 若返回同一个旧实例,
        // 那实例的 `by feedViewModels` 是 LazyThreadSafetyMode.NONE 的**实例字段**、早在第一次
        // onViewCreated 就缓存好了 —— Lazy 不会重跑,于是这个 tab 抱着一个 scope 已死的 VM:
        // refresh()/loadMore() 的 launch 全是 no-op,表现为下拉刷新永远转、翻页失效;若该页
        // 之前只被预建、从没 RESUMED 过(hasLoadedOnce=false),refresh 恒 Idle 会让
        // showFullscreenLoading/showEmptyState/showFullscreenError 全 false → **永久空白页,
        // 连「点击重试」都没有**。(Fragment.initState() 里 mWho 会重新 randomUUID,复活后是
        // 全新 ViewModelStore,和 Lazy 里那个旧 VM 更是彻底脱钩。)
        val yearKeys = years.map { it.first }

        binding.viewPager.adapter = object : FragmentStatePagerAdapter(
            childFragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT
        ) {
            override fun getItem(position: Int): Fragment =
                YearRankIllustFeedFragment.newInstance(yearKeys[position])
            override fun getCount(): Int = titles.size
            override fun getPageTitle(position: Int): CharSequence = titles[position]
        }
        binding.tabLayout.setupWithViewPager(binding.viewPager)
        val pos = currentYearPos.coerceIn(0, titles.size - 1)
        currentYearPos = pos
        binding.viewPager.setCurrentItem(pos, false)
    }

    companion object {
        private const val KEY_YEAR_POS = "year_rank_year_pos"
        /** 服务端 enum,不是展示文案。年代榜目前只做插画(漫画/小说样本量太小)。 */
        private const val TYPE_ILLUST = "illust"

        /** 1234 → 「1.2k」,988 → 「988」。tab 上没有空间放完整数字。 */
        private fun formatCount(n: Int): String =
            if (n >= 1000) String.format("%.1fk", n / 1000f) else n.toString()

        @JvmStatic
        fun newInstance(): YearRankFragment = YearRankFragment()
    }
}
