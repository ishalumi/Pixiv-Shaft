package ceui.pixiv.ui.bookmark

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.content.Intent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentSelectTagFeedBinding
import ceui.lisa.databinding.RecySelectTagBinding
import ceui.lisa.http.ErrorCtrl
import ceui.lisa.http.Retro
import ceui.lisa.model.ListBookmarkTag
import ceui.lisa.models.NullResponse
import ceui.lisa.models.TagsBean
import ceui.lisa.repo.SelectTagRepo
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.lisa.view.LinearItemDecoration
import ceui.pixiv.feeds.FeedCell
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.feeds.updateItems
import ceui.pixiv.ui.common.awaitFirstValue
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.ppppx
import com.blankj.utilcode.util.BarUtils
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「按标签收藏」表单页（feeds 框架版，替代 legacy [ceui.lisa.fragments.FragmentSB] + SAdapter +
 * fragment_select_tag）。核心收藏入口，从插画/小说详情、瀑布流卡长按等 6+ 处进入。
 *
 * 这不是浏览列表，是一张**表单**：可勾选的收藏夹标签列表 + 私密开关 + 提交条（用所选标签给作品点赞）。
 * 选中态挂在可变的 [TagsBean]（`isSelected`/`is_registered`）上，逐格命令式翻（不走整表 diff，
 * 对齐 [ceui.pixiv.ui.muted.MutedUserFeedFragment] 的关注按钮）。
 *
 * 全部行为一比一复刻 legacy（见各方法 KDoc）：同义词自动勾选（issue #904）、全选设置、添加标签、
 * 私密开关、提交（illust/novel × 有无标签四路 + toast + LIKED 广播 + finish）、底部条 navbar inset。
 */
class SelectTagFeedFragment : FeedFragment(R.layout.fragment_select_tag_feed) {

    private val binding by viewBinding(FragmentSelectTagFeedBinding::bind)

    private val illustID: Int by lazy { arguments?.getInt(Params.ILLUST_ID) ?: 0 }
    private val type: String by lazy { arguments?.getString(Params.DATA_TYPE) ?: Params.TYPE_ILLUST }
    private val tagNamesArg: List<String> by lazy {
        arguments?.getStringArray(Params.TAG_NAMES)?.toList() ?: emptyList()
    }

    override val feedViewModel by feedViewModels {
        // 零捕获：Fragment 参数先读进局部 val，数据源只吃基本类型 + 不可变 list（约定见 feedViewModels 文档）。
        val id = illustID
        val t = type
        val names = tagNamesArg
        SelectTagFeedSource(id, t, names)
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> =
        listOf(selectTagRenderer())

    /** 卡间距对齐 legacy ListFragment 默认的 LinearItemDecoration(12dp)（FragmentSB 未覆写列表形态）。 */
    override fun onListReady(listView: RecyclerView) {
        listView.addItemDecoration(LinearItemDecoration(12.ppppx))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Toolbar（5 件套）：状态栏 inset 走 BarUtils 手动 padding（EdgeToEdge 下不用 fitsSystemWindows）。
        binding.toolbar.updatePadding(top = BarUtils.getStatusBarHeight())
        binding.toolbar.setNavigationOnClickListener { requireActivity().finish() }
        binding.toolbarTitle.text = getString(R.string.string_238)
        binding.toolbar.inflateMenu(R.menu.add_tag)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_add) {
                showAddTagDialog()
                true
            } else {
                false
            }
        }

        // 底部提交条：私密开关初值取设置；提交按钮点击 = submitStar。
        binding.isPrivate.isChecked = Shaft.sSettings.isPrivateStar
        binding.submitArea.setOnClickListener { submitStar() }

        // 底部条 navbar inset：legacy 把 navigationBars 底 inset 当 bottomMargin 抬起 bottom_rela，逐字复刻。
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val navInset = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            (binding.bottomRela.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.bottomMargin = navInset.bottom
                binding.bottomRela.layoutParams = lp
            }
            windowInsets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    // ── 每行渲染 + 选中态 ────────────────────────────────────────────────
    /**
     * 复刻 SAdapter：star_size 显示「标签名/译名」，illust_count 复选框即选中态的全部视觉。
     * 复选框自身不独立响应点击（回收复用会脏勾），整行点击统一走 [toggleSelection]。
     */
    private fun selectTagRenderer() = feedRenderer<SelectTagFeedItem, RecySelectTagBinding>(
        inflate = RecySelectTagBinding::inflate,
        create = { cell ->
            cell.binding.illustCount.isClickable = false
            cell.binding.illustCount.isFocusable = false
            cell.binding.root.setOnClickListener { toggleSelection(cell) }
        },
    ) { cell -> bindTagRow(cell) }

