package space.subread.core

import kotlin.math.roundToLong

object Srt {
    /**
     * One line of text per cue, always. Hoshi Reader reads exactly the third
     * line of each block as the cue text, so a wrapped cue would lose its tail
     * there; and nothing downstream benefits from the wrapping.
     */
    fun write(cues: List<Cue>): String {
        val out = StringBuilder()
        var n = 0
        for (cue in cues) {
            val text = cue.text.replace(Regex("\\s*[\\r\\n]+\\s*"), " ").trim()
            if (text.isEmpty()) continue
            n++
            out.append(n).append('\n')
                .append(stamp(cue.start)).append(" --> ").append(stamp(cue.end)).append('\n')
                .append(text).append("\n\n")
        }
        return out.toString()
    }

    /** `HH:MM:SS,mmm`. Rounded to the millisecond first, so 59.9996 s carries properly. */
    fun stamp(seconds: Double): String {
        val ms = (maxOf(seconds, 0.0) * 1000).roundToLong()
        return "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
    }
}
