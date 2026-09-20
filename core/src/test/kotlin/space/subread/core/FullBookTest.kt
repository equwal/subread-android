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
 * A whole audiobook against a whole book, in one pass - what the phone does.
 * Needs tools/make_fullbook.py output, which is local-only; skipped otherwise.
 */
class FullBookTest {
    @Test
    fun aNineteenHourBookAlignsInOneGo() {
        val dir = javaClass.classLoader.getResource("golden-local/full")?.let { File(it.toURI()) }
        val files = dir?.listFiles { f -> f.extension == "json" }.orEmpty()
        assumeTrue("no whole-book fixtures on this machine", files.isNotEmpty())

        for (file in files) {
            val json = Json.parseToJsonElement(file.readText()).jsonObject
            val language = Language.of(json.getValue("language").jsonPrimitive.content)
            val transcript = json.getValue("transcript").jsonArray.map {
                val o = it.jsonObject
                TranscriptSegment(
                    o.getValue("text").jsonPrimitive.content,
                    o.getValue("start").jsonPrimitive.double,
                    o.getValue("end").jsonPrimitive.double,
                )
            }
            val paragraphs = json.getValue("paragraphs").jsonArray.map { it.jsonPrimitive.content }

            System.gc()
            val before = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
            val started = System.nanoTime()
            val result = BookAligner.align(transcript, paragraphs, language)
            val seconds = (System.nanoTime() - started) / 1e9
            val after = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

            val rate = BookAligner.matchRate(result.cues)
            val longest = result.cues.maxOf { it.text.length }
            println("%s: %d cues in %.1fs, +%d MB; %.1f%% matched; %d of %d paragraphs dropped; longest cue %d chars"
                .format(file.nameWithoutExtension, result.cues.size, seconds, (after - before) shr 20,
                    rate * 100, result.paragraphsDropped, paragraphs.size, longest))

            assertEquals(transcript.size, result.cues.size)
            assertTrue("match rate $rate", rate > 0.95)
            // A cue swallowing pages of text is the signature of unread text glued on.
            assertTrue("a $longest-character cue", longest < 400)
            assertTrue("took ${seconds}s", seconds < 120)
        }
    }
}
