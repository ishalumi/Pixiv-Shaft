package ceui.lisa.fragments

import androidx.lifecycle.LiveData

/**
 * 浏览历史子 tab 的多选删除契约。host([FragmentHistoryTabs]) 上 toolbar 点「多选」后,
 * 把当前可见 tab 切进选择态:host 负责 contextual toolbar(✕/已选 N 项/全选/删除),
 * 子 tab 负责自己列表的选中状态 + 落库删除。一次只有当前 tab 进选择态,切 tab/返回即退出。
 */
interface SelectableHistoryTab {

    /** 当前已选条数,host 用来刷 toolbar 标题 + 删除键可用态。 */
    val selectedCount: LiveData<Int>

    /** 列表当前是否有可选条目(空列表没必要进选择态)。 */
    fun hasItems(): Boolean

    /** 当前加载出的条目是否已全选,host 用来切「全选 / 取消全选」图标。 */
    fun isAllSelected(): Boolean

    /** 进选择态(清空旧选中,列表开始显示勾选框)。 */
    fun enterSelectionMode()

    /** 退选择态(清空选中,恢复普通点击/长按)。 */
    fun exitSelectionMode()

    /** 全选 ↔ 取消全选(master-checkbox 行为,跟 [isAllSelected] 对称)。 */
    fun toggleSelectAll()

    /** 删除已选条目(本地 + 远端),完成后回调实际删除条数让 host 提示并恢复 toolbar。 */
    fun deleteSelected(onComplete: (deletedCount: Int) -> Unit)
}
