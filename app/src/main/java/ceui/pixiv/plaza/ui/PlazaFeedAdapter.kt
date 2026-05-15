package ceui.pixiv.plaza.ui

import android.content.Intent
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.CellPlazaPostBinding
import ceui.lisa.databinding.CellPlazaRefIllustBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.utils.Params
import ceui.pixiv.chat.base.BaseListAdapter
import ceui.lisa.network.PlazaIllustRef
import ceui.lisa.network.PlazaPost
import ceui.lisa.network.PlazaUserRef
import ceui.loxia.ObjectPool
import ceui.pixiv.utils.ppppx
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target

/**
 * 广场 feed adapter。每条卡片一个 [PlazaPostViewHolder]。
 *
 * 引用 illust 走 LinearLayout 容器,布局按数量切:
 *   - 1 → 单图按原比例 wrap (限 maxHeight)
 *   - 2 → 横排 2 方块
 *   - 3 → 左大 + 右 2 小 (Twitter 风)
 *   - 4 → 2x2 方块
 *   - 5..9 → 3 列方块
 *
 * 用户点击事件回调 onMore (作者点 ⋯) / onRefIllustClick (点引用图)
 * 通过构造参数透出,避免在 Holder 里持 Fragment ref。
 */
class PlazaFeedAdapter(
    private val selfUid: Long,
    private val onMore: (PlazaPost, View) -> Unit,
    private val onCardClick: (PlazaPost) -> Unit,
) : BaseListAdapter<PlazaPost, PlazaFeedAdapter.PlazaPostViewHolder>(
    diffCallback(keySelector = { it.id })
) {

    override fun onCreateDataViewHolder(parent: ViewGroup, viewType: Int): PlazaPostViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = CellPlazaPostBinding.inflate(inflater, parent, false)
        return PlazaPostViewHolder(binding, selfUid, onMore, onCardClick)
    }

    override fun onBindDataViewHolder(holder: PlazaPostViewHolder, item: PlazaPost) {
        holder.bind(item)
    }

    class PlazaPostViewHolder(
        private val binding: CellPlazaPostBinding,
        private val selfUid: Long,
        private val onMore: (PlazaPost, View) -> Unit,
        private val onCardClick: (PlazaPost) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(post: PlazaPost) {
            bindPlazaPostCard(
                binding = binding,
                post = post,
                selfUid = selfUid,
                onMore = onMore,
                onCardClick = onCardClick,
                verticalIllustLayout = false,
            )
        }
    }
}

/**
 * 把一个 [CellPlazaPostBinding] 当模板,用 post 数据填好整张卡片 —— 包括头像 /
 * 名字 / 时间 / 正文 / 引用 illust grid / 引用 user chips / ⋯ 菜单 / 卡片整体 click。
 *
 * Feed 的 RecyclerView Holder 用,详情页 PlazaPostDetailFragment 也用 (复用视觉一致)。
 * onMore 仅在 selfUid 匹配时挂监听 + 显示按钮。onCardClick 传 null 表示不挂(详情页
 * 已经在自己内部,无需再 navigate)。
 */
internal fun bindPlazaPostCard(
    binding: CellPlazaPostBinding,
    post: PlazaPost,
    selfUid: Long,
    onMore: ((PlazaPost, View) -> Unit)?,
    onCardClick: ((PlazaPost) -> Unit)?,
    /**
     * 详情页用 true: 每张引用图按原宽高比纵向堆叠,特别高的图收窄宽度(不撑满)。
     * Feed 用 false: 走 [bindIllustGrid] 的 1/2/3/4/N 网格策略。
     */
    verticalIllustLayout: Boolean = false,
) {
    val ctx = binding.root.context

    binding.displayName.text = post.display_name ?: post.uid.toString()
    binding.postTime.text = formatRelativeTime(ctx, post.ts)
    binding.bodyText.text = post.text

    // 作者头像 —— server 没下发,先用占位。后续可以从 ObjectPool.get<User>(post.uid)
    // 或本地 chat profile 缓存补头像。
    binding.avatar.setImageResource(R.drawable.chat_avatar_placeholder)

    // ⋯ 菜单只在自己的帖子上显示。当前菜单只有「删除」,展示给别人没意义。
    val isMine = selfUid > 0L && post.uid == selfUid
    binding.moreBtn.isVisible = isMine && onMore != null
    if (isMine && onMore != null) {
        binding.moreBtn.setOnClickListener { v -> onMore(post, v) }
    } else {
        binding.moreBtn.setOnClickListener(null)
    }

    // 卡片整体点击 —— feed 页跳详情。详情页 onCardClick = null 不挂。
    // 排除头像 / illust 缩略 / ⋯ 这些已有自己 click 的子区。
    if (onCardClick != null) {
        binding.card.setOnClickListener { onCardClick(post) }
    } else {
        binding.card.setOnClickListener(null)
        binding.card.isClickable = false
    }

    if (verticalIllustLayout) {
        bindIllustsVertical(binding, post.refs.illust)
    } else {
        bindIllustGrid(binding, post.refs.illust)
    }
    bindUserRefs(binding, post.refs.user)
    bindActionChips(binding, post)
}

