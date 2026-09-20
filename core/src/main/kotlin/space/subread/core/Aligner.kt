package space.subread.core

/**
 * The whole job: a transcript with times and a book without them go in,
 * subtitles worded by the book and timed by the transcript come out.
 */
object Aligner {

    /**
     * Above this many traceback cells the exact aligner is not attempted and
     * the anchored one takes over. 64M cells is 64 MB, comfortable on a phone.
     */
    const val EXACT_CELL_LIMIT = 64_000_000L

    fun align(
        transcript: List<TranscriptSegment>,
        paragraphs: List<String>,
        language: Language,
        offset: Double = 0.0,
    ): List<Cue> {
        if (transcript.isEmpty()) return emptyList()

        val subsClean = transcript.map { language.clean(it.text).toCodePoints() }
        val textClean = paragraphs.map { language.clean(it).toCodePoints() }
        val query = concat(subsClean)
        val target = concat(textClean)

        if (target.isEmpty() || query.isEmpty()) {
            return transcript.map { Cue(Subs.UNMATCHED + it.text, it.start + offset, it.end + offset) }
        }

        val coordinates =
            if (Gotoh.cells(target.size, query.size) <= EXACT_CELL_LIMIT) Gotoh.align(target, query).coordinates
            else AnchoredAligner.align(target, query).coordinates

        val spans = AlignSub.alignSub(coordinates, textClean, subsClean)
        AlignSub.fix(language, paragraphs, textClean, spans)
        AlignSub.fixPunc(paragraphs, spans, Subs.PREPEND_SET, Subs.APPEND_SET, Subs.NOPEND_SET)

        return Subs.shiftAlign(Subs.toSubs(paragraphs, transcript, spans, offset))
    }

    internal fun concat(parts: List<IntArray>): IntArray {
        val out = IntArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) {
            p.copyInto(out, at)
            at += p.size
        }
        return out
    }
}
