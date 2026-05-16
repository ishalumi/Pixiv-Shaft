package ceui.pixiv.ui.novel.reader.export

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import ceui.pixiv.download.model.RelativePath
import ceui.pixiv.ui.novel.reader.paginate.TextMeasurer
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 合并下载（单系列章节合并 / 跨系列作者合集）共享的输出层。Single-export 走的是
 * [NovelExporter]（Novel + WebNovel + tokens），合并下载没有 tokens 这一层（章节
 * 是从 Web API 字符串里抠的），所以另开一套抽象，避免硬把 [String] 塞进 token AST。
 *
 * 文件命名 / 目录由 caller 通过 [RelativePath] 决定 —— writer 只负责把内容按格式
 * 落盘。所有 writer 都走 [ExportUtils.saveToDownloads] 写 MediaStore Novel bucket。
 */
interface MergedNovelWriter {
    val format: ExportFormat

    suspend fun write(
        context: Context,
        content: MergedNovelContent,
        destination: RelativePath,
    ): Boolean
}

/**
 * 合并下载的内容包：封面元数据 + 章节列表。chapter 的 text 已经做了 br→\n、
 * 去 HTML 标签处理（见 [DownloadNovelTask.replaceBrWithNewLine] 等清洗路径）。
 */
data class MergedNovelContent(
    /** 显示用标题，如「《某系列》合集」「某作者 全系列合集」。 */
    val displayTitle: String,
    val author: String?,
    /** 比如 https://www.pixiv.net/novel/series/123，可空。 */
    val sourceUrl: String?,
    /** 作者写的系列简介，可空。 */
    val caption: String?,
    val chapters: List<MergedChapter>,
    /** EPUB unique-identifier 用，TXT/MD/PDF 也用作 footer。 */
    val documentId: String,
)

data class MergedChapter(val title: String, val text: String)

object MergedNovelWriters {
    private val writers: Map<ExportFormat, MergedNovelWriter> = mapOf(
        ExportFormat.Txt to MergedTxtWriter,
        ExportFormat.Markdown to MergedMarkdownWriter,
        ExportFormat.Pdf to MergedPdfWriter,
        ExportFormat.Epub to MergedEpubWriter,
    )

    fun forFormat(format: ExportFormat): MergedNovelWriter = writers.getValue(format)
}

// ────────────────────────────────────────────────────────────────────
// TXT
// ────────────────────────────────────────────────────────────────────

private object MergedTxtWriter : MergedNovelWriter {
    override val format = ExportFormat.Txt

    override suspend fun write(
        context: Context,
        content: MergedNovelContent,
        destination: RelativePath,
    ): Boolean {
        val text = buildString {
            append("《").append(content.displayTitle).append("》\n")
            content.author?.takeIf { it.isNotBlank() }?.let { append("作者: ").append(it).append('\n') }
            content.sourceUrl?.takeIf { it.isNotBlank() }?.let { append("来源: ").append(it).append('\n') }
            content.caption?.takeIf { it.isNotBlank() }?.let {
                append('\n').append("简介:\n").append(it.trim()).append('\n')
            }
            append('\n').append("----------------------\n\n\n")
            content.chapters.forEach { ch ->
                append("\n\n<===== ").append(ch.title).append(" =====>\n\n")
                append(ch.text)
                append("\n\n")
            }
        }
        val uri = ExportUtils.saveToDownloads(context, destination, format.mimeType) { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
        }
        return uri != null
    }
}

// ────────────────────────────────────────────────────────────────────
// Markdown
// ────────────────────────────────────────────────────────────────────

private object MergedMarkdownWriter : MergedNovelWriter {
    override val format = ExportFormat.Markdown

    override suspend fun write(
        context: Context,
        content: MergedNovelContent,
        destination: RelativePath,
    ): Boolean {
        val text = buildString {
            append("# ").append(content.displayTitle).append("\n\n")
            content.author?.takeIf { it.isNotBlank() }?.let { append("**作者**: ").append(it).append("  \n") }
            content.sourceUrl?.takeIf { it.isNotBlank() }?.let { append("**来源**: <").append(it).append(">  \n") }
            content.caption?.takeIf { it.isNotBlank() }?.let {
                append('\n').append("> ").append(it.trim().replace("\n", "\n> ")).append('\n')
            }
            append("\n---\n")
            content.chapters.forEach { ch ->
                append("\n## ").append(ch.title).append("\n\n")
                ch.text.split('\n').forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) append('\n') else append(trimmed).append("\n\n")
                }
            }
        }
        val uri = ExportUtils.saveToDownloads(context, destination, format.mimeType) { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
        }
        return uri != null
    }
}