/**
 * 渲染 like / comment 计数 chip。Feed 卡片里只展示数值,点击转跳详情页统一
 * 处理 toggle(避免多入口同时改 like_count 漂移)。详情页 viewModel 走乐观
 * 更新 + server 权威 count 校正。
 */
private fun bindActionChips(binding: CellPlazaPostBinding, post: PlazaPost) {
    binding.likeCount.text = post.like_count.toString()
    binding.commentCount.text = post.comment_count.toString()
    binding.likeIcon.setImageResource(
        if (post.liked_by_viewer == true) R.drawable.ic_heart_filled_16
        else R.drawable.ic_heart_outline_16
    )
}

private fun bindIllustGrid(binding: CellPlazaPostBinding, illusts: List<PlazaIllustRef>) {
    val container = binding.illustGrid
    container.removeAllViews()
    if (illusts.isEmpty()) {
        container.isVisible = false
        return
    }
    container.isVisible = true

    // 屏宽 - illust_grid 容器自身左右 18dp margin。新版 cell 没 CardView
    // 包装,直接铺满父宽,卡片间用 divider 分隔。
    val cardWidth = container.resources.displayMetrics.widthPixels - 36.ppppx
    val gap = 4.ppppx

    when (illusts.size) {
        1 -> container.addView(buildSingleCell(container, illusts[0], cardWidth))
        2 -> {
            val cell = (cardWidth - gap) / 2
            container.addView(buildSquareRow(container, illusts, cell, gap))
        }
        3 -> container.addView(buildBigPlusTwoSmall(container, illusts, cardWidth, gap))
        4 -> {
            val cell = (cardWidth - gap) / 2
            container.addView(buildSquareRow(container, illusts.subList(0, 2), cell, gap))
            container.addView(buildSquareRow(container, illusts.subList(2, 4), cell, gap).also {
                (it.layoutParams as LinearLayout.LayoutParams).topMargin = gap
            })
        }
        else -> {
            // 5..9: 每行 3 个方块
            val columns = 3
            val cell = (cardWidth - gap * (columns - 1)) / columns
            var idx = 0
            while (idx < illusts.size) {
                val end = minOf(idx + columns, illusts.size)
                val row = buildSquareRow(container, illusts.subList(idx, end), cell, gap)
                if (idx > 0) (row.layoutParams as LinearLayout.LayoutParams).topMargin = gap
                container.addView(row)
                idx += columns
            }
        }
    }
}

/**
 * 详情页专用:引用图纵向堆叠,每张保留自己原始宽高比。
 *
 * 高图限高策略:cell 最大高度 = cardWidth * MAX_RATIO。比这更高的图,**收窄宽度**
 * 让 height 顶到 maxHeight —— 即"特别高的图,宽度不会撑满"。结果是 portrait
 * 巨长图会变成一根窄长条,水平居中。
 *
 * 低/常规图 (h/w ≤ MAX_RATIO):width = cardWidth,height = w * h0/w0,撑满父宽。
 */
