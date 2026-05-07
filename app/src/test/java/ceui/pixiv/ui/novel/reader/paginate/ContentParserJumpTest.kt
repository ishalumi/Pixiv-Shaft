package ceui.pixiv.ui.novel.reader.paginate

import ceui.pixiv.ui.novel.reader.model.ContentToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户反馈 (issue #860)：含 `[jump:N]` 跳转标签的小说，原生 Pixiv 把它渲染为
 * 跳转按钮，Shaft 却把整行作为正文显示，多个 jump 之间的内容混在一起无法
 * 区分。修复点：parser 识别 `[jump:N]` 为独占整行的块级 token；resolver 把
 * target 解析为对应 [newpage] 段的字符位置。
 */
class ContentParserJumpTest {

    @Test fun `bare jump line emits a Jump token with parsed target`() {
        val tokens = ContentParser.tokenize("正文上\n[jump:3]\n正文下")
        val jump = tokens.filterIsInstance<ContentToken.Jump>().single()
        assertEquals(3, jump.target)
    }

    @Test fun `jump tag with surrounding whitespace still parses`() {
        val tokens = ContentParser.tokenize("  [jump:1]  ")
        val jump = tokens.filterIsInstance<ContentToken.Jump>().single()
        assertEquals(1, jump.target)
    }

    @Test fun `inline jump at end of option line splits into paragraph plus jump`() {
        // 真机抓取（novel id 17949544）里 CYOA 风格小说会把 jump 贴在选项行末，
        // 形如 `——原路返回吧。[jump:8]`。原 fix 用 matchEntire 漏掉这种写法，
        // 导致 `[jump:8]` 字面量直接漏到正文。现在按 jump 位置切片：前缀文字
        // 走 Paragraph，jump 单独成 Jump token。
        val tokens = ContentParser.tokenize("——原路返回吧。[jump:8]")
        val nonBlank = tokens.filter { it !is ContentToken.BlankLine }
        assertEquals(2, nonBlank.size)
        val para = nonBlank[0] as ContentToken.Paragraph
        assertEquals("——原路返回吧。", para.text)
        val jump = nonBlank[1] as ContentToken.Jump
        assertEquals(8, jump.target)
    }

    @Test fun `inline jump in middle of line still splits both sides`() {
        val tokens = ContentParser.tokenize("看这里[jump:2]再继续")
        val seq = tokens.filter { it !is ContentToken.BlankLine }
        assertEquals(3, seq.size)
        assertEquals("看这里", (seq[0] as ContentToken.Paragraph).text)
        assertEquals(2, (seq[1] as ContentToken.Jump).target)
        assertEquals("再继续", (seq[2] as ContentToken.Paragraph).text)
    }

    @Test fun `multiple inline jumps on one line each emit jump`() {
        val tokens = ContentParser.tokenize("a[jump:1]b[jump:2]c")
        val targets = tokens.filterIsInstance<ContentToken.Jump>().map { it.target }
        assertEquals(listOf(1, 2), targets)
        val texts = tokens.filterIsInstance<ContentToken.Paragraph>().map { it.text }
        assertEquals(listOf("a", "b", "c"), texts)
    }

    @Test fun `multiple jump tags each emit their own token`() {
        val tokens = ContentParser.tokenize(
            "目录\n[jump:1]\n[jump:2]\n[jump:3]"
        )
        val jumps = tokens.filterIsInstance<ContentToken.Jump>()
        assertEquals(listOf(1, 2, 3), jumps.map { it.target })
    }

    @Test fun `resolveJumpTarget for target 1 returns doc start`() {
        val tokens = ContentParser.tokenize("第一段\n[newpage]\n第二段")
        val char = ContentParser.resolveJumpTarget(tokens, 1)
        assertEquals(0, char)
    }

    @Test fun `resolveJumpTarget for target 2 returns char after first newpage`() {
        val source = "第一段\n[newpage]\n第二段"
        val tokens = ContentParser.tokenize(source)
        val char = ContentParser.resolveJumpTarget(tokens, 2)
        // [newpage] line ends just before "\n第二段"; sourceEnd points past
        // the closing ']' of the tag. Either way char must land inside the
        // second segment.
        assertTrue("char=$char source.length=${source.length}", char != null && char > 0 && char < source.length)
        // Substring from char to end should overlap with second segment.
        assertTrue(source.substring(char!!).contains("第二段"))
    }

    @Test fun `resolveJumpTarget for target N counts newpage boundaries`() {
        val tokens = ContentParser.tokenize("a\n[newpage]\nb\n[newpage]\nc\n[newpage]\nd")
        val char3 = ContentParser.resolveJumpTarget(tokens, 3)
        assertTrue("target 3 should resolve", char3 != null)
        // Page 3 starts after the second [newpage]; should land at or before 'c'.
        val source = "a\n[newpage]\nb\n[newpage]\nc\n[newpage]\nd"
        assertTrue(source.substring(char3!!).startsWith("\nc") || source.substring(char3).startsWith("c"))
    }

    @Test fun `resolveJumpTarget out of range returns null`() {
        val tokens = ContentParser.tokenize("just one segment")
        assertNull(ContentParser.resolveJumpTarget(tokens, 5))
        assertNull(ContentParser.resolveJumpTarget(tokens, 0))
        assertNull(ContentParser.resolveJumpTarget(tokens, -1))
    }
}