// ────────────────────────────────────────────────────────────────────
// PDF
// ────────────────────────────────────────────────────────────────────
//
// Layout pen-pictures the single-novel [PdfExporter]: A4 @72dpi, serif body,
// chapter heads on a fresh page, paragraph-flow with line-level page splits.
// Images are skipped — merge sources have hundreds of chapters; embedding
// every illustration would blow file size up. Picking EPUB stays the path
// for "I want pictures with my text."

private object MergedPdfWriter : MergedNovelWriter {
    override val format = ExportFormat.Pdf

    private const val PAGE_WIDTH_PT = 595
    private const val PAGE_HEIGHT_PT = 842
    private const val MARGIN_PT = 48f
    private val contentWidthPt: Float = PAGE_WIDTH_PT - MARGIN_PT * 2

    override suspend fun write(
        context: Context,
        content: MergedNovelContent,
        destination: RelativePath,
    ): Boolean {
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("serif", Typeface.NORMAL)
            textSize = 12f
            color = 0xFF222222.toInt()
        }
        // Chapter title 走 StaticLayout + Layout.Alignment.ALIGN_CENTER；不再
        // 设 Paint.Align.CENTER —— 两套对齐机制混用在部分 Android 版本下会让
        // 字符 x 偏移半字宽。与单篇 PdfExporter.chapterPaint 一致。
        val chapterPaint = TextPaint(bodyPaint).apply {
            textSize = 18f
            isFakeBoldText = true
        }
        val titlePaint = TextPaint(bodyPaint).apply {
            textSize = 22f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val metaPaint = TextPaint(bodyPaint).apply {
            textSize = 10f
            color = 0xFF777777.toInt()
            textAlign = Paint.Align.CENTER
        }
        val captionPaint = TextPaint(bodyPaint).apply {
            textSize = 11f
            color = 0xFF555555.toInt()
        }

        val document = PdfDocument()
        val state = PageState()
        state.start(document)

        // Cover block (drawn once at the top of page 1).
        state.currentPage?.canvas?.let { canvas ->
            val cx = PAGE_WIDTH_PT / 2f
            canvas.drawText(content.displayTitle, cx, state.y + 24f, titlePaint)
            state.y += 60f
            content.author?.takeIf { it.isNotBlank() }?.let {
                canvas.drawText("作者: $it", cx, state.y, metaPaint)
                state.y += 16f
            }
            content.sourceUrl?.takeIf { it.isNotBlank() }?.let {
                canvas.drawText(it, cx, state.y, metaPaint)
                state.y += 16f
            }
            canvas.drawText(
                "${content.chapters.size} 章 · Pixiv-Shaft",
                cx, state.y, metaPaint,
            )
            state.y += 48f
        }
        content.caption?.takeIf { it.isNotBlank() }?.let { cap ->
            state.flowText(document, cap.trim(), captionPaint, lineSpacingMultiplier = 1.4f)
            state.y += 16f
        }

        content.chapters.forEach { ch ->
            state.startNewPage(document)
            val titleLayout = TextMeasurer.buildStaticLayout(
                text = ch.title,
                paint = chapterPaint,
                width = contentWidthPt.toInt(),
                lineSpacingMultiplier = 1.2f,
                lineSpacingExtra = 0f,
                alignment = Layout.Alignment.ALIGN_CENTER,
            )
            state.drawLayout(titleLayout)
            state.y += 24f
            state.flowText(document, ch.text, bodyPaint, lineSpacingMultiplier = 1.55f)
        }
        state.finish(document)

        val uri = ExportUtils.saveToDownloads(context, destination, format.mimeType) { out ->
            document.writeTo(out)
        }
        document.close()
        return uri != null
    }

    /** Drawing cursor shared between the cover block, chapter heads, and paragraph flow. */
    private class PageState {
        var currentPage: PdfDocument.Page? = null
        var pageNumber = 0
        var y: Float = 0f

        fun start(document: PdfDocument) {
            pageNumber += 1
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH_PT, PAGE_HEIGHT_PT, pageNumber).create()
            currentPage = document.startPage(info)
            y = MARGIN_PT
        }

