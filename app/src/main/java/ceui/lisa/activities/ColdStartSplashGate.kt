package ceui.lisa.activities

/**
 * 开屏动画保持条件的信号源：首页推荐插画 tab（[ceui.pixiv.ui.home.RecmdIllustFeedFragment]，
 * `TYPE_ILLUST` 实例，宿主是 `FragmentLeft`）的本地优先缓存裁决完成（命中或未命中）后置位。
 * 同一个 Fragment 类还被 `RecmdMangaFeedFragment`（独立 TemplateActivity 页面，`TYPE_MANGA`）
 * 复用，那份实例跟 MainActivity 冷启动无关，不会、也不该触发这个信号。
 *
 * [MainActivity] 用 `SplashScreen.setKeepOnScreenCondition` 按帧轮询 [isResolved]，
 * 决定何时放开系统开屏动画——只等本地裁决（有界、通常几十毫秒），不等网络（无界，
 * 网络慢 / 挂断不能把开屏焊死）。这样开屏消失后屏幕上直接是内容（缓存命中）或常规
 * 全屏 loading（未命中），中间不会再闪一帧过渡态。
 *
 * 必须配合 [MainActivity] 的两层兜底，单靠这个信号本身既不判断「这次冷启动是否用得上」
 * 也没有超时保护：
 * 1. `getNavigationInitPosition() != 0`（用户把启动页设成了别的 tab）时 MainActivity
 *    直接放行，不等——那种情况下首页推荐 tab 根本不会被创建，这个信号永远不会置位；
 * 2. `SPLASH_SAFETY_TIMEOUT_MS` 安全超时：即便落在了首页推荐 tab，Fragment 链路也可能
 *    因异常等原因没跑到，超时后强制放行，开屏不会永久卡住。
 */
object ColdStartSplashGate {

    @Volatile
    private var resolved = false

    /** [MainActivity.onCreate] 每次真正创建时重置，避免同进程内第二个实例复用到旧状态。 */
    @JvmStatic
    fun reset() {
        resolved = false
    }

    @JvmStatic
    fun markResolved() {
        resolved = true
    }

    @JvmStatic
    fun isResolved(): Boolean = resolved
}
