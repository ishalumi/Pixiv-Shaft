package ceui.pixiv.ui.upscale

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Faithful Kotlin port of manga-image-translator's `textline_merge` module.
 * Upstream (Apache-2.0):
 *   github.com/zyddnys/manga-image-translator/blob/main/manga_translator/textline_merge/__init__.py
 *   github.com/zyddnys/manga-image-translator/blob/main/manga_translator/utils/generic.py
 *
 * 行号注释保留方便上游对账;算法本身不做任何"creative"改动。
 */
object MangaTextlineMerge {

    private data class Pt(val x: Float, val y: Float)

    /** 上游 Quadrilateral 的子集,只暴露 merge 需要的属性。 */
    private class Quad(val region: OcrTextRegion) {
        val pts: Array<Pt> = run {
            val raw = region.corners.map { Pt(it.first, it.second) }
            // 上游 sort_pnts 把 4 个点排成 TL,TR,BR,BL 顺序;PaddleOCR 输出本来就是这个顺序
            if (raw.size == 4) raw.toTypedArray()
            else {
                // fallback: 用 AABB 拼一个
                val xs = raw.map { it.x }; val ys = raw.map { it.y }
                arrayOf(
                    Pt(xs.min(), ys.min()), Pt(xs.max(), ys.min()),
                    Pt(xs.max(), ys.max()), Pt(xs.min(), ys.max()),
                )
            }
        }

        /** generic.py:378-383 structure 中点连线向量 */
        private val structure: Array<Pt> by lazy {
            arrayOf(
                Pt((pts[0].x + pts[1].x) / 2f, (pts[0].y + pts[1].y) / 2f),
                Pt((pts[2].x + pts[3].x) / 2f, (pts[2].y + pts[3].y) / 2f),
                Pt((pts[1].x + pts[2].x) / 2f, (pts[1].y + pts[2].y) / 2f),
                Pt((pts[3].x + pts[0].x) / 2f, (pts[3].y + pts[0].y) / 2f),
            )
        }
        private val v1: Pt by lazy { Pt(structure[1].x - structure[0].x, structure[1].y - structure[0].y) }
        private val v2: Pt by lazy { Pt(structure[3].x - structure[2].x, structure[3].y - structure[2].y) }
        private val v1n: Float by lazy { hypot(v1.x, v1.y) }
        private val v2n: Float by lazy { hypot(v2.x, v2.y) }

        /** generic.py:412-417 */
        val fontSize: Float by lazy { min(v1n, v2n) }

        /** generic.py:405-410 aspect_ratio = v2 / v1 */
        val aspectRatio: Float by lazy { if (v1n == 0f) 0f else v2n / v1n }

        /** generic.py:521-523 centroid = 4 顶点均值 */
        val centroid: Pt by lazy {
            Pt(pts.map { it.x }.average().toFloat(), pts.map { it.y }.average().toFloat())
        }

        /** generic.py:517-519 angle = arccos(v1·xAxis)  mod π */
        val angle: Float by lazy {
            val u = if (v1n == 0f) 0f else v1.x / v1n
            val a = acos(u.coerceIn(-1f, 1f).toDouble()).toFloat()
            ((a + PI) % PI).toFloat()
        }

        /** generic.py:497-507 axis-aligned 容差 0.05 */
        val isApproximateAxisAligned: Boolean by lazy {
            val u1x = if (v1n == 0f) 0f else v1.x / v1n
            val u1y = if (v1n == 0f) 0f else v1.y / v1n
            val u2x = if (v2n == 0f) 0f else v2.x / v2n
            val u2y = if (v2n == 0f) 0f else v2.y / v2n
            abs(u1x) < 0.05f || abs(u1y) < 0.05f || abs(u2x) < 0.05f || abs(u2y) < 0.05f
        }

        /** generic.py:438-443 AABB */
        val aabb: FloatArray by lazy {
            val xs = pts.map { it.x }; val ys = pts.map { it.y }
            floatArrayOf(xs.min(), ys.min(), xs.max() - xs.min(), ys.max() - ys.min())
        }

        val direction: Char get() = if (region.orientation == 1) 'v' else 'h'

        /** generic.py:540-541 凸包距离 — 对 4 顶点凸四边形等价于多边形距离 */
        fun polyDistance(other: Quad): Float = convexPolyDistance(pts, other.pts)
    }

    /**
     * 凸多边形 A 到 B 的最短距离:
     *  - 相交 → 0
     *  - 否则 = 顶点-边距离的最小值,两个方向各取一遍
     */
    private fun convexPolyDistance(a: Array<Pt>, b: Array<Pt>): Float {
        if (polygonsIntersect(a, b)) return 0f
        var d = Float.MAX_VALUE
        for (p in a) for (i in b.indices) d = min(d, pointToSegment(p, b[i], b[(i + 1) % b.size]))
        for (p in b) for (i in a.indices) d = min(d, pointToSegment(p, a[i], a[(i + 1) % a.size]))
        return d
    }

