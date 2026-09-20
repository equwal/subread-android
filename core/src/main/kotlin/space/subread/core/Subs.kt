package space.subread.core

/**
 * From spans to subtitles, then the punctuation tidy-up.
 *
 * `toSubs` is ats/main.py's to_subs; `shiftAlign` and its helpers are
 * subplz/align.py's. Ported faithfully, quirks included - see AlignSub for why.
 */
object Subs {
    const val START_PUNC = "『「(（《｟[{\"'“¿" + "'“\"¿([{-『「（〈《〔【｛［｟＜<‘“〝※"
    const val END_PUNC = "'\"・.。!！?？:：”>＞⦆)]}』」）〉》〕】｝］’〟／＼～〜~;；─―–-➡"
    const val OTHER_PUNC = "＊　,，、…"
    const val NOPEND =
        "うぁぃぅぇぉっゃゅょゎゕゖァィゥェォヵㇰヶㇱㇲッㇳㇴㇵㇶㇷㇷ゚ㇸㇹㇺャュョㇻㇼㇽㇾㇿヮ…　 "

    val PREPEND_SET: Set<Int> = START_PUNC.toCodePoints().toSet()
    val APPEND_SET: Set<Int> = (END_PUNC + OTHER_PUNC).toCodePoints().toSet()
    val NOPEND_SET: Set<Int> = NOPEND.toCodePoints().toSet()

    private val punctuation: Set<Char> = (START_PUNC + END_PUNC + OTHER_PUNC).toSet()
    private val startPunc: Set<Char> = START_PUNC.toSet()
    private val endPunc: Set<Char> = END_PUNC.toSet()

    /** A transcript-only line: spoken, but not found in the book. */
    const val UNMATCHED = "＊"

    /**
     * One cue per transcript segment, timed by the recogniser and worded by
     * the book. A segment that claimed no text keeps its own words, flagged.
     */
    fun toSubs(
        text: List<String>,
        subs: List<TranscriptSegment>,
        alignment: List<List<Span>>,
        offset: Double = 0.0,
    ): MutableList<Cue> {
        class Flat(val span: Span, val line: Int)
        val flat = alignment.flatMapIndexed { i, spans -> spans.map { Flat(it, i) } }
        val lines = text.map { it.toCodePoints() }

        val out = ArrayList<Cue>(subs.size)
        var start = 0
        var end = 0
        for ((si, s) in subs.withIndex()) {
            while (end < flat.size && flat[end].span.sub == si) end++
            val r = StringBuilder()
            for (k in start until end) {
                val a = flat[k]
                r.append(pySlice(lines[a.line], a.span.start, a.span.end))
            }
            val body = r.toString()
            out += if (body.isNotBlank()) Cue(body, s.start + offset, s.end + offset)
            else Cue(UNMATCHED + s.text, s.start + offset, s.end + offset)
            start = end
        }
        return out
    }

    /** `seq[a:b]` with Python's rules: negatives wrap, out of range clamps. */
    private fun pySlice(cps: IntArray, a: Int, b: Int): String {
        val n = cps.size
        val lo = (if (a < 0) a + n else a).coerceIn(0, n)
        val hi = (if (b < 0) b + n else b).coerceIn(0, n)
        return cps.asString(lo, hi)
    }

    // ------------------------------------------------------------------
    // shift_align
    // ------------------------------------------------------------------

    private fun punctuationIndices(s: String) = s.indices.filter { s[it] in punctuation }
    private fun countNonPunctuation(s: String) = s.count { it !in punctuation }
    private fun hasEndingPunctuation(c: Char) = c in endPunc
    private fun hasDoubleComma(starts: String, ends: String) = starts.last() == '、' && ends.last() == '、'

    /**
     * Move a stranded character or two across a cue boundary so that cues
     * start and end on punctuation where they nearly did already.
     */
    fun shiftAlign(segments: List<Cue>): List<Cue> {
        val fresh = ArrayList<Cue>(segments.size)
        var startIndex = 0  // read by the second pass too, as in the original

        for ((i, segment) in segments.withIndex()) {
            var text = segment.text
            val indices = punctuationIndices(text)
            if (indices.isEmpty()) {
                fresh += segment
                continue
            }
            startIndex = indices[0]
            val nonPunc = countNonPunctuation(text.substring(0, startIndex))
            if (nonPunc == 0 || countNonPunctuation(text.substring(startIndex + 1)) == 0) {
                fresh += segment
                continue
            }
            if (nonPunc <= 2 && i > 0 && fresh.isNotEmpty() &&
                !hasEndingPunctuation(fresh.last().text.last()) &&
                !hasDoubleComma(fresh.last().text, text.substring(0, startIndex + 1))
            ) {
                // A word or less before the first mark: it belongs to the previous cue.
                fresh.last().text += text.substring(0, startIndex + 1)
                text = text.substring(startIndex + 1)
            }
            fresh += Cue(text, segment.start, segment.end)
        }

        val final = ArrayList<Cue>(fresh.size)
        for (i in fresh.indices) {
            val segment = fresh[i]
            var text = segment.text
            val indices = punctuationIndices(text)
            if (indices.isNotEmpty()) {
                val lastIndex = indices.last()
                val tail = text.substring(lastIndex)
                if (countNonPunctuation(tail) == 0 || tail.length == text.length) {
                    final += segment
                    continue
                }
                if (countNonPunctuation(tail) <= 2 && i + 1 < fresh.size &&
                    !hasEndingPunctuation(fresh[i + 1].text.first()) &&
                    !hasDoubleComma(
                        fresh[i + 1].text.substring(0, 1),
                        text.substring(0, minOf(startIndex + 1, text.length)),
                    )
                ) {
                    // A word or less after the last mark: it belongs to the next cue.
                    // The original reaches into the *input* list here, not the
                    // first pass's output. Kept: the fixtures depend on it.
                    val next = segments[i + 1]
                    next.text = text.substring(lastIndex + 1) + next.text
                    text = text.substring(0, lastIndex + 1)
                    final += Cue(text, segment.start, segment.end)
                    fresh[i + 1] = next
                    continue
                }
            }
            final += Cue(text, segment.start, segment.end)
        }

        return doubleCheckMisalignedPairs(final).map { Cue(it.text.trim(), it.start, it.end) }
    }

    private val strayOpening = Regex("(」「(.{1,2})、$|」「(.{1})、$)")

    private fun doubleCheckMisalignedPairs(segments: MutableList<Cue>): List<Cue> {
        if (segments.size < 2) return segments
        val adjusted = ArrayList<Cue>(segments.size)
        for ((i, segment) in segments.withIndex()) {
            // 」「ばか、 at the very end is the start of the next line of dialogue.
            strayOpening.find(segment.text)?.let { m ->
                if (i < segments.size - 1) {
                    segments[i + 1].text = m.value + segments[i + 1].text
                    segment.text = segment.text.substring(0, m.range.first)
                }
            }
            if (segment.text.isNotEmpty() && segment.text.first() in endPunc && i > 0) {
                adjusted.last().text += segment.text.first()
                segment.text = segment.text.substring(1)
            }
            if (segment.text.isNotEmpty() && segment.text.last() in startPunc && i < segments.size - 1) {
                segments[i + 1].text = segment.text.last() + segments[i + 1].text
                segment.text = segment.text.substring(0, segment.text.length - 1)
            }
            adjusted += segment
        }
        return adjusted
    }
}