private fun bindIllustsVertical(binding: CellPlazaPostBinding, illusts: List<PlazaIllustRef>) {
    val container = binding.illustGrid
    container.removeAllViews()
    if (illusts.isEmpty()) {
        container.isVisible = false
        return
    }
    container.isVisible = true

    val cardWidth = container.resources.displayMetrics.widthPixels - 36.ppppx
    // 高图阈值:cell 不会高于 cardWidth * 1.4。再高就收宽。
    val maxHeight = (cardWidth * 1.4f).toInt()
    val gap = 8.ppppx

    illusts.forEachIndexed { i, ref ->
        val cell = buildVerticalCell(container, ref, cardWidth, maxHeight)
        if (i > 0) (cell.layoutParams as LinearLayout.LayoutParams).topMargin = gap
        container.addView(cell)
    }
}

/**
 * 详情页里单张引用图 cell。
 *
 * 宽高比来源(优先级):
 *   1. server 返回的 [PlazaIllustMeta.width]/[PlazaIllustMeta.height] —— 帖子入库时
 *      由 IllustsBean 拷过来,详情 GET 直接拿,不需要等图加载。
 *   2. [ObjectPool.getIllust] 本地缓存的 IllustsBean.width/height —— 老帖子 server 还
 *      没下发尺寸时兜底,通常用户都看过这张图,pool 里有现成的。
 *   3. 都没有 → onResourceReady 拿图本身像素尺寸算(下面 Glide listener 那条 fallback)。
 *
 * 计算策略:
 *   - 正常图 (h/w ≤ MAX_RATIO):width = cardWidth,height = w * h0/w0,撑满父宽
 *   - 特别高图 (h/w > MAX_RATIO):height = maxHeight,width = h * w0/h0,**收窄不撑满**
 *   - 窄 cell 用 gravity=center_horizontal 在父宽内居中
 *
 * 图源:thumb_url 是 Pixiv `square_medium`(/c/360x360_70/ 强裁方图),套到非方
 * cell 里 centerCrop 会拉伸出蒙板边或糊面。统一升到 master1200(无裁切、原比例、
 * 最长边 1200),详情见 [upgradeToMaster]。
 */
private fun buildVerticalCell(
    parent: ViewGroup,
    ref: PlazaIllustRef,
    cardWidth: Int,
    maxHeight: Int,
): View {
    val knownW = ref.meta?.width?.takeIf { it > 0 }
        ?: ObjectPool.getIllust(ref.id).value?.width?.takeIf { it > 0 }
    val knownH = ref.meta?.height?.takeIf { it > 0 }
        ?: ObjectPool.getIllust(ref.id).value?.height?.takeIf { it > 0 }

    // 已知尺寸 → 直接算 cell。未知 → cardWidth × cardWidth*0.75 (4:3) 占位,等 Glide
    // onResourceReady 再 patch。
    val (initialW, initialH) = if (knownW != null && knownH != null) {
        computeCellSize(knownW, knownH, cardWidth, maxHeight)
    } else {
        cardWidth to (cardWidth * 0.75f).toInt()
    }

    val cell = inflateCellShell(parent, ref, initialW, initialH)
    (cell.root.layoutParams as LinearLayout.LayoutParams).gravity = android.view.Gravity.CENTER_HORIZONTAL

    val thumbUrl = ref.meta?.thumb_url?.let(::upgradeToMaster)
    if (!thumbUrl.isNullOrEmpty()) {
        val request = Glide.with(parent.context)
            .load(GlideUrlChild(thumbUrl))
            .placeholder(android.R.color.transparent)

        if (knownW == null || knownH == null) {
            // meta 缺尺寸 → 退化到 Glide 加载后量像素再回填(老帖子 / pool 没缓存)
            request.listener(object : RequestListener<Drawable> {
                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean {
                    val dw = resource.intrinsicWidth
                    val dh = resource.intrinsicHeight
                    if (dw > 0 && dh > 0) {
                        val (tw, th) = computeCellSize(dw, dh, cardWidth, maxHeight)
                        val lp = cell.root.layoutParams as LinearLayout.LayoutParams
                        if (lp.width != tw || lp.height != th) {
                            lp.width = tw
                            lp.height = th
                            cell.root.layoutParams = lp
                        }
                    }
                    return false
                }

                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean,
                ): Boolean = false
            })
        }

        request.into(cell.thumb)
    }
    return cell.root
}

