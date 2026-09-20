package space.subread.core

/**
 * Aligning a whole book at once, rather than chapter by matched chapter.
 *
 * The reference implementation gives every character of the text it is handed
 * to some cue. That is right when the text is exactly what was read aloud, and
 * wrong for a whole epub: the title page, the contents, the copyright notice,
 * a translator's afterword nobody narrated - all of it would be glued onto
 * whichever cue happens to sit next to it. subplz avoids that by matching
 * chapters up first and dropping the ones that match nothing, which needs
 * chaptered audio and a similarity threshold that does not hold across scripts.
 *
 * Here the alignment itself says what was narrated. A first pass lines up
 * everything; stretches of text where almost nothing agrees with the
 * transcript were evidently not read, and are left out of the second pass.
 */
object BookAligner {

    /** Share of a paragraph's characters that must agree before it counts as heard. */
    private const val HEARD = 0.20

    /**
     * Unheard paragraphs are only dropped in runs at least this long (cleaned
     * characters). A single heading the recogniser mangled completely is still
     * a heading that was read; a page of nothing is not.
     */
    private const val MIN_UNREAD_RUN = 240

    class Result(val cues: List<Cue>, val paragraphsUsed: Int, val paragraphsDropped: Int)

    fun align(
        transcript: List<TranscriptSegment>,
        paragraphs: List<String>,
        language: Language,
    ): Result {
        val narrated = narratedOnly(transcript, paragraphs, language)
        val cues = Aligner.align(transcript, narrated, language)
        return Result(cues, narrated.size, paragraphs.size - narrated.size)
    }

    internal fun narratedOnly(
        transcript: List<TranscriptSegment>,
        paragraphs: List<String>,
        language: Language,
    ): List<String> {
        val textClean = paragraphs.map { language.clean(it).toCodePoints() }
        val target = Aligner.concat(textClean)
        val query = Aligner.concat(transcript.map { language.clean(it.text).toCodePoints() })
        if (target.isEmpty() || query.isEmpty()) return paragraphs

        val coordinates = AnchoredAligner.align(target, query).coordinates

        // Characters of the text that met the same character in the transcript.
        val agreed = BooleanArray(target.size)
        for (k in 1 until coordinates.size) {
            val t0 = coordinates.target[k - 1]
            val q0 = coordinates.query[k - 1]
            val dt = coordinates.target[k] - t0
            if (dt > 0 && coordinates.query[k] - q0 > 0) {
                for (d in 0 until dt) agreed[t0 + d] = target[t0 + d] == query[q0 + d]
            }
        }

        val heard = BooleanArray(paragraphs.size)
        var at = 0
        for ((i, cps) in textClean.withIndex()) {
            val hits = (at until at + cps.size).count { agreed[it] }
            // Nothing to compare (a paragraph of pure punctuation) is not evidence either way.
            heard[i] = cps.isEmpty() || hits >= HEARD * cps.size
            at += cps.size
        }

        // Drop unheard paragraphs only where they form a long enough run.
        val keep = BooleanArray(paragraphs.size) { true }
        var i = 0
        while (i < paragraphs.size) {
            if (heard[i]) { i++; continue }
            var j = i
            var chars = 0
            while (j < paragraphs.size && (!heard[j] || textClean[j].isEmpty())) {
                chars += textClean[j].size
                j++
            }
            if (chars >= MIN_UNREAD_RUN) for (k in i until j) keep[k] = false
            i = j
        }

        val out = paragraphs.filterIndexed { k, _ -> keep[k] }
        // A book with nothing recognisably narrated is a mismatched book; let
        // the caller see that in the cues rather than returning nothing at all.
        return out.ifEmpty { paragraphs }
    }

    /** How much of the transcript found a home in the book, 0..1. For "is this the right book?". */
    fun matchRate(cues: List<Cue>): Double =
        if (cues.isEmpty()) 0.0 else cues.count { !it.text.startsWith(Subs.UNMATCHED) }.toDouble() / cues.size
}
