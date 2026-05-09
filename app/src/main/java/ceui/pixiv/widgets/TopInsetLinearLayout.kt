package ceui.pixiv.widgets

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 只把状态栏 inset 吃成 paddingTop。导航栏区域不动，让内容继续延伸到屏幕底部、
 * 与透明导航栏融为一体——避免 fitsSystemWindows="true" 在 edge-to-edge 模式下
 * 同时吞掉底部 inset 形成一条 colorPrimary 色带 (issue #853 收尾)。
 */
class TopInsetLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    init {
        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, systemBars.top, v.paddingRight, v.paddingBottom)
            insets
        }
    }
}
