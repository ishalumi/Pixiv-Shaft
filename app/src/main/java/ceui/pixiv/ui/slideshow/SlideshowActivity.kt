package ceui.pixiv.ui.slideshow

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import ceui.lisa.R
import ceui.pixiv.i18n.AppLocales

class SlideshowActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }

    // 这个 Activity 不继承 BaseActivity，所以得自己把 locale wrap 接上 —— 否则
    // 首装 onboarding 选完语言、登录后用户进幻灯片，幻灯片 Activity 的 Resources 还会停留在
    // 系统 locale，里面 @string 资源是错的。
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocales.wrapWithSavedLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_slideshow)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}
