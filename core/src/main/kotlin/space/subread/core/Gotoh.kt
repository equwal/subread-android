package space.subread.core

/**
 * Where two sequences line up, as a path of breakpoints: between consecutive
 * columns either both positions advance (an aligned run) or only one does (a
 * gap). Same shape as Biopython's `Alignment.coordinates`, which is what the
 * reference implementation consumes.
 */
class Coordinates(val target: IntArray, val query: IntArray) {
    val size: Int get() = target.size

    init {
        require(target.size == query.size)
    }
}

class Alignment(val score: Int, val coordinates: Coordinates)

/**
 * Exact global alignment with affine gaps (Gotoh).
 *
 * Scores are the reference's, times ten so the arithmetic stays in integers
 * and ties are exact: match 1, mismatch -0.6, gap open -0.8, gap extend -0.5.
 *
 * Costs rows * columns bytes for the traceback. That is fine for a chapter and
 * hopeless for a book, which is why whole books go through [AnchoredAligner]
 * and only the stretches between anchors end up here.
 */
object Gotoh {
    const val MATCH = 10
    const val MISMATCH = -6
    const val OPEN = -8
    const val EXTEND = -5

    private const val NEG = Int.MIN_VALUE / 4

    // Traceback, packed per cell: which state each of the three states came from.
    private const val M_FROM_X = 1       // bits 0-1: predecessor of M (0 = M)
    private const val M_FROM_Y = 2
    private const val X_FROM_X = 1 shl 2 // bits 2-3: predecessor of X (0 = M)
    private const val X_FROM_Y = 2 shl 2
    private const val Y_FROM_X = 1 shl 4 // bits 4-5: predecessor of Y (0 = M)
    private const val Y_FROM_Y = 2 shl 4

    fun cells(n: Int, m: Int): Long = (n + 1L) * (m + 1L)

    fun align(target: IntArray, query: IntArray): Alignment {
        val n = target.size
        val m = query.size
        val width = m + 1
        val trace = ByteArray(Math.toIntExact(cells(n, m)))

        // M: target[i-1] against query[j-1]. X: target[i-1] against a gap.
        // Y: query[j-1] against a gap. Two rows of each are enough for scores.
        var pm = IntArray(width) { NEG }
        var px = IntArray(width) { NEG }
        var py = IntArray(width) { NEG }
        var cm = IntArray(width)
        var cx = IntArray(width)
        var cy = IntArray(width)

        pm[0] = 0
        for (j in 1..m) {
            py[j] = OPEN + (j - 1) * EXTEND
            trace[j] = (if (j == 1) 0 else Y_FROM_Y).toByte()
        }

        for (i in 1..n) {
            val row = i * width
            cm[0] = NEG
            cy[0] = NEG
            cx[0] = OPEN + (i - 1) * EXTEND
            trace[row] = (if (i == 1) 0 else X_FROM_X).toByte()

            val t = target[i - 1]
            for (j in 1..m) {
                var bits = 0

                // M: diagonal step out of whichever state was best.
                var best = pm[j - 1]
                if (px[j - 1] > best) { best = px[j - 1]; bits = M_FROM_X }
                if (py[j - 1] > best) { best = py[j - 1]; bits = M_FROM_Y }
                cm[j] = best + if (t == query[j - 1]) MATCH else MISMATCH

                // X: step down. Extending is cheaper than reopening.
                var bx = pm[j] + OPEN
                var xb = 0
                if (px[j] + EXTEND > bx) { bx = px[j] + EXTEND; xb = X_FROM_X }
                if (py[j] + OPEN > bx) { bx = py[j] + OPEN; xb = X_FROM_Y }
                cx[j] = bx

                // Y: step right.
                var by = cm[j - 1] + OPEN
                var yb = 0
                if (cx[j - 1] + OPEN > by) { by = cx[j - 1] + OPEN; yb = Y_FROM_X }
                if (cy[j - 1] + EXTEND > by) { by = cy[j - 1] + EXTEND; yb = Y_FROM_Y }
                cy[j] = by

                trace[row + j] = (bits or xb or yb).toByte()
            }

            val tm = pm; pm = cm; cm = tm
            val tx = px; px = cx; cx = tx
            val ty = py; py = cy; cy = ty
        }

        var state = 0 // 0 = M, 1 = X, 2 = Y
        var score = pm[m]
        if (px[m] > score) { score = px[m]; state = 1 }
        if (py[m] > score) { score = py[m]; state = 2 }

        // Walk back, noting a breakpoint whenever the kind of step changes.
        val ti = IntList()
        val qi = IntList()
        var i = n
        var j = m
        ti.add(i); qi.add(j)
        var last = -1
        while (i > 0 || j > 0) {
            if (last != -1 && last != state) { ti.add(i); qi.add(j) }
            last = state
            val bits = trace[i * width + j].toInt()
            when (state) {
                0 -> { state = bits and 3; i--; j-- }
                1 -> { state = (bits shr 2) and 3; i-- }
                else -> { state = (bits shr 4) and 3; j-- }
            }
        }
        if (ti.last() != 0 || qi.last() != 0) { ti.add(0); qi.add(0) }

        return Alignment(score, Coordinates(ti.reversed(), qi.reversed()))
    }

    /** What [coordinates] is worth, for checking an alignment made some other way. */
    fun score(target: IntArray, query: IntArray, coordinates: Coordinates): Int {
        var total = 0
        for (k in 1 until coordinates.size) {
            val dt = coordinates.target[k] - coordinates.target[k - 1]
            val dq = coordinates.query[k] - coordinates.query[k - 1]
            when {
                dt > 0 && dq > 0 -> {
                    require(dt == dq) { "ragged aligned run at column $k" }
                    val t0 = coordinates.target[k - 1]
                    val q0 = coordinates.query[k - 1]
                    for (d in 0 until dt) {
                        total += if (target[t0 + d] == query[q0 + d]) MATCH else MISMATCH
                    }
                }
                dt > 0 || dq > 0 -> total += OPEN + (maxOf(dt, dq) - 1) * EXTEND
            }
        }
        return total
    }
}

/** Growable int array; boxing a few hundred thousand Ints is worth avoiding. */
internal class IntList(capacity: Int = 64) {
    private var data = IntArray(capacity)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    operator fun get(i: Int) = data[i]
    fun last() = data[size - 1]
    fun setLast(v: Int) { data[size - 1] = v }
    fun toArray(): IntArray = data.copyOf(size)
    fun reversed(): IntArray = IntArray(size) { data[size - 1 - it] }
}
