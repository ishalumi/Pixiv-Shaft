package ceui.pixiv.ui.detail

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.BuildConfig
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.activities.UActivity
import ceui.lisa.activities.VActivity
import ceui.lisa.activities.followUser
import ceui.lisa.activities.unfollowUser
import ceui.lisa.adapters.LAdapter
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.databinding.CellCommentPreviewBinding
import ceui.lisa.databinding.SectionV3ArtistBinding
import ceui.lisa.databinding.SectionV3AuthorWorksBinding
import ceui.lisa.databinding.SectionV3CommentsBinding
import ceui.lisa.databinding.SectionV3DescriptionBinding
import ceui.lisa.databinding.SectionV3DetailPanelBinding
import ceui.lisa.databinding.SectionV3HeroBinding
import ceui.lisa.databinding.SectionV3RelatedHeaderBinding
import ceui.lisa.databinding.SectionV3SeriesBinding
import ceui.lisa.databinding.SectionV3StatsBinding
import ceui.lisa.databinding.SectionV3TagsBinding
import ceui.lisa.models.IllustsBean
import ceui.lisa.models.TagsBean
import ceui.lisa.models.UserBean
import ceui.lisa.utils.Common
import ceui.lisa.utils.ClipBoardUtils
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.utils.SearchTypeUtil
import ceui.lisa.utils.V3Palette
import ceui.loxia.Comment
import ceui.loxia.ObjectPool
import ceui.loxia.ProgressTextButton
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.comments.CommentEmojiSpanner
import ceui.pixiv.ui.comments.translateCommentToChinese
import ceui.pixiv.ui.user.binding_loadUserIcon
import ceui.pixiv.utils.buildPinnedTagPreviewJson
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import de.hdodenhof.circleimageview.CircleImageView
import java.text.NumberFormat

