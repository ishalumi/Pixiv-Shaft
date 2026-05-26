package ceui.pixiv.ui.recommend

import android.os.Bundle
import androidx.databinding.ViewDataBinding
import ceui.lisa.adapters.BaseAdapter
import ceui.lisa.adapters.IAdapter
import ceui.lisa.core.RemoteRepo
import ceui.lisa.databinding.FragmentBaseListBinding
import ceui.lisa.fragments.NetListFragment
import ceui.lisa.model.ListIllust
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Params

/**
 * 当前最热里的插画/漫画 tab — 普通瀑布流。dataType 直接是服务端 enum "illust"/"manga"
 * (复用 [FragmentTrendingIllust] 的常量),不能用 localized 字符串,理由同 trending:
 * 系统语言切换 + ViewPager 状态恢复会让 arguments 里的旧字符串跟新 getString 对不上。
 */
class FragmentRecentIllust :
    NetListFragment<FragmentBaseListBinding, ListIllust, IllustsBean>() {

    private var serverType: String = FragmentTrendingIllust.TYPE_ILLUST
    private var window: String? = null

    override fun initBundle(bundle: Bundle) {
        val raw = bundle.getString(Params.DATA_TYPE)
        // 未知值 → fallback 到 illust,不抛。
        serverType = if (raw == FragmentTrendingIllust.TYPE_MANGA)
            FragmentTrendingIllust.TYPE_MANGA else FragmentTrendingIllust.TYPE_ILLUST
        window = bundle.getString(ARG_WINDOW)  // null=实时流;day|week|month=榜
    }

    override fun showToolbar(): Boolean = false

    override fun adapter(): BaseAdapter<*, out ViewDataBinding> {
        return IAdapter(allItems, mContext)
    }

    override fun initRecyclerView() {
        staggerRecyclerView()
    }

    override fun repository(): RemoteRepo<ListIllust> = RecentWorksRepo(serverType, window = window)

    companion object {
        /** 当前最热窗口 arg:null=实时流,否则 day|week|month。 */
        const val ARG_WINDOW = "recent_window"

        /**
         * dataType 必须是 [FragmentTrendingIllust.TYPE_ILLUST] / [FragmentTrendingIllust.TYPE_MANGA]。
         * window: null=实时流,否则 day|week|month。
         */
        fun newInstance(dataType: String, window: String? = null): FragmentRecentIllust {
            val frag = FragmentRecentIllust()
            frag.arguments = Bundle().apply {
                putString(Params.DATA_TYPE, dataType)
                putString(ARG_WINDOW, window)
            }
            return frag
        }
    }
}
