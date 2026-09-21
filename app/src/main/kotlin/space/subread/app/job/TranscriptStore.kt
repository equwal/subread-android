package space.subread.app.job

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import space.subread.core.TranscriptSegment
import java.io.File
import java.security.MessageDigest

/**
 * What has been transcribed so far, on disk, per audio file.
 *
 * Transcribing a book takes hours and Android is entitled to kill the process
 * at any point in them. Every finished chunk is appended here, so a restart
 * picks up at the last chunk rather than at the beginning - and aligning the
 * same audio against a different edition of the book costs seconds, not hours,
 * because the transcript depends on the audio alone.
 */
class TranscriptStore private constructor(private val dir: File) {

    private val segmentsFile = File(dir, "segments.jsonl")
    private val stateFile = File(dir, "state.json")

    /** Audio before this point (seconds) is transcribed and saved. */
    var doneUntil: Double = 0.0
        private set
    var language: String? = null
        private set
    var complete: Boolean = false
        private set

    init {
        dir.mkdirs()
        if (stateFile.exists()) runCatching {
            val o = JSONObject(stateFile.readText())
            doneUntil = o.optDouble("doneUntil", 0.0)
            language = o.optString("language").ifEmpty { null }
            complete = o.optBoolean("complete", false)
        }
        // A chunk's segments are written before its state. Killed in between,
        // the file holds segments past doneUntil that are about to be
        // transcribed a second time; remove them now or they end up doubled.
        if (segmentsFile.exists()) {
            val kept = segmentsFile.readLines().filter { line ->
                runCatching { JSONObject(line).getDouble("s") < doneUntil }.getOrDefault(false)
            }
            segmentsFile.writeText(kept.joinToString("") { it + "\n" })
        }
    }

    fun segments(): List<TranscriptSegment> {
        if (!segmentsFile.exists()) return emptyList()
        return segmentsFile.readLines().mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                TranscriptSegment(o.getString("t"), o.getDouble("s"), o.getDouble("e"))
            }.getOrNull()
        }
    }

    fun append(chunk: List<TranscriptSegment>, until: Double, language: String?) {
        segmentsFile.appendText(chunk.joinToString("") { s ->
            JSONObject().put("s", s.start).put("e", s.end).put("t", s.text).toString() + "\n"
        })
        doneUntil = until
        if (language != null) this.language = language
        save()
    }

    fun finish() {
        complete = true
        save()
    }

    fun reset() {
        segmentsFile.delete()
        stateFile.delete()
        doneUntil = 0.0
        language = null
        complete = false
    }

    private fun save() {
        val tmp = File(dir, "state.json.tmp")
        tmp.writeText(
            JSONObject().put("doneUntil", doneUntil).put("language", language ?: "")
                .put("complete", complete).toString()
        )
        tmp.renameTo(stateFile)
    }

    companion object {
        fun forAudio(context: Context, audio: Uri): TranscriptStore {
            val (name, size) = describe(context, audio)
            // Name and size, not the URI: the same file picked again through a
            // different route gets a different URI and should still resume.
            val key = MessageDigest.getInstance("SHA-1").digest("$name|$size".toByteArray())
                .joinToString("") { "%02x".format(it) }.take(20)
            return TranscriptStore(File(context.filesDir, "transcripts/$key"))
        }

        /** Display name and byte size of a picked document. */
        fun describe(context: Context, uri: Uri): Pair<String, Long> {
            var name = uri.lastPathSegment ?: "file"
            var size = -1L
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val s = c.getColumnIndex(OpenableColumns.SIZE)
                        if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                        if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                    }
                }
            }
            return name to size
        }
    }
}
