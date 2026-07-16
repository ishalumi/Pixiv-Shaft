package ceui.pixiv.ui.settings

import androidx.annotation.StringRes
import ceui.lisa.R

/**
 * 十种主题色的目录：索引 → (名称, 色值)。
 *
 * 索引就是 [ceui.lisa.utils.Settings.getThemeIndex] 持久化的那个值，也是
 * `R.style.AppTheme_IndexN` 的 N（见 [ceui.lisa.activities.BaseActivity] / [ceui.lisa.activities.Shaft]
 * 的 updateTheme）。**顺序只能往后追加，绝不能重排或中间删** —— 老用户存的是下标，
 * 重排等于把所有人的主题静默换成另一个颜色。
 *
 * 建这个目录是为了收口：同一份色值以前有两份硬编码（旧 FragmentColors 的列表 + [ceui.lisa.activities.Shaft.getThemeColor]
 * 的 switch），而且已经漂了 —— 6 号一处写 `#f44336` 一处写 `#F44336`。现在两边都读这里。
 */
object ThemeColorCatalog {

    data class Entry(@StringRes val nameRes: Int, val hex: String)

    val entries: List<Entry> = listOf(
        Entry(R.string.color_shiYinPurple, "#686bdd"), // 纪念尹子烨（尹桂祥）
        Entry(R.string.color_classicBlue, "#56baec"),
        Entry(R.string.color_officialBlue, "#008BF3"),
        Entry(R.string.color_scallionGreen, "#03d0bf"),
        Entry(R.string.color_summerYellow, "#fee65e"),
        Entry(R.string.color_peachPink, "#fe83a2"),
        Entry(R.string.color_activeRed, "#f44336"),
        Entry(R.string.color_classicPurple, "#673AB7"),
        Entry(R.string.color_classicGreen, "#4CAF50"),
        Entry(R.string.color_girlPink, "#E91E63"),
    )

    /** 越界一律回落 0 号，与 updateTheme 的 `default -> AppTheme_Default` 分支同义。 */
    private fun entryOf(index: Int): Entry = entries.getOrElse(index) { entries[0] }

    @JvmStatic
    @StringRes
    fun nameResOf(index: Int): Int = entryOf(index).nameRes

    @JvmStatic
    fun hexOf(index: Int): String = entryOf(index).hex
}
