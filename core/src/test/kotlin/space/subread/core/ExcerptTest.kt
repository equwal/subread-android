package space.subread.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * A short excerpt from the middle of a novel, against the whole epub. This is
 * the case that went wrong on a real device. Local only: the book is in
 * copyright. Needs golden-local/excerpt/ with the epub and the device transcript.
 */
class ExcerptTest {
    @Test
    fun anExcerptFindsItsPlaceInTheWholeBook() {
        val dir = javaClass.classLoader.getResource("golden-local/excerpt")?.let { File(it.toURI()) }
        assumeTrue("no local excerpt fixture", dir != null && File(dir, "moskva.epub").exists())
        val json = Json.parseToJsonElement(File(dir, "moskva_device.json").readText()).jsonObject
        val transcript = json.getValue("transcript").jsonArray.map {
            val o = it.jsonObject
            TranscriptSegment(o.getValue("text").jsonPrimitive.content,
                o.getValue("start").jsonPrimitive.double, o.getValue("end").jsonPrimitive.double)
        }
        val reference = json.getValue("paragraphs").jsonArray.map { it.jsonPrimitive.content }

        val paragraphs = BookText.read(File(dir, "moskva.epub"))
        println("paragraphs: ours ${paragraphs.size}, reference reader ${reference.size}; longest ours ${paragraphs.maxOf { it.length }}")
        assertEquals(reference.size, paragraphs.size)

        val result = BookAligner.align(transcript, paragraphs, Language.of("ru"))
        result.cues.take(4).forEach { println(it.text) }
        assertTrue(result.cues[0].text, result.cues[0].text.startsWith("Чемоданчик я все-таки взял с собой"))
        assertEquals("в ожидании заказа.", result.cues[1].text)
    }
}
