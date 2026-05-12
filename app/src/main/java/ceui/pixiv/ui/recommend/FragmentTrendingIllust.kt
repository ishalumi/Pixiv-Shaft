package ceui.pixiv.ui.recommend

import android.os.Bundle
import androidx.databinding.ViewDataBinding
import ceui.lisa.R
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
 * dataType 区分服务端 type=illust 还是 manga。
 */
class FragmentTrendingIllust :
    NetListFragment<FragmentBaseListBinding, ListIllust, IllustsBean>() {

    private var dataType: String = ""

    override fun initBundle(bundle: Bundle) {
        dataType = bundle.getString(Params.DATA_TYPE) ?: ""
    }

    override fun showToolbar(): Boolean = false

    override fun adapter(): BaseAdapter<*, out ViewDataBinding> {
        return IAdapter(allItems, mContext)
    }

    override fun initRecyclerView() {
        staggerRecyclerView()
    }

    override fun repository(): RemoteRepo<ListIllust> {
        val serverType = when (dataType) {
            getString(R.string.type_manga) -> "manga"
            else -> "illust"
        }
        return TrendingWorksRepo(serverType)
    }

    companion object {
        /** dataType: "插画" 或 "漫画" */
        fun newInstance(dataType: String): FragmentTrendingIllust {
            val frag = FragmentTrendingIllust()
            frag.arguments = Bundle().apply { putString(Params.DATA_TYPE, dataType) }
            return frag
        }
    }
}
