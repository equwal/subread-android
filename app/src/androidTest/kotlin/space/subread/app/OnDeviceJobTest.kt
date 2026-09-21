package space.subread.app

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import space.subread.app.job.Job
import space.subread.app.job.Phase
import space.subread.app.job.TranscriptStore
import java.io.File

/**
 * The whole job on real hardware: decode, transcribe with the native speech
 * model, align, write the subtitles. No screen is needed.
 *
 * The media is not in the repository. Put an audio file and its book in
 * app/src/androidTest/assets/local/ (gitignored); the test skips without them.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceJobTest {

    @Test
    fun anAudiobookAndItsBookBecomeSubtitles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val names = instrumentation.context.assets.list("local").orEmpty()
        val audioName = names.firstOrNull { it.endsWith(".mp3") || it.endsWith(".m4b") || it.endsWith(".m4a") }
        val bookName = names.firstOrNull { it.endsWith(".epub") || it.endsWith(".txt") }
        assumeTrue("no local test media", audioName != null && bookName != null)

        val app = instrumentation.targetContext
        fun stage(name: String): Uri {
            val out = File(app.cacheDir, name)
            instrumentation.context.assets.open("local/$name").use { src -> out.outputStream().use { src.copyTo(it) } }
            return Uri.fromFile(out)
        }
        val audio = stage(audioName!!)
        val book = stage(bookName!!)
        TranscriptStore.forAudio(app, audio).reset()   // measure a cold run, not a cached one

        val started = System.nanoTime()
        Job.run(app, audio, book, "auto")
        val seconds = (System.nanoTime() - started) / 1e9

        val status = Job.status.value
        Log.i("SubReadTest", "phase=${status.phase} detail=${status.detail} cues=${status.cues} " +
            "match=${status.matchRate} dropped=${status.paragraphsDropped} seconds=%.1f".format(seconds))
        assertEquals(status.detail, Phase.DONE, status.phase)
        assertNotNull(status.srt)
        val srt = status.srt!!.readText()
        Log.i("SubReadTest", "language=${TranscriptStore.forAudio(app, audio).language} srt head:\n" +
            srt.lineSequence().take(12).joinToString("\n"))
        // Side by side: what the model heard, and the book text given to that cue.
        val heard = TranscriptStore.forAudio(app, audio).segments()
        val cues = srt.trim().split("\n\n").map { it.lines().getOrElse(2) { "" } }
        heard.forEach { s ->
            Log.i("SubReadSeg", org.json.JSONObject().put("text", s.text).put("start", s.start).put("end", s.end).toString())
        }
        heard.take(14).forEachIndexed { i, s ->
            Log.i("SubReadTest", "%6.2f-%6.2f heard: %s | book: %s".format(s.start, s.end, s.text, cues.getOrElse(i) { "" }))
        }
        assertTrue("only ${status.cues} cues", status.cues >= 5)
        assertTrue("match rate ${status.matchRate}", (status.matchRate ?: 0.0) > 0.8)
        assertTrue(srt.startsWith("1\n00:00:"))

        // A second run reuses the saved transcript: seconds, not minutes.
        val again = System.nanoTime()
        Job.run(app, audio, book, "auto")
        val cached = (System.nanoTime() - again) / 1e9
        Log.i("SubReadTest", "cached rerun seconds=%.1f".format(cached))
        assertEquals(Phase.DONE, Job.status.value.phase)
        assertTrue("cached rerun took ${cached}s", cached < seconds / 2 + 5)
    }
}
