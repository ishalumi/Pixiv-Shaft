package ceui.pixiv.ui.recommend

import android.os.Bundle
import androidx.databinding.ViewDataBinding
import ceui.lisa.adapters.BaseAdapter
import ceui.lisa.adapters.NAdapter
import ceui.lisa.core.RemoteRepo
import ceui.lisa.databinding.FragmentBaseListBinding
import ceui.lisa.fragments.NetListFragment
import ceui.lisa.model.ListNovel
import ceui.lisa.models.NovelBean

/**
 * 当前最热里的小说 tab — 普通线性列表。window: null=实时流,否则 day|week|month。
 */
class FragmentRecentNovel :
    NetListFragment<FragmentBaseListBinding, ListNovel, NovelBean>() {

    private var window: String? = null

    override fun initBundle(bundle: Bundle) {
        window = bundle.getString(FragmentRecentIllust.ARG_WINDOW)
    }

    override fun showToolbar(): Boolean = false

    override fun adapter(): BaseAdapter<*, out ViewDataBinding> {
        return NAdapter(allItems, mContext)
    }

    override fun repository(): RemoteRepo<ListNovel> = RecentNovelsRepo(window = window)

    companion object {
        /** window: null=实时流,否则 day|week|month。 */
        fun newInstance(window: String? = null): FragmentRecentNovel {
            val frag = FragmentRecentNovel()
            frag.arguments = Bundle().apply {
                putString(FragmentRecentIllust.ARG_WINDOW, window)
            }
            return frag
        }
    }
}
