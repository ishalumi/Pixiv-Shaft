package ceui.pixiv.plaza.ui

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.CellPlazaPostBinding
import ceui.lisa.databinding.CellPlazaRefIllustBinding
import ceui.lisa.utils.Params
import ceui.pixiv.chat.base.BaseListAdapter
import ceui.pixiv.plaza.api.PlazaIllustRef
import ceui.pixiv.plaza.api.PlazaPost
import ceui.pixiv.plaza.api.PlazaUserRef
import ceui.pixiv.utils.ppppx
import com.bumptech.glide.Glide

/**
 * 广场 feed adapter。每条卡片一个 [PlazaPostViewHolder]。
 *
 * 引用 illust 走嵌套 RecyclerView,layout 数随 illust 数量动态切:
 *   - 1 → 单列大图 (cell 高 220dp)
 *   - 2 → 2 列方块
 *   - 3..9 → 3 列方块
 *
 * 用户点击事件回调 onMore (作者点 ⋯) / onRefIllustClick (点引用图)
 * 通过构造参数透出,避免在 Holder 里持 Fragment ref。
 */
class PlazaFeedAdapter(
    private val selfUid: Long,
    private val onMore: (PlazaPost, View) -> Unit,
) : BaseListAdapter<PlazaPost, PlazaFeedAdapter.PlazaPostViewHolder>(
    diffCallback(keySelector = { it.id })
) {

    override fun onCreateDataViewHolder(parent: ViewGroup, viewType: Int): PlazaPostViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = CellPlazaPostBinding.inflate(inflater, parent, false)
        return PlazaPostViewHolder(binding, selfUid, onMore)
    }

    override fun onBindDataViewHolder(holder: PlazaPostViewHolder, item: PlazaPost) {
        holder.bind(item)
    }

    class PlazaPostViewHolder(
        private val binding: CellPlazaPostBinding,
        private val selfUid: Long,
        private val onMore: (PlazaPost, View) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(post: PlazaPost) {
            val ctx = binding.root.context

            binding.displayName.text = post.display_name ?: post.uid.toString()
            binding.postTime.text = formatRelativeTime(ctx, post.ts)
            binding.bodyText.text = post.text

            // 作者头像 —— server 没下发，先用占位。后续可以从 ObjectPool.get<User>(post.uid)
            // 或本地 chat profile 缓存补头像，这里 MVP 用占位。
            binding.avatar.setImageResource(R.drawable.chat_avatar_placeholder)

            // ⋯ 菜单只在自己的帖子上显示 —— 当前菜单只有「删除」,展示给别人没意义。
            // 后续补举报 / 复制链接等通用项时再放开 non-author 路径。
            val isMine = selfUid > 0L && post.uid == selfUid
            binding.moreBtn.isVisible = isMine
            if (isMine) {
                binding.moreBtn.setOnClickListener { v -> onMore(post, v) }
            } else {
                binding.moreBtn.setOnClickListener(null)
            }

            bindIllustGrid(post.refs.illust)
            bindUserRefs(post.refs.user)
        }

        private fun bindIllustGrid(illusts: List<PlazaIllustRef>) {
            val grid = binding.illustGrid
            if (illusts.isEmpty()) {
                grid.isVisible = false
                grid.adapter = null
                return
            }
            grid.isVisible = true

            val ctx = grid.context
            val spans = when {
                illusts.size == 1 -> 1
                illusts.size == 2 -> 2
                else -> 3
            }
            grid.layoutManager = GridLayoutManager(ctx, spans)
            // 嵌套 RV 关 nested scroll，避免跟外层 SmartRefresh 抢手势
            grid.isNestedScrollingEnabled = false
            // 卡片宽度 - 28dp(左右内边距)。每 cell 含左右各 2dp margin =>
            // 实际占用 = (cellWidth + 4dp) * spans。所以 cellWidth 要扣掉
            // spans 个 4dp 而不是 (spans-1) 个 —— 否则最后一列会贴到 cardWidth
            // 边界外被裁。
            val cardWidth = grid.resources.displayMetrics.widthPixels - 24.ppppx - 28.ppppx
            val cellWidth = (cardWidth - spans * 4.ppppx) / spans
            val cellHeight = if (spans == 1) 220.ppppx else cellWidth
            grid.adapter = PlazaIllustRefAdapter(illusts, cellWidth, cellHeight)
        }

        private fun bindUserRefs(users: List<PlazaUserRef>) {
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
    }
}

/**
 * 卡片内嵌的 illust grid adapter。cell 用 [CellPlazaRefIllustBinding]。
 * 点击进 ArtworkV3Fragment。
 */
private class PlazaIllustRefAdapter(
    private val items: List<PlazaIllustRef>,
    private val cellWidth: Int,
    private val cellHeight: Int,
) : RecyclerView.Adapter<PlazaIllustRefAdapter.RefHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RefHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = CellPlazaRefIllustBinding.inflate(inflater, parent, false)
        // cell 自己设固定尺寸,避免 wrap_content 在 GridLayoutManager 下塌缩。
        // 用 RecyclerView.LayoutParams (而不是 ViewGroup.MarginLayoutParams) ——
        // RecyclerView.checkLayoutParams 要求 instanceof RecyclerView.LayoutParams,
        // 不匹配会走 generateLayoutParams 转换路径,有丢尺寸的可能。
        binding.root.layoutParams = RecyclerView.LayoutParams(cellWidth, cellHeight).apply {
            setMargins(2.ppppx, 2.ppppx, 2.ppppx, 2.ppppx)
        }
        return RefHolder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RefHolder, position: Int) {
        holder.bind(items[position])
    }

    class RefHolder(val binding: CellPlazaRefIllustBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(ref: PlazaIllustRef) {
            val ctx = binding.root.context
            val thumbUrl = ref.meta?.thumb_url
            if (thumbUrl.isNullOrEmpty()) {
                // server 还没缓存这个 illust 的 meta —— 显示 ID 占位,
                // 用户仍可点击进详情让 ArtworkV3Fragment 自己拉
                binding.thumb.setImageDrawable(null)
                binding.placeholderId.isVisible = true
                binding.placeholderId.text = ref.id.toString()
            } else {
                binding.placeholderId.isVisible = false
                Glide.with(ctx)
                    .load(thumbUrl)
                    .placeholder(android.R.color.transparent)
                    .into(binding.thumb)
            }
            binding.root.setOnClickListener {
                val intent = Intent(ctx, TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "Plaza打开作品")
                intent.putExtra(Params.ILLUST_ID, ref.id.toInt())
                ctx.startActivity(intent)
            }
        }
    }
}

/** ts (毫秒) 相对当前时间格式化。 */
private fun formatRelativeTime(ctx: android.content.Context, tsMillis: Long): String {
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
