package space.subread.core

/**
 * Turning an alignment path into "which piece of which paragraph does each
 * transcript segment cover".
 *
 * Ported from ats/align.py, statement for statement. It is dense and a little
 * odd in places; the oddities are kept, because the fixtures in
 * src/test/resources/golden are this code's real output and the port is only
 * trustworthy while it reproduces them exactly. Improvements belong in a
 * separate, separately tested step - not folded in here.
 */
object AlignSub {

    /**
     * @param text cleaned paragraphs, as code points
     * @param subs cleaned transcript segments, as code points
     * @return for each paragraph, the spans of it claimed by transcript segments
     */
    fun alignSub(
        coordinates: Coordinates,
        text: List<IntArray>,
        subs: List<IntArray>,
        thing: Int = 2,
    ): List<MutableList<Span>> {
        var line = 0            // current[0]
        var sub = 0             // current[1]
        val pos = intArrayOf(0, 0)
        var toff = 0
        val p = intArrayOf(0, 0)
        val gaps = intArrayOf(0, 0)
        val segments = mutableListOf<MutableList<Span>>(mutableListOf())
        var off = 0

        for (i in 0 until coordinates.size) {
            val c0 = coordinates.target[i]
            val c1 = coordinates.query[i]
            // A column where either side stood still is a gap in the other.
            val isGap = (c0 - p[0] == 0) || (c1 - p[1] == 0)

            while (sub < subs.size && pos[1] + subs[sub].size <= c1) {
                if (line >= text.size) return segments.take(text.size)
                val subLen = subs[sub].size
                pos[1] += subLen
                if (isGap) {
                    // np.clip(pos - p, 0, None)[::-1]
                    gaps[0] += maxOf(pos[1] - p[1], 0)
                    gaps[1] += maxOf(pos[0] - p[0], 0)
                }

                val diff = subLen + gaps[1] - gaps[0]
                if (diff > subLen / 4) {
                    var target = toff + diff + off
                    off = 0
                    while (line < text.size && target >= text[line].size) {
                        val start = toff
                        val end = text[line].size
                        if (end - start != 0) {
                            val prev = segments.last()
                            if (end - start < thing || distinctExceptFullStop(text[line], start, end) < thing) {
                                // Too small to stand alone: glue it on.
                                if (prev.isNotEmpty()) prev.last().end = end
                                else prev.add(Span(start, end, sub))
                            } else {
                                prev.add(Span(start, end, sub))
                            }
                        }
                        segments.add(mutableListOf())
                        pos[0] += end - start
                        target -= text[line].size
                        line++
                        toff = 0
                    }
                    pos[0] += target - toff
                    segments.last().add(Span(toff, target, sub))
                    toff = target
                } else {
                    // The recogniser heard something the book does not have.
                    val prev = segments.last()
                    if (toff >= text[line].size / 2 && prev.isNotEmpty()) {
                        prev.last().end += diff
                        toff += diff
                    } else {
                        off += diff
                    }
                }

                sub++
                gaps[0] = 0
                gaps[1] = 0
                p[0] = maxOf(pos[0], p[0])
                p[1] = maxOf(pos[1], p[1])
            }

            if (isGap) {
                // gaps += (c - p)[::-1]
                gaps[0] += c1 - p[1]
                gaps[1] += c0 - p[0]
            }
            p[0] = c0
            p[1] = c1
        }
        return segments.take(text.size)
    }

    private fun distinctExceptFullStop(cps: IntArray, start: Int, end: Int): Int {
        val seen = HashSet<Int>()
        for (k in start until minOf(end, cps.size)) if (cps[k] != '。'.code) seen.add(cps[k])
        return seen.size
    }

    /**
     * Spans were computed on cleaned text. Map them back onto the original.
     *
     * The cleaned string is the translated one with characters removed, so it
     * is a subsequence of it, and a two-finger walk recovers each offset.
     */
    fun fix(lang: Language, original: List<String>, edited: List<IntArray>, segments: List<MutableList<Span>>) {
        for ((l, spans) in segments.withIndex()) {
            val o = lang.translate(original[l]).toCodePoints()
            val e = edited[l]
            val m = IntArray(e.size + 1) { -1 }
            var ei = 0
            for (oi in o.indices) {
                if (ei < e.size && o[oi] == e[ei]) {
                    m[ei] = oi
                    ei++
                }
            }
            m[ei] = o.size  // snap to end
            m[0] = 0        // snap to zero
            var last = 0
            for (i in 0 until e.size) {
                if (m[i] != -1) last = i else m[i] = m[last]
            }
            if (m[e.size] == -1) m[e.size] = o.size

            for (f in spans) {
                f.start = m[f.start.coerceIn(0, e.size)]
                f.end = m[f.end.coerceIn(0, e.size)]
            }
        }
    }

    /**
     * Nudge span ends so punctuation stays with the words it belongs to: a
     * closing quote goes with what it closes, an opening one with what follows,
     * and a small kana or ellipsis is never stranded at the head of a cue.
     */
    fun fixPunc(
        text: List<String>,
        segments: List<MutableList<Span>>,
        prepend: Set<Int>,
        append: Set<Int>,
        nopend: Set<Int>,
    ) {
        for ((l, s) in segments.withIndex()) {
            if (s.isEmpty()) continue
            val t = text[l].toCodePoints()
            for (k in s.indices) {
                val p = s[k]
                // The last span is paired with itself, as in the original's
                // zip(s, s[1:] + [s[-1]]).
                val f = if (k + 1 < s.size) s[k + 1] else s[k]
                val connected = f.start == p.end
                var loop = 0
                while (loop <= 20) {
                    if (p.end < t.size && at(t, p.end) in append) {
                        p.end += 1
                    } else if (at(t, p.end - 1) in prepend) {
                        p.end -= 1
                    } else if (
                        (p.end > 0 && at(t, p.end - 1) in nopend) ||
                        (p.end < t.size && at(t, p.end) in nopend) ||
                        (p.end < t.size - 1 && at(t, p.end + 1) in nopend)
                    ) {
                        var start = p.end - 1
                        var end = p.end
                        if (p.end < t.size - 1) {
                            val here = at(t, p.end)
                            // Do not end a cue on a kanji whose okurigana follows.
                            if ((at(t, p.end + 1) in nopend && 0x4E00 > here) || here > 0x9FAF) end += 1
                        }
                        while (start > 0 && at(t, start) in nopend) start -= 1
                        while (end < t.size - 1 && at(t, end) in nopend) end += 1

                        if (at(t, start) in prepend) {
                            if (p.end == start) break
                            p.end = start
                        } else if (at(t, start) in append) {
                            if (p.end == start + 1) break
                            p.end = start + 1
                        } else if (end < t.size && at(t, end) in prepend) {
                            if (p.end == end) break
                            p.end = end
                        } else if (end < t.size && at(t, end) in append) {
                            if (p.end == end + 1) break
                            p.end = end + 1
                        } else {
                            break
                        }
                    } else {
                        break
                    }
                    loop++
                }
                if (connected) f.start = p.end
            }
        }
    }

    /** Python indexing: negatives count from the end. Out of range is "no character". */
    private fun at(t: IntArray, i: Int): Int {
        val k = if (i < 0) i + t.size else i
        return if (k in t.indices) t[k] else -1
    }
}
