package ceui.pixiv.ui.translate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import timber.log.Timber
import java.io.File
import java.nio.FloatBuffer

/**
 * comic-text-detector (dmMaze) Android port.
 *
 * 替代 PaddleOCR 通用文本行检测,直接给气泡/文本块级别的 bbox。
 * - 模型:YOLOv5-base + DBNet,2 类(text=0, balloon=1)
 * - 输入:[1, 3, 1024, 1024] float32, RGB / 255,letterbox 居中
 * - 输出:`blk` (1, N, K) raw YOLO anchors,K = 5 + num_classes(text + balloon = 7)
 *   还有 `mask` / `lines_map` 输出,本实现忽略 — 只用 blk 拿气泡级 AABB
 *
 * 上游精度比 paddle 通用 detection 高一档,且天然按气泡分组 — 不再需要 MangaTextlineMerge。
 */
data class DetectionBox(
    /** 原图坐标系下的中心 + 尺寸(已反 letterbox 还原) */
    val cx: Float,
    val cy: Float,
    val width: Float,
    val height: Float,
    val confidence: Float,
    val classId: Int, // 0=text(free), 1=balloon(bubble)
)

object ComicTextDetector {

    /** YOLO 输入尺寸,固定 1024。 */
    private const val INPUT_SIZE = 1024

    /** 候选 box 最低 conf。跟 dmMaze 上游 conf_thresh 一致。 */
    private const val DEFAULT_CONF_THRESHOLD = 0.4f

    /**
     * NMS IoU 阈值,跟 dmMaze 上游 nms_thresh=0.35 一致(比 YOLOv5 默认 0.45 紧)。
     *
     * 上游用 **class-aware** NMS(text/balloon 互不抑制),我们改 **class-agnostic** —
     * 理由:OCR 下游对同一气泡只想拿一个 crop;text(文字紧框)和 balloon(气泡含背景)
     * 经常都在同一气泡触发,class-aware 会两个都留,manga-ocr 跑两遍出相同文本。
     * IoU 0.35 + class-agnostic 能稳定把这对 dup 框合并到高分那个。
     */
    private const val DEFAULT_IOU_THRESHOLD = 0.35f

    /**
     * letterbox 填充色 — 跟上游 dmMaze inference.py 一致用 **black (0,0,0)**。
     * 注意:YOLOv5 训练默认是 gray 114,但 CTD 推理代码 letterbox 默认 color=(0,0,0),
     * 模型实际在黑色 padding 分布上工作,用 gray 会让边界 anchor 偏移。
     */
    private const val LETTERBOX_FILL_COLOR = 0xFF000000.toInt()

    private var session: OrtSession? = null
    private var ortEnv: OrtEnvironment? = null
    private var inputName: String = "images"
    /** 0/1/2 哪个 output 是 blk;首次跑后按形状识别 */
    private var blkOutputIndex: Int = 0

    val isLoaded: Boolean get() = session != null

