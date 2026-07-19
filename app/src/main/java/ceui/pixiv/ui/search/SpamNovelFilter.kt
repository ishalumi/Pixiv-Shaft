package ceui.pixiv.ui.search

import ceui.lisa.models.NovelBean
import java.util.Locale

/**
 * 小说 AI 灌水 / 引流垃圾启发式过滤（isha）。
 *
 * 样本特征（用户反馈）：
 * - 作者名/账号：纯小写乱码键盘串，如 dxtyjmg / drthbv / dxthnmj
 * - 简介：`传送门：https://teach.link/xxxx`
 * - 常无官方 AI 标签，但内容是垃圾引流
 *
 * 宁可误伤少量乱码用户名，也不放行 teach.link 流水线。
 */
object SpamNovelFilter {

    private val PORTAL_URL = Regex(
        """https?://(?:www\.)?teach\.link/\S+""",
        RegexOption.IGNORE_CASE,
    )
    private val PORTAL_CN = Regex("""传送门\s*[:：]?\s*https?://""", RegexOption.IGNORE_CASE)
    private val GIBBERISH = Regex("""^[a-z]{5,14}$""")
    private val CONSONANT_RUN = Regex("""[bcdfghjklmnpqrstvwxyz]{4,}""")

    fun isSpam(bean: NovelBean): Boolean {
        val caption = bean.caption.orEmpty()
        // 1) 简介里有 teach.link / 传送门：硬丢
        if (PORTAL_URL.containsMatchIn(caption) || PORTAL_CN.containsMatchIn(caption)) {
            return true
        }
        if (caption.contains("teach.link", ignoreCase = true) && caption.contains("传送门")) {
            return true
        }

        val name = bean.user?.name.orEmpty().trim()
        val account = bean.user?.account.orEmpty().trim()
        val gibberishAuthor = isGibberishHandle(name) || isGibberishHandle(account)

        // 2) 乱码作者 + 低互动（收藏≤2 或 浏览≤5）→ 丢
        if (gibberishAuthor && (bean.total_bookmarks <= 2 || bean.total_view <= 5)) {
            return true
        }

        // 3) 乱码作者 + 简介极短/空 + 超长斜杠标签堆 → 丢
        if (gibberishAuthor && caption.length <= 40 && hasSlashTagDump(bean)) {
            return true
        }

        // 4) 官方 novel_ai_type==2 且收藏≤3（AI 新灌）→ 丢
        if (bean.novel_ai_type == 2 && bean.total_bookmarks <= 3) {
            return true
        }

        return false
    }

    /** 纯小写 a-z、5–14 位、元音极少或连续辅音 ≥4 —— 键盘乱敲特征。 */
    fun isGibberishHandle(raw: String): Boolean {
        val s = raw.trim().lowercase(Locale.ROOT)
        if (!GIBBERISH.matches(s)) return false
        // 常见真账号白名单前缀，避免误伤
        if (s.startsWith("user") || s.startsWith("pixiv") || s.startsWith("official")) return false
        val vowels = s.count { it in "aeiou" }
        if (vowels == 0) return true
        if (vowels <= 1 && s.length >= 6) return true
        if (CONSONANT_RUN.containsMatchIn(s)) return true
        return false
    }

    /** 标签里有大量「a/b/c/d」式堆砌（灌水脚本特征）。 */
    private fun hasSlashTagDump(bean: NovelBean): Boolean {
        val tags = bean.tags ?: return false
        var slashy = 0
        for (tag in tags) {
            val n = tag.name.orEmpty()
            if (n.count { it == '/' } >= 3) slashy++
        }
        return slashy >= 2 || tags.size >= 12
    }
}
