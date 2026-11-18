package com.example.satchel.data

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory

/**
 * The text of PowerPoint (.pptx) and Word (.docx) files, for reading inside the
 * app. Android has no renderer for these formats, so instead of pictures of the
 * pages the reader gets their words, in order: each slide's title and points, or
 * a document's headings, paragraphs and list items. Layout and images are lost.
 *
 * Both formats are zip files of XML; only the parts holding text are read.
 */
object OfficeText {
    enum class Style { Title, Heading, Body, ListItem }

    data class Paragraph(val text: String, val style: Style = Style.Body, val level: Int = 0)

    /** A slide (label "Slide 3", its title, its other text), or a whole Word document (no label). */
    data class Section(val label: String?, val title: String?, val paragraphs: List<Paragraph>)

    /** Stops reading enormous files: about 200 pages of text is plenty to read on a phone. */
    private const val MAX_CHARS = 400_000

    fun pptx(file: File): List<Section> = ZipFile(file).use { zip ->
        val budget = Budget()
        slideOrder(zip).mapIndexedNotNull { i, path ->
            val entry = zip.getEntry(path) ?: return@mapIndexedNotNull null
            if (budget.spent) return@mapIndexedNotNull null
            val paragraphs = zip.getInputStream(entry).use { parse(it, SlideHandler(budget)) }
            Section(
                label = "Slide ${i + 1}",
                title = paragraphs.filter { it.style == Style.Title }.joinToString(" ") { it.text }.ifBlank { null },
                paragraphs = paragraphs.filter { it.style != Style.Title },
            )
        }
    }

    fun docx(file: File): List<Section> = ZipFile(file).use { zip ->
        val entry = zip.getEntry("word/document.xml") ?: return emptyList()
        val paragraphs = zip.getInputStream(entry).use { parse(it, WordHandler(Budget())) }
        listOf(Section(label = null, title = null, paragraphs = paragraphs))
    }

    /**
     * Slides in presentation order. presentation.xml lists them by relationship id,
     * and its .rels file maps each id to a slide file; slide files' numbers need not
     * match their order. Falls back to the numbers if those parts are missing.
     */
    private fun slideOrder(zip: ZipFile): List<String> {
        val ids = zip.getEntry("ppt/presentation.xml")?.let { e ->
            zip.getInputStream(e).use { stream ->
                val found = mutableListOf<String>()
                parse(stream, object : CollectingHandler() {
                    override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
                        if (qName.endsWith("sldId")) attrs.getValue("r:id")?.let(found::add)
                    }
                })
                found
            }
        }.orEmpty()
        val targets = zip.getEntry("ppt/_rels/presentation.xml.rels")?.let { e ->
            zip.getInputStream(e).use { stream ->
                val map = mutableMapOf<String, String>()
                parse(stream, object : CollectingHandler() {
                    override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
                        if (qName == "Relationship") {
                            val id = attrs.getValue("Id") ?: return
                            val target = attrs.getValue("Target") ?: return
                            map[id] = if (target.startsWith("/")) target.removePrefix("/") else "ppt/$target"
                        }
                    }
                })
                map
            }
        }.orEmpty()
        val ordered = ids.mapNotNull(targets::get)
        if (ordered.isNotEmpty()) return ordered

        val number = Regex("""^ppt/slides/slide(\d+)\.xml$""")
        return zip.entries().asSequence().map { it.name }
            .mapNotNull { name -> number.matchEntire(name)?.let { name to it.groupValues[1].toInt() } }
            .sortedBy { it.second }.map { it.first }.toList()
    }

    // ------------------------------- parsing ------------------------------ //

    private class Budget(var used: Int = 0) {
        val spent: Boolean get() = used >= MAX_CHARS
    }

    private class Enough : RuntimeException()

    private abstract class CollectingHandler : DefaultHandler() {
        open val paragraphs: List<Paragraph> get() = emptyList()
    }

    /** One paragraph's text, built from its runs. */
    private abstract class ParagraphHandler(private val budget: Budget, private val textTag: String) : CollectingHandler() {
        override val paragraphs = mutableListOf<Paragraph>()
        protected val text = StringBuilder()
        private var inText = false

        protected fun startText(qName: String) {
            if (qName == textTag) inText = true
        }

        protected fun endText(qName: String) {
            if (qName == textTag) inText = false
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inText) text.append(ch, start, length)
        }

        protected fun finishParagraph(style: Style, level: Int) {
            val value = text.toString().replace(Regex("[ \\t]+"), " ").trim()
            text.setLength(0)
            if (value.isEmpty()) return
            paragraphs += Paragraph(value, style, level)
            budget.used += value.length
            if (budget.spent) throw Enough()
        }
    }

    /** DrawingML in slides: <a:p> paragraphs of <a:t> runs; title placeholders mark the title. */
    private class SlideHandler(budget: Budget) : ParagraphHandler(budget, "a:t") {
        private var inTitleShape = false
        private var inSkippedShape = false   // slide number, footer, date: not content
        private var level = 0

        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
            when (qName) {
                "p:sp", "p:graphicFrame" -> { inTitleShape = false; inSkippedShape = false }
                "p:ph" -> when (attrs.getValue("type")) {
                    "title", "ctrTitle" -> inTitleShape = true
                    "sldNum", "ftr", "dt" -> inSkippedShape = true
                }
                "a:p" -> { text.setLength(0); level = 0 }
                "a:pPr" -> level = attrs.getValue("lvl")?.toIntOrNull() ?: 0
                "a:br" -> text.append('\n')
                else -> startText(qName)
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when {
                qName != "a:p" -> endText(qName)
                inSkippedShape -> text.setLength(0)
                else -> finishParagraph(if (inTitleShape) Style.Title else Style.Body, level)
            }
        }
    }

    /** WordprocessingML: <w:p> paragraphs of <w:t> runs, with heading styles and list numbering. */
    private class WordHandler(budget: Budget) : ParagraphHandler(budget, "w:t") {
        private var style = Style.Body
        private var level = 0

        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
            when (qName) {
                "w:p" -> { text.setLength(0); style = Style.Body; level = 0 }
                "w:pStyle" -> {
                    val name = attrs.getValue("w:val").orEmpty().lowercase()
                    style = when {
                        name == "title" -> Style.Title
                        name.startsWith("heading") || name.startsWith("titre") -> Style.Heading
                        else -> style
                    }
                }
                "w:numPr" -> if (style == Style.Body) style = Style.ListItem
                "w:ilvl" -> level = attrs.getValue("w:val")?.toIntOrNull() ?: 0
                "w:tab" -> text.append(' ')
                "w:br", "w:cr" -> text.append('\n')
                else -> startText(qName)
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            if (qName == "w:p") finishParagraph(style, level) else endText(qName)
        }
    }

    private fun parse(stream: InputStream, handler: CollectingHandler): List<Paragraph> {
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false   // keep prefixes ("a:t") in element names
            // These files come from other people: never fetch or expand outside entities.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        try {
            factory.newSAXParser().parse(stream, handler)
        } catch (_: Enough) {
            // Read as much as the budget allows; the rest is left out.
        }
        return handler.paragraphs
    }
}
