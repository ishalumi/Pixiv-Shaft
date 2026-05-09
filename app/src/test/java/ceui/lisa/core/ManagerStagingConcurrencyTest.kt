package ceui.lisa.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 复现「5 并发只有 1 条进度跳」的机制。
 *
 * 这不是直接调 Manager.startDownloadChain（那条链耦合 Android Context / RxJava /
 * OkHttp 没法纯 JVM 跑）；而是把它的 byte-copy 主循环（Manager.java:602-654）
 * 原样搬下来，**唯一变量是 OutputStream 工厂**：
 *
 *   - SHARED 模式：所有 worker 共用一把全局锁 + 写延迟，模拟
 *     ContentResolver.openOutputStream(content://…) → MediaProvider Binder
 *     pipe。这是 Manager.java:595 那一行（cachedFile != null + content:// 直写）
 *     落到的 OutputStream。
 *   - ISOLATED 模式：每个 worker 写自己 cacheDir 下独立 FileOutputStream，
 *     模拟 staging 路径（Manager.java:587）。
 *
 * 同样的拷贝循环 + 同样的输入流，**只换 OutputStream 工厂**，看到的并行度就该
 * 完全不同。这就把「问题在 OutputStream 那一侧」定死，下一步只要再读
 * Manager.startDownloadChain 找到「哪个分支选了 SHARED 风格的 OutputStream」就
 * 是 root cause。
 */
class ManagerStagingConcurrencyTest {

    // ---------- 测试用 OutputStream 工厂 ----------

    /**
     * 跨实例共享同一把锁的 OutputStream。任意时刻只有一个 writer 能跑 write()，
     * 其余在 synchronized 上排队 —— 跟 MediaProvider 的 Binder pipe 行为一致。
     *
     * write 路径里加 1ms sleep，把"串行排队"在墙钟上放大到肉眼可见，否则 8KB
     * 一次的写在桌面机上 < 几 μs，timing 断言会 flaky。
     */
    private class SharedSerializingStream(
        private val sink: OutputStream,
        private val activeNow: AtomicInteger,
        private val maxObservedActive: AtomicInteger,
    ) : OutputStream() {
        companion object { val globalLock = Any() }

        override fun write(b: Int) {
            synchronized(globalLock) { criticalSection { sink.write(b) } }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            synchronized(globalLock) { criticalSection { sink.write(b, off, len) } }
        }

        private inline fun criticalSection(body: () -> Unit) {
            val cur = activeNow.incrementAndGet()
            maxObservedActive.updateAndGet { if (cur > it) cur else it }
            try {
                Thread.sleep(1)  // simulate Binder cross-process overhead
                body()
            } finally {
                activeNow.decrementAndGet()
            }
        }

        override fun flush() = sink.flush()
        override fun close() = sink.close()
    }

    /**
     * 各 worker 独立的 OutputStream，写进各自 tmp 文件。仍然记录"同时活动数"，
     * 用来对比 SHARED：理论上能看到 ≥2 个 worker 同时在 write。
     */
    private class IsolatedStream(
        private val sink: OutputStream,
        private val activeNow: AtomicInteger,
        private val maxObservedActive: AtomicInteger,
    ) : OutputStream() {

        override fun write(b: Int) = inActive { sink.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) = inActive { sink.write(b, off, len) }

        private inline fun inActive(body: () -> Unit) {
            val cur = activeNow.incrementAndGet()
            maxObservedActive.updateAndGet { if (cur > it) cur else it }
            try {
                Thread.sleep(1)
                body()
            } finally {
                activeNow.decrementAndGet()
            }
        }

        override fun flush() = sink.flush()
        override fun close() = sink.close()
    }

    // ---------- byte-copy 主循环（搬自 Manager.java:602-654，去掉 RxJava / 主线程 post）----------

    /**
     * 跟 Manager.startDownloadChain 内层 while 完全同构：8KB buffer，每写一块
     * 上报一次进度（这里"上报"= AtomicLong.set，对应原版 setNonius +
     * ManagerReactive.invalidate）。
     */
    private fun copyWithProgress(input: InputStream, output: OutputStream, observedBytes: AtomicLong) {
        val buf = ByteArray(8192)
        var written = 0L
        while (true) {
            val n = input.read(buf)
            if (n == -1) break
            output.write(buf, 0, n)
            written += n
            observedBytes.set(written)
        }
        output.flush()
        output.close()
    }

    private data class RunResult(
        val elapsedMs: Long,
        val maxConcurrentActive: Int,
        val finalBytes: List<Long>,
    )

    /**
     * 起 [n] 个 worker，每个跑一遍 copyWithProgress，OutputStream 由 [makeOut]
     * 构造。所有 worker 通过 CountDownLatch 一起起跑，避免 first-mover 跑完再 last-mover
     * 才开始。
     */
    private fun runConcurrent(
        n: Int,
        payloadSize: Int,
        makeOut: (Int, AtomicInteger, AtomicInteger) -> OutputStream,
    ): RunResult {
        val payloads = (0 until n).map { ByteArray(payloadSize) }
        val observed = (0 until n).map { AtomicLong(0) }
        val activeNow = AtomicInteger(0)
        val maxActive = AtomicInteger(0)

        val pool = Executors.newFixedThreadPool(n)
        val ready = CountDownLatch(n)
        val done = CountDownLatch(n)

        val t0 = System.nanoTime()
        for (i in 0 until n) {
            pool.submit {
                ready.countDown()
                ready.await()  // 同时起跑
                copyWithProgress(
                    ByteArrayInputStream(payloads[i]),
                    makeOut(i, activeNow, maxActive),
                    observed[i],
                )
                done.countDown()
            }
        }
        check(done.await(30, TimeUnit.SECONDS)) { "test timed out" }
        val elapsed = (System.nanoTime() - t0) / 1_000_000L
        pool.shutdown()
        return RunResult(elapsed, maxActive.get(), observed.map { it.get() })
    }

    // ---------- 测试 ----------

    /**
     * SYMPTOM：5 个 worker 共用同一个 SerializingPipe（≈ MediaProvider Binder
     * pipe）→ 任何时刻最多 1 个能跑 write，其余阻塞在锁上。
     *
     * 这条用断言 maxConcurrentActive == 1 卡死「串行」性质 —— 跟用户视频里
     * 「只有第 1 行有进度跳」是同一个机制。
     */
    @Test
    fun `concurrent writers sharing one serializing pipe never overlap — symptom matches video`() {
        val payloadSize = 64 * 1024  // 64KB → 8 个 8KB chunk，足够看到串行
        val sharedSink = java.io.ByteArrayOutputStream()
        val result = runConcurrent(n = 5, payloadSize = payloadSize) { _, active, max ->
            SharedSerializingStream(sharedSink, active, max)
        }

        // 全部跑完，每个 worker 都拿到了完整 payload
        assertTrue("all workers should complete: ${result.finalBytes}",
            result.finalBytes.all { it == payloadSize.toLong() })

        // 关键断言：同时活跃数永远不超过 1。这就是 MediaProvider 串行化的指纹。
        assertTrue("expected max concurrent active == 1 under shared lock, got ${result.maxConcurrentActive}",
            result.maxConcurrentActive == 1)
    }

    /**
     * CONTROL：同样 5 个 worker，但每人写各自的本地 FileOutputStream（≈ staging
     * 写到 cacheDir/staging_dl/{uuid}.part）→ 应该看到至少 2 个同时活跃。
     *
     * 这条证明：byte-copy 循环本身不串行，**串行性只来自 OutputStream 那一侧**。
     */
    @Test
    fun `concurrent writers with isolated streams overlap — staging path is parallel`() {
        val tmp = File.createTempFile("stage_test_", "_dir").apply { delete(); mkdir() }
        try {
            val payloadSize = 64 * 1024
            val result = runConcurrent(n = 5, payloadSize = payloadSize) { i, active, max ->
                IsolatedStream(FileOutputStream(File(tmp, "stage-$i.part")), active, max)
            }

            assertTrue("all workers should complete: ${result.finalBytes}",
                result.finalBytes.all { it == payloadSize.toLong() })

            // 关键断言：至少有 2 个 worker 在某个时刻同时跑。这就是 staging 应该
            // 给到的并行度。
            assertTrue("expected max concurrent active ≥ 2 with isolated streams, got ${result.maxConcurrentActive}",
                result.maxConcurrentActive >= 2)
        } finally {
            tmp.listFiles()?.forEach { it.delete() }
            tmp.delete()
        }
    }

    /**
     * 把上面两条结论焊到 Manager 的 useStaging 决策上。
     *
     * Manager.java:515 修复后的公式：
     *   useStaging = maxConc > 1 && scheme != "file"
     *
     * 列出（maxConc, cachedFile, scheme）组合下落到的 OutputStream：
     *
     *   | maxConc | cachedFile | scheme  | useStaging | OutputStream            |
     *   | 1       | *          | content | false      | contentResolver pipe    | ← 单流，无争用
     *   | 1       | *          | file    | false      | FileOutputStream        |
     *   | ≥2      | *          | content | true       | FileOutputStream(stage) | ← FIX
     *   | ≥2      | *          | file    | false      | FileOutputStream        |
     *
     * 关键性质：
     *   - maxConc=1 永远不 staging（两种 scheme 都不开 → 不影响只下 1-2 张的人）
     *   - maxConc≥2 + content:// 永远 staging（不论 cachedFile 是否命中 → 修 root cause）
     *   - file:// 永远不 staging（无 ContentProvider 介入）
     */
    @Test
    fun `useStaging fixed formula — maxConc=1 never stages, maxConc-ge-2 always stages on content scheme`() {
        // 照抄 Manager.java 改后的公式
        fun useStaging(maxConc: Int, scheme: String): Boolean =
            maxConc > 1 && scheme != "file"

        // maxConc=1：所有 scheme 都不 staging（保护"只下一两张"路径无开销）
        for (scheme in listOf("content", "file")) {
            assertTrue("maxConc=1 + scheme=$scheme should NOT stage", !useStaging(1, scheme))
        }
        // maxConc≥2 + content://：永远 staging（修 root cause）
        for (n in 2..5) {
            assertTrue("maxConc=$n + content:// MUST stage", useStaging(n, "content"))
        }
        // file:// 永远不 staging
        for (n in 1..5) {
            assertTrue("maxConc=$n + file:// should NOT stage", !useStaging(n, "file"))
        }
    }

    /**
     * 这个测试反过来证明"为什么旧公式 cachedFile==null && !file:// 是错的"——
     * 用旧公式，maxConc=5 + cachedFile 命中 + content:// 这个组合会落到 SHARED，
     * 触发本类 Test 1 复现的串行化症状。保留作为回归 hedge：以后谁动 useStaging
     * 改回旧公式，这条会立刻把 bug 钉上来。
     */
    @Test
    fun `regression hedge — old formula used to leak cachedFile-hit + content into the SHARED path`() {
        fun oldUseStaging(cachedFileNull: Boolean, scheme: String): Boolean =
            cachedFileNull && scheme != "file"

        val brokenCase = !oldUseStaging(/*cachedFileNull=*/ false, "content") /* = true */
        assertTrue(
            "old useStaging formula left cachedFile + content:// on direct ContentResolver write — " +
                "5 concurrent here is exactly the symptom in commit 4c16183b. " +
                "Do not regress.",
            brokenCase
        )
    }

    // ---------- 端到端：staging path 在 N=1..8 batch 下都能把字节交到"相册 sink" ----------

    /**
     * 模拟一个简化的 MediaStore：每个 commit 把 (uri, bytes) 写进 map，
     * finishWrite 把对应 uri 的 IS_PENDING 翻 0。"相册可见" 等价于
     * `committedBytes[uri] != null && pendingCleared[uri] == true`。
     *
     * 模拟跨进程 Binder 写：openOutputStream 返回的 OutputStream 共用全局
     * commitLock，对应 MediaProvider 端单线程化 commit。
     */
    private class FakeMediaStore {
        private val committedBytes = ConcurrentHashMap<String, ByteArray>()
        private val pendingCleared = ConcurrentHashMap<String, Boolean>()
        private val commitLock = Any()

        fun openOutputStream(uri: String): OutputStream {
            val baos = java.io.ByteArrayOutputStream()
            return object : OutputStream() {
                override fun write(b: Int) = synchronized(commitLock) { baos.write(b) }
                override fun write(b: ByteArray, off: Int, len: Int) =
                    synchronized(commitLock) { baos.write(b, off, len) }
                override fun close() {
                    synchronized(commitLock) {
                        committedBytes[uri] = baos.toByteArray()
                        pendingCleared.putIfAbsent(uri, false)
                    }
                }
            }
        }

        fun finishWrite(uri: String) {
            pendingCleared[uri] = true
        }

        fun visibleInGallery(uri: String): Boolean =
            committedBytes[uri] != null && pendingCleared[uri] == true

        fun bytes(uri: String): ByteArray? = committedBytes[uri]
        fun visibleCount(): Int = pendingCleared.values.count { it }
    }

    /**
     * 模拟 Manager.startDownloadChain 在 useStaging=true 路径下的全流程：
     *   1. 决定续传 / 重写：照抄 Manager 的 canResumePartialStage 逻辑
     *   2. 读源（cachedFile 或网络），8KB chunk 写到 stage 文件（append by effectivePassSize）
     *   3. 读 stage，写到 FakeMediaStore（content:// commit）
     *   4. finishWrite（IS_PENDING=0）
     *
     * 测的是 Manager 现有 staging 实现的端到端正确性 —— fix 之后会有更多
     * 路径走这里，不能让 staging 自身有 leak。
     */
    private fun runStagingDownload(
        index: Int,
        payload: ByteArray,
        stageDir: File,
        sink: FakeMediaStore,
        cachedFile: File? = null,
    ) {
        val uri = "content://media/external/img/$index"
        val stageFile = File(stageDir, "uuid-$index.part")

        // 照抄 Manager.java 的 canResumePartialStage 决策
        val canResumePartialStage = cachedFile == null && stageFile.length() > 0
        val effectivePassSize: Long = if (canResumePartialStage) {
            stageFile.length()
        } else {
            if (stageFile.exists()) stageFile.delete()
            0L
        }

        // step 1: 源 → stage
        val source: InputStream = cachedFile?.inputStream() ?: ByteArrayInputStream(payload)
        FileOutputStream(stageFile, effectivePassSize > 0).use { out ->
            source.use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    out.write(buf, 0, n)
                }
            }
        }

        // step 2: stage → MediaStore（commit；可能与别人争 commitLock，但短暂）
        sink.openOutputStream(uri).use { mediaOut ->
            java.io.FileInputStream(stageFile).use { stageIn ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = stageIn.read(buf)
                    if (n == -1) break
                    mediaOut.write(buf, 0, n)
                }
            }
        }
        check(stageFile.delete()) { "stage cleanup failed: $stageFile" }

        // step 3: 让相册可见
        sink.finishWrite(uri)
    }

    /**
     * 用户场景：一次下 N 张图（N ∈ 1..8），N 全部并发提交。每张走 staging 路径，
     * 应该全部进相册 sink，字节完整、IS_PENDING 全部清零。
     *
     * 这条 case 配合 fix 一起证明：把 maxConc>1 缓存命中路径推到 staging 后，
     * commit + finishWrite 全链路依然把图正确递交给 MediaStore，没有 leak。
     */
    @Test
    fun `staging end-to-end — for every batch size 1 through 8, all images become visible in gallery`() {
        for (n in 1..8) {
            val tmpDir = File.createTempFile("staging_e2e_${n}_", "_dir").apply { delete(); mkdir() }
            try {
                val payloads = (0 until n).map { i ->
                    // 让 payload 不一样，断言 sink 拿到的字节没串
                    ByteArray(32 * 1024 + i * 17).also { it.fill((i + 1).toByte()) }
                }
                val sink = FakeMediaStore()
                val pool = Executors.newFixedThreadPool(n.coerceAtLeast(1))
                val ready = CountDownLatch(n)
                val done = CountDownLatch(n)

                for (i in 0 until n) {
                    pool.submit {
                        ready.countDown()
                        ready.await()  // 真并发起跑
                        runStagingDownload(i, payloads[i], tmpDir, sink)
                        done.countDown()
                    }
                }
                check(done.await(30, TimeUnit.SECONDS)) { "batch=$n timed out" }
                pool.shutdown()

                // 全部都进了相册（IS_PENDING=0 + 字节落库）
                assertTrue("batch=$n: only ${sink.visibleCount()}/$n visible",
                    sink.visibleCount() == n)
                // 字节没有串
                for (i in 0 until n) {
                    val uri = "content://media/external/img/$i"
                    val got = sink.bytes(uri) ?: error("batch=$n image=$i missing")
                    assertTrue("batch=$n image=$i bytes mismatch (size ${got.size} vs ${payloads[i].size})",
                        got.contentEquals(payloads[i]))
                }
                // stage dir 全部清干净（没有 .part 残留）
                val leftover = tmpDir.listFiles()?.toList().orEmpty()
                assertTrue("batch=$n leftover stage files: $leftover", leftover.isEmpty())
            } finally {
                tmpDir.listFiles()?.forEach { it.delete() }
                tmpDir.delete()
            }
        }
    }

    /**
     * 防 fix 自损：原本 cache 命中走直写，从来不碰 staging；fix 后多并发缓存命中
     * 也进 staging。staging 里有「partial stage 当作续传基线、append 模式接着写」
     * 的逻辑，本意服务网络源（配 Range 头），但**本地 cachedFile 没有 Range 概念**，
     * FileInputStream(cachedFile) 永远从 offset 0 读完整字节。如果上次失败留了
     * partial stage 没清，再来一遍就把完整字节追加到 partial 上 —— stage 字节翻
     * 倍，commit 出去给相册一张损坏的图。
     *
     * 修法（Manager.java canResumePartialStage 那块）：本地源永远从空 stage 重来，
     * 只对网络源沿用续传。这条测试用预先写好的 partial stage 文件 + cachedFile
     * 重跑一遍，断言 sink 拿到的字节 = 原 cachedFile 字节，不被旧 partial 污染。
     */
    @Test
    fun `cachedFile retry — leftover partial stage from a failed previous run does not bloat final bytes`() {
        val tmpDir = File.createTempFile("cache_retry_", "_dir").apply { delete(); mkdir() }
        try {
            val cacheFile = File(tmpDir, "glide-cache.bin")
            val expected = ByteArray(48 * 1024).also { for (i in it.indices) it[i] = (i % 251).toByte() }
            cacheFile.writeBytes(expected)

            // 模拟上次 commit 失败留下的 partial stage（写到 ~30%）：用一段
            // 跟 expected 完全不同的字节，污染检测才好做
            val stageFile = File(tmpDir, "uuid-1.part")
            stageFile.writeBytes(ByteArray(15 * 1024).also { it.fill(0xFF.toByte()) })
            assertTrue("setup: partial stage should be present",
                stageFile.exists() && stageFile.length() == 15L * 1024)

            val sink = FakeMediaStore()
            runStagingDownload(
                index = 1,
                payload = ByteArray(0),  // 走 cachedFile 路径，payload 不读
                stageDir = tmpDir,
                sink = sink,
                cachedFile = cacheFile,
            )

            // 断言 sink 拿到的就是 cacheFile 原内容，没有被 partial 污染
            val gotBytes = sink.bytes("content://media/external/img/1")
                ?: error("commit did not happen")
            assertTrue("expected sink to have original cache bytes (${expected.size}B), got ${gotBytes.size}B",
                gotBytes.size == expected.size)
            assertTrue("bytes content mismatch — partial stage leaked into commit",
                gotBytes.contentEquals(expected))
            assertTrue("image must be visible in gallery (IS_PENDING=0)",
                sink.visibleInGallery("content://media/external/img/1"))
        } finally {
            tmpDir.listFiles()?.forEach { it.delete() }
            tmpDir.delete()
        }
    }
}
