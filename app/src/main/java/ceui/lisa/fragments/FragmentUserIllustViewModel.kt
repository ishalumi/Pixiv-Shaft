package ceui.lisa.fragments

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

/**
 * FragmentUserIllust 的轻量 VM —— 只装跨配置变更要存活的数据。
 * 目前是作者的插画总数 (用来给「下载全部」二次确认弹窗里报准数字)。
 */
class FragmentUserIllustViewModel : ViewModel() {
    /** 作者插画总数;-1 = 还没拉到。Fragment 观察这条决定按钮是否显出来 + 弹窗里报数字。 */
    val totalIllusts = MutableLiveData<Int>()
}
