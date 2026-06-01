package ceui.pixiv.ui.novel.reader.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #903：合并下载的章节标题 ① 不许截断 ② TXT 章节头要能被阅读 App 自动拆章。
 *
 * 阅读 App（Legado / 多看等）的默认章节正则带行长上限——Legado 默认规则
 * 以 `.{0,30}$` 结尾，即「章」字之后最多 30 个字符。所以这两个诉求在长标题
 * 上是冲突的，解法是报告者建议的：TXT 头行用截短版（可识别），完整标题补在
 * 头行下一行；MD/PDF/EPUB 用完整标题。
 */
class MergedChapterTest {

    /** Legado 默认 TXT 章节识别正则的等价简化版：第N章 + 后续不超过 30 字符。 */
    private val legadoLikeRule = Regex("^第\\d+章.{0,30}$")

    @Test
    fun `short title - header equals full title and matches reader rule`() {
        val ch = MergedChapter.numbered(1, "雨の日", "正文")
        assertEquals("第1章 雨の日", ch.title)
        assertEquals(ch.title, ch.txtHeaderLine)
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
        // 跨系列 AllMergedOne 的「系列分隔头」走默认构造,头行 = 标题,不做任何加工
        val ch = MergedChapter(title = "<系列 1/3>《某系列》", text = "SeriesId: 1")
        assertEquals(ch.title, ch.txtHeaderLine)
    }
}
