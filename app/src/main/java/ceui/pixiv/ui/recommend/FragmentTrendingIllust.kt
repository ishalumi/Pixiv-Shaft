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
 * 站长推荐里的插画/漫画 tab — 普通瀑布流,没有顶部 ranking header。
 * dataType 直接是服务端 enum: "illust" 或 "manga"。不能用 localized 字符串,否则
 * 系统语言切换 + ViewPager 状态恢复会让 arguments 里的旧字符串跟新 getString 对不上。
 */
class FragmentTrendingIllust :
    NetListFragment<FragmentBaseListBinding, ListIllust, IllustsBean>() {

    private var serverType: String = TYPE_ILLUST

    override fun initBundle(bundle: Bundle) {
        val raw = bundle.getString(Params.DATA_TYPE)
        // 未知值 → fallback 到 illust,不抛。比 throw 在生产里安全。
        serverType = if (raw == TYPE_MANGA) TYPE_MANGA else TYPE_ILLUST
    }

    override fun showToolbar(): Boolean = false

    override fun adapter(): BaseAdapter<*, out ViewDataBinding> {
        return IAdapter(allItems, mContext)
    }

    override fun initRecyclerView() {
        staggerRecyclerView()
    }

    override fun repository(): RemoteRepo<ListIllust> = TrendingWorksRepo(serverType)

    companion object {
        const val TYPE_ILLUST = "illust"
        const val TYPE_MANGA = "manga"

        /** dataType 必须是 [TYPE_ILLUST] / [TYPE_MANGA] 中的一个 (服务端 enum,不要传中文)。 */
        fun newInstance(dataType: String): FragmentTrendingIllust {
            val frag = FragmentTrendingIllust()
            frag.arguments = Bundle().apply { putString(Params.DATA_TYPE, dataType) }
            return frag
        }
    }
}