    private fun bindTagRow(cell: FeedCell<SelectTagFeedItem, RecySelectTagBinding>) {
        val tag = cell.item.tag
        val name = tag.name.orEmpty()
        val translated = tag.translated_name
        cell.binding.starSize.text =
            if (!translated.isNullOrEmpty()) "$name/$translated" else name
        cell.binding.illustCount.isChecked = tag.isSelectedLocalOrRemote
    }

    /**
     * 整行点击翻选中态（对齐 SAdapter 的 itemView → checkbox.performClick）：读当前
     * isSelectedLocalOrRemote 取反，setSelectedLocalAndRemote 同时写 isSelected/is_registered，
     * 命令式改复选框视觉，不动列表（不触发整表 diff）。bean 被 [SelectTagFeedItem] 持引用，
     * 提交时按 bean 状态收集。
     */
    private fun toggleSelection(cell: FeedCell<SelectTagFeedItem, RecySelectTagBinding>) {
        val tag = cell.item.tag
        val newValue = !tag.isSelectedLocalOrRemote
        tag.setSelectedLocalAndRemote(newValue)
        cell.binding.illustCount.isChecked = newValue
    }

    // ── 添加标签（toolbar 菜单 action_add）─────────────────────────────────
    private fun showAddTagDialog() {
        val activity = activity ?: return
        val builder = QMUIDialog.EditTextDialogBuilder(activity)
        builder.setTitle("添加标签")
            .setSkinManager(QMUISkinManager.defaultInstance(activity))
            .setPlaceholder("请输入标签(收藏夹)名")
            .setInputType(InputType.TYPE_CLASS_TEXT)
            .addAction("取消") { dialog, _ -> dialog.dismiss() }
            .addAction("添加") { dialog, _ ->
                val text = builder.editText.text
                if (text != null && text.isNotEmpty()) {
                    addTag(text.toString())
                    dialog.dismiss()
                } else {
                    Common.showToast("请填入标签")
                }
            }
            .show()
    }

    /**
     * 复刻 FragmentSB.addTag：已存在同名标签 → 勾选并重绑该行；否则 new TagsBean(count 0, selected)
     * 前插到列表顶部并回顶。
     *
     * 「已存在」用 [ceui.pixiv.feeds.FeedViewModel.updateItems] 换一个包同一 bean 的新
     * [SelectTagFeedItem] 实例：feedKey（标签名）不变 → DiffUtil 判定为同一条内容变化 → 复用
     * ViewHolder 原地重绑（[SelectTagFeedItem] 非 data class，靠实例身份触发重绑），checkbox 刷成勾选。
     */
    private fun addTag(tagName: String) {
        val existing = feedViewModel.uiState.value.items
            .filterIsInstance<SelectTagFeedItem>()
            .firstOrNull { it.tag.name == tagName }
        if (existing != null) {
            existing.tag.isSelected = true
            feedViewModel.updateItems<SelectTagFeedItem> {
                if (it.tag.name == tagName) SelectTagFeedItem(it.tag) else it
            }
            return
        }
        val bean = TagsBean().apply {
            count = 0
            isSelected = true
            name = tagName
        }
        feedViewModel.mutateItems { listOf(SelectTagFeedItem(bean)) + it }
        scrollToTop()
    }

