package ceui.pixiv.ui.novel.local

import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

/**
 * 本地小说书库的浏览状态。按项目约定，SAF 目录枚举是异步数据，必须由 VM 承载
 * （Fragment 不当数据持有方）——顺带让钻入层级在配置变更（旋转）后存活，且所有
 * ContentResolver 访问用 application context，不在 IO 线程碰 Fragment context。
 */
class LocalLibraryViewModel : ViewModel() {

    data class Crumb(val docId: String, val name: String)
    data class Entry(val docId: String, val name: String, val isDir: Boolean, val size: Long)

    sealed class UiState {
        /** 还没选根目录（或授权失效）。 */
        object NeedRoot : UiState()
        /** 当前目录内容；[entries] 为空即空文件夹。 */
        data class Browsing(val crumbs: List<Crumb>, val entries: List<Entry>) : UiState()
    }

    private val _state = MutableLiveData<UiState>()
    val state: LiveData<UiState> = _state

    private var treeUri: Uri? = null
    private val crumbs = ArrayList<Crumb>()
    private var loadJob: Job? = null
    private var loadGen = 0

    private fun ctx() = Shaft.getContext()

    /** Fragment onViewCreated 调用。已有 treeUri（配置变更后 VM 存活）则保留现状不重载。 */
    fun startIfNeeded() {
        if (treeUri != null) return
        val saved = LocalLibraryStore.rootUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (saved != null && hasPermission(saved)) {
            openRoot(saved)
        } else {
            // 没选过 / 授权丢了（重装、撤权、清数据）→ 清掉脏 URI，回到选文件夹空态。
            if (saved != null) LocalLibraryStore.rootUri = null
            _state.value = UiState.NeedRoot
        }
    }

    fun currentTree(): Uri? = treeUri
    fun currentCrumbs(): List<Crumb> = crumbs.toList()
    fun canGoUp(): Boolean = crumbs.size > 1

    fun openRoot(uri: Uri) {
        treeUri = uri
        val rootDocId = DocumentsContract.getTreeDocumentId(uri) // 纯字符串运算，主线程安全
        crumbs.clear()
        crumbs.add(Crumb(rootDocId, ctx().getString(R.string.local_novel_entry))) // 真实名异步补
        val gen = ++loadGen
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val rootName = withContext(Dispatchers.IO) { queryDisplayName(uri, rootDocId) }
            if (gen != loadGen) return@launch
            if (!rootName.isNullOrEmpty()) crumbs[0] = Crumb(rootDocId, rootName)
            val entries = withContext(Dispatchers.IO) {
                runCatching { listChildren(uri, rootDocId) }.getOrDefault(emptyList())
            }
            if (gen != loadGen) return@launch
            _state.value = UiState.Browsing(crumbs.toList(), entries)
        }
    }

    fun drillInto(entry: Entry) {
        if (!entry.isDir) return
        val tree = treeUri ?: return
        crumbs.add(Crumb(entry.docId, entry.name))
        loadInto(tree, entry.docId)
    }

    /** @return true 已弹出一层（调用方刷新）；false 已在根目录（调用方应 finish）。 */
    fun goUp(): Boolean {
        if (crumbs.size <= 1) return false
        val tree = treeUri ?: return false
        crumbs.removeAt(crumbs.lastIndex)
        loadInto(tree, crumbs.last().docId)
        return true
    }

    private fun loadInto(tree: Uri, docId: String) {
        val gen = ++loadGen
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) {
                runCatching { listChildren(tree, docId) }.getOrDefault(emptyList())
            }
            if (gen != loadGen) return@launch // 已经又翻进/退出别的目录，丢弃这帧
            _state.value = UiState.Browsing(crumbs.toList(), entries)
        }
    }

    private fun hasPermission(uri: Uri): Boolean = runCatching {
        ctx().contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    }.getOrDefault(false)

    private fun listChildren(tree: Uri, parentDocId: String): List<Entry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val out = ArrayList<Entry>()
        ctx().contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            while (c.moveToNext()) {
                val docId = c.getString(idIdx) ?: continue
                val name = c.getString(nameIdx) ?: continue
                val mime = c.getString(mimeIdx)
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val size = if (c.isNull(sizeIdx)) 0L else c.getLong(sizeIdx)
                when {
                    isDir -> out += Entry(docId, name, true, size)
                    name.endsWith(".txt", ignoreCase = true) || mime == "text/plain" ->
                        out += Entry(docId, name, false, size)
                }
            }
        }
        // 文件夹在前、文件在后，各自按名称 locale 自然排序。Collator 非线程安全，每次新建。
        val collator = Collator.getInstance(Locale.getDefault())
        return out.sortedWith(
            compareBy<Entry> { !it.isDir }.thenComparator { a, b -> collator.compare(a.name, b.name) },
        )
    }

    private fun queryDisplayName(tree: Uri, docId: String): String? {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        return runCatching {
            ctx().contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }
}
