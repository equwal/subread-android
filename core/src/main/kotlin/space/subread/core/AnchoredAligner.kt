package space.subread.core

/**
 * Global alignment for inputs far too big for a full dynamic-programming table.
 *
 * The reference implementation hands Biopython one chapter at a time and needs
 * a couple of gigabytes to do it, which is why it depends on first guessing
 * which chapter of the book each chapter of audio is. A phone has neither the
 * memory nor, often, chaptered audio. So this aligns the whole transcript
 * against the whole book instead, the way diff tools handle large files:
 *
 *  1. Find stretches that occur exactly once in both sequences. In two copies
 *     of the same book, those are overwhelmingly the same words in the same
 *     place - anchors.
 *  2. Keep the longest chain of anchors that is in order in both.
 *  3. Between neighbouring anchors, recurse with shorter stretches; when what
 *     is left is small, solve it exactly with [Gotoh].
 *
 * Text the narrator skipped, or an introduction the book lacks, simply has no
 * anchors in it and comes out as one long gap. No chapter matching needed.
 */
object AnchoredAligner {

    /** A stretch identical in both sequences. */
    private class Run(var t: Int, var q: Int, var len: Int)

    fun align(
        target: IntArray,
        query: IntArray,
        exactCells: Long = 4_000_000L,
        firstK: Int = 14,
        lastK: Int = 5,
    ): Alignment {
        val path = Path()
        Solver(target, query, exactCells, lastK, path).solve(0, target.size, 0, query.size, firstK)
        val coordinates = path.build(target.size, query.size)
        return Alignment(Gotoh.score(target, query, coordinates), coordinates)
    }

    private class Solver(
        val target: IntArray,
        val query: IntArray,
        val exactCells: Long,
        val lastK: Int,
        val path: Path,
    ) {
        fun solve(t0: Int, t1: Int, q0: Int, q1: Int, k: Int) {
            val n = t1 - t0
            val m = q1 - q0
            if (n == 0 || m == 0) {
                path.moveTo(t1, q1)
                return
            }
            if (Gotoh.cells(n, m) <= exactCells) {
                val sub = Gotoh.align(target.copyOfRange(t0, t1), query.copyOfRange(q0, q1)).coordinates
                for (i in 0 until sub.size) path.moveTo(t0 + sub.target[i], q0 + sub.query[i])
                return
            }

            var kk = k
            var chain = emptyList<Run>()
            while (kk >= lastK) {
                chain = chainOf(anchors(t0, t1, q0, q1, kk))
                if (chain.isNotEmpty()) break
                kk -= 3
            }

            if (chain.isEmpty()) {
                // Two long stretches with nothing in common. There is no good
                // answer here, so take a cheap one: halve both and carry on.
                val tm = t0 + n / 2
                val qm = q0 + m / 2
                solve(t0, tm, q0, qm, lastK)
                solve(tm, t1, qm, q1, lastK)
                return
            }

            var ct = t0
            var cq = q0
            for (run in chain) {
                solve(ct, run.t, cq, run.q, kk)
                path.moveTo(run.t + run.len, run.q + run.len)
                ct = run.t + run.len
                cq = run.q + run.len
            }
            solve(ct, t1, cq, q1, kk)
        }

        /** Every k-gram occurring exactly once on each side, joined into runs. */
        private fun anchors(t0: Int, t1: Int, q0: Int, q1: Int, k: Int): List<Run> {
            if (t1 - t0 < k || q1 - q0 < k) return emptyList()

            val inTarget = uniqueGrams(target, t0, t1, k)
            val runs = ArrayList<Run>()
            var current: Run? = null

            val inQuery = uniqueGrams(query, q0, q1, k)
            // Walk the query in order so that consecutive hits extend a run.
            for (qi in q0..q1 - k) {
                val h = hash(query, qi, k)
                if (inQuery.get(h) != qi) { current = null; continue }
                val ti = inTarget.get(h)
                if (ti < 0 || !same(ti, qi, k)) { current = null; continue }

                val c = current
                if (c != null && ti == c.t + (qi - c.q)) {
                    c.len = qi - c.q + k
                } else {
                    current = Run(ti, qi, k).also { runs += it }
                }
            }
            return runs
        }

        private fun same(ti: Int, qi: Int, k: Int): Boolean {
            for (d in 0 until k) if (target[ti + d] != query[qi + d]) return false
            return true
        }

        /** k-gram hash to its position, or to DUPLICATE when it occurs more than once. */
        private fun uniqueGrams(seq: IntArray, from: Int, to: Int, k: Int): LongIntMap {
            val map = LongIntMap(to - from)
            for (i in from..to - k) {
                val h = hash(seq, i, k)
                map.put(h, if (map.get(h) == LongIntMap.ABSENT) i else LongIntMap.DUPLICATE)
            }
            return map
        }

        private fun hash(seq: IntArray, at: Int, k: Int): Long {
            var h = 1469598103934665603L
            for (d in 0 until k) h = (h xor seq[at + d].toLong()) * 1099511628211L
            return h
        }

        /** Longest chain of runs in order on both sides, overlaps trimmed. */
        private fun chainOf(runs: List<Run>): List<Run> {
            if (runs.isEmpty()) return runs
            val byTarget = runs.sortedBy { it.t }

            // Longest increasing subsequence on query position (patience sort).
            val tails = IntArray(byTarget.size)
            val prev = IntArray(byTarget.size) { -1 }
            var size = 0
            for (i in byTarget.indices) {
                val q = byTarget[i].q
                var lo = 0
                var hi = size
                while (lo < hi) {
                    val mid = (lo + hi) ushr 1
                    if (byTarget[tails[mid]].q < q) lo = mid + 1 else hi = mid
                }
                if (lo > 0) prev[i] = tails[lo - 1]
                tails[lo] = i
                if (lo == size) size++
            }
            val picked = ArrayList<Run>(size)
            var at = tails[size - 1]
            while (at >= 0) { picked += byTarget[at]; at = prev[at] }
            picked.reverse()

            // Neighbouring runs on different diagonals can overlap by a few
            // characters; give the overlap to the earlier one.
            val out = ArrayList<Run>(picked.size)
            var endT = 0
            var endQ = 0
            for (r in picked) {
                val cut = maxOf(endT - r.t, endQ - r.q, 0)
                if (cut >= r.len) continue
                r.t += cut; r.q += cut; r.len -= cut
                out += r
                endT = r.t + r.len
                endQ = r.q + r.len
            }
            return out
        }
    }

