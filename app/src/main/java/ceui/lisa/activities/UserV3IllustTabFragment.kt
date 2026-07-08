package ceui.lisa.activities

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import ceui.lisa.R
import ceui.lisa.fragments.FragmentUserIllust
import ceui.lisa.models.IllustsBean
import ceui.lisa.models.TagsBean
import ceui.lisa.utils.Params
import ceui.pixiv.widgets.V3TagFlowView

private const val ARG_USER_ID = "illust_tab_user_id"
private const val MAX_ILLUST_TAG_CHIPS = 20 // issue #569: 高频 tag 药丸最多展示数(折叠前的候选池)

/**
 * 画师主页「插画」Tab —— 顶部标签筛选条 + 内嵌 FragmentUserIllust。
 *
 * 筛选条(issue #569)住在页面内部,跟随 ViewPager 横滑;数据复用插画列表首屏
 * (onUserIllustFirstPage 回调),进主页零额外请求。
 */
class UserV3IllustTabFragment : Fragment(), UserIllustFirstPageListener {

    private var userId = 0
    private var tagsRendered = false
    private lateinit var filterBar: V3TagFlowView

    companion object {
        fun newInstance(userId: Int): UserV3IllustTabFragment =
            UserV3IllustTabFragment().apply {
                arguments = Bundle().apply { putInt(ARG_USER_ID, userId) }
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        userId = arguments?.getInt(ARG_USER_ID) ?: 0
        return inflater.inflate(R.layout.fragment_user_v3_illust_tab, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        filterBar = view.findViewById(R.id.illust_filter_bar)
        // 旋转/重建时 childFragmentManager 自己恢复列表,别再 add 一份
        if (childFragmentManager.findFragmentById(R.id.illust_list_container) == null) {
            childFragmentManager.beginTransaction()
                .add(R.id.illust_list_container, FragmentUserIllust.newInstance(userId, false))
                .commit()
        }
    }

    /** 插画列表首屏回调:聚合高频 tag → 短到长排序 → 渲染筛选条(最多 2 行,溢出「+N」)。 */
    override fun onUserIllustFirstPage(illusts: List<IllustsBean>) {
        if (view == null || tagsRendered || illusts.isEmpty()) return
        // 列表首屏已按全局设置过滤过屏蔽作品/tag,这里直接按频率聚合,保留首次出现的 TagsBean(含译名)
        val freq = LinkedHashMap<String, Int>()
        val beanOf = HashMap<String, TagsBean>()
        for (ill in illusts) {
            val tags = ill.tags ?: continue
            for (t in tags) {
                val name = t.name ?: continue
                if (name.isBlank()) continue
                freq[name] = (freq[name] ?: 0) + 1
                beanOf.getOrPut(name) { t }
            }
        }
        if (freq.isEmpty()) return
        // 先按频率取高频 Top-N,再按 chip 视觉宽度从短到长排列 —— 换行堆叠更整齐
        val top = freq.entries
            .sortedByDescending { it.value }
            .take(MAX_ILLUST_TAG_CHIPS)
            .mapNotNull { beanOf[it.key] }
            .sortedBy { chipVisualWidth(it) }
        if (top.isEmpty()) return
        tagsRendered = true

        filterBar.compact = true          // 小号 chip(11.5sp)
        filterBar.showHashPrefix = false  // 列表筛选条不带「#」前缀
        filterBar.maxTags = -1            // 先全渲染,再按行数折叠(见 trimToTwoRows)
        // 点击不跳搜索页,而是打开该画师按此 tag 筛选的作品页(issue #569 现有 ajax 路由)
        filterBar.onTagClick = { name ->
            val intent = Intent(requireContext(), TemplateActivity::class.java)
            intent.putExtra(Params.USER_ID, userId)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "插画标签作品")
            intent.putExtra(Params.KEY_WORD, name)
            startActivity(intent)
        }
        filterBar.setJavaTags(top)
        filterBar.isVisible = true
        filterBar.post { trimToTwoRows() }
    }

    /**
     * 真·行数限制:让 flexbox 先自然换行,读 flexLines 数出前 2 行实际能容纳的 chip 数,
     * 超出的用一个「+N」块表达(V3TagFlowView.maxTags)。既不像 maxLine 那样硬切末尾 chip,
     * 也不拍脑袋定死数量 —— 放得下几个就显示几个。
     */
    private fun trimToTwoRows() {
        if (view == null || !filterBar.isVisible || filterBar.width == 0) return
        val lines = filterBar.flexLines
        if (lines.size <= 2) return                       // 已经 ≤2 行,无需折叠
        val firstTwo = lines[0].itemCount + lines[1].itemCount
        val target = (firstTwo - 1).coerceAtLeast(1)      // 留一个位给「+N」块
        if (filterBar.maxTags == target) return           // 防抖:已折到目标就停
        filterBar.maxTags = target                        // setter 内部重渲染 + 追加「+N」
        // 「+N」块占位可能把第 2 行再挤出一个 → 收敛校验一次
        filterBar.post {
            if (view != null && filterBar.flexLines.size > 2 && filterBar.maxTags > 1) {
                filterBar.maxTags = filterBar.maxTags - 1
            }
        }
    }

    /** chip 视觉宽度估算:CJK/假名占 2 格、ASCII 占 1 格;含原文 + 译文(V3TagFlowView 同 chip 显示两者)。 */
    private fun chipVisualWidth(tag: TagsBean): Int {
        fun width(s: String?): Int {
            if (s.isNullOrEmpty()) return 0
            var w = 0
            for (c in s) w += if (c.code > 0x2E7F) 2 else 1
            return w
        }
        val disp = tag.translated_name?.takeIf { it.isNotBlank() }
        return width(tag.name) + (disp?.let { 1 + width(it) } ?: 0)
    }
}