    /** SAT-based 凸多边形相交测试 */
    private fun polygonsIntersect(a: Array<Pt>, b: Array<Pt>): Boolean {
        for (poly in arrayOf(a, b)) {
            for (i in poly.indices) {
                val p1 = poly[i]; val p2 = poly[(i + 1) % poly.size]
                val nx = -(p2.y - p1.y); val ny = p2.x - p1.x
                var minA = Float.MAX_VALUE; var maxA = -Float.MAX_VALUE
                for (p in a) { val proj = p.x * nx + p.y * ny; if (proj < minA) minA = proj; if (proj > maxA) maxA = proj }
                var minB = Float.MAX_VALUE; var maxB = -Float.MAX_VALUE
                for (p in b) { val proj = p.x * nx + p.y * ny; if (proj < minB) minB = proj; if (proj > maxB) maxB = proj }
                if (maxA < minB || maxB < minA) return false
            }
        }
        return true
    }

    private fun pointToSegment(p: Pt, p1: Pt, p2: Pt): Float {
        // generic.py:620-650
        val cx = p2.x - p1.x; val cy = p2.y - p1.y
        val lenSq = cx * cx + cy * cy
        val param = if (lenSq == 0f) -1f else ((p.x - p1.x) * cx + (p.y - p1.y) * cy) / lenSq
        val (xx, yy) = when {
            param < 0f -> p1.x to p1.y
            param > 1f -> p2.x to p2.y
            else -> (p1.x + param * cx) to (p1.y + param * cy)
        }
        return hypot(p.x - xx, p.y - yy)
    }

    /**
     * generic.py:653-698 quadrilateral_can_merge_region
     * 上游 textline_merge 调用时传 aspect_ratio_tol=1.3, font_size_ratio_tol=2,
     * char_gap_tolerance=1, char_gap_tolerance2=3 (textline_merge/__init__.py:134)
     */
    private fun canMergeRegion(
        a: Quad, b: Quad,
        ratio: Float = 1.9f,
        discardConnectionGap: Float = 2f,
        charGapTolerance: Float = 1f,
        charGapTolerance2: Float = 3f,
        fontSizeRatioTol: Float = 2f,
        aspectRatioTol: Float = 1.3f,
    ): Boolean {
        val charSize = min(a.fontSize, b.fontSize)
        val dist = a.polyDistance(b)
        if (dist > discardConnectionGap * charSize) return false                // L663
        if (max(a.fontSize, b.fontSize) / charSize > fontSizeRatioTol) return false // L665
        if (a.aspectRatio > aspectRatioTol && b.aspectRatio < 1f / aspectRatioTol) return false // L667
        if (b.aspectRatio > aspectRatioTol && a.aspectRatio < 1f / aspectRatioTol) return false // L669

        val aAa = a.isApproximateAxisAligned
        val bAa = b.isApproximateAxisAligned
        if (aAa && bAa) {                                                         // L673
            if (dist < charSize * charGapTolerance) {                             // L674
                val (x1, y1, w1, h1) = a.aabb.let { Quadruple(it[0], it[1], it[2], it[3]) }
                val (x2, y2, w2, h2) = b.aabb.let { Quadruple(it[0], it[1], it[2], it[3]) }
                if (abs(x1 + w1 / 2f - (x2 + w2 / 2f)) < charGapTolerance2) return true // L675
                if (w1 > h1 * ratio && h2 > w2 * ratio) return false               // L677
                if (w2 > h2 * ratio && h1 > w1 * ratio) return false               // L679
                if (w1 > h1 * ratio || w2 > h2 * ratio) {                          // L681 horizontal
                    return abs(x1 - x2) < charSize * charGapTolerance2 ||
                        abs(x1 + w1 - (x2 + w2)) < charSize * charGapTolerance2
                } else if (h1 > w1 * ratio || h2 > w2 * ratio) {                   // L683 vertical
                    return abs(y1 - y2) < charSize * charGapTolerance2 ||
                        abs(y1 + h1 - (y2 + h2)) < charSize * charGapTolerance2
                }
                return false
            }
            return false
        }
        // L688 general (含倾斜)
        if (abs(a.angle - b.angle) < 15f * PI.toFloat() / 180f) {
            val fs = min(a.fontSize, b.fontSize)
            if (a.polyDistance(b) > fs * charGapTolerance2) return false
            if (abs(a.fontSize - b.fontSize) / fs > 0.25f) return false
            return true
        }
        return false
    }

    private data class Quadruple(val a: Float, val b: Float, val c: Float, val d: Float)