    @Synchronized
    fun loadModel(context: Context, model: ComicTextDetectorModel) {
        if (isLoaded) return
        val modelDir = ComicTextDetectorModelManager.modelDir(context, model)
        val modelFile = File(modelDir, model.modelFiles.first())
        val env = OrtEnvironment.getEnvironment()
        ortEnv = env
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
        }
        Timber.d("CTD: loading model from ${modelFile.absolutePath}")
        session = env.createSession(modelFile.absolutePath, opts)
        // 抓真实 input 名 — 不同导出版本可能是 "images" / "input" / "input.1"
        inputName = session!!.inputNames.firstOrNull() ?: "images"
        // 推 blk 在哪个 output:遍历找形状 (1, N, K) 且 K in [5..16] 的那个
        // 其他 output 是 mask (1, C, H, W) 显然不是。fail-fast — 找不到直接抛,
        // 让 load 时就暴露问题,而不是 detect 时拿错 tensor 跑 crash。
        val outputInfos = session!!.outputInfo.entries.toList()
        var blkIdx = -1
        for ((idx, e) in outputInfos.withIndex()) {
            val info = (e.value.info as? ai.onnxruntime.TensorInfo) ?: continue
            val s = info.shape
            if (s.size == 3 && s[2] in 5..16) { blkIdx = idx; break }
        }
        if (blkIdx < 0) {
            val shapes = outputInfos.map { it.key to ((it.value.info as? ai.onnxruntime.TensorInfo)?.shape?.toList() ?: "?") }
            session?.close()
            session = null
            throw IllegalStateException("CTD: no 3-D blk output found among $shapes")
        }
        blkOutputIndex = blkIdx
        Timber.d("CTD: input=$inputName, blk output index=$blkOutputIndex, outputs=${outputInfos.map { it.key }}")
    }

    @Synchronized
    fun unloadModel() {
        session?.close()
        session = null
        Timber.d("CTD: model unloaded")
    }

    /**
     * 检测一张漫画页的所有文本/气泡 region。
     *
     * @param bitmap 原图(任意尺寸),内部会 letterbox 到 1024x1024
     * @return 原图坐标系下的 box 列表,按 confidence 降序
     */
    fun detect(
        bitmap: Bitmap,
        confThreshold: Float = DEFAULT_CONF_THRESHOLD,
        iouThreshold: Float = DEFAULT_IOU_THRESHOLD,
    ): List<DetectionBox> {
        val sess = session ?: throw IllegalStateException("CTD model not loaded")
        val env = ortEnv ?: throw IllegalStateException("CTD model not loaded")

        val (lb, scale, padX, padY) = letterbox(bitmap, INPUT_SIZE)
        val inputBuf = try {
            bitmapToTensor(lb)
        } finally {
            if (lb !== bitmap) lb.recycle()
        }

        val inputTensor = OnnxTensor.createTensor(
            env, inputBuf, longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        )
        val candidates = mutableListOf<DetectionBox>()
        try {
            val outputs = sess.run(mapOf(inputName to inputTensor))
            try {
                val blkTensor = outputs[blkOutputIndex] as OnnxTensor
                val shape = blkTensor.info.shape
                if (shape.size != 3) {
                    Timber.e("CTD: unexpected blk shape ${shape.toList()}, abort")
                    return emptyList()
                }
                val n = shape[1].toInt()
                val k = shape[2].toInt()
                val buf = blkTensor.floatBuffer
                // YOLOv5: [cx, cy, w, h, obj, cls0, cls1, ...]
                for (i in 0 until n) {
                    val off = i * k
                    val obj = buf.get(off + 4)
                    if (obj < confThreshold) continue
                    var maxCls = 1f
                    var clsId = 0
                    if (k > 5) {
                        maxCls = Float.NEGATIVE_INFINITY
                        for (c in 5 until k) {
                            val v = buf.get(off + c)
                            if (v > maxCls) { maxCls = v; clsId = c - 5 }
                        }
                    }
                    val conf = obj * maxCls
                    if (conf < confThreshold) continue
                    candidates.add(
                        DetectionBox(
                            cx = buf.get(off),
                            cy = buf.get(off + 1),
                            width = buf.get(off + 2),
                            height = buf.get(off + 3),
                            confidence = conf,
                            classId = clsId,
                        )
                    )
                }
            } finally {
                outputs.close()
            }
        } finally {
            inputTensor.close()
        }

        // class-agnostic NMS:text/balloon 互相覆盖时取高分
        val kept = nms(candidates, iouThreshold)
        Timber.d("CTD: ${candidates.size} candidates → ${kept.size} after NMS (conf>=$confThreshold, iou<=$iouThreshold)")

        // 反 letterbox 到原图坐标
        return kept.map { b ->
            DetectionBox(
                cx = (b.cx - padX) / scale,
                cy = (b.cy - padY) / scale,
                width = b.width / scale,
                height = b.height / scale,
                confidence = b.confidence,
                classId = b.classId,
            )
        }
    }

    /**
     * Letterbox:保持长宽比缩放到目标尺寸,padding 全堆在 **右下**,填充色 **black**。
     * 跟上游 dmMaze utils/imgproc_utils.py 一致:
     *   `cv2.copyMakeBorder(im, 0, dh, 0, dw, BORDER_CONSTANT, value=(0,0,0))`
     *   即 top=0, left=0, 全部 padding 加到 bottom/right。
     *
     * 居中 padding 数学上反算也对(只要 padX/padY 是真实左/上 padding 量),但模型在训练时
     * 看的是 right-bottom padding 分布,居中会有轻微 distribution shift。这里对齐训练分布。
     */
    private data class LetterboxResult(
        val bitmap: Bitmap,
        val scale: Float,
        val padX: Float, // 左侧 padding(对齐右下方案:= 0)
        val padY: Float, // 上侧 padding(对齐右下方案:= 0)
    )

    private fun letterbox(src: Bitmap, target: Int): LetterboxResult {
        val scale = minOf(target.toFloat() / src.width, target.toFloat() / src.height)
        val newW = (src.width * scale).toInt().coerceAtLeast(1)
        val newH = (src.height * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(LETTERBOX_FILL_COLOR)
        val srcRect = Rect(0, 0, src.width, src.height)
        // top=0, left=0 — padding 全部堆右下
        val dstRect = RectF(0f, 0f, newW.toFloat(), newH.toFloat())
        canvas.drawBitmap(src, srcRect, dstRect, Paint(Paint.FILTER_BITMAP_FLAG))
        return LetterboxResult(out, scale, padX = 0f, padY = 0f)
    }

    /** RGB / 255 → CHW float32。简单 [0,1] 归一化,跟 CTD 训练分布一致(无 ImageNet mean/std)。 */
    private fun bitmapToTensor(bitmap: Bitmap): FloatBuffer {
        val size = INPUT_SIZE
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        val buf = FloatBuffer.allocate(3 * size * size)
        // R 通道
        for (p in pixels) buf.put(((p shr 16) and 0xFF) / 255f)
        // G 通道
        for (p in pixels) buf.put(((p shr 8) and 0xFF) / 255f)
        // B 通道
        for (p in pixels) buf.put((p and 0xFF) / 255f)
        buf.rewind()
        return buf
    }

    private fun nms(boxes: List<DetectionBox>, iouThreshold: Float): List<DetectionBox> {
        if (boxes.isEmpty()) return boxes
        val sorted = boxes.sortedByDescending { it.confidence }.toMutableList()
        val kept = mutableListOf<DetectionBox>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept += best
            val it = sorted.iterator()
            while (it.hasNext()) {
                if (iou(best, it.next()) > iouThreshold) it.remove()
            }
        }
        return kept
    }

    private fun iou(a: DetectionBox, b: DetectionBox): Float {
        val ax1 = a.cx - a.width / 2f
        val ay1 = a.cy - a.height / 2f
        val ax2 = a.cx + a.width / 2f
        val ay2 = a.cy + a.height / 2f
        val bx1 = b.cx - b.width / 2f
        val by1 = b.cy - b.height / 2f
        val bx2 = b.cx + b.width / 2f
        val by2 = b.cy + b.height / 2f
        val xL = maxOf(ax1, bx1)
        val yT = maxOf(ay1, by1)
        val xR = minOf(ax2, bx2)
        val yB = minOf(ay2, by2)
        if (xR <= xL || yB <= yT) return 0f
        val inter = (xR - xL) * (yB - yT)
        val areaA = (ax2 - ax1) * (ay2 - ay1)
        val areaB = (bx2 - bx1) * (by2 - by1)
        return inter / (areaA + areaB - inter)
    }
}
