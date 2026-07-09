package ceui.pixiv.ui.recommend

import androidx.databinding.ViewDataBinding
import ceui.lisa.R
import ceui.lisa.adapters.BaseAdapter
import ceui.lisa.adapters.IAdapter
import ceui.lisa.core.RemoteRepo
import ceui.lisa.databinding.FragmentBaseListBinding
import ceui.lisa.fragments.NetListFragment
import ceui.lisa.model.ListIllust
import ceui.lisa.models.IllustsBean

/**
 * 全站浏览量榜 —— 单作按 pixiv 总浏览数排(含 R-18),打自建 shaft-api-v2 的 discover/most-viewed。
 * 普通插画瀑布流 + 自带 toolbar(不 override showToolbar,走 getToolbarTitle 显示标题),
 * 用 legacy IAdapter(Intent 导航,能在 TemplateActivity 承载)。
 */
class FragmentViewRank :
    NetListFragment<FragmentBaseListBinding, ListIllust, IllustsBean>() {

    override fun adapter(): BaseAdapter<*, out ViewDataBinding> {
        return IAdapter(allItems, mContext)
    }

    override fun initRecyclerView() {
        staggerRecyclerView()
    }

    override fun repository(): RemoteRepo<ListIllust> = ViewRankRepo("illust")

    override fun getToolbarTitle(): String = getString(R.string.view_rank_title)
}
