package ceui.pixiv.ui.recommend

import android.view.View
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 站长推荐 score 显示工具。score 字段在生产范围是个位 ~ 千位,极端可能上万。
 * < 1000 直接显示;1k ~ 999k 用 k 后缀;>= 1M 用 M;均保留 1 位小数,整数时省略。
 *
 * 输入容错:负数 / NaN / 0 一律视为"无 score",调用方走 View.GONE。
 */
fun formatTrendingScore(score: Float?): String? {
    if (score == null || !score.isFinite() || score <= 0f) return null
    val n = score.roundToInt()
    return when {
        n < 1000 -> n.toString()
        n < 1_000_000 -> trimDecimal(n / 1000.0) + "k"
        else -> trimDecimal(n / 1_000_000.0) + "M"
    }
}

private fun trimDecimal(v: Double): String {
    val r = String.format(Locale.US, "%.1f", v)
    return if (r.endsWith(".0")) r.dropLast(2) else r
}

/** 把 score 套到 pill TextView 上:有值→VISIBLE + "▲ <formatted>",无值→GONE。 */
fun TextView.bindTrendingScore(score: Float?) {
    val text = formatTrendingScore(score)
    if (text == null) {
        visibility = View.GONE
    } else {
        visibility = View.VISIBLE
        this.text = "▲ $text"
    }
}
