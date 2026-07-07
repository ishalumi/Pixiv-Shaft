package ceui.lisa.fragments

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.downloadProbeDispatcher
import ceui.lisa.database.hasDownloadRecord
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Common
import ceui.loxia.ObjectPool
import ceui.loxia.fetchFullIllustDetail
import ceui.loxia.isFullDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * VM for the "new" illust detail page (FragmentIllust).
 *
 * Exists primarily to move the download-state probe (SAF existence + Room query)
 * off the main thread — on Android 11+ SAF queries per page add up fast, and for
 * multi-P works this was ANR'ing the detail screen on entry (issue #835).
 */
class FragmentIllustViewModel(private val illustId: Long) : ViewModel() {

    private val _hasDownload = MutableLiveData<Boolean>()
    val hasDownload: LiveData<Boolean> = _hasDownload

    init {
        // issue #569: 从「按 Tag 筛选」等精简来源进来时,池里的 bean 缺分页图/原图。后台回 API 拉完整版,
        // 整体覆盖 ObjectPool 后,FragmentIllust 的 illust observer 会带完整数据再次 fire、自动重建图片区。
        // 拉取失败则保留现有(精简)数据 —— GlideUtil / IllustDownload 已加空值兜底,不会崩,降级显示封面。
        viewModelScope.launch {
            val cur = ObjectPool.get<IllustsBean>(illustId).value
            if (cur == null || !cur.isFullDetail()) {
                fetchFullIllustDetail(illustId)
            }
        }
    }

    /** Kick off an async download-state refresh. Result lands on [hasDownload]. */
    fun refreshDownloadState(context: Context) {
        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val illust = ObjectPool.get<IllustsBean>(illustId).value
                    ?: return@launch
                val hasLocalFile = Common.isIllustDownloaded(illust)
                // hasDownloadRecord 走 v38 的 illustId 索引（O(log n)），不再扫 2GB illustGson blob；
                // 存量回填未完成时才退回旧 LIKE 兜底。仍串行到单车道 dispatcher 兜底旧库/回填窗口期。
                val hasRecord = if (hasLocalFile) false else withContext(downloadProbeDispatcher) {
                    AppDatabase
                        .getAppDatabase(appContext)
                        .downloadDao()
                        .hasDownloadRecord(illust.id.toLong())
                }
                _hasDownload.postValue(hasLocalFile || hasRecord)
            } catch (e: Exception) {
                Timber.w(e, "refreshDownloadState failed illustId=%d", illustId)
            }
        }
    }

    class Factory(private val illustId: Long) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FragmentIllustViewModel(illustId) as T
        }
    }
}
