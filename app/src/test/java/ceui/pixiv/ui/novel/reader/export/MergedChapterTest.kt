package ceui.pixiv.ui.novel.reader.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #903：合并下载的章节标题 ① 不许截断 ② TXT 章节头要能被阅读 App 自动拆章。
 *
 * 阅读 App（Legado / 多看等）的默认章节正则带行长上限——Legado 默认规则
 * 以 `.{0,30}$` 结尾，即「章」字之后最多 30 个字符。所以这两个诉求在长标题
 * 上是冲突的，解法是报告者建议的：TXT 头行用截短版（可识别），完整标题补在
 * 头行下一行；MD/PDF/EPUB 用完整标题。
 *
 * 副标题行（完整标题）必须**不带**「第N章」前缀——否则标题恰好 29 字时副标题
 * 行也落在正则限制内，同一章被识别成两章（见「exactly one」回归测试）。
 */
class MergedChapterTest {

    /** Legado 默认 TXT 章节识别正则的等价简化版：第N章 + 后续不超过 30 字符。 */
    private val legadoLikeRule = Regex("^第\\d+章.{0,30}$")

    /** 模拟 MergedTxtWriter 输出的章节头行（头行 + 可选的副标题行）。 */
    private fun txtHeadLines(ch: MergedChapter): List<String> =
        listOfNotNull(ch.txtHeaderLine, ch.txtSubtitleLine)

    @Test
    fun `short title - header equals full title and matches reader rule`() {
        val ch = MergedChapter.numbered(1, "雨の日", "正文")
        assertEquals("第1章 雨の日", ch.title)
        assertEquals(ch.title, ch.txtHeaderLine)
        assertNull(ch.txtSubtitleLine)
        assertTrue(legadoLikeRule.matches(ch.txtHeaderLine))
    }

    @Test
    fun `long title - full title is NOT truncated`() {
        val longTitle = "这是一个超级长的标题".repeat(5) // 50 字
        val ch = MergedChapter.numbered(3, longTitle, "正文")
        assertEquals("第3章 $longTitle", ch.title)
    }

    @Test
    fun `long title - txt header is truncated and still matches reader rule`() {
        val longTitle = "这是一个超级长的标题".repeat(5) // 50 字
        val ch = MergedChapter.numbered(3, longTitle, "正文")
        assertTrue(ch.txtHeaderLine != ch.title)
        assertTrue(ch.txtHeaderLine.endsWith("…"))
        assertTrue(
            "TXT 头行必须能被阅读 App 章节正则识别: '${ch.txtHeaderLine}'",
            legadoLikeRule.matches(ch.txtHeaderLine),
        )
    }

    @Test
    fun `long title - subtitle line is full title without chapter prefix`() {
        val longTitle = "这是一个超级长的标题".repeat(5) // 50 字
        val ch = MergedChapter.numbered(3, longTitle, "正文")
        // 副标题行 = 完整标题原文,不带「第N章」前缀
        assertEquals(longTitle, ch.txtSubtitleLine)
        // 不带前缀 → 不可能被章节正则误识别成新章节
        assertFalse(legadoLikeRule.matches(ch.txtSubtitleLine!!))
    }

    @Test
    fun `every title length produces exactly ONE reader-detectable head line`() {
        // 回归(#903 边界 bug):标题恰好 29 字时,带「第N章」前缀的副标题行
        // (章后正好 30 字符)也会匹配阅读 App 正则 → 同一章被拆成两章。
        // 不变量:无论标题多长,章节头部分恰好有且只有 1 行可被识别。
        for (len in 1..60) {
            val ch = MergedChapter.numbered(7, "标".repeat(len), "正文")
            val headLines = txtHeadLines(ch)
            assertEquals(
                "标题长度 $len 时章节头可识别行数应为 1: $headLines",
                1,
                headLines.count { legadoLikeRule.matches(it) },
            )
        }
    }

    @Test
    fun `chapter index with many digits still matches reader rule`() {
        val longTitle = "标题".repeat(20)
        val ch = MergedChapter.numbered(1234, longTitle, "正文")
        assertTrue(legadoLikeRule.matches(ch.txtHeaderLine))
    }

    @Test
    fun `newlines and tabs in title are collapsed to single space`() {
        val ch = MergedChapter.numbered(2, "上篇\n\t下篇  完", "正文")
        assertEquals("第2章 上篇 下篇 完", ch.title)
        // 头行绝不能含换行,否则章节头会被拆成多行,正则匹配不上
        assertFalse(ch.txtHeaderLine.contains('\n'))
        assertFalse(ch.title.contains('\n'))
    }

    @Test
    fun `truncation does not split surrogate pairs`() {
        // 27 个普通字符后跟 emoji(surrogate pair):截断点恰好落在 emoji 中间
        val title = "字".repeat(27) + "😀" + "尾巴".repeat(10)
        val ch = MergedChapter.numbered(5, title, "正文")
        // 截短结果里不允许出现不成对的 surrogate
        val header = ch.txtHeaderLine
        header.forEachIndexed { idx, c ->
            if (c.isHighSurrogate()) {
                assertTrue("位置 $idx 出现不成对的 high surrogate", idx + 1 < header.length && header[idx + 1].isLowSurrogate())
            }
            if (c.isLowSurrogate()) {
                assertTrue("位置 $idx 出现不成对的 low surrogate", idx > 0 && header[idx - 1].isHighSurrogate())
            }
        }
    }

    @Test
    fun `default constructor keeps header equal to title for synthetic chapters`() {
        // 跨系列 AllMergedOne 的「系列分隔头」走默认构造,头行 = 标题,无副标题行
        val ch = MergedChapter(title = "<系列 1/3>《某系列》", text = "SeriesId: 1")
        assertEquals(ch.title, ch.txtHeaderLine)
        assertNull(ch.txtSubtitleLine)
    }
}
