package ceui.pixiv.ui.detail

import ceui.loxia.Comment
import ceui.loxia.User
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkCommentPreviewTest {

    @Test
    fun `older preview response keeps locally acknowledged comments first`() {
        val local = comment(30L)
        val loaded = listOf(comment(20L), comment(10L))

        val merged = mergeCommentPreview(listOf(local), loaded)

        assertEquals(listOf(30L, 20L, 10L), merged.map { it.id })
    }

    @Test
    fun `preview merge orders newest local comment first and removes server duplicates`() {
        val olderLocal = comment(20L)
        val newerLocal = comment(30L)
        val loaded = listOf(comment(30L), comment(10L))

        val merged = mergeCommentPreview(listOf(olderLocal, newerLocal), loaded)

        assertEquals(listOf(30L, 20L, 10L), merged.map { it.id })
    }

    private fun comment(id: Long) = Comment(id = id, user = User(id = id))
}