    // ── 提交 ────────────────────────────────────────────────────────────
    /**
     * 复刻 FragmentSB.submitStar：收集选中标签名 → 按 type × 有无标签四路调 postLike*；
     * restrict = 私密开关 ? private : public。成功后 toast（私密/公开）+ [setFollowed] 广播 + finish。
     *
     * 保留 legacy 的 RxJava + [ErrorCtrl] 链路，让错误处理与成功回调时序与旧版**逐字节一致**
     * （task 明确允许，且 ErrorCtrl 的错误解析无法在协程侧无损重写）。
     */
    private fun submitStar() {
        val activity = activity ?: return
        val selectedNames = feedViewModel.uiState.value.items
            .filterIsInstance<SelectTagFeedItem>()
            .filter { it.tag.isSelectedLocalOrRemote }
            .mapNotNull { it.tag.name }

        val isPrivate = binding.isPrivate.isChecked
        val restrict = if (isPrivate) Params.TYPE_PRIVATE else Params.TYPE_PUBLIC
        val toastMsg = getString(
            if (isPrivate) R.string.like_novel_success_private else R.string.like_novel_success_public
        )

        val api: Observable<NullResponse>? = if (selectedNames.isEmpty()) {
            when (type) {
                Params.TYPE_ILLUST -> Retro.getAppApi().postLikeIllust(illustID, restrict)
                Params.TYPE_NOVEL -> Retro.getAppApi().postLikeNovel(illustID, restrict)
                else -> null
            }
        } else {
            val tags = selectedNames.toTypedArray()
            when (type) {
                Params.TYPE_ILLUST ->
                    Retro.getAppApi().postLikeIllustWithTags(illustID, restrict, *tags)
                Params.TYPE_NOVEL ->
                    Retro.getAppApi().postLikeNovelWithTags(illustID, restrict, *tags)
                else -> null
            }
        }
        api ?: return
        api.subscribeOn(Schedulers.newThread())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : ErrorCtrl<NullResponse>() {
                override fun next(nullResponse: NullResponse?) {
                    Common.showToast(toastMsg)
                    setFollowed(activity)
                }
            })
    }

    /** 复刻 FragmentSB.setFollowed：广播 LIKED_ILLUST/LIKED_NOVEL(ID + IS_LIKED=true) 通知别处刷新收藏态，再 finish。 */
    private fun setFollowed(activity: androidx.fragment.app.FragmentActivity) {
        val action = if (type == Params.TYPE_ILLUST) Params.LIKED_ILLUST else Params.LIKED_NOVEL
        val intent = Intent(action).apply {
            putExtra(Params.ID, illustID)
            putExtra(Params.IS_LIKED, true)
        }
        LocalBroadcastManager.getInstance(activity).sendBroadcast(intent)
        activity.finish()
    }

    companion object {
        @JvmStatic
        fun newInstance(illustID: Int, type: String?, tagNames: Array<String>?): SelectTagFeedFragment =
            SelectTagFeedFragment().apply {
                arguments = Bundle().apply {
                    putInt(Params.ILLUST_ID, illustID)
                    putString(Params.DATA_TYPE, type)
                    putStringArray(Params.TAG_NAMES, tagNames)
                }
            }
    }
}

/**
 * 一行收藏夹标签。选中态挂在可变的 [tag]（`isSelected`/`is_registered`）上，逐格命令式翻，
 * 故本类**刻意非 data class**：内容相等退化为实例身份，让 [SelectTagFeedFragment.addTag] 能靠
 * 换新实例（feedKey 不变）触发 DiffUtil 重绑该行。feedKey 用标签名（同类内唯一稳定）。
 */
class SelectTagFeedItem(val tag: TagsBean) : FeedItem {
    override val feedKey: Any get() = tag.name ?: tag
}

/**
 * 「按标签收藏」数据源：包裹 legacy [SelectTagRepo]，单页（nextCursor 恒 null，对齐 initNextApi=null）。
 *
 * load(null)：IO 上建 + 订阅 [SelectTagRepo.initApi]（api2 拉全量标签 → flatMap 到 api1 拉本作品标签，
 * 顺带把 listTag 存进 repo），await 首值；再在 IO 上跑 [SelectTagRepo.mapper]（同义词词典自动勾选
 * issue #904，含全量 DB 读，绝不能上主线程）+ beforeFirstLoad 全选，最后映射成条目。
 *
 * 零 Fragment 捕获：只吃 illustID/type/tagNames（基本类型 + 不可变 list）。
 */
class SelectTagFeedSource(
    private val illustID: Int,
    private val type: String,
    private val tagNames: List<String>,
) : FeedSource<String> {

    override suspend fun load(cursor: String?): FeedPage<String> {
        // 单页：框架只会用 cursor == null 调本源（nextCursor 恒 null，无翻页）。
        val repo = SelectTagRepo(illustID, type, tagNames)
        val resp: ListBookmarkTag = withContext(Dispatchers.IO) { repo.initApi() }.awaitFirstValue()
        val tags: List<TagsBean> = withContext(Dispatchers.IO) {
            // 同义词词典自动勾选（issue #904）在 mapper 里做，跑后台线程（读全量词典 DB），不阻塞 UI。
            repo.mapper().apply(resp)
            // beforeFirstLoad 全选：设置开 + (tagNames 空 或 该标签命中作品标签) → 勾选。
            if (Shaft.sSettings.isStarWithTagSelectAll) {
                resp.list?.forEach { tag ->
                    if (tagNames.isEmpty() || tagNames.contains(tag.name)) {
                        tag.isSelected = true
                    }
                }
            }
            resp.list.orEmpty()
        }
        val items: List<FeedItem> = tags.map { SelectTagFeedItem(it) }
        return FeedPage(items, null)
    }
}