        fun startNewPage(document: PdfDocument) {
            finish(document)
            start(document)
        }

        fun finish(document: PdfDocument) {
            currentPage?.let { document.finishPage(it) }
            currentPage = null
        }

        fun drawLayout(layout: Layout) {
            val canvas = currentPage?.canvas ?: return
            val save = canvas.save()
            canvas.translate(MARGIN_PT, y)
            layout.draw(canvas)
            canvas.restoreToCount(save)
            y += layout.height
        }

        fun flowText(
            document: PdfDocument,
            text: String,
            paint: TextPaint,
            lineSpacingMultiplier: Float,
        ) {
            // Paragraph-by-paragraph so a chapter break aligns with a real
            // paragraph boundary rather than a mid-sentence newline.
            val paragraphs = text.split('\n').map { it.trim() }
            for (p in paragraphs) {
                if (p.isEmpty()) {
                    y += paint.textSize
                    continue
                }
                val layout = TextMeasurer.buildStaticLayout(
                    text = p,
                    paint = paint,
                    width = contentWidthPt.toInt(),
                    lineSpacingMultiplier = lineSpacingMultiplier,
                    lineSpacingExtra = 0f,
                )
                drawLayoutWithPageSplit(document, layout)
                y += paint.textSize * 0.6f
            }
        }

