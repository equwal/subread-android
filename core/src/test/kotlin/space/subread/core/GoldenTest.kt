package space.subread.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every stage of the port, checked against what the reference implementation
 * actually produced on real Whisper-tiny transcripts (tools/make_golden.py).
 *
 * Stages are fed the reference's own intermediate output, so a failure points
 * at one function rather than at "the alignment is somehow different".
 */
class GoldenTest {

    class Case(json: JsonObject) {
        val name = json.str("name")
        val language = Language.of(json.str("language"))
        val transcript = json.arr("transcript").map {
            val o = it.jsonObject
            TranscriptSegment(o.str("text"), o.num("start"), o.num("end"))
        }
        val paragraphs = json.arr("paragraphs").map { it.jsonPrimitive.content }
        val transcriptClean = json.arr("transcript_clean").map { it.jsonPrimitive.content }
        val paragraphsClean = json.arr("paragraphs_clean").map { it.jsonPrimitive.content }
        val score = json.num("score")
        val coordinates = json.arr("coords").let { rows ->
            Coordinates(rows[0].ints(), rows[1].ints())
        }
        val afterAlignSub = json.spans("after_align_sub")
        val afterFix = json.spans("after_fix")
        val afterFixPunc = json.spans("after_fix_punc")
        val cuesRaw = json.cues("cues_raw")
        val cues = json.cues("cues")

        override fun toString() = name
    }

    private val cases: List<Case> by lazy {
        val dirs = listOf("golden", "golden-local").mapNotNull { name ->
            javaClass.classLoader.getResource(name)?.let { File(it.toURI()) }
        }
        dirs.flatMap { it.listFiles { f -> f.extension == "json" }!!.sorted() }
            .map { Case(Json.parseToJsonElement(it.readText()).jsonObject) }
            .also { assertTrue("no golden fixtures found", it.isNotEmpty()) }
    }

    @Test
    fun cleaningMatchesTheReference() = cases.forEach { c ->
        c.transcript.forEachIndexed { i, s ->
            assertEquals("$c transcript[$i]", c.transcriptClean[i], c.language.clean(s.text))
        }
        c.paragraphs.forEachIndexed { i, p ->
            assertEquals("$c paragraph[$i]", c.paragraphsClean[i], c.language.clean(p))
        }
    }

    @Test
    fun alignSubMatchesTheReference() = cases.forEach { c ->
        val got = AlignSub.alignSub(c.coordinates, c.cleanText(), c.cleanSubs())
        assertEquals(c.name, render(c.afterAlignSub), render(got))
    }

    @Test
    fun fixMatchesTheReference() = cases.forEach { c ->
        val spans = copy(c.afterAlignSub)
        AlignSub.fix(c.language, c.paragraphs, c.cleanText(), spans)
        assertEquals(c.name, render(c.afterFix), render(spans))
    }

    @Test
    fun fixPuncMatchesTheReference() = cases.forEach { c ->
        val spans = copy(c.afterFix)
        AlignSub.fixPunc(c.paragraphs, spans, Subs.PREPEND_SET, Subs.APPEND_SET, Subs.NOPEND_SET)
        assertEquals(c.name, render(c.afterFixPunc), render(spans))
    }

    @Test
    fun toSubsMatchesTheReference() = cases.forEach { c ->
        val got = Subs.toSubs(c.paragraphs, c.transcript, c.afterFixPunc)
        assertEquals(c.name, renderCues(c.cuesRaw), renderCues(got))
    }

    @Test
    fun shiftAlignMatchesTheReference() = cases.forEach { c ->
        val input = c.cuesRaw.map { Cue(it.text, it.start, it.end) }
        assertEquals(c.name, renderCues(c.cues), renderCues(Subs.shiftAlign(input)))
    }

    /** The exact aligner must find an alignment as good as Biopython's. */
    @Test
    fun gotohFindsTheOptimalScore() = cases.forEach { c ->
        val target = c.paragraphsClean.joinToString("").toCodePoints()
        val query = c.transcriptClean.joinToString("").toCodePoints()
        val got = Gotoh.align(target, query)
        assertEquals("$c: reported vs recomputed", got.score, Gotoh.score(target, query, got.coordinates))
        assertEquals("$c: reference path scored our way",
            Math.round(c.score * 10).toInt(), Gotoh.score(target, query, c.coordinates))
        assertEquals("$c: optimal score", Math.round(c.score * 10).toInt(), got.score)
    }