/**
 * 已知原图宽高 → 算 cell 在 cardWidth/maxHeight 约束下的目标宽高,保持原宽高比。
 *
 * 当原图 h/w 超过 maxHeight/cardWidth 时,height 顶到 maxHeight 同时 width 跟着按
 * 原比例缩。即"特别高的图,宽度不会撑满"。
 */
private fun computeCellSize(
    naturalW: Int,
    naturalH: Int,
    cardWidth: Int,
    maxHeight: Int,
): Pair<Int, Int> {
    val fitH = (cardWidth.toFloat() * naturalH / naturalW).toInt()
    return if (fitH > maxHeight) {
        val h = maxHeight
        val w = (h.toFloat() * naturalW / naturalH).toInt().coerceAtLeast(1)
        w to h
    } else {
        cardWidth to fitH
    }
}

/**
 * Pixiv square_medium URL → master1200 URL 转换。
 *
 * 输入示例 `https://i.pximg.net/c/360x360_70/img-master/img/.../12345_p0_square1200.jpg`
 * 输出     `https://i.pximg.net/img-master/img/.../12345_p0_master1200.jpg`
 *
 * 两步替换:
 *   1. 去掉 `/c/<size>[_<q>]/(img-master|custom-thumb)/` 中的 crop 段 →
 *      `/img-master/`,server 给的就是无裁切的最长边 1200 master1200。
 *   2. 文件名 `_square1200` / `_custom1200` → `_master1200`。
 *
 * 不匹配的 URL(非 pximg.net 或格式漂移)整段保留,Glide 仍能 load 原 url 当 fallback。
 */
private fun upgradeToMaster(url: String): String {
    return url
        .replace(Regex("""/c/\d+x\d+(_\d+)?/(img-master|custom-thumb)/"""), "/img-master/")
        .replace("_square1200.", "_master1200.")
        .replace("_custom1200.", "_master1200.")
}

/**
 * 单图: 加载前用 4:3 占位高度撑住,Glide listener 在 onResourceReady 拿到原图比例后
 * 把 root 高度调到目标。比 adjustViewBounds 那条路稳 —— 后者依赖 Glide 触发的
 * requestLayout 链,在 wrap_content 容器里有概率不撑开。
 */
private fun buildSingleCell(parent: ViewGroup, ref: PlazaIllustRef, width: Int): View {
    val placeholderH = (width * 0.75f).toInt()
    val maxH = (width * 1.4f).toInt()
    val minH = (width * 0.4f).toInt()

    val cell = inflateCellShell(parent, ref, width, placeholderH)
    val thumbUrl = ref.meta?.thumb_url
    if (!thumbUrl.isNullOrEmpty()) {
        Glide.with(parent.context)
            .load(GlideUrlChild(thumbUrl))
            .placeholder(android.R.color.transparent)
            .listener(object : RequestListener<Drawable> {
                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean {
                    val dw = resource.intrinsicWidth
                    val dh = resource.intrinsicHeight
                    if (dw > 0 && dh > 0) {
                        val targetH = (width.toFloat() * dh / dw).toInt().coerceIn(minH, maxH)
                        val lp = cell.root.layoutParams as LinearLayout.LayoutParams
                        if (lp.height != targetH) {
                            lp.height = targetH
                            cell.root.layoutParams = lp
                        }
                    }
                    return false
                }

                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean,
                ): Boolean = false
            })
            .into(cell.thumb)
    }
    return cell.root
}

/**
 * 3 张图: 左 1 大方块 (边 = cardWidth/2),右两小竖排 (宽 = cardWidth/2)。
 * 整行高度 = 左方块边长。
 */
private fun buildBigPlusTwoSmall(
    parent: ViewGroup,
    illusts: List<PlazaIllustRef>,
    cardWidth: Int,
    gap: Int,
): View {
    val bigSize = (cardWidth - gap) / 2
    val smallH = (bigSize - gap) / 2

    val row = LinearLayout(parent.context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, bigSize)
    }
    row.addView(inflateCell(row, illusts[0], bigSize, bigSize).root)

    val rightCol = LinearLayout(parent.context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(bigSize, bigSize).apply {
            leftMargin = gap
        }
    }
    rightCol.addView(inflateCell(rightCol, illusts[1], bigSize, smallH).root)
    rightCol.addView(inflateCell(rightCol, illusts[2], bigSize, smallH).root.also {
        (it.layoutParams as LinearLayout.LayoutParams).topMargin = gap
    })
    row.addView(rightCol)
    return row
}

