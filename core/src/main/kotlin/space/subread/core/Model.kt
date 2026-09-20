package space.subread.core

/** One stretch of speech as the recogniser heard it. Times are seconds. */
data class TranscriptSegment(val text: String, val start: Double, val end: Double)

/** One subtitle. Mutable on purpose: the punctuation pass edits neighbours in place. */
class Cue(var text: String, val start: Double, val end: Double) {
    override fun toString() = "Cue(%.3f-%.3f %s)".format(start, end, text)
}

/**
 * A slice `[start, end)` of one paragraph, claimed by transcript segment [sub].
 * Offsets are in code points, never UTF-16 units, so a slice can not split a
 * surrogate pair however the text was cut.
 */
class Span(var start: Int, var end: Int, val sub: Int) {
    override fun toString() = "[$start, $end, $sub]"
}

internal fun String.toCodePoints(): IntArray = codePoints().toArray()

internal fun IntArray.asString(from: Int = 0, to: Int = size): String =
    if (to <= from) "" else String(this, from, to - from)
