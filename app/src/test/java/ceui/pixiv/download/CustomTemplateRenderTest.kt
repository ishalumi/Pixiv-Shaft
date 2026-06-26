package ceui.pixiv.download

import ceui.pixiv.download.backend.StorageBackend
import ceui.pixiv.download.config.BucketConfig
import ceui.pixiv.download.config.BucketDefaults
import ceui.pixiv.download.config.ConfigPresets
import ceui.pixiv.download.config.DownloadConfig
import ceui.pixiv.download.config.OverwritePolicy
import ceui.pixiv.download.config.StorageChoice
import ceui.pixiv.download.model.Author
import ceui.pixiv.download.model.Bucket
import ceui.pixiv.download.model.DownloadItem
import ceui.pixiv.download.model.ItemMeta
import ceui.pixiv.download.model.RelativePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * 用户反馈：自定义命名规则后下载的文件名里好像没出现 ID。
 * 这里把「设置自定义模板 → plan → 拿到最终路径」的端到端链路用单测固定下来，
 * 防止以后哪一层默默把模板替换/退回到默认值。
 */
class CustomTemplateRenderTest {

    private val meta = ItemMeta(
        id = 12345L,
        title = "夜の物語",
        author = Author(id = 67890L, name = "Alice"),
        createdAt = Instant.parse("2024-05-01T12:34:56Z"),
        page = 0,
        totalPages = 3,
        width = 1920,
        height = 1080,
        flags = emptySet(),
    )

    private val illustItem = DownloadItem(
        bucket = Bucket.Illust,
        ext = "png",
        mime = "image/png",
        sourceUrl = "",
        meta = meta,
    )

    private val novelItem = DownloadItem(
        bucket = Bucket.Novel,
        ext = "txt",
        mime = "text/plain",
        sourceUrl = "",
        meta = meta,
    )

    private val saneStorage = StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Images)

    private fun cfg(template: String, perBucket: Map<Bucket, BucketConfig> = emptyMap()) =
        DownloadConfig(
            defaults = BucketDefaults(template = template, storage = saneStorage, overwrite = OverwritePolicy.Rename),
            perBucket = perBucket,
        )

    /**
     * 全模板自定义：作者/作品 ID/标题/扩展名都应当原样出现在最终路径里。
     * 反向覆盖用户的怀疑——「ID 不在文件名里」。
     */
    @Test fun `custom template includes id author and title verbatim`() {
        val facade = Downloads(cfg("Shaft/{author}/{id}_{title}.{ext}"), { NoopBackend() })
        val plan = facade.plan(illustItem)
        assertEquals(listOf("Shaft", "Alice", "12345_夜の物語.png"), plan.path.segments)
    }

    /** 多页插画：page 受 pageIndexFrom1 控制，默认 1-based。 */
    @Test fun `page variable substitutes with correct offset`() {
        val multi = illustItem.copy(meta = meta.copy(page = 4))
        val facade = Downloads(cfg("{author}/{id}_p{page}.{ext}"), { NoopBackend() })
        val plan = facade.plan(multi)
        assertEquals(listOf("Alice", "12345_p5.png"), plan.path.segments)
    }

    /** 用户在「小说」桶上单独覆写模板，全局默认应当被覆盖。 */
    @Test fun `per bucket override beats default for novel bucket`() {
        val facade = Downloads(
            cfg(
                template = "default/{id}.{ext}",
                perBucket = mapOf(
                    Bucket.Novel to BucketConfig(template = "ShaftNovels/{author}/{id}_{title}.{ext}"),
                ),
            ),
            { NoopBackend() },
        )
        val plan = facade.plan(novelItem)
        assertEquals(listOf("ShaftNovels", "Alice", "12345_夜の物語.txt"), plan.path.segments)
    }

    /**
     * 默认模板（Shaft 经典风格）下，作者目录 + ID + 标题三件套都应该在最终路径里出现。
     * 这条单测的目的：哪天我们改默认模板，万一不小心把 {id} 删掉，CI 立刻红。
     */
    @Test fun `shaft classic preset default keeps id in output path`() {
        val cfg = ConfigPresets.of(ConfigPresets.Id.ShaftClassic, saneStorage, saneStorage)
        val facade = Downloads(cfg, { NoopBackend() })
        val plan = facade.plan(illustItem)
        assertTrue(
            "shaftClassic 模板必须包含作品 ID，实际 path = ${plan.path.joinTo()}",
            plan.path.joinTo().contains("12345"),
        )
    }

    /** 路径分隔符出现在变量值里时，FsSanitizer 应当替换为下划线，而不是新建子目录。 */
    @Test fun `slash in title is sanitized into underscore not new directory`() {
        val dirty = illustItem.copy(meta = meta.copy(title = "evil/../boom"))
        val facade = Downloads(cfg("{title}_{id}.{ext}"), { NoopBackend() })
        val plan = facade.plan(dirty)
        assertEquals(listOf("evil_.._boom_12345.png"), plan.path.segments)
    }

    /**
     * 坏模板不能崩溃下载。文件名是在 Java 端 DownloadItem 构造器里同步渲染的
     * （主线程、Manager 接手之前），任何抛出都没人接 → 直接闪退。
     * 现在 SafeTemplateRender 在渲染失败时退回该桶的默认模板，下载照常进行。
     */
    @Test fun `unknown variable falls back to bucket default instead of crashing`() {
        val facade = Downloads(cfg("{nope}.{ext}"), { NoopBackend() })
        val plan = facade.plan(illustItem)
        // 退回默认 Illust 模板（ShaftImages/{title}_{id}…），ID 仍在
        assertTrue(
            "坏模板应退回默认命名，实际 path = ${plan.path.joinTo()}",
            plan.path.joinTo().contains("12345"),
        )
    }

    /**
     * 复现崩溃：用户把命名条件写成 `[?p<100:…]`（只支持 p>N），渲染期
     * TemplateContext.evaluate 会因「未知 flag」抛 IllegalStateException。
     * 这条钉住「不再崩溃、退回默认」。
     */
    @Test fun `unsupported page condition does not crash download`() {
        val facade = Downloads(cfg("ShaftImages/{title}_{id}[?p<100:_p{page}].{ext}"), { NoopBackend() })
        val plan = facade.plan(illustItem)
        assertTrue(plan.path.joinTo().contains("12345"))
    }

    private class NoopBackend : StorageBackend {
        override fun open(relPath: RelativePath, mime: String): StorageBackend.WriteHandle =
            error("not used")
        override fun exists(relPath: RelativePath): Boolean = false
        override fun delete(relPath: RelativePath): Boolean = false
    }
}
