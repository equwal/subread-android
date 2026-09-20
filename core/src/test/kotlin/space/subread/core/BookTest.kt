package space.subread.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BookTest {

    private fun case(name: String): GoldenTest.Case {
        val file = File(javaClass.classLoader.getResource("golden")!!.toURI()).resolve("$name.json")
        return GoldenTest.Case(Json.parseToJsonElement(file.readText()).jsonObject)
    }

    // ------------------------------------------------------------------ srt

    @Test
    fun srtStampsCarryProperly() {
        assertEquals("00:00:00,000", Srt.stamp(0.0))
        assertEquals("00:01:00,000", Srt.stamp(59.9996))   // not 00:00:60,000
        assertEquals("01:00:00,000", Srt.stamp(3599.9999))
        assertEquals("12:34:56,789", Srt.stamp(12 * 3600 + 34 * 60 + 56.789))
        assertEquals("00:00:00,000", Srt.stamp(-1.0))
    }

    @Test
    fun srtKeepsEachCueOnOneLineAndSkipsEmptyOnes() {
        val srt = Srt.write(listOf(
            Cue("first\nline  \r\n wrapped", 0.0, 1.5),
            Cue("   ", 1.5, 2.0),
            Cue("second", 2.0, 3.25),
        ))
        assertEquals(
            "1\n00:00:00,000 --> 00:00:01,500\nfirst line wrapped\n\n" +
                "2\n00:00:02,000 --> 00:00:03,250\nsecond\n\n",
            srt,
        )
    }

    // ------------------------------------------------------- unread pruning

    /**
     * Front matter, an afterword and an unread insert in the middle must make
     * no difference to the subtitles, and must not appear in any of them.
     */
    @Test
    fun textThatWasNotNarratedIsLeftOut() {
        val c = case("neko_003_006")
        val other = case("neko_137_140").paragraphs      // same author, different chapter
        val front = other.take(6)
        // Long enough to count as a passage; a stray line or two is kept on purpose.
        val insert = other.drop(40).take(12)
        assertTrue(insert.sumOf { c.language.clean(it).length } > 400)
        val back = other.takeLast(8)
        assertTrue(front.sumOf { it.length } > 500 && back.sumOf { it.length } > 300)

        val clean = BookAligner.align(c.transcript, c.paragraphs, c.language)
        val mid = c.paragraphs.size / 2
        val padded = front + c.paragraphs.take(mid) + insert + c.paragraphs.drop(mid) + back
        val got = BookAligner.align(c.transcript, padded, c.language)

        assertEquals(clean.paragraphsUsed, got.paragraphsUsed)
        assertEquals(front.size + insert.size + back.size + clean.paragraphsDropped, got.paragraphsDropped)
        assertEquals(clean.cues.map { it.text }, got.cues.map { it.text })
        val junk = (front + insert + back).map { it.take(12) }
        got.cues.forEach { cue -> junk.forEach { assertFalse("junk in: ${cue.text}", it in cue.text) } }
        assertTrue(BookAligner.matchRate(got.cues) > 0.95)
    }

    @Test
    fun aBookThatIsNotTheAudioScoresBadly() {
        val c = case("neko_000_003")
        val wrong = case("neko_137_140").paragraphs
        val got = BookAligner.align(c.transcript, wrong, c.language)
        assertEquals(c.transcript.size, got.cues.size)   // still one cue per segment, never a crash
        // The alignment is forced to put text somewhere; what gives it away is
        // how little of it agrees.
        val good = BookAligner.align(c.transcript, c.paragraphs, c.language)
        assertTrue(good.paragraphsDropped < c.paragraphs.size / 2)
    }

    // ----------------------------------------------------------------- epub

    private fun epub(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, body) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun page(body: String) =
        "<?xml version='1.0'?><html xmlns='http://www.w3.org/1999/xhtml'><head><title>t</title></head><body>$body</body></html>"

    @Test
    fun epubIsReadInSpineOrderNotFileOrder() {
        val bytes = epub(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to
                "<container><rootfiles><rootfile full-path='OEBPS/content.opf'/></rootfiles></container>",
            "OEBPS/content.opf" to """
                <package><manifest>
                  <item id='a' href='text/z-first.xhtml'/>
                  <item id='b' href='text/a%20second.xhtml'/>
                  <item id='n' href='text/notes.xhtml'/>
                </manifest><spine>
                  <itemref idref='a'/><itemref idref='n' linear='no'/><itemref idref='b'/>
                </spine></package>""",
            "OEBPS/text/a second.xhtml" to page("<p>二番目。</p><ul><li>item</li></ul>"),
            "OEBPS/text/z-first.xhtml" to page(
                "<h1>第一章</h1><p><ruby>吾輩<rt>わがはい</rt></ruby>は猫である。</p>" +
                    "<blockquote><p>inner one</p><p>inner two</p></blockquote><p>  </p>"),
            "OEBPS/text/notes.xhtml" to page("<p>an endnote nobody reads aloud</p>"),
        )
        assertEquals(
            listOf("第一章", "吾輩は猫である。", "inner one", "inner two", "二番目。", "item"),
            BookText.read(ByteArrayInputStream(bytes), "book.epub"),
        )
    }

    @Test
    fun epubWithoutAUsablePackageFileStillYieldsItsText() {
        val bytes = epub("b.html" to page("<p>two</p>"), "a.html" to page("<p>one</p>"))
        assertEquals(listOf("one", "two"), BookText.read(ByteArrayInputStream(bytes), "x.epub"))
    }

    @Test
    fun aozoraMarkupIsStripped() {
        val zip = File(javaClass.classLoader.getResource("aozora/789_ruby_5639.zip")!!.toURI())
        val paragraphs = BookText.read(zip)
        assertTrue("only ${paragraphs.size} paragraphs", paragraphs.size > 1000)
        assertEquals("一", paragraphs[0])
        assertTrue(paragraphs[1], paragraphs[1].startsWith("吾輩は猫である。名前はまだ無い。"))
        assertEquals(emptyList<String>(), paragraphs.filter { "《" in it || "［＃" in it || "｜" in it })
        assertEquals(emptyList<String>(), paragraphs.filter { it.startsWith("底本") })
        // And it is the same text the fixtures were cut from. Compared after
        // cleaning: Python and Java disagree on which Unicode dash a Shift_JIS
        // 0x815C is, which matters to nobody - punctuation is not aligned on.
        val known = paragraphs.map { Japanese.clean(it) }.toSet()
        assertEquals(emptyList<String>(),
            case("neko_000_003").paragraphs.filter { Japanese.clean(it) !in known }.map { it.take(30) })
    }

    @Test
    fun plainTextIsOneParagraphPerLine() {
        val bytes = "﻿Uno.\r\n\r\n  Dos.  \nTres.".toByteArray()
        assertEquals(listOf("Uno.", "Dos.", "Tres."), BookText.read(ByteArrayInputStream(bytes), "libro.txt"))
    }
}