    /**
     * textline_merge/__init__.py:10-83 split_text_region
     * MST + 统计阈值,决定一个 connected component 是不是应该再拆。
     */
    private fun splitTextRegion(
        quads: List<Quad>,
        indices: Set<Int>,
        gamma: Float = 0.5f,
        sigma: Float = 2f,
    ): List<Set<Int>> {
        val list = indices.toList()
        if (list.size == 1) return listOf(setOf(list[0]))                          // L22 case 1
        if (list.size == 2) {                                                       // L26 case 2
            val a = quads[list[0]]; val b = quads[list[1]]
            val fs = max(a.fontSize, b.fontSize)
            val close = a.polyDistance(b) < (1f + gamma) * fs
            val similarAngle = abs(a.angle - b.angle) < 0.2f * PI.toFloat()
            return if (close && similarAngle) listOf(list.toSet())
            else listOf(setOf(list[0]), setOf(list[1]))
        }
        // L42 case 3: build MST
        data class Edge(val u: Int, val v: Int, val w: Float)
        val edges = mutableListOf<Edge>()
        for (i in list.indices) for (j in i + 1 until list.size) {
            edges += Edge(list[i], list[j], quads[list[i]].polyDistance(quads[list[j]]))
        }
        edges.sortBy { it.w }
        val parent = IntArray(quads.size) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
        val mst = mutableListOf<Edge>()
        for (e in edges) {
            val ru = find(e.u); val rv = find(e.v)
            if (ru != rv) { parent[ru] = rv; mst += e }
        }
        mst.sortByDescending { it.w }                                              // L49 reverse=True
        val distancesSorted = mst.map { it.w }
        val fontsize = list.map { quads[it].fontSize }.average().toFloat()
        val mean = distancesSorted.average().toFloat()
        val std = run {
            val m = mean
            sqrt(distancesSorted.map { (it - m) * (it - m) }.average().toFloat())
        }
        val stdThreshold = max(0.3f * fontsize + 5f, 5f)                           // L54
        val keep = (distancesSorted[0] <= mean + std * sigma ||                    // L66
            distancesSorted[0] <= fontsize * (1f + gamma)) &&
            (std < stdThreshold ||                                                  // L68
                run {
                    val b1 = quads[mst[0].u]; val b2 = quads[mst[0].v]
                    val centroidAlign = min(abs(b1.centroid.x - b2.centroid.x), abs(b1.centroid.y - b2.centroid.y))
                    b1.polyDistance(b2) == 0f && centroidAlign < 5f
                })
        if (keep) return listOf(list.toSet())
        // L74 拆:重建图,去掉最大权重边,再递归
        val parent2 = IntArray(quads.size) { it }
        fun find2(x: Int): Int { var r = x; while (parent2[r] != r) r = parent2[r]; return r }
        for (i in 1 until mst.size) {
            val e = mst[i]
            val ru = find2(e.u); val rv = find2(e.v)
            if (ru != rv) parent2[ru] = rv
        }
        val groups = HashMap<Int, MutableSet<Int>>()
        for (idx in list) groups.getOrPut(find2(idx)) { mutableSetOf() }.add(idx)
        return groups.values.flatMap { splitTextRegion(quads, it, gamma, sigma) }
    }

    /**
     * textline_merge/__init__.py:110-182 merge_bboxes_text_region
     * 输入 PaddleOCR 的所有检测框,输出每个 region 内已排好序的下标列表。
     */
    fun merge(regions: List<OcrTextRegion>): List<List<Int>> {
        if (regions.size <= 1) return regions.indices.map { listOf(it) }
        val quads = regions.map { Quad(it) }

        // L128 step 1: 构图,可合并的连一条边
        val adj = Array(quads.size) { mutableListOf<Int>() }
        for (i in quads.indices) for (j in i + 1 until quads.size) {
            if (canMergeRegion(quads[i], quads[j])) {
                adj[i].add(j); adj[j].add(i)
            }
        }

        // 连通分量
        val visited = BooleanArray(quads.size)
        val components = mutableListOf<Set<Int>>()
        for (s in quads.indices) {
            if (visited[s]) continue
            val comp = mutableSetOf<Int>()
            val stack = ArrayDeque<Int>(); stack.addLast(s); visited[s] = true
            while (stack.isNotEmpty()) {
                val u = stack.removeLast(); comp += u
                for (v in adj[u]) if (!visited[v]) { visited[v] = true; stack.addLast(v) }
            }
            components += comp
        }

        // L139 step 2: 每个 component 再 split
        val regionsIdx = components.flatMap { splitTextRegion(quads, it) }

        // L143 step 3: 决定方向,排序
        return regionsIdx.map { nodeSet ->
            val dirs = nodeSet.map { quads[it].direction }
            val counts = dirs.groupingBy { it }.eachCount()
            val top2 = counts.entries.sortedByDescending { it.value }.take(2)
            val majorityDir: Char = when {
                top2.size == 1 -> top2[0].key
                top2[0].value == top2[1].value -> {                                 // L162 平票:看 aspect_ratio
                    var maxRatio = -100f; var dir = top2[0].key
                    for (i in nodeSet) {
                        val q = quads[i]; val r = q.aspectRatio
                        if (r > maxRatio) { maxRatio = r; dir = q.direction }
                        val invR = if (r != 0f) 1f / r else 0f
                        if (invR > maxRatio) { maxRatio = invR; dir = q.direction }
                    }
                    dir
                }
                else -> top2[0].key
            }
            val sorted = if (majorityDir == 'h') {                                  // L175
                nodeSet.sortedBy { quads[it].centroid.y }
            } else {                                                                // L177 vertical:右→左
                nodeSet.sortedByDescending { quads[it].centroid.x }
            }
            sorted
        }
    }
}
