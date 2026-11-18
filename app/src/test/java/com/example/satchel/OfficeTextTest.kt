package com.example.satchel

import com.example.satchel.data.OfficeText
import com.example.satchel.data.OfficeText.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OfficeTextTest {
    private fun zip(vararg parts: Pair<String, String>): File =
        File.createTempFile("office", ".zip").apply {
            deleteOnExit()
            ZipOutputStream(outputStream()).use { z ->
                parts.forEach { (name, text) -> z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            }
        }

    private fun slide(title: String, vararg points: Pair<String, Int>) = """
        <p:sld xmlns:p="p" xmlns:a="a"><p:cSld><p:spTree>
          <p:sp><p:nvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr>
            <p:txBody><a:p><a:r><a:t>$title</a:t></a:r></a:p></p:txBody></p:sp>
          <p:sp><p:nvSpPr><p:nvPr><p:ph idx="1"/></p:nvPr></p:nvSpPr><p:txBody>
            ${points.joinToString("") { (t, lvl) -> """<a:p><a:pPr lvl="$lvl"/><a:r><a:t>${t.substring(0, 3)}</a:t></a:r><a:r><a:t>${t.substring(3)}</a:t></a:r></a:p>""" }}
            <a:p></a:p>
          </p:txBody></p:sp>
          <p:sp><p:nvSpPr><p:nvPr><p:ph type="sldNum"/></p:nvPr></p:nvSpPr>
            <p:txBody><a:p><a:r><a:t>7</a:t></a:r></a:p></p:txBody></p:sp>
        </p:spTree></p:cSld></p:sld>
    """

    @Test
    fun slidesFollowThePresentationOrderNotFileNumbers() {
        val file = zip(
            "ppt/presentation.xml" to """<p:presentation xmlns:p="p" xmlns:r="r"><p:sldIdLst>
                <p:sldId id="256" r:id="rId9"/><p:sldId id="257" r:id="rId2"/></p:sldIdLst></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to """<Relationships>
                <Relationship Id="rId2" Target="slides/slide1.xml"/>
                <Relationship Id="rId9" Target="slides/slide2.xml"/></Relationships>""",
            "ppt/slides/slide1.xml" to slide("Joins", "Inner join" to 0, "Outer join" to 1),
            "ppt/slides/slide2.xml" to slide("Introduction", "What is SQL" to 0),
        )
        val sections = OfficeText.pptx(file)
        assertEquals(listOf("Slide 1", "Slide 2"), sections.map { it.label })
        assertEquals(listOf("Introduction", "Joins"), sections.map { it.title })
        // Runs join into one paragraph; empty paragraphs are dropped; levels are kept.
        assertEquals(
            listOf(OfficeText.Paragraph("Inner join", Style.Body, 0), OfficeText.Paragraph("Outer join", Style.Body, 1)),
            sections[1].paragraphs,
        )
    }

    @Test
    fun slidesFallBackToFileNumbersWithoutAPresentationPart() {
        val file = zip(
            "ppt/slides/slide10.xml" to slide("Ten"),
            "ppt/slides/slide2.xml" to slide("Two"),
        )
        assertEquals(listOf("Two", "Ten"), OfficeText.pptx(file).map { it.title })
    }

    @Test
    fun wordHeadingsListsAndParagraphs() {
        val file = zip("word/document.xml" to """<w:document xmlns:w="w"><w:body>
            <w:p><w:pPr><w:pStyle w:val="Title"/></w:pPr><w:r><w:t>Linux notes</w:t></w:r></w:p>
            <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Permissions</w:t></w:r></w:p>
            <w:p><w:r><w:t xml:space="preserve">Every file has </w:t></w:r><w:r><w:t>an owner.</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:ilvl w:val="1"/></w:numPr></w:pPr><w:r><w:t>chmod</w:t></w:r></w:p>
            </w:body></w:document>""")
        val section = OfficeText.docx(file).single()
        assertNull(section.label)
        assertEquals(
            listOf(
                OfficeText.Paragraph("Linux notes", Style.Title),
                OfficeText.Paragraph("Permissions", Style.Heading),
                OfficeText.Paragraph("Every file has an owner.", Style.Body),
                OfficeText.Paragraph("chmod", Style.ListItem, 1),
            ),
            section.paragraphs,
        )
    }

    @Test
    fun outsideEntitiesAreNeverRead() {
        val secret = File.createTempFile("secret", ".txt").apply { deleteOnExit(); writeText("TOP SECRET") }
        val file = zip("word/document.xml" to """<?xml version="1.0"?>
            <!DOCTYPE d [<!ENTITY x SYSTEM "${secret.toURI()}">]>
            <w:document xmlns:w="w"><w:body><w:p><w:r><w:t>&x;</w:t></w:r></w:p></w:body></w:document>""")
        val text = runCatching { OfficeText.docx(file) }.getOrDefault(emptyList())
            .flatMap { it.paragraphs }.joinToString { it.text }
        assertFalse(text.contains("TOP SECRET"))
    }
}
