package space.subread.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.io.File

/** Not a test: prints where the pipeline first departs from the reference. */
class DebugDiff {
    @Test
    fun show() {
        val name = System.getProperty("golden.case") ?: return
        val file = File(javaClass.classLoader.getResource("golden")!!.toURI()).resolve("$name.json")
        val c = GoldenTest.Case(Json.parseToJsonElement(file.readText()).jsonObject)

        val target = c.paragraphsClean.joinToString("").toCodePoints()
        val query = c.transcriptClean.joinToString("").toCodePoints()
        val mine = Gotoh.align(target, query).coordinates
        println("columns: reference ${c.coordinates.size}, mine ${mine.size}")
        var k = 0
        while (k < minOf(mine.size, c.coordinates.size) &&
            mine.target[k] == c.coordinates.target[k] && mine.query[k] == c.coordinates.query[k]) k++
        println("paths agree for $k columns")
        for (d in -1..4) {
            val i = k + d
            if (i < 0) continue
            val r = if (i < c.coordinates.size) "(${c.coordinates.target[i]}, ${c.coordinates.query[i]})" else "-"
            val m = if (i < mine.size) "(${mine.target[i]}, ${mine.query[i]})" else "-"
            println("  col $i  reference $r   mine $m")
        }

        // How far does the anchored path stray from the exact one?
        val anchored = AnchoredAligner.align(target, query)
        println("score: exact ${Gotoh.score(target, query, mine)}  anchored ${anchored.score}")
        fun diagonals(co: Coordinates) = buildList {
            for (i in 1 until co.size) {
                val dt = co.target[i] - co.target[i - 1]; val dq = co.query[i] - co.query[i - 1]
                if (dt > 0 && dq > 0) add(Triple(co.target[i - 1], co.query[i - 1], dt))
            }
        }
        val exactOffsetAt = HashMap<Int, Int>()  // target pos -> query pos on the exact path
        for ((t, q, len) in diagonals(mine)) for (d in 0 until len) exactOffsetAt[t + d] = q + d
        var shown = 0
        for ((t, q, len) in diagonals(anchored.coordinates)) {
            val on = (0 until len).count { exactOffsetAt[t + it] == q + it }
            if (on < len && shown++ < 12) {
                println("  anchored run t=$t q=$q len=$len: $on on the exact path | " +
                    "text='${target.asString(t, minOf(t + 24, target.size))}' heard='${query.asString(q, minOf(q + 24, query.size))}'")
            }
        }
        println("anchored diagonal runs off the exact path: $shown")

        val got = Aligner.align(c.transcript, c.paragraphs, c.language)
        val first = c.cues.indices.firstOrNull { c.cues[it].text != got[it].text } ?: return
        println("first differing cue: $first of ${c.cues.size}")
        for (i in maxOf(0, first - 2)..minOf(c.cues.size - 1, first + 6)) {
            println("  [$i] ref : ${c.cues[i].text}")
            println("  [$i] mine: ${got[i].text}")
        }
        val tail = c.cues.indices.count { it > first && c.cues[it].text == got[it].text }
        println("identical after the first difference: $tail")
    }
}
