package ceui.pixiv.ui.debug

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.utils.Common
import ceui.pixiv.download.backend.SafBackend
import ceui.pixiv.download.model.RelativePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SAF 写入压测页。用来人手复现 Google Play 上「下载文件数超过 3 万后非常慢」的
 * 用户反馈 —— 在用户选定的 SAF tree 下创建 `ShaftPerfTest/` 子目录，循环 N 次
 * `SafBackend.replace(...)` 写一个 4 字节文件，每 100 张回报一次累计耗时 +
 * 最近一批耗时 + 当前速度。
 *
 * 复现思路:
 *   - 目录里文件越多，`DocumentFile.findFile` 内部触发的 `listFiles()` 越慢
 *     (IPC 列全部 children，client 端做 displayName 比对)
 *   - `SafBackend` 默认 replace = delete + open，单次保存触发 3 次 listFiles
 *     (delete 的 findDirectory + findFile，open 的 ensureDirectory)
 *   - 跑 30000 张能直观看到「前 1000 几秒，后 1000 几十秒」的曲线
 *
 * 跑完后保留文件不清空，方便修复前/后跑同一压测对比。要回收测试目录的话
 * 点「清空目录」按钮。
 *
 * 故意不复用用户当前下载用的 SAF tree —— 让用户单独 OPEN_DOCUMENT_TREE 选
 * 一个测试位置，避免污染生产相册数据。
 */
class SafPerfTestFragment : Fragment(R.layout.fragment_saf_perf_test) {

    private val tsFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val testDirSegment = "ShaftPerfTest"

    private lateinit var statusText: TextView
    private lateinit var treeUriText: TextView
    private lateinit var payloadText: TextView
    private lateinit var outputText: TextView
    private lateinit var outputScroll: NestedScrollView

    private var treeUri: Uri? = null
    private var payloadBytes: ByteArray? = null
    private var payloadMime: String = "image/jpeg"
    private var payloadExt: String = ".jpg"
    private val cancelFlag = AtomicBoolean(false)
    private var runningJob: Job? = null

