package ceui.pixiv.ui.home

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding

/**
 * 「推荐漫画」页（TemplateActivity 宿主）：推荐 feed 的漫画变体 + 自带 toolbar
 * （对齐 legacy FragmentRankIllust 漫画分支 showToolbar()=true）。
 */
class RecmdMangaFeedFragment : RecmdIllustFeedFragment(R.layout.fragment_toolbar_feed) {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding.toolbarLayout, feedBinding.feedListView)
        // 对齐 legacy getToolbarTitle：推荐 + dataType
        binding.toolbarLayout.naviTitle.text = getString(R.string.recommend) + dataType
    }

    companion object {
        @JvmStatic
        fun newInstance(): RecmdMangaFeedFragment {
            return RecmdMangaFeedFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_DATA_TYPE, TYPE_MANGA)
                }
            }
        }
    }
}
