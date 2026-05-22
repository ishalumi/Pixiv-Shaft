package ceui.pixiv.db

import ceui.loxia.Client
import ceui.loxia.HistoryReportBody
import ceui.loxia.HistoryReportItem
import ceui.pixiv.session.SessionManager
import com.google.gson.JsonElement
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Buffers browse-history visits and flushes them to pixshaft-api in batches, so
 * opening an artwork never blocks on a network call. Best-effort: a few of the
 * most-recent visits may be lost on a hard process kill, which is fine for
 * history. Keyed by the logged-in viewer's uid (skipped when logged out).
 */
object HistoryReporter {

    private const val FLUSH_DELAY_MS = 2_000L
    private const val MAX_BATCH = 50 // server cap is 100; stay well under
    private const val MAX_QUEUE = 500 // bound memory if the backend is down for a while

    private val queue = ConcurrentLinkedQueue<HistoryReportItem>()
    // Swallow any stray throwable so a background report can never crash the app.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> Timber.e(e, "HistoryReporter coroutine error (swallowed)") },
    )
    private val flushMutex = Mutex()
    private var flushJob: Job? = null

    fun enqueue(targetType: String, targetId: Long, payload: JsonElement?) {
        if (SessionManager.loggedInUid <= 0L) return // history is per-viewer
        // drop oldest if the backend has been unreachable and the queue piled up
        while (queue.size >= MAX_QUEUE) queue.poll()
        queue.add(HistoryReportItem(targetType, targetId, payload))
        if (queue.size >= MAX_BATCH) {
            scope.launch { flush() }
        } else {
            scheduleFlush()
        }
    }

    private fun scheduleFlush() {
        flushJob?.cancel()
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            flush()
        }
    }

    /** Drains the queue to the server in MAX_BATCH chunks. Safe to call anytime. */
    suspend fun flush() {
        flushMutex.withLock {
            val uid = SessionManager.loggedInUid
            if (uid <= 0L) {
                queue.clear()
                return
            }
            while (true) {
                val batch = ArrayList<HistoryReportItem>(MAX_BATCH)
                while (queue.isNotEmpty() && batch.size < MAX_BATCH) {
                    queue.poll()?.let { batch.add(it) }
                }
                if (batch.isEmpty()) break
                try {
                    Client.pixshaft.reportHistory(uid, HistoryReportBody(batch))
                } catch (ex: Exception) {
                    // Drop this batch; don't spin retrying a flaky network.
                    Timber.e(ex, "history report failed (${batch.size} items dropped)")
                    break
                }
            }
        }
    }
}
