package ceui.pixiv.ui.search

import ceui.lisa.activities.Shaft
import ceui.lisa.models.NovelBean
import java.util.Locale

/**
 * 小说 AI 灌水 / 引流垃圾过滤（isha）。
 *
 * 规则：
 * 1. 用户样本：乱码作者 + 简介「传送门：https://teach.link/...」
 * 2. 移植 [echo152/pixiv-custom-filter] 的关键词思路（内容/作者）
 * 3. 用户可在「其他条件」里开关 + 追加额外屏蔽词（存 Settings）
 *
 * 内置词始终生效（开关打开时）；额外词与内置合并。
 */
object SpamNovelFilter {

    // ── 内置：引流 / AI 流水线（不含个人口味标签） ──
    private val BUILTIN_CONTENT = listOf(
        "teach.link",
        "传送门",
        "reurl",
        "t.me",
        "telegram",
        "ai平台",
        "ai风月",
        "ai网站",
        "ai创作",
        "ai角色",
        "无限制ai",
        "支付宝、微信购买渠道",
        "推荐节点",
        "本试读版",
        "小说合集",
        "各类小说合集",
        "标签随意",
        "精彩小说",
        "全网",
        "当天更新。",
        "日更一",
        "群内每天都有小说免费看",
        "查看主页",
        "梯子",
        "约稿",
        "接约稿",
        "加qq",
        "加Q",
        "+Q",
        "加q",
        "网调",
        ".chat/",
        "编号：",
        "零帧起手",
    )

    private val BUILTIN_AUTHOR = listOf(
        "kajns", "ytks", "dfgf", "ykk", "vbovx", "msubm", "yqnaz",
        "zour", "fhg", "gfh", "suibi", "painoral", "crazybrain",
        "moncheri", "nicky",
    )

    private val PORTAL_URL = Regex(
        """https?://(?:www\.)?(?:teach\.link|reurl\.cc|t\.me)/\S+""",
        RegexOption.IGNORE_CASE,
    )
    private val PORTAL_CN = Regex("""传送门\s*[:：]?\s*https?://""", RegexOption.IGNORE_CASE)
    private val GIBBERISH = Regex("""^[a-z]{5,14}$""")
    private val CONSONANT_RUN = Regex("""[bcdfghjklmnpqrstvwxyz]{4,}""")
    private val DIGIT_USER = Regex("""^用户\d{6,}$""")

    fun isEnabled(): Boolean {
        return try {
            Shaft.sSettings?.isNovelSpamFilterEnabled != false
        } catch (_: Throwable) {
            true
        }
    }

    fun isSpam(bean: NovelBean): Boolean {
        if (!isEnabled()) return false

        val title = bean.title.orEmpty()
        val caption = bean.caption.orEmpty()
        val name = bean.user?.name.orEmpty().trim()
        val account = bean.user?.account.orEmpty().trim()
        val tagText = bean.tags?.joinToString(" ") { it.name.orEmpty() }.orEmpty()
        val hay = "$title\n$caption\n$tagText".lowercase(Locale.ROOT)

        // 1) URL / 传送门
        if (PORTAL_URL.containsMatchIn(caption) || PORTAL_URL.containsMatchIn(title)) return true
        if (PORTAL_CN.containsMatchIn(caption) || PORTAL_CN.containsMatchIn(title)) return true

        // 2) 内容关键词 = 内置 + 用户额外
        for (kw in contentKeywords()) {
            if (kw.isNotEmpty() && hay.contains(kw.lowercase(Locale.ROOT))) return true
        }

        // 3) 作者关键词
        val authorHay = "$name $account".lowercase(Locale.ROOT)
        for (kw in authorKeywords()) {
            if (kw.isNotEmpty() && authorHay.contains(kw.lowercase(Locale.ROOT))) return true
        }
        if (DIGIT_USER.containsMatchIn(name) || DIGIT_USER.containsMatchIn(account)) return true

        // 4) 乱码作者启发式
        val gibberishAuthor = isGibberishHandle(name) || isGibberishHandle(account)
        if (gibberishAuthor && (bean.total_bookmarks <= 2 || bean.total_view <= 5)) return true
        if (gibberishAuthor && caption.length <= 40 && hasSlashTagDump(bean)) return true

        // 5) 无有效简介 + 极低互动
        if (caption.trim().length < 4 && bean.total_bookmarks <= 1 && bean.total_view <= 3) {
            return true
        }

        // 6) 官方 AI 标 + 几乎无收藏
        if (bean.novel_ai_type == 2 && bean.total_bookmarks <= 3) return true

        return false
    }

    fun contentKeywords(): List<String> {
        val extra = parseLines(
            try { Shaft.sSettings?.novelSpamContentExtra } catch (_: Throwable) { null }
        )
        return BUILTIN_CONTENT + extra
    }

    fun authorKeywords(): List<String> {
        val extra = parseLines(
            try { Shaft.sSettings?.novelSpamAuthorExtra } catch (_: Throwable) { null }
        )
        return BUILTIN_AUTHOR + extra
    }

    fun extraContentCount(): Int = parseLines(
        try { Shaft.sSettings?.novelSpamContentExtra } catch (_: Throwable) { null }
    ).size

    fun extraAuthorCount(): Int = parseLines(
        try { Shaft.sSettings?.novelSpamAuthorExtra } catch (_: Throwable) { null }
    ).size

    private fun parseLines(raw: String?): List<String> =
        raw.orEmpty().split('\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

    fun isGibberishHandle(raw: String): Boolean {
        val s = raw.trim().lowercase(Locale.ROOT)
        if (!GIBBERISH.matches(s)) return false
        if (s.startsWith("user") || s.startsWith("pixiv") || s.startsWith("official")) return false
        val vowels = s.count { it in "aeiou" }
        if (vowels == 0) return true
        if (vowels <= 1 && s.length >= 6) return true
        if (CONSONANT_RUN.containsMatchIn(s)) return true
        return false
    }

    private fun hasSlashTagDump(bean: NovelBean): Boolean {
        val tags = bean.tags ?: return false
        var slashy = 0
        for (tag in tags) {
            if (tag.name.orEmpty().count { it == '/' } >= 3) slashy++
        }
        return slashy >= 2 || tags.size >= 12
    }
}