/** 一行 N 个等大方块,横向排列,中间用 gap 隔开。 */
private fun buildSquareRow(
    parent: ViewGroup,
    illusts: List<PlazaIllustRef>,
    cellSize: Int,
    gap: Int,
): LinearLayout {
    val row = LinearLayout(parent.context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, cellSize)
    }
    illusts.forEachIndexed { i, ref ->
        val cell = inflateCell(row, ref, cellSize, cellSize)
        if (i > 0) (cell.root.layoutParams as LinearLayout.LayoutParams).leftMargin = gap
        row.addView(cell.root)
    }
    return row
}

/**
 * Inflate cell + 尺寸 + 占位 ID + click,但**不**调 Glide。
 * 加载图的策略由 caller 决定(多图直接 into,单图带 listener 调高度)。
 */
private fun inflateCellShell(
    parent: ViewGroup,
    ref: PlazaIllustRef,
    width: Int,
    height: Int,
): CellPlazaRefIllustBinding {
    val binding = CellPlazaRefIllustBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    binding.root.layoutParams = LinearLayout.LayoutParams(width, height)
    val ctx = binding.root.context
    if (ref.meta?.thumb_url.isNullOrEmpty()) {
        binding.thumb.setImageDrawable(null)
        binding.placeholderId.isVisible = true
        binding.placeholderId.text = ref.id.toString()
    } else {
        binding.placeholderId.isVisible = false
    }
    binding.root.setOnClickListener {
        val intent = Intent(ctx, TemplateActivity::class.java)
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "Plaza打开作品")
        intent.putExtra(Params.ILLUST_ID, ref.id.toInt())
        ctx.startActivity(intent)
    }
    return binding
}

/** 多图模式:inflateCellShell + 默认 Glide.into。 */
private fun inflateCell(
    parent: ViewGroup,
    ref: PlazaIllustRef,
    width: Int,
    height: Int,
): CellPlazaRefIllustBinding {
    val binding = inflateCellShell(parent, ref, width, height)
    val thumbUrl = ref.meta?.thumb_url
    if (!thumbUrl.isNullOrEmpty()) {
        Glide.with(binding.root.context)
            .load(GlideUrlChild(thumbUrl))
            .placeholder(android.R.color.transparent)
            .into(binding.thumb)
    }
    return binding
}

private fun bindUserRefs(binding: CellPlazaPostBinding, users: List<PlazaUserRef>) {
    val container = binding.userRefsRow
    container.removeAllViews()
    if (users.isEmpty()) {
        container.isVisible = false
        return
    }
    container.isVisible = true
    val ctx = container.context
    for (u in users) {
        val displayName = u.meta?.name ?: u.id.toString()
        val chip = TextView(ctx).apply {
            text = ctx.getString(R.string.plaza_referenced_user, displayName)
            textSize = 12f
            setPadding(12.ppppx, 6.ppppx, 12.ppppx, 6.ppppx)
            setBackgroundResource(R.drawable.bg_v3_chip)
            setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    this, com.google.android.material.R.attr.colorOnSurfaceVariant
                )
            )
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 8.ppppx, 8.ppppx) }
        }
        container.addView(chip)
    }
}

/** ts (毫秒) 相对当前时间格式化。 */
internal fun formatRelativeTime(ctx: android.content.Context, tsMillis: Long): String {
    val deltaMs = (System.currentTimeMillis() - tsMillis).coerceAtLeast(0L)
    val deltaSec = deltaMs / 1000
    return when {
        deltaSec < 60 -> ctx.getString(R.string.plaza_just_now)
        deltaSec < 3600 -> ctx.getString(R.string.plaza_minutes_ago, (deltaSec / 60).toInt())
        deltaSec < 86_400 -> ctx.getString(R.string.plaza_hours_ago, (deltaSec / 3600).toInt())
        deltaSec < 7 * 86_400 -> ctx.getString(R.string.plaza_days_ago, (deltaSec / 86_400).toInt())
        else -> {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            sdf.format(java.util.Date(tsMillis))
        }
    }
}