class ArtworkDetailAdapter(
    private val fragment: androidx.fragment.app.Fragment
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    val palette: V3Palette = V3Palette.from(fragment.requireContext())
    private val items = mutableListOf<ArtworkDetailItem>()
    private val animatedViewTypes = mutableSetOf<Int>()
    var onCommentsVisible: (() -> Unit)? = null
    var onAuthorWorksVisible: (() -> Unit)? = null
    var onRelatedVisible: (() -> Unit)? = null
    /** 「留下你的评论吧」入口被点:唤起 Fragment 底部的内联输入栏(见 ArtworkV3Fragment.showComposer)。 */
    var onClickAddComment: (() -> Unit)? = null

    fun submitItems(newItems: List<ArtworkDetailItem>) {
        val t = if (BuildConfig.DEBUG) SystemClock.elapsedRealtime() else 0L
        val oldItems = items.toList()
        items.clear()
        items.addAll(newItems)

        if (oldItems.size == newItems.size &&
            oldItems.zip(newItems).all { (a, b) -> viewTypeOf(a) == viewTypeOf(b) }
        ) {
            var changedCount = 0
            for (i in newItems.indices) {
                if (oldItems[i] != newItems[i]) {
                    notifyItemChanged(i)
                    changedCount++
                }
            }
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "submitItems: same structure, $changedCount changed, ${SystemClock.elapsedRealtime() - t}ms"
                )
            }
        } else if (oldItems.size < newItems.size &&
            oldItems.indices.all { viewTypeOf(oldItems[it]) == viewTypeOf(newItems[it]) }
        ) {
            // Items appended at the end (load more related)
            for (i in oldItems.indices) {
                if (oldItems[i] != newItems[i]) notifyItemChanged(i)
            }
            notifyItemRangeInserted(oldItems.size, newItems.size - oldItems.size)
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "submitItems: appended ${newItems.size - oldItems.size} items, ${SystemClock.elapsedRealtime() - t}ms"
                )
            }
        } else {
            notifyDataSetChanged()
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "submitItems: structural change ${oldItems.size}->${newItems.size}, ${SystemClock.elapsedRealtime() - t}ms"
                )
            }
        }
    }

    fun findIndex(predicate: (ArtworkDetailItem) -> Boolean): Int {
        return items.indexOfFirst(predicate)
    }

    fun updateItem(index: Int, item: ArtworkDetailItem) {
        if (index in items.indices && items[index] != item) {
            items[index] = item
            notifyItemChanged(index)
        }
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = viewTypeOf(items[position])

    private fun viewTypeOf(item: ArtworkDetailItem): Int = when (item) {
        is ArtworkDetailItem.Hero -> TYPE_HERO
        is ArtworkDetailItem.Series -> TYPE_SERIES
        is ArtworkDetailItem.Desc -> TYPE_DESC
        is ArtworkDetailItem.Stats -> TYPE_STATS
        is ArtworkDetailItem.Tags -> TYPE_TAGS
        is ArtworkDetailItem.Artist -> TYPE_ARTIST
        is ArtworkDetailItem.DetailPanel -> TYPE_DETAIL
        is ArtworkDetailItem.Comments -> TYPE_COMMENTS
        is ArtworkDetailItem.AuthorWorks -> TYPE_AUTHOR_WORKS
        is ArtworkDetailItem.RelatedHeader -> TYPE_RELATED_HEADER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HERO -> HeroVH(SectionV3HeroBinding.inflate(inflater, parent, false))
            TYPE_SERIES -> SeriesVH(SectionV3SeriesBinding.inflate(inflater, parent, false))
            TYPE_DESC -> DescVH(SectionV3DescriptionBinding.inflate(inflater, parent, false))
            TYPE_STATS -> StatsVH(SectionV3StatsBinding.inflate(inflater, parent, false))
            TYPE_TAGS -> TagsVH(SectionV3TagsBinding.inflate(inflater, parent, false))
            TYPE_ARTIST -> ArtistVH(SectionV3ArtistBinding.inflate(inflater, parent, false))
            TYPE_DETAIL -> DetailPanelVH(
                SectionV3DetailPanelBinding.inflate(
                    inflater,
                    parent,
                    false
                )
            )

            TYPE_COMMENTS -> CommentsVH(SectionV3CommentsBinding.inflate(inflater, parent, false))
            TYPE_AUTHOR_WORKS -> AuthorWorksVH(
                SectionV3AuthorWorksBinding.inflate(
                    inflater,
                    parent,
                    false
                )
            )

            TYPE_RELATED_HEADER -> RelatedHeaderVH(
                SectionV3RelatedHeaderBinding.inflate(
                    inflater,
                    parent,
                    false
                )
            )

            else -> throw IllegalArgumentException("Unknown viewType $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val t = if (BuildConfig.DEBUG) SystemClock.elapsedRealtime() else 0L
        val item = items[position]
        when {
            holder is HeroVH && item is ArtworkDetailItem.Hero -> holder.bind(item.illust)
            holder is SeriesVH && item is ArtworkDetailItem.Series -> holder.bind(item.illust)
            holder is DescVH && item is ArtworkDetailItem.Desc -> holder.bind(item.caption)
            holder is StatsVH && item is ArtworkDetailItem.Stats -> holder.bind(item.illust)
            holder is TagsVH && item is ArtworkDetailItem.Tags -> holder.bind(item.illust)
            holder is ArtistVH && item is ArtworkDetailItem.Artist -> holder.bind(item.illust)

            holder is DetailPanelVH && item is ArtworkDetailItem.DetailPanel -> holder.bind(item.illust)
            holder is CommentsVH && item is ArtworkDetailItem.Comments -> holder.bind(item)
            holder is AuthorWorksVH && item is ArtworkDetailItem.AuthorWorks -> holder.bind(item)
            holder is RelatedHeaderVH && item is ArtworkDetailItem.RelatedHeader -> holder.bind(item)
        }
        if (BuildConfig.DEBUG) {
            val elapsed = SystemClock.elapsedRealtime() - t
            if (elapsed > 2) {
                Log.d(
                    TAG,
                    "onBindViewHolder pos=$position type=${getItemViewType(position)} took ${elapsed}ms"
                )
            }
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        val lp = holder.itemView.layoutParams
        if (lp is StaggeredGridLayoutManager.LayoutParams && !lp.isFullSpan) {
            lp.isFullSpan = true
            holder.itemView.layoutParams = lp
        }

        // Entrance animation: only the first time each view type attaches. Running
        // this here (not in onBindViewHolder) keeps rebinds — e.g. from follow-state
        // updates — from re-triggering the opacity/translation animation.
        val vt = holder.itemViewType
        if (animatedViewTypes.add(vt)) {
            val view = holder.itemView
            view.alpha = 0f
            view.translationY = 16.ppppx.toFloat()
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(350)
                .setInterpolator(DecelerateInterpolator(2.5f))
                .start()
        }

        // Lazy-load trigger: fire API calls only when the section scrolls into view
        val pos = holder.bindingAdapterPosition
        if (pos in items.indices) {
            when (val item = items[pos]) {
                is ArtworkDetailItem.Comments -> if (item.liveData.value == null) onCommentsVisible?.invoke()
                is ArtworkDetailItem.AuthorWorks -> if (item.liveData.value == null) onAuthorWorksVisible?.invoke()
                is ArtworkDetailItem.RelatedHeader -> if (item.liveData.value == null) onRelatedVisible?.invoke()
                else -> {}
            }
        }
    }

    private val ctx: Context get() = fragment.requireContext()
    private val glide get() = Glide.with(fragment)

    /** 第 0 P 原图 URL 的文件后缀(大写,如 PNG / JPG);拿不到 URL 或无后缀返回 null。 */
    private fun page0Extension(illust: IllustsBean): String? {
        val url = if (illust.page_count <= 1) {
            illust.meta_single_page?.original_image_url
        } else {
            illust.meta_pages?.getOrNull(0)?.image_urls?.original
        }
        val clean = url?.substringBefore('?')?.substringBefore('#') ?: return null
        val dot = clean.lastIndexOf('.')
        if (dot < 0 || dot == clean.length - 1) return null
        val ext = clean.substring(dot + 1)
        // 后缀里带 '/' 或过长 = 那个点其实在域名/路径里(URL 没真正的扩展名),别把整段路径当后缀显示。
        if (ext.length > 5 || ext.contains('/')) return null
        return ext.uppercase()
    }

    // =================== ViewHolders ===================

    inner class HeroVH(private val b: SectionV3HeroBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(illust: IllustsBean) {
            b.heroTitle.text = illust.title
            // 长按标题复制 —— 跟 novel V3 / novel series V3 的同名组件一致
            b.heroTitle.setOnLongClickListener {
                Common.copy(ctx, illust.title.orEmpty()); true
            }
            b.metaType.text = when (illust.type) {
                "manga" -> ctx.getString(R.string.v3_type_manga)
                "ugoira" -> ctx.getString(R.string.v3_type_ugoira)
                else -> ctx.getString(R.string.v3_type_illustration)
            }
            // 文件拓展名(取第 0 P 原图 URL 的后缀;多 P 只看第 0 P)。精简 / 网页来源 bean
            // 缺原图 URL 时整段隐藏,免得留个空分隔点。issue #938 讨论衍生。
            val ext = page0Extension(illust)
            b.metaExt.isVisible = ext != null
            b.metaExtSep.isVisible = ext != null
            if (ext != null) b.metaExt.text = ext
            b.metaDate.text = Common.getLocalYYYYMMDDHHMMString(illust.create_date)
            b.metaPages.text = if (illust.page_count == 1) ctx.getString(R.string.v3_page_count_one)
            else ctx.getString(R.string.v3_page_count_many, illust.page_count)
        }
    }

    inner class SeriesVH(private val b: SectionV3SeriesBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(illust: IllustsBean) {
            val series = illust.series ?: return
            b.seriesName.text = series.title
            // Apply themed series strip
            val d = ctx.resources.displayMetrics.density
            b.seriesStrip.background = palette.seriesStripBg(20f * d)
            b.seriesIcon.background = palette.seriesIconBg(10f * d)
            // 文字跟随主题:浅色条底白字会糊,改主题色压深(日夜双模)
            b.seriesName.setTextColor(palette.seriesStripText)
            b.seriesLabel.setTextColor(palette.seriesStripText)
            b.seriesChevron.setTextColor(palette.seriesStripText)
            b.root.setOnClickListener {
                val intent = Intent(ctx, TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "漫画系列详情")
                intent.putExtra(Params.MANGA_SERIES_ID, series.id)
                ctx.startActivity(intent)
            }
            applyTouchScale(b.root)
        }
    }

    inner class DescVH(private val b: SectionV3DescriptionBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(caption: String) {
            b.description.setHtml(caption)
        }
    }

    inner class StatsVH(private val b: SectionV3StatsBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(illust: IllustsBean) {
            val fmt = NumberFormat.getNumberInstance()
            b.statViews.text = fmt.format(illust.total_view)
            b.statBookmarks.text = fmt.format(illust.total_bookmarks)
        }
    }

    inner class TagsVH(private val b: SectionV3TagsBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(illust: IllustsBean) {
            b.tagsFlow.searchIndex = 0 // illust tab
            b.tagsFlow.setJavaTags(illust.tags.orEmpty())
            // 同义词词典（issue #904）：匹配框喂当前作品标签
            b.synonymMatch.setWorkTags(illust.tags.orEmpty())
            // 长按菜单接「固定 tag」：写 search_table，pinned=true 时 previewIllustsJson
            // 存当前 illust（shape 对齐 PrimeTagResult）；和 FragmentIllust 行为一致。
            b.tagsFlow.onPinTag = { name, translated, newPinned ->
                val tagBean = TagsBean().apply {
                    this.name = name
                    this.translated_name = translated
                }
                val previewJson =
                    if (newPinned) buildPinnedTagPreviewJson(tagBean, illust) else null
                PixivOperate.insertPinnedSearchHistory(
                    name, SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD, newPinned, previewJson
                )
                Common.showToast(R.string.operate_success)
            }
        }
    }

    inner class ArtistVH(private val b: SectionV3ArtistBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(illust: IllustsBean) {
            val user = illust.user ?: return
            b.artistName.text = user.name
            b.artistHandle.text = "@${user.account ?: ""}"

            val openUser = View.OnClickListener {
                val intent = Intent(ctx, UActivity::class.java)
                intent.putExtra(Params.USER_ID, user.id)
                ctx.startActivity(intent)
            }
            b.artistCard.setOnClickListener(openUser)
            // 长按复制作者昵称 / handle；单击仍走 openUser 跳 V3 主页
            // (设置 longClickListener 会让 TextView 自己消费 touch,事件不会冒泡到父 card)
            b.artistName.setOnClickListener(openUser)
            b.artistName.setOnLongClickListener {
                Common.copy(ctx, user.name.orEmpty()); true
            }
            b.artistHandle.setOnClickListener(openUser)
            b.artistHandle.setOnLongClickListener {
                Common.copy(ctx, b.artistHandle.text?.toString().orEmpty()); true
            }
            glide.load(GlideUtil.getUrl(user.profile_image_urls?.medium))
                .error(R.drawable.no_profile)
                .into(b.artistAvatar)

            applyTouchScale(b.artistCard)

            bindFollowState(user)
            b.artistBio.isVisible = !user.comment.isNullOrBlank()
            if (b.artistBio.isVisible) b.artistBio.text = user.comment
        }

        private fun bindFollowState(user: UserBean) {
            // illust.user may be an orphan (see ArtworkDetailItem.Artist.resolveIsFollowed).
            // Always read isIs_followed from the canonical pool entry so the button
            // text matches what Artist.isFollowed snapshotted.
            val isFollowed = ObjectPool.get<UserBean>(user.id.toLong()).value?.isIs_followed
                ?: user.isIs_followed
            if (isFollowed) {
                b.followBtn.text = ctx.getString(R.string.unfollow)
                palette.applyUnfollowBtn(b.followBtn)
                b.followBtn.setOnClick { fragment.unfollowUser(it as ProgressTextButton, user.id) }
                // Drop the "long-press = private follow" handler set in the
                // unfollowed branch — re-binds via ObjectPool can flip this VH
                // between the two states without re-inflating.
                b.followBtn.setOnLongClickListener(null)
                b.followBtn.isLongClickable = false
            } else {
                b.followBtn.text = ctx.getString(R.string.follow)
                palette.applyFollowBtn(b.followBtn)
                b.followBtn.setTextColor(Color.WHITE)
                b.followBtn.setOnClick {
                    fragment.followUser(
                        it as ProgressTextButton,
                        user.id,
                        Params.TYPE_PUBLIC
                    )
                }
                b.followBtn.setOnLongClickListener {
                    fragment.followUser(b.followBtn, user.id, Params.TYPE_PRIVATE); true
                }
            }
        }
    }

    inner class DetailPanelVH(private val b: SectionV3DetailPanelBinding) :
        RecyclerView.ViewHolder(b.root) {

        private var expanded = true

        fun bind(illust: IllustsBean) {
            b.detailGrid.removeAllViews()
            buildChips(illust)
            b.detailHeader.setOnClickListener {
                expanded = !expanded
                val grid = b.detailGrid;
                val arrow = b.detailArrow
                if (!expanded) {
                    grid.animate().alpha(0f).translationY(-12.ppppx.toFloat()).setDuration(250)
                        .setInterpolator(DecelerateInterpolator(2f))
                        .withEndAction { grid.isVisible = false; grid.translationY = 0f }.start()
                    arrow.animate().rotation(180f).setDuration(300).start()
                } else {
                    grid.alpha = 0f; grid.translationY = -12.ppppx.toFloat(); grid.isVisible = true
                    grid.animate().alpha(1f).translationY(0f).setDuration(350)
                        .setInterpolator(DecelerateInterpolator(2f)).start()
                    arrow.animate().rotation(0f).setDuration(300).start()
                }
            }
        }

        private fun buildChips(illust: IllustsBean) {
            fun s(resId: Int) = ctx.getString(resId)
            val chips = listOf(
                s(R.string.v3_detail_artwork_id) to illust.id.toString(),
                s(R.string.v3_detail_user_id) to (illust.user?.id?.toString() ?: "--"),
                s(R.string.v3_detail_type) to when (illust.type) {
                    "manga" -> s(R.string.v3_type_manga)
                    "ugoira" -> s(R.string.v3_type_ugoira)
                    else -> s(R.string.v3_type_illustration)
                },
                s(R.string.v3_detail_resolution) to "${illust.width} × ${illust.height}",
                s(R.string.v3_detail_pages) to illust.page_count.toString(),
                s(R.string.v3_detail_ai) to if (illust.illust_ai_type == 2) s(R.string.v3_detail_ai_yes) else s(
                    R.string.v3_detail_ai_no
                ),
                s(R.string.v3_detail_restriction) to when {
                    illust.x_restrict == 1 -> "R-18"
                    illust.x_restrict == 2 -> "R-18G"
                    else -> s(R.string.v3_detail_all_ages)
                },
                s(R.string.v3_detail_published) to Common.getLocalYYYYMMDDHHMMString(illust.create_date)
            )
            for (i in chips.indices step 2) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    if (i > 0) layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 8.ppppx }
                }
                row.addView(createDetailChip(chips[i].first, chips[i].second, illust))
                if (i + 1 < chips.size) {
                    row.addView(View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(8.ppppx, 1)
                    })
                    row.addView(createDetailChip(chips[i + 1].first, chips[i + 1].second, illust))
                }
                b.detailGrid.addView(row)
            }
        }

        private fun createDetailChip(
            label: String,
            value: String,
            illust: IllustsBean
        ): LinearLayout {
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.v3_detail_chip_bg)
                setPadding(12.ppppx, 10.ppppx, 12.ppppx, 10.ppppx)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(ctx).apply {
                    text = label.uppercase(); textSize = 9f
                    setTextColor(ctx.getColor(R.color.v3_text_3)); letterSpacing = 0.08f; alpha =
                    0.7f
                })
                val artworkIdLabel = ctx.getString(R.string.v3_detail_artwork_id)
                val userIdLabel = ctx.getString(R.string.v3_detail_user_id)
                val aiLabel = ctx.getString(R.string.v3_detail_ai)
                val restrictionLabel = ctx.getString(R.string.v3_detail_restriction)
                addView(TextView(ctx).apply {
                    text = value; textSize = 13f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    setTypeface(
                        if (label == artworkIdLabel || label == userIdLabel) Typeface.MONOSPACE else typeface,
                        Typeface.BOLD
                    )
                    setTextColor(
                        when {
                            label == artworkIdLabel || label == userIdLabel -> palette.textAccent
                            label == aiLabel && illust.illust_ai_type == 2 -> ctx.getColor(R.color.v3_purple)
                            label == aiLabel -> ctx.getColor(R.color.v3_green)
                            label == restrictionLabel && illust.x_restrict > 0 -> ctx.getColor(R.color.v3_pink)
                            label == restrictionLabel -> ctx.getColor(R.color.v3_blue)
                            else -> ctx.getColor(R.color.v3_text_1)
                        }
                    )
                    alpha = if (label == artworkIdLabel || label == userIdLabel) 1f else 0.8f
                })
                setOnClickListener { Common.copy(ctx, value) }
            }
        }
    }

    inner class CommentsVH(private val b: SectionV3CommentsBinding) :
        RecyclerView.ViewHolder(b.root) {
        private var observing = false

        fun bind(item: ArtworkDetailItem.Comments) {
            fun openCommentList() {
                val intent = Intent(ctx, TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "相关评论")
                intent.putExtra(Params.ILLUST_ID, item.illustId)
                intent.putExtra(Params.ILLUST_TITLE, item.illustTitle)
                ctx.startActivity(intent)
            }

            // 「查看更多」常驻评论 label 右侧:不管有没有评论(甚至加载中)都能点进完整列表
            b.commentsMore.setTextColor(palette.textAccent)
            b.commentsMore.setOnClick { openCommentList() }

            // 「添加评论」入口:展示已登录用户头像 + 描边胶囊(跟输入栏本身的胶囊同款
            // settingsCardBg——圆角 22dp + 1px hairline),点击唤出 Fragment 底部的内联输入栏,
            // 不再跳走完整评论列表页。
            val density = ctx.resources.displayMetrics.density
            b.addCommentEntry.background = palette.settingsCardBg(22f * density, (1 * density).toInt())
            b.addCommentAvatar.binding_loadUserIcon(SessionManager.loggedInUser)
            b.addCommentEntry.setOnClick { onClickAddComment?.invoke() }

            if (!observing) {
                observing = true
                item.liveData.observe(fragment.viewLifecycleOwner) { comments ->
                    render(comments, item.illustAuthorId)
                }
            }
        }

        private fun render(comments: List<Comment>?, illustAuthorId: Int) {
            val isLoading = comments == null
            b.commentsLoading.isVisible = isLoading
            b.commentsList.isVisible = !isLoading
            b.commentsEmpty.isVisible = false
            if (isLoading) return

            b.commentsList.removeAllViews()
            val hasComments = comments.isNotEmpty()
            b.commentsEmpty.isVisible = !hasComments

            // 作品评论列表页毛玻璃卡的缩小版预览:复用 cell_comment_preview + V3Palette 强调色
            val inflater = LayoutInflater.from(ctx)
            val accent = palette.textAccent
            comments.forEach { comment ->
                val cell = CellCommentPreviewBinding.inflate(inflater, b.commentsList, false)
                (cell.root.layoutParams as ViewGroup.MarginLayoutParams).topMargin =
                    if (b.commentsList.childCount > 0) 8.ppppx else 0

                cell.userName.text = comment.user.name
                cell.commentTime.text = comment.displayCommentDate()

                val isArthur = illustAuthorId.toLong() == comment.user.id
                cell.arthurLabel.isVisible = isArthur
                if (isArthur) {
                    cell.arthurLabel.backgroundTintList = ColorStateList.valueOf(palette.alpha15)
                    cell.arthurLabel.setTextColor(accent)
                }
                cell.userIcon.borderColor =
                    if (isArthur) accent else ctx.getColor(R.color.v3_border_2)
                comment.user.profile_image_urls?.medium?.let {
                    glide.load(GlideUrlChild(it)).circleCrop().into(cell.userIcon)
                }

                val stampUrl = comment.stamp?.stamp_url
                cell.commentStamp.isVisible = stampUrl != null
                cell.commentContent.isVisible = stampUrl == null
                if (stampUrl != null) {
                    glide.load(GlideUrlChild(stampUrl)).into(cell.commentStamp)
                } else {
                    cell.commentContent.text = CommentEmojiSpanner.format(
                        ctx, comment.comment, cell.commentContent.textSize.toInt()
                    )
                }

                // 长按预览卡:与列表页保持一致(只读一瞥,取子集:复制评论 / 查看用户)
                cell.root.setOnLongClickListener {
                    val text = comment.comment
                    fragment.showV3Menu("PreviewCommentMenu") {
                        if (!text.isNullOrBlank()) {
                            item(
                                ctx.getString(R.string.string_173),
                                R.drawable.baseline_content_copy_24
                            ) { ClipBoardUtils.putTextIntoClipboard(ctx, text) }
                            item(
                                ctx.getString(R.string.comment_translate_to_zh),
                                R.drawable.ic_baseline_translate_24
                            ) { fragment.translateCommentToChinese(text) }
                        }
                        item(
                            ctx.getString(R.string.string_174),
                            R.drawable.ic_supervisor_account_black_24dp
                        ) {
                            val intent = Intent(ctx, UActivity::class.java)
                            intent.putExtra(Params.USER_ID, comment.user.id.toInt())
                            ctx.startActivity(intent)
                        }
                    }
                    true
                }

                b.commentsList.addView(cell.root)
            }
        }
    }

    inner class AuthorWorksVH(private val b: SectionV3AuthorWorksBinding) :
        RecyclerView.ViewHolder(b.root) {
        private var lAdapter: LAdapter? = null
        private val worksList = mutableListOf<IllustsBean>()
        private var observing = false

        fun bind(item: ArtworkDetailItem.AuthorWorks) {
            b.authorWorksLabel.text =
                ctx.getString(R.string.v3_author_works, item.authorName).uppercase()
            b.authorWorksSeeAll.setTextColor(palette.textAccent)
            b.authorWorksSeeAll.setOnClickListener {
                val intent = Intent(ctx, TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "插画作品")
                intent.putExtra(Params.USER_ID, item.userId)
                ctx.startActivity(intent)
            }
            if (!observing) {
                observing = true
                item.liveData.observe(fragment.viewLifecycleOwner) { works -> render(works) }
            }
        }

        private fun render(works: List<IllustsBean>?) {
            if (works == null) {
                b.authorWorksLoading.isVisible = true
                b.authorWorksRv.isVisible = false
                b.authorWorksSeeAll.isVisible = false
                return
            }

            b.authorWorksLoading.isVisible = false

            if (works.isEmpty()) {
                b.authorWorksLabel.isVisible = false
                b.authorWorksRv.isVisible = false
                b.authorWorksSeeAll.isVisible = false
                return
            }

            b.authorWorksLabel.isVisible = true
            b.authorWorksRv.isVisible = true
            b.authorWorksSeeAll.isVisible = true

            if (lAdapter == null) {
                lAdapter = LAdapter(worksList, ctx)
                lAdapter!!.setOnItemClickListener { _, position, _ ->
                    val pageData = PageData(worksList)
                    Container.get().addPageToMap(pageData)
                    val intent = Intent(ctx, VActivity::class.java)
                    intent.putExtra(Params.POSITION, position)
                    intent.putExtra(Params.PAGE_UUID, pageData.uuid)
                    ctx.startActivity(intent)
                }
                b.authorWorksRv.layoutManager =
                    LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
                b.authorWorksRv.addItemDecoration(object : RecyclerView.ItemDecoration() {
                    override fun getItemOffsets(
                        outRect: android.graphics.Rect, view: View,
                        parent: RecyclerView, state: RecyclerView.State
                    ) {
                        outRect.right = 8.ppppx
                    }
                })
                b.authorWorksRv.adapter = lAdapter
                val lp = b.authorWorksRv.layoutParams
                lp.height = lAdapter!!.imageSize +
                        ctx.resources.getDimensionPixelSize(R.dimen.sixteen_dp)
                b.authorWorksRv.layoutParams = lp
            }

            if (worksList != works) {
                worksList.clear()
                worksList.addAll(works)
                lAdapter?.notifyDataSetChanged()
            }
        }
    }

    inner class RelatedHeaderVH(private val b: SectionV3RelatedHeaderBinding) :
        RecyclerView.ViewHolder(b.root) {
        private var observing = false

        fun bind(item: ArtworkDetailItem.RelatedHeader) {
            b.relatedSeeMore.setTextColor(palette.textAccent)
            b.relatedSeeMore.setOnClick {
                val intent = Intent(ctx, TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "相关作品")
                intent.putExtra(Params.ILLUST_ID, item.illustId)
                intent.putExtra(Params.ILLUST_TITLE, item.illustTitle)
                ctx.startActivity(intent)
            }
            if (!observing) {
                observing = true
                item.liveData.observe(fragment.viewLifecycleOwner) { loaded -> render(loaded) }
            }
        }

        private fun render(loaded: Boolean?) {
            when (loaded) {
                null -> {
                    b.relatedLoadingContainer.isVisible = true
                    b.relatedSeeMore.isVisible = false
                    b.relatedEmpty.isVisible = false
                }

                false -> {
                    b.relatedLoadingContainer.isVisible = false
                    b.relatedSeeMore.isVisible = false
                    b.relatedEmpty.isVisible = true
                }

                true -> {
                    b.relatedLoadingContainer.isVisible = false
                    b.relatedSeeMore.isVisible = true
                    b.relatedEmpty.isVisible = false
                }
            }
        }
    }

    // =================== Helpers ===================

    private fun applyTouchScale(view: View, scale: Float = 0.97f) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(scale).scaleY(scale).setDuration(200)
                    .start()

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f)
                    .scaleY(1f).setDuration(200).start()
            }
            false
        }
    }

    companion object {
        private const val TAG = "ArtworkV3Adapter"
        const val TYPE_HERO = 0
        const val TYPE_SERIES = 1
        const val TYPE_DESC = 2
        const val TYPE_STATS = 3
        const val TYPE_TAGS = 4
        const val TYPE_ARTIST = 5
        const val TYPE_DETAIL = 6
        const val TYPE_COMMENTS = 7
        const val TYPE_AUTHOR_WORKS = 8
        const val TYPE_RELATED_HEADER = 9
    }
}
