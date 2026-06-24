package ceui.lisa.fragments

import android.graphics.Color
import android.widget.ImageView
import androidx.core.view.isVisible
import ceui.lisa.R

/**
 * 浏览历史多选态的勾选角标渲染。三个 holder(插画/小说/用户)共用一份,保证三 tab
 * 的选中/未选中视觉一致。
 *  - 非选择态:隐藏
 *  - 选择态 + 已选:实心(?attr/colorPrimary)圆 + 白勾
 *  - 选择态 + 未选:半透明空心圈(无勾),提示"可点选"
 */
object HistorySelectBadge {

    fun bind(badge: ImageView, selectionMode: Boolean, selected: Boolean) {
        badge.isVisible = selectionMode
        if (!selectionMode) return
        if (selected) {
            badge.setBackgroundResource(R.drawable.bulk_select_check_bg)
            badge.setImageResource(R.drawable.ic_check_24dp)
            badge.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        } else {
            badge.setBackgroundResource(R.drawable.history_check_unselected)
            badge.setImageDrawable(null)
        }
    }
}
