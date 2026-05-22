package ceui.pixiv.ui.recommend

import androidx.databinding.ViewDataBinding
import ceui.lisa.adapters.BaseAdapter
import ceui.lisa.adapters.NAdapter
import ceui.lisa.core.RemoteRepo
import ceui.lisa.databinding.FragmentBaseListBinding
import ceui.lisa.fragments.NetListFragment
import ceui.lisa.model.ListNovel
import ceui.lisa.models.NovelBean

/**
 * 当前最热里的小说 tab — 普通线性列表。
 */
class FragmentRecentNovel :
    NetListFragment<FragmentBaseListBinding, ListNovel, NovelBean>() {

    override fun showToolbar(): Boolean = false

    override fun adapter(): BaseAdapter<*, out ViewDataBinding> {
        return NAdapter(allItems, mContext)
    }

    override fun repository(): RemoteRepo<ListNovel> = RecentNovelsRepo()
}