    /**
     * The whole-book aligner never sees the full table, so it cannot promise
     * the optimum - but on two copies of the same text it should land on it,
     * or within a whisker. Forced through tiny blocks here so that even these
     * chapter-sized fixtures exercise anchoring, chaining and stitching.
     */
    @Test
    fun anchoredAlignmentIsAsGoodAsExact() = cases.forEach { c ->
        val target = c.paragraphsClean.joinToString("").toCodePoints()
        val query = c.transcriptClean.joinToString("").toCodePoints()
        val exact = Math.round(c.score * 10).toInt()
        // Production block size must be essentially exact. The tiny sizes are a
        // stress test of the stitching: every join that lands inside a long gap
        // pays to reopen it, so they are allowed to fall a little short.
        for ((cells, floor) in listOf(4_000_000L to 0.999, 40_000L to 0.98, 2_500L to 0.97)) {
            val got = AnchoredAligner.align(target, query, exactCells = cells)
            assertEquals("$c: path must span both sequences", target.size, got.coordinates.target.last())
            assertEquals(query.size, got.coordinates.query.last())
            val ratio = got.score.toDouble() / exact
            println("%-14s blocks<=%-8d anchored %6d / exact %6d (%.2f%%)".format(c.name, cells, got.score, exact, ratio * 100))
            assertTrue("$c: anchored score ${got.score} vs exact $exact", ratio >= floor)
        }
    }

    /** ...and the cues that come out of it should be the reference's. */
    @Test
    fun anchoredCuesAgreeWithTheReference() = cases.forEach { c ->
        val textClean = c.cleanText()
        val subsClean = c.cleanSubs()
        val coordinates = AnchoredAligner.align(
            Aligner.concat(textClean), Aligner.concat(subsClean), exactCells = 40_000L).coordinates
        val spans = AlignSub.alignSub(coordinates, textClean, subsClean)
        AlignSub.fix(c.language, c.paragraphs, textClean, spans)
        AlignSub.fixPunc(c.paragraphs, spans, Subs.PREPEND_SET, Subs.APPEND_SET, Subs.NOPEND_SET)
        val got = Subs.shiftAlign(Subs.toSubs(c.paragraphs, c.transcript, spans))
        val same = c.cues.indices.count { c.cues[it].text == got[it].text }
        val share = same.toDouble() / c.cues.size
        println("%-14s anchored: %d/%d cues identical (%.1f%%)".format(c.name, same, c.cues.size, share * 100))
        assertTrue("$c: only ${"%.1f".format(share * 100)}% of cues match", share >= 0.95)
    }

    /**
     * End to end, from raw text to cues. Optimal alignments are not unique, so
     * this can legitimately differ from the reference in the odd cue; what it
     * must not do is differ in many.
     */
    @Test
    fun wholePipelineAgreesWithTheReference() = cases.forEach { c ->
        val got = Aligner.align(c.transcript, c.paragraphs, c.language)
        assertEquals("$c cue count", c.cues.size, got.size)
        val same = c.cues.indices.count { c.cues[it].text == got[it].text }
        val share = same.toDouble() / c.cues.size
        println("%-14s %d/%d cues identical (%.1f%%)".format(c.name, same, c.cues.size, share * 100))
        assertTrue("$c: only ${"%.1f".format(share * 100)}% of cues match", share >= 0.97)
        c.cues.indices.forEach {
            assertEquals(c.cues[it].start, got[it].start, 1e-9)
            assertEquals(c.cues[it].end, got[it].end, 1e-9)
        }
    }

    // ------------------------------------------------------------------

    private fun Case.cleanText() = paragraphsClean.map { it.toCodePoints() }
    private fun Case.cleanSubs() = transcriptClean.map { it.toCodePoints() }

    private fun copy(spans: List<List<Span>>) =
        spans.map { line -> line.map { Span(it.start, it.end, it.sub) }.toMutableList() }

    private fun render(spans: List<List<Span>>) =
        spans.withIndex().joinToString("\n") { (i, line) -> "$i: $line" }

    private fun renderCues(cues: List<Cue>) =
        cues.joinToString("\n") { "%.3f %.3f %s".format(it.start, it.end, it.text) }
}

private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
private fun JsonObject.num(key: String) = getValue(key).jsonPrimitive.double
private fun JsonObject.arr(key: String): JsonArray = getValue(key).jsonArray
private fun kotlinx.serialization.json.JsonElement.ints() = jsonArray.map { it.jsonPrimitive.int }.toIntArray()
private fun JsonObject.spans(key: String): List<List<Span>> = arr(key).map { line ->
    line.jsonArray.map { s -> s.ints().let { Span(it[0], it[1], it[2]) } }
}
private fun JsonObject.cues(key: String): List<Cue> = arr(key).map {
    val o = it.jsonObject
    Cue(o.str("text"), o.num("start"), o.num("end"))
}
