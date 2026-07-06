package ceui.pixiv.ui.watchlater

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.models.IllustsBean
import ceui.pixiv.db.RecordType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「稍后再看」列表页的数据。按项目约定，DB 回来的列表是异步状态，必须由 VM 承载
 * （Fragment 不当数据持有方）——顺带让列表在配置变更（旋转）后存活。所有 DB 访问用
 * application context（[Shaft.getContext]），不在 IO 线程碰 Fragment context。
 *
 * 数据源见 [WatchLaterFragment]：general_table(WATCH_LATER) 里存的是 IllustsBean JSON。
 */
class WatchLaterViewModel : ViewModel() {

    private val _items = MutableLiveData<List<IllustsBean>>()
    val items: LiveData<List<IllustsBean>> = _items

    /** 当前列表快照，供「全部播放」「清空前判空」等一次性动作直接取用。 */
    val current: List<IllustsBean> get() = _items.value.orEmpty()

    /**
     * 重拉 DB 覆盖 [items]。[done] 是完成信号（如 finishRefresh），主线程回调，不承载数据。
     * 跑在 viewModelScope 上，配置变更后仍会把结果 post 给存活的 VM。
     */
    fun reload(done: (() -> Unit)? = null) {
        viewModelScope.launch {
            val beans = withContext(Dispatchers.IO) {
                AppDatabase.getAppDatabase(Shaft.getContext()).generalDao()
                    .getByRecordType(RecordType.WATCH_LATER, 0, Int.MAX_VALUE)
                    .mapNotNull {
                        runCatching { Shaft.sGson.fromJson(it.json, IllustsBean::class.java) }.getOrNull()
                    }
            }
            _items.value = beans
            done?.invoke()
        }
    }
}