        private fun drawLayoutWithPageSplit(document: PdfDocument, layout: StaticLayout) {
            var lineCursor = 0
            while (lineCursor < layout.lineCount) {
                val remaining = (PAGE_HEIGHT_PT - MARGIN_PT) - y
                val startTop = layout.getLineTop(lineCursor)
                var fit = 0
                var used = 0f
                for (i in lineCursor until layout.lineCount) {
                    val h = (layout.getLineBottom(i) - startTop).toFloat()
                    if (h > remaining && fit > 0) break
                    fit = i - lineCursor + 1
                    used = h
                }
                if (fit == 0) {
                    startNewPage(document)
                    continue
                }
                val canvas = currentPage?.canvas
                if (canvas != null) {
                    val save = canvas.save()
                    canvas.translate(MARGIN_PT, y - layout.getLineTop(lineCursor).toFloat())
                    canvas.clipRect(
                        0f,
                        layout.getLineTop(lineCursor).toFloat(),
                        layout.width.toFloat(),
                        layout.getLineBottom(lineCursor + fit - 1).toFloat(),
                    )
                    layout.draw(canvas)
                    canvas.restoreToCount(save)
                }
                y += used
                lineCursor += fit
                if (lineCursor < layout.lineCount) startNewPage(document)
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────
// EPUB
// ────────────────────────────────────────────────────────────────────
//
// Mirrors single-novel [EpubExporter] structure: mimetype (STORED first),
// META-INF/container.xml, OEBPS/{content.opf, toc.ncx, content.xhtml}.
// One combined XHTML with h2 anchors per chapter — same approach as the
// single-novel exporter. Skip images on purpose (see PDF note).

private object MergedEpubWriter : MergedNovelWriter {
    override val format = ExportFormat.Epub

    override suspend fun write(
        context: Context,
        content: MergedNovelContent,
        destination: RelativePath,
    ): Boolean {
        val title = content.displayTitle.ifEmpty { "novel_merge" }
        val novelId = content.documentId.ifEmpty { System.currentTimeMillis().toString() }
        val author = content.author.orEmpty()

        val chapterHtml = buildChapterXhtml(title, author, content.sourceUrl, content.caption, content.chapters)
        val opf = buildContentOpf(novelId, title, author)
        val ncx = buildTocNcx(novelId, title, content.chapters)

        val uri = ExportUtils.saveToDownloads(context, destination, format.mimeType) { out ->
            ZipOutputStream(out).use { zip ->
                storeEntry(zip, "mimetype", "application/epub+zip".toByteArray(Charsets.US_ASCII))
                deflateEntry(zip, "META-INF/container.xml", CONTAINER_XML.toByteArray(Charsets.UTF_8))
                deflateEntry(zip, "OEBPS/content.opf", opf.toByteArray(Charsets.UTF_8))
                deflateEntry(zip, "OEBPS/toc.ncx", ncx.toByteArray(Charsets.UTF_8))
                deflateEntry(zip, "OEBPS/content.xhtml", chapterHtml.toByteArray(Charsets.UTF_8))
            }
        }
        return uri != null
    }

    private fun buildChapterXhtml(
        title: String,
        author: String,
        sourceUrl: String?,
        caption: String?,
        chapters: List<MergedChapter>,
    ): String {
        val body = buildString {
            append("<h1>").append(escape(title)).append("</h1>\n")
            if (author.isNotEmpty()) {
                append("<p class=\"meta\">作者: ").append(escape(author)).append("</p>\n")
            }
            if (!sourceUrl.isNullOrBlank()) {
                append("<p class=\"meta\">").append(escape(sourceUrl)).append("</p>\n")
            }
            if (!caption.isNullOrBlank()) {
                append("<div class=\"caption\"><p>")
                append(escape(caption.trim()).replace("\n", "</p><p>"))
                append("</p></div>\n")
            }
            append("<hr/>\n")
            chapters.forEachIndexed { index, ch ->
                val anchor = "ch${index + 1}"
                append("<h2 id=\"").append(anchor).append("\">")
                append(escape(ch.title)).append("</h2>\n")
                ch.text.split('\n').forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) append("<br/>\n")
                    else append("<p>").append(escape(trimmed)).append("</p>\n")
                }
            }
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
  <title>${escape(title)}</title>
  <meta http-equiv="Content-Type" content="application/xhtml+xml; charset=utf-8"/>
  <style type="text/css">
    body { font-family: serif; line-height: 1.7; padding: 1em; }
    h1 { text-align: center; }
    h2 { border-bottom: 1px solid #ccc; padding-bottom: 0.25em; margin-top: 2em; }
    p { text-indent: 2em; margin: 0.5em 0; }
    p.meta { text-indent: 0; color: #666; font-size: 0.9em; text-align: center; }
    .caption { border-left: 3px solid #ccc; padding: 0.5em 1em; margin: 1em 0; background: #f9f9f9; }
    .caption p { text-indent: 0; color: #555; font-size: 0.95em; }
    hr { border: 0; border-top: 1px dashed #aaa; margin: 2em 25%; }
  </style>
</head>
<body>
$body
</body>
</html>
"""
    }

    private fun buildContentOpf(novelId: String, title: String, author: String): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId" version="2.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
    <dc:title>${escape(title)}</dc:title>
    <dc:creator opf:role="aut">${escape(author.ifEmpty { "Pixiv" })}</dc:creator>
    <dc:language>zh-CN</dc:language>
    <dc:identifier id="BookId" opf:scheme="pixiv">${escape(novelId)}</dc:identifier>
    <dc:publisher>Pixiv-Shaft</dc:publisher>
  </metadata>
  <manifest>
    <item id="content" href="content.xhtml" media-type="application/xhtml+xml"/>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
  </manifest>
  <spine toc="ncx">
    <itemref idref="content"/>
  </spine>
</package>
"""
    }

    private fun buildTocNcx(novelId: String, title: String, chapters: List<MergedChapter>): String {
        val navPoints = if (chapters.isEmpty()) {
            """<navPoint id="p1" playOrder="1">
              <navLabel><text>${escape(title)}</text></navLabel>
              <content src="content.xhtml"/>
            </navPoint>"""
        } else {
            chapters.mapIndexed { index, ch ->
                """<navPoint id="p${index + 1}" playOrder="${index + 1}">
              <navLabel><text>${escape(ch.title)}</text></navLabel>
              <content src="content.xhtml#ch${index + 1}"/>
            </navPoint>"""
            }.joinToString("\n    ")
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="${escape(novelId)}"/>
    <meta name="dtb:depth" content="1"/>
    <meta name="dtb:totalPageCount" content="0"/>
    <meta name="dtb:maxPageNumber" content="0"/>
  </head>
  <docTitle><text>${escape(title)}</text></docTitle>
  <navMap>
    $navPoints
  </navMap>
</ncx>
"""
    }

    private fun storeEntry(zip: ZipOutputStream, name: String, data: ByteArray) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = data.size.toLong()
            compressedSize = data.size.toLong()
            crc = CRC32().apply { update(data) }.value
        }
        zip.putNextEntry(entry)
        zip.write(data)
        zip.closeEntry()
    }

    private fun deflateEntry(zip: ZipOutputStream, name: String, data: ByteArray) {
        val entry = ZipEntry(name).apply { method = ZipEntry.DEFLATED }
        zip.putNextEntry(entry)
        zip.write(data)
        zip.closeEntry()
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private const val CONTAINER_XML = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""
}