    private val pickTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            Common.showToast(getString(R.string.saf_perf_test_pick_failed, e.message))
            return@registerForActivityResult
        }
        treeUri = uri
        treeUriText.text = uri.toString()
        appendLine(getString(R.string.saf_perf_test_pick_ok, uri.toString()))
    }

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val appCtx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val (bytes, mime) = withContext(Dispatchers.IO) { loadPayload(appCtx, uri) } ?: return@launch
            payloadBytes = bytes
            payloadMime = mime
            payloadExt = extForMime(mime)
            payloadText.text = getString(R.string.saf_perf_test_payload_loaded, bytes.size, mime)
            appendLine(getString(R.string.saf_perf_test_payload_loaded, bytes.size, mime))
        }
    }

    private fun loadPayload(ctx: Context, uri: Uri): Pair<ByteArray, String>? = try {
        val resolver = ctx.contentResolver
        val mime = resolver.getType(uri) ?: "image/jpeg"
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes == null) null else bytes to mime
    } catch (e: Exception) {
        Common.showToast(ctx.getString(R.string.saf_perf_test_payload_failed, e.javaClass.simpleName))
        null
    }

    private fun extForMime(mime: String): String = when (mime.lowercase(Locale.US)) {
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        "image/gif" -> ".gif"
        "image/bmp" -> ".bmp"
        "image/heic" -> ".heic"
        else -> ".jpg"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<Toolbar>(R.id.toolbar)
            .setNavigationOnClickListener { activity?.finish() }

        statusText = view.findViewById(R.id.statusText)
        treeUriText = view.findViewById(R.id.treeUriText)
        payloadText = view.findViewById(R.id.payloadText)
        outputText = view.findViewById(R.id.outputText)
        outputScroll = view.findViewById(R.id.outputScroll)

        view.findViewById<Button>(R.id.btnPickTree).setOnClickListener {
            pickTree.launch(null)
        }
        view.findViewById<Button>(R.id.btnPickImage).setOnClickListener {
            pickImage.launch(arrayOf("image/*"))
        }
        view.findViewById<Button>(R.id.btnRun1k).setOnClickListener { startRun(1_000) }
        view.findViewById<Button>(R.id.btnRun5k).setOnClickListener { startRun(5_000) }
        view.findViewById<Button>(R.id.btnRun30k).setOnClickListener { startRun(30_000) }
        view.findViewById<Button>(R.id.btnCancel).setOnClickListener {
            if (runningJob?.isActive == true) {
                cancelFlag.set(true)
                appendLine(getString(R.string.saf_perf_test_cancelling))
            }
        }
        view.findViewById<Button>(R.id.btnPurge).setOnClickListener { startPurge() }
        view.findViewById<Button>(R.id.btnDiag).setOnClickListener { startDiag() }

        statusText.text = getString(R.string.saf_perf_test_status_idle)
    }

    private fun startRun(total: Int) {
        val uri = treeUri
        if (uri == null) {
            Common.showToast(getString(R.string.saf_perf_test_need_pick))
            return
        }
        val bytes = payloadBytes
        if (bytes == null) {
            Common.showToast(getString(R.string.saf_perf_test_need_payload))
            return
        }
        if (runningJob?.isActive == true) {
            Common.showToast(getString(R.string.saf_perf_test_busy))
            return
        }
        cancelFlag.set(false)
        appendLine(
            "──── ${getString(R.string.saf_perf_test_start, total)} @ ${tsFormat.format(Date())} ────"
        )
        statusText.text = getString(R.string.saf_perf_test_status_running, 0, total)

        val payload = bytes
        val mime = payloadMime
        val ext = payloadExt
        val appCtx = requireContext().applicationContext
        runningJob = viewLifecycleOwner.lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                runBenchmark(appCtx, uri, total, payload, mime, ext)
            }
            appendLine(report)
            statusText.text = getString(R.string.saf_perf_test_status_idle)
        }
    }

    /**
     * 单次拆解每一步的耗时,让用户看清楚 1 秒/张到底花在哪:
     *   - root.listFiles() 耗时 + root 下文件数
     *   - ShaftPerfTest/.listFiles() 耗时 + 子项数 (没就 skip)
     *   - 一次 backend.replace 全程耗时 (= 实际下载链路)
     *   - 一次裸 DocumentFile.createFile (复用 parent doc,跳过 backend
     *     的 delete + ensureDirectory) 作为对照组,衡量「绕过 backend 还能
     *     再省多少 IPC」
     */
    private fun startDiag() {
        val uri = treeUri
        if (uri == null) {
            Common.showToast(getString(R.string.saf_perf_test_need_pick))
            return
        }
        val bytes = payloadBytes
        if (bytes == null) {
            Common.showToast(getString(R.string.saf_perf_test_need_payload))
            return
        }
        if (runningJob?.isActive == true) {
            Common.showToast(getString(R.string.saf_perf_test_busy))
            return
        }
        appendLine("──── ${getString(R.string.saf_perf_test_diag_start)} @ ${tsFormat.format(Date())} ────")
        statusText.text = getString(R.string.saf_perf_test_status_diag)
        val appCtx = requireContext().applicationContext
        runningJob = viewLifecycleOwner.lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) { runDiag(appCtx, uri, bytes, payloadMime, payloadExt) }
            appendLine(report)
            statusText.text = getString(R.string.saf_perf_test_status_idle)
        }
    }

    private fun runDiag(ctx: Context, tree: Uri, payload: ByteArray, mime: String, ext: String): String {
        val sb = StringBuilder()

        // Step 1: root.listFiles()
        val rootDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, tree)
            ?: return ctx.getString(R.string.saf_perf_test_purge_no_tree)
        val rootListStart = System.nanoTime()
        val rootChildren = rootDoc.listFiles()
        val rootListMs = (System.nanoTime() - rootListStart) / 1_000_000.0
        sb.appendLine(ctx.getString(R.string.saf_perf_test_diag_root, rootChildren.size, rootListMs))

        // Step 2: ShaftPerfTest/.listFiles() 如果存在
        val testDir = rootChildren.firstOrNull { it.isDirectory && it.name == testDirSegment }
        if (testDir != null) {
            val testListStart = System.nanoTime()
            val testChildren = testDir.listFiles()
            val testListMs = (System.nanoTime() - testListStart) / 1_000_000.0
            sb.appendLine(ctx.getString(R.string.saf_perf_test_diag_subdir, testChildren.size, testListMs))
        } else {
            sb.appendLine(ctx.getString(R.string.saf_perf_test_diag_subdir_absent))
        }

        // Step 3: 一次 backend.replace (走真实下载链路)
        val backend = SafBackend(ctx, tree)
        val backendName = "${UUID.randomUUID()}$ext"
        val backendRel = RelativePath(listOf(testDirSegment, backendName))
        val backendStart = System.nanoTime()
        try {
            val handle = backend.replace(backendRel, mime)
            handle.stream.use { it.write(payload) }
            handle.onFinish()
        } catch (e: Exception) {
            sb.appendLine(ctx.getString(R.string.saf_perf_test_diag_replace_failed, e.javaClass.simpleName, e.message ?: ""))
            return sb.toString()
        }
        val backendMs = (System.nanoTime() - backendStart) / 1_000_000.0
        sb.appendLine(ctx.getString(R.string.saf_perf_test_diag_replace, backendMs))

        // Step 4: 裸 createFile (绕过 backend.delete + ensureDirectory)
        // 重新拿父目录引用,模拟「已经缓存了 parent 不需要再查」的理想情况
        val testDirRefresh = rootDoc.findFile(testDirSegment)
        if (testDirRefresh == null) {
            sb.appendLine(ctx.getString(R.string.saf_perf_test_diag_raw_no_parent))
            return sb.toString()
        }
        val rawName = "${UUID.randomUUID()}$ext"
        val rawStart = System.nanoTime()
        try {
            val doc = testDirRefresh.createFile(mime, rawName)
                ?: error("createFile null")
            ctx.contentResolver.openOutputStream(doc.uri, "rwt")?.use { it.write(payload) }
        } catch (e: Exception) {
            sb.appendLine(ctx.getString(R.string.saf_perf_test_diag_raw_failed, e.javaClass.simpleName, e.message ?: ""))
            return sb.toString()
        }
        val rawMs = (System.nanoTime() - rawStart) / 1_000_000.0
        sb.appendLine(ctx.getString(R.string.saf_perf_test_diag_raw, rawMs))

        // Step 5: 结论提示
        sb.appendLine(ctx.getString(R.string.saf_perf_test_diag_conclusion, rootListMs, backendMs, rawMs))
        return sb.toString()
    }

    private fun startPurge() {
        val uri = treeUri
        if (uri == null) {
            Common.showToast(getString(R.string.saf_perf_test_need_pick))
            return
        }
        if (runningJob?.isActive == true) {
            Common.showToast(getString(R.string.saf_perf_test_busy))
            return
        }
        cancelFlag.set(false)
        appendLine("──── ${getString(R.string.saf_perf_test_purge_start)} @ ${tsFormat.format(Date())} ────")
        statusText.text = getString(R.string.saf_perf_test_status_purging)
        val appCtx = requireContext().applicationContext
        runningJob = viewLifecycleOwner.lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) { purgeTestDir(appCtx, uri) }
            appendLine(msg)
            statusText.text = getString(R.string.saf_perf_test_status_idle)
        }
    }

    /**
     * 走真实下载路径 `SafBackend.replace(...)`(对应 OverwritePolicy.Replace,
     * Pixiv-Shaft 全局默认)。每张固定 [payload] (用户在 fragment 内选的图),
     * 文件名 UUID。关心的不是磁盘 I/O 而是 SAF IPC 次数,但选真实大小图能
     * 顺带反映写带宽。进度上报粒度 [PROGRESS_EVERY],便于看曲线。
     */
    private suspend fun runBenchmark(
        ctx: Context,
        tree: Uri,
        total: Int,
        payload: ByteArray,
        mime: String,
        ext: String,
    ): String {
        val backend = SafBackend(ctx, tree)
        val batchStart = LongArray(1) { System.nanoTime() }
        val totalStart = System.nanoTime()
        var done = 0
        var lastBatchDoneCount = 0

        try {
            while (done < total) {
                if (cancelFlag.get()) {
                    return ctx.getString(
                        R.string.saf_perf_test_cancelled,
                        done, total,
                        (System.nanoTime() - totalStart) / 1_000_000_000.0,
                    )
                }
                val name = "${UUID.randomUUID()}$ext"
                val rel = RelativePath(listOf(testDirSegment, name))
                try {
                    val handle = backend.replace(rel, mime)
                    try {
                        handle.stream.use { it.write(payload) }
                        handle.onFinish()
                    } catch (e: Exception) {
                        runCatching { handle.onAbort() }
                        throw e
                    }
                } catch (e: Exception) {
                    return ctx.getString(
                        R.string.saf_perf_test_failed,
                        done, total, e.javaClass.simpleName, e.message ?: "",
                    )
                }
                done++

                // 每张都让 status 数字跳一下,避免在 SAF 慢路径下 (1 张/秒) 用户
                // 看着 "0/1000" 不动几分钟,以为程序卡死。statusText.post 是线程
                // 安全的 (走 View handler),不切协程上下文,IO 循环里几乎零成本。
                val tickDone = done
                val tickTotal = total
                statusText.post {
                    if (isAdded) {
                        statusText.text = ctx.getString(R.string.saf_perf_test_status_running, tickDone, tickTotal)
                    }
                }

                if (done % PROGRESS_EVERY == 0 || done == total) {
                    val now = System.nanoTime()
                    val batchSec = (now - batchStart[0]) / 1_000_000_000.0
                    val batchCount = done - lastBatchDoneCount
                    val totalSec = (now - totalStart) / 1_000_000_000.0
                    val batchRate = if (batchSec > 0) batchCount / batchSec else 0.0
                    val avgRate = if (totalSec > 0) done / totalSec else 0.0
                    batchStart[0] = now
                    lastBatchDoneCount = done
                    val progressLine = ctx.getString(
                        R.string.saf_perf_test_progress,
                        done, total, totalSec, batchCount, batchSec, batchRate, avgRate,
                    )
                    withContext(Dispatchers.Main) {
                        statusText.text = ctx.getString(R.string.saf_perf_test_status_running, done, total)
                        appendLineDirect(progressLine)
                    }
                }
            }
        } finally {
            // no-op; backend is local and GC'd
        }
        val totalSec = (System.nanoTime() - totalStart) / 1_000_000_000.0
        return ctx.getString(
            R.string.saf_perf_test_done,
            total, totalSec, total / totalSec,
        )
    }

    /**
     * 递归删 `ShaftPerfTest/` 整目录。30000 个 child 也得列一遍,这一步本身
     * 就是 SAF 列目录速度的另一个观测点 —— 「purge 都慢」也是有用信号。
     */
    private suspend fun purgeTestDir(ctx: Context, tree: Uri): String {
        val started = System.nanoTime()
        val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(
            ctx, tree
        ) ?: return ctx.getString(R.string.saf_perf_test_purge_no_tree)
        val testDir = root.findFile(testDirSegment)
        if (testDir == null || !testDir.isDirectory) {
            return ctx.getString(R.string.saf_perf_test_purge_empty)
        }
        val listStart = System.nanoTime()
        val children = testDir.listFiles()
        val listSec = (System.nanoTime() - listStart) / 1_000_000_000.0
        val count = children.size
        var deleted = 0
        for (child in children) {
            if (cancelFlag.get()) break
            if (child.delete()) deleted++
            if (deleted % 500 == 0) {
                withContext(Dispatchers.Main) {
                    statusText.text = ctx.getString(R.string.saf_perf_test_status_purging_n, deleted, count)
                }
            }
        }
        runCatching { testDir.delete() }
        val totalSec = (System.nanoTime() - started) / 1_000_000_000.0
        return ctx.getString(R.string.saf_perf_test_purge_done, deleted, count, listSec, totalSec)
    }

    private fun appendLine(line: String) {
        appendLineDirect(line)
    }

    private fun appendLineDirect(line: String) {
        val prev = outputText.text?.toString().orEmpty()
        outputText.text = if (prev.isEmpty()) line else "$prev\n$line"
        outputScroll.post { outputScroll.fullScroll(View.FOCUS_DOWN) }
    }

    companion object {
        // SAF 慢路径下大概 1 张/秒,100 一批用户得等 100 秒才看到第一行曲线。
        // 20 一批刚好兼顾「细粒度看速度变化」+「不刷屏」。
        private const val PROGRESS_EVERY = 20
    }
}
