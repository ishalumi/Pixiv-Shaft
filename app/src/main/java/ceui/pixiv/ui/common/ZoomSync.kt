package ceui.pixiv.ui.common

import android.annotation.SuppressLint
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.github.panpf.zoomimage.SketchZoomImageView
import com.github.panpf.zoomimage.util.OffsetCompat
import com.github.panpf.zoomimage.util.TransformCompat
import kotlinx.coroutines.launch

/**
 * 把两张 SketchZoomImageView 的缩放/位移做单向同步：
 * 用户当前手指落在哪张图,哪张就是 source,另一张被动跟随。
 *
 * 同步的是 **userTransform**(用户手势相对 fit 的倍率/位移),不是 final
 * transform。两张图尺寸不一样(典型例子:超分对比,原图 2400x3200、上采样
 * 4800x6400)时 baseScale 也不一样,直接同步 final scale 会让 follower 在视
 * 觉上比 source 多/少缩放——top 双击到 2x base 时,bottom 把同一个 final
 * scale 解释成 4x base,两边对不上(issue #882 后续报告)。
 *
 * 而双向 syncing-flag 又会在 min/max scale 不一致时反复 clamp 把 UI 喂死
 * (issue #882),所以仍然走 source-follower 单向。
 */
@SuppressLint("ClickableViewAccessibility")
fun setupZoomSync(
    lifecycleOwner: LifecycleOwner,
    left: SketchZoomImageView,
    right: SketchZoomImageView,
) {
    var source: SketchZoomImageView? = null
    val tracker = android.view.View.OnTouchListener { v, _ ->
        source = v as? SketchZoomImageView
        false
    }
    left.setOnTouchListener(tracker)
    right.setOnTouchListener(tracker)

    lifecycleOwner.lifecycleScope.launch {
        left.zoomable.transformState.collect { transform ->
            if (source !== left) return@collect
            mirrorUserTransform(src = left, dst = right, srcTransform = transform)
        }
    }
    lifecycleOwner.lifecycleScope.launch {
        right.zoomable.transformState.collect { transform ->
            if (source !== right) return@collect
            mirrorUserTransform(src = right, dst = left, srcTransform = transform)
        }
    }
}

/**
 * 把 src 的 userTransform 镜像到 dst:
 *   userScale_src  = transform.scaleX / baseScale_src
 *   userOffset_src = transform.offset - baseOffset_src * userScale_src
 * 想要 dst 的 userScale/userOffset 与 src 一致, 反推出要传给 dst.scale/offset
 * 的 final 值(API 接收 final, 内部再除回 dst 自己的 baseScale)。
 */
private suspend fun mirrorUserTransform(
    src: SketchZoomImageView,
    dst: SketchZoomImageView,
    srcTransform: TransformCompat,
) {
    val srcBase = src.zoomable.baseTransformState.value
    val dstBase = dst.zoomable.baseTransformState.value
    val srcBaseScale = srcBase.scaleX
    val dstBaseScale = dstBase.scaleX
    if (srcBaseScale <= 0f || dstBaseScale <= 0f) return

    val userScale = srcTransform.scaleX / srcBaseScale
    val userOffsetX = srcTransform.offset.x - srcBase.offset.x * userScale
    val userOffsetY = srcTransform.offset.y - srcBase.offset.y * userScale

    val dstFinalScale = userScale * dstBaseScale
    val dstFinalOffset = OffsetCompat(
        x = userOffsetX + dstBase.offset.x * userScale,
        y = userOffsetY + dstBase.offset.y * userScale,
    )

    dst.zoomable.scale(dstFinalScale, animated = false)
    dst.zoomable.offset(dstFinalOffset, animated = false)
}
