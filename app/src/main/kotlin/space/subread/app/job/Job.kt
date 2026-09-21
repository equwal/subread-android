package space.subread.app.job

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import space.subread.app.audio.AudioDecoder
import space.subread.app.whisper.TranscriptionCancelled
import space.subread.app.whisper.Whisper
import space.subread.core.BookAligner
import space.subread.core.BookText
import space.subread.core.Language
import space.subread.core.Srt
import java.io.File

enum class Phase { IDLE, PREPARING, TRANSCRIBING, ALIGNING, DONE, FAILED, CANCELLED }

data class JobStatus(
    val phase: Phase = Phase.IDLE,
    val detail: String = "",
    /** 0..1 over the whole job. */
    val fraction: Float = 0f,
    val etaSeconds: Long? = null,
    /** Audio seconds transcribed per wall-clock second. */
    val speed: Double? = null,
    val srt: File? = null,
    val cues: Int = 0,
    val matchRate: Double? = null,
    val paragraphsDropped: Int = 0,
) {
    val running: Boolean get() = phase == Phase.PREPARING || phase == Phase.TRANSCRIBING || phase == Phase.ALIGNING
}

/** The one job this app runs at a time, observable from the UI and the service alike. */
object Job {
    private val state = MutableStateFlow(JobStatus())
    val status: StateFlow<JobStatus> = state

    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        Whisper.cancel()
    }

    fun clear() { if (!state.value.running) state.value = JobStatus() }

    /**
     * Transcribe (or resume transcribing) [audio], align it to [book], write
     * the subtitles. Blocks for as long as that takes; call from a worker thread.
     *
     * @param language a whisper language code, or "auto"
     */
    fun run(context: Context, audio: Uri, book: Uri, language: String) {
        cancelled = false
        state.value = JobStatus(Phase.PREPARING, "Loading the speech model")
        try {
            val store = TranscriptStore.forAudio(context, audio)
            var lang = if (language == "auto") store.language ?: "auto" else language

            if (!store.complete) transcribe(context, audio, store, lang) { lang = it }
            if (lang == "auto") lang = store.language ?: "en"

            state.value = state.value.copy(phase = Phase.ALIGNING, detail = "Matching the book to the narration",
                fraction = 0.97f, etaSeconds = null)
            val (bookName, _) = TranscriptStore.describe(context, book)
            val paragraphs = context.contentResolver.openInputStream(book)!!.use { BookText.read(it, bookName) }
            if (paragraphs.isEmpty()) throw IllegalStateException("No text could be read from $bookName.")

            val result = BookAligner.align(store.segments(), paragraphs, Language.of(lang))

            val (audioName, _) = TranscriptStore.describe(context, audio)
            val out = File(context.filesDir, "subtitles").apply { mkdirs() }
                .resolve(audioName.substringBeforeLast('.') + ".$lang.srt")
            out.writeText(Srt.write(result.cues))

            state.value = JobStatus(
                Phase.DONE, "Done", 1f, srt = out, cues = result.cues.size,
                matchRate = BookAligner.matchRate(result.cues), paragraphsDropped = result.paragraphsDropped,
            )
        } catch (e: TranscriptionCancelled) {
            state.value = JobStatus(Phase.CANCELLED, "Stopped. Progress is saved; start again to continue.")
        } catch (e: Throwable) {
            state.value = JobStatus(Phase.FAILED, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun transcribe(
        context: Context, audio: Uri, store: TranscriptStore, language: String, onLanguage: (String) -> Unit,
    ) {
        var lang = language
        Whisper.open(context).use { whisper ->
            AudioDecoder(context, audio, startAtSeconds = store.doneUntil).use { decoder ->
                val total = decoder.durationSeconds
                val resumedAt = store.doneUntil
                val started = SystemClock.elapsedRealtime()

                while (true) {
                    if (cancelled) throw TranscriptionCancelled()
                    val chunk = decoder.next() ?: break
                    report(chunk.startSeconds, total, resumedAt, started)

                    val segments = whisper.transcribe(chunk.samples, lang, chunk.startSeconds)
                    // Detect once, then hold it: a stretch of music must not be
                    // allowed to flip the rest of the book into another language.
                    if (lang == "auto") {
                        lang = whisper.detectedLanguage.ifEmpty { "en" }
                        onLanguage(lang)
                    }
                    store.append(segments, chunk.endSeconds, lang)
                    report(chunk.endSeconds, total, resumedAt, started)
                }
                store.finish()
            }
        }
    }

    private fun report(at: Double, total: Double, resumedAt: Double, startedMs: Long) {
        val elapsed = (SystemClock.elapsedRealtime() - startedMs) / 1000.0
        val done = at - resumedAt
        val speed = if (elapsed > 5 && done > 0) done / elapsed else null
        val eta = if (speed != null && total > 0) ((total - at) / speed).toLong().coerceAtLeast(0) else null
        state.value = JobStatus(
            Phase.TRANSCRIBING,
            "Listening: ${clock(at)}" + if (total > 0) " of ${clock(total)}" else "",
            // Alignment takes seconds; nearly all of the bar is transcription.
            fraction = if (total > 0) (at / total * 0.96).toFloat().coerceIn(0f, 0.96f) else 0f,
            etaSeconds = eta, speed = speed,
        )
    }

    fun clock(seconds: Double): String {
        val s = seconds.toLong()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }
}
