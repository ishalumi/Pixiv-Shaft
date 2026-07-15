package ceui.lisa.fragments

import android.os.Bundle
import android.view.View
import androidx.databinding.ViewDataBinding
import ceui.lisa.R
import ceui.lisa.adapters.BaseAdapter
import ceui.lisa.adapters.NAdapter
import ceui.lisa.core.BaseRepo
import ceui.lisa.databinding.FragmentBaseListBinding
import ceui.lisa.model.ListNovel
import ceui.lisa.models.NovelBean
import ceui.lisa.repo.NewNovelRepo
import ceui.lisa.utils.Params

class FragmentNewNovels : NetListFragment<FragmentBaseListBinding, ListNovel, NovelBean>() {

    private var restrict: String = Params.TYPE_ALL
    private var hideToolbar: Boolean = false

    override fun initBundle(bundle: Bundle) {
        super.initBundle(bundle)
        bundle.getString(ARG_RESTRICT)?.takeIf { it.isNotEmpty() }?.let { restrict = it }
        hideToolbar = bundle.getBoolean(ARG_HIDE_TOOLBAR, false)
    }

    override fun adapter(): BaseAdapter<*, out ViewDataBinding> {
        return NAdapter(allItems, mContext)
    }

    override fun repository(): BaseRepo {
        return NewNovelRepo(restrict)
    }

    override fun getToolbarTitle(): String {
        return getString(R.string.string_197)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (hideToolbar) {
            // 嵌入 FragmentRight 时父页面已经有自己的 toolbar，隐藏自带的避免重复。
            view.findViewById<View>(R.id.toolbar)?.visibility = View.GONE
        }
    }

    // FragmentRight 切换 全部/公开/私人 时复用本 fragment，按新 restrict 重拉。
    fun setRestrict(restrict: String) {
        if (this.restrict == restrict) return
        this.restrict = restrict
        (mRemoteRepo as? NewNovelRepo)?.restrict = restrict
        // 判 view 而不是 isAdded：宿主会在自己的 onCreateView 里(经 lazyData)推一次 restrict，
        // 那时本 fragment 已经 added 但视图还没建——mRecyclerView / mRemoteRepo 都还是 null，
        // 旧写法会在 scrollToTop 里 NPE 再被 ListFragment 的 catch 吞掉，看着像没事其实是踩空。
        // 这种时候本来也不该刷：首屏还没拉过，上面刚写好的字段会被 repository() 用上(initView
        // 先于 initData/lazyData 建 repo，见 BaseFragment.onCreateView)。
        if (view != null) {
            forceRefresh()
        }
    }

    companion object {
        const val ARG_RESTRICT = "restrict"
        const val ARG_HIDE_TOOLBAR = "hide_toolbar"

        @JvmStatic
        fun newInstance(restrict: String): FragmentNewNovels {
            return FragmentNewNovels().apply {
                arguments = Bundle().apply {
                    putString(ARG_RESTRICT, restrict)
                    putBoolean(ARG_HIDE_TOOLBAR, true)
                }
            }
        }
    }
}