    /**
     * Collects breakpoints, merging consecutive steps of one kind so the result
     * has the same canonical shape Biopython produces.
     */
    private class Path {
        private val ts = IntList()
        private val qs = IntList()
        private var lastKind = 0

        init { ts.add(0); qs.add(0) }

        fun moveTo(t: Int, q: Int) {
            val pt = ts.last()
            val pq = qs.last()
            if (t == pt && q == pq) return
            val dt = t - pt
            val dq = q - pq
            require(dt >= 0 && dq >= 0) { "path went backwards: ($pt,$pq) -> ($t,$q)" }
            if (dt > 0 && dq > 0 && dt != dq) {
                // A diagonal and a gap in one step: split at the corner.
                val d = minOf(dt, dq)
                moveTo(pt + d, pq + d)
                moveTo(t, q)
                return
            }
            val kind = if (dt > 0 && dq > 0) 1 else if (dt > 0) 2 else 3
            if (kind == lastKind && ts.size > 1) { ts.setLast(t); qs.setLast(q) }
            else { ts.add(t); qs.add(q) }
            lastKind = kind
        }

        fun build(n: Int, m: Int): Coordinates {
            moveTo(n, m)
            return Coordinates(ts.toArray(), qs.toArray())
        }
    }
}

/** Open-addressing Long -> Int map. A HashMap<Long, Int> of a whole book boxes millions of objects. */
internal class LongIntMap(expected: Int) {
    private val capacity = Integer.highestOneBit(maxOf(16, expected * 2) - 1) shl 1
    private val mask = capacity - 1
    private val keys = LongArray(capacity)
    private val values = IntArray(capacity) { ABSENT }

    private fun slot(key: Long): Int {
        var i = (key xor (key ushr 32)).toInt() and mask
        while (values[i] != ABSENT && keys[i] != key) i = (i + 1) and mask
        return i
    }

    fun get(key: Long): Int = values[slot(key)]

    fun put(key: Long, value: Int) {
        val i = slot(key)
        keys[i] = key
        values[i] = value
    }

    companion object {
        const val ABSENT = -1
        const val DUPLICATE = -2
    }
}
