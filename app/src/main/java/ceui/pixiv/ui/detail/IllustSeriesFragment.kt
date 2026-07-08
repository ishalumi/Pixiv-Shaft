package ceui.pixiv.ui.detail

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.VActivity
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.ProgressImageButton
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.novel.NovelSeriesHeaderActionReceiver
import com.hjq.toast.ToastUtils
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 漫画系列 V3 详情页。整页镜像小说系列详情页 [ceui.pixiv.ui.novel.NovelSeriesFragment]
 * 的观感：全屏 hero（标题 + 收藏 + 阅读最新一话）、作者卡、作品档案、简介、
 * 「作品列表」标题 + 标题优先的单话列表（替代旧瀑布流缩略图）。
 *
 * 入口有两条：① 经典 TemplateActivity "漫画系列详情"（用户主页里的漫画系列）；
 * ② NavHost 里 V3 详情页点系列名（navigation_illust_series）。两条都靠 arguments
 * 里的 "series_id"(Long)。列表项点击一律走 VActivity + PageData（而不是 pushFragment，
 * 后者在 TemplateActivity 里没有 NavHost 会崩）。
 */
class IllustSeriesFragment :
    PixivFragment(R.layout.fragment_pixiv_list),
    NovelSeriesHeaderActionReceiver {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val seriesId: Long by lazy { arguments?.getLong(ARG_SERIES_ID, 0L) ?: 0L }
    private val viewModel by constructVM({ seriesId }) { id ->
        IllustSeriesViewModel(id)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel, ListMode.VERTICAL_NO_HORIZONTAL)
        val density = resources.displayMetrics.density
        binding.listView.clipToPadding = false
        binding.pageBackground.setBackgroundColor(
            ContextCompat.getColor(requireContext(), R.color.v3_bg)
        )
        binding.toolbarLayout.root.visibility = View.GONE
        binding.topShadow.isVisible = false

        // Edge-to-edge：TemplateActivity 画到状态栏/刘海下面，首个 holder 要清掉
        // systemBars.top 再留一点呼吸位；底部留出导航栏 inset + 一点空隙。setUpToolbar
        // 会给 toolbarLayout.root 挂 inset 监听把 top padding 清 0，先摘掉它（toolbar 已 GONE）。
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbarLayout.root, null)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.listView.updatePadding(
                top = bars.top + (12 * density).toInt(),
                bottom = bars.bottom + (24 * density).toInt()
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    // ── 单话/最新话点击：打开整条系列的查看器并定位到这一话 ─────────────

    override fun onClickIllust(illustId: Long) {
        val illusts = viewModel.allLoadedIllusts()
        val pos = illusts.indexOfFirst { it.id == illustId }
        if (illusts.isEmpty() || pos < 0) {
            // 兜底：列表还没加载到这一话（理论上不会），单独拉取只放它一个。
            viewLifecycleOwner.lifecycleScope.launch {
                val illust = runCatching { Client.appApi.getIllust(illustId).illust }
                    .getOrNull() ?: return@launch
                openSeriesViewer(listOf(illust), 0)
            }
            return
        }
        openSeriesViewer(illusts, pos)
    }

    private fun openSeriesViewer(illusts: List<Illust>, position: Int) {
        if (!isAdded || illusts.isEmpty()) return
        val gson = Shaft.sGson
        val beans = illusts.map { gson.fromJson(gson.toJson(it), IllustsBean::class.java) }
        val uuid = UUID.randomUUID().toString()
        val pageData = PageData(uuid, null, beans)
        Container.get().addPageToMap(pageData)
        val intent = Intent(requireContext(), VActivity::class.java).apply {
            putExtra(Params.POSITION, position)
            putExtra(Params.PAGE_UUID, uuid)
        }
        startActivity(intent)
    }

    // ── NovelSeriesHeaderActionReceiver（hero 卡片复用小说那套接口）─────────

    override fun onClickToggleWatchlist(progressView: ProgressImageButton) {
        progressView.showProgress()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                viewModel.toggleWatchlist()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isAdded) ToastUtils.show(getString(R.string.task_status_error))
            } finally {
                if (isAdded) progressView.hideProgress()
            }
        }
    }

    override fun onClickReadLatestEpisode(novelId: Long) {
        // 参数名沿用接口的 novelId，这里其实是最新一话的 illustId。跳转与列表点击一致。
        onClickIllust(novelId)
    }

    companion object {
        const val ARG_SERIES_ID = "series_id"

        fun newInstance(seriesId: Long): IllustSeriesFragment = IllustSeriesFragment().apply {
            arguments = Bundle().apply { putLong(ARG_SERIES_ID, seriesId) }
        }
    }
}
