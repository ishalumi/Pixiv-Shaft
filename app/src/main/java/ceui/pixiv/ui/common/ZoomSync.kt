package ceui.pixiv.ui.common

import android.annotation.SuppressLint
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.github.panpf.zoomimage.SketchZoomImageView
import kotlinx.coroutines.launch

/**
 * 把两张 SketchZoomImageView 的缩放/位移做单向同步：
 * 用户当前手指落在哪张图,哪张就是 source,另一张被动跟随。
 *
 * 双向 syncing-flag 方案在两张图 min/max scale 不一致时(典型例子:超分对比,
 * 一张原图一张 2x 上采样)会在边界值反复 clamp,在主线程上无限弹来弹去,把
 * UI 卡死(见 issue #882)。改成 source-follower 后,被动方永不回灌,从根上
 * 断掉回路。
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
            right.zoomable.scale(transform.scaleX, animated = false)
            right.zoomable.offset(transform.offset, animated = false)
        }
    }
    lifecycleOwner.lifecycleScope.launch {
        right.zoomable.transformState.collect { transform ->
            if (source !== right) return@collect
            left.zoomable.scale(transform.scaleX, animated = false)
            left.zoomable.offset(transform.offset, animated = false)
        }
    }
}
