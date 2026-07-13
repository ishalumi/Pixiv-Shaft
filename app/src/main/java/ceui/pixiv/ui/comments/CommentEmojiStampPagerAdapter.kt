package ceui.pixiv.ui.comments

import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 「绘文字」/「表情贴图」面板的两页,喂给 ViewPager2:配合 tab 点击 + registerOnPageChangeCallback
 * 双向同步,左右滑动即可切页(同官方 App 打法),不必只靠点 tab。
 */
class CommentEmojiStampPagerAdapter(
    private val kaomojiAdapter: CommentEmojiPickerAdapter,
    private val stampAdapter: CommentStampPickerAdapter,
) : RecyclerView.Adapter<CommentEmojiStampPagerAdapter.PageVH>() {

    class PageVH(val recyclerView: RecyclerView) : RecyclerView.ViewHolder(recyclerView)

    override fun getItemCount(): Int = 2

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
        val density = parent.resources.displayMetrics.density
        val recyclerView = RecyclerView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            clipToPadding = false
            val padH = (10 * density).toInt()
            val padV = (6 * density).toInt()
            setPadding(padH, padV, padH, padV)
        }
        return PageVH(recyclerView)
    }

    override fun onBindViewHolder(holder: PageVH, position: Int) {
        if (position == 0) {
            holder.recyclerView.layoutManager = GridLayoutManager(holder.recyclerView.context, 7)
            holder.recyclerView.adapter = kaomojiAdapter
        } else {
            holder.recyclerView.layoutManager = GridLayoutManager(holder.recyclerView.context, 4)
            holder.recyclerView.adapter = stampAdapter
        }
    }
}
