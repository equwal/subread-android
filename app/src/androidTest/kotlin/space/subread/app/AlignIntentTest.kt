package space.subread.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.subread.app.intent.AlignContract
import space.subread.app.job.Job
import space.subread.app.job.Phase
import space.subread.app.job.TranscriptStore
import java.io.File

/**
 * Another app asks for the subtitles of a book and gets the file back.
 * See docs/intent-api.md.
 *
 * The media is not in the repository. Put an audio file and its book in
 * app/src/androidTest/assets/local/ (gitignored); the test skips without them.
 */
@RunWith(AndroidJUnit4::class)
class AlignIntentTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app: Context get() = instrumentation.targetContext

    @Test
    fun anotherAppAsksForSubtitlesAndGetsTheFileBack() {
        val names = instrumentation.context.assets.list("local").orEmpty()
        val audioName = names.firstOrNull { it.endsWith(".mp3") || it.endsWith(".m4b") || it.endsWith(".m4a") }
        val bookName = names.firstOrNull { it.endsWith(".epub") || it.endsWith(".txt") }
        assumeTrue("no local test media", audioName != null && bookName != null)

        val audio = stage(audioName!!)
        val book = stage(bookName!!)
        TranscriptStore.forAudio(app, audio).reset()
        val picks = savePicks()

        ActivityScenario.launchActivityForResult<MainActivity>(ask(audio, book, "auto")).use { scenario ->
            // The job starts by itself, and the screen says who asked for it.
            compose.waitUntil(30_000) { Job.status.value.running }
            compose.onNodeWithText("asked for subtitles", substring = true).assertExists()
            // The files belong to the ask: the user cannot change them under it.
            compose.onAllNodesWithText("Change")[0].assertIsNotEnabled()

            compose.waitUntil(1_800_000) {
                Job.status.value.phase == Phase.DONE || Job.status.value.phase == Phase.FAILED
            }
            // A failed job stays on the screen for the user to read, so there is no answer to wait for.
            assertEquals(Job.status.value.detail, Phase.DONE, Job.status.value.phase)
            // The screen answers from its own update loop, and in a Compose test that loop runs
            // only while the test asks the rule to wait. scenario.result alone would block it.
            compose.waitUntil(60_000) { scenario.state == Lifecycle.State.DESTROYED }
            val result = scenario.result
            assertEquals(Job.status.value.detail, Activity.RESULT_OK, result.resultCode)

            val answer = result.resultData
            val srt = answer.data
            assertNotNull("no Uri in the answer", srt)
            assertEquals("content", srt!!.scheme)
            assertEquals(
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                answer.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )

            val text = app.contentResolver.openInputStream(srt)!!.use { it.readBytes().decodeToString() }
            assertTrue(text.take(20), text.startsWith("1\n00:00:"))
            assertEquals(text.trim().split("\n\n").size, answer.getIntExtra(AlignContract.EXTRA_CUES, 0))
            assertTrue(
                "match rate ${answer.getDoubleExtra(AlignContract.EXTRA_MATCH_RATE, 0.0)}",
                answer.getDoubleExtra(AlignContract.EXTRA_MATCH_RATE, 0.0) > 0.8,
            )
            val language = answer.getStringExtra(AlignContract.EXTRA_LANGUAGE)
            assertTrue("language $language", language != null && language.matches(Regex("[a-z]{2,3}")))
        }
        assertEquals("the ask must not change what the user picked", picks, savePicks())

        // The same ask again. SubRead has the work of the first job, so the subtitles are ready in
        // less than a second. On a tablet the screen opened and closed at once, and the user
        // took it for a fault. Now the screen stays, says why, and has a button for the way back.
        ActivityScenario.launchActivityForResult<MainActivity>(ask(audio, book, "auto")).use { scenario ->
            compose.waitUntil(60_000) { Job.status.value.phase == Phase.DONE }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("ready at once", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue("the screen closed by itself", scenario.state != Lifecycle.State.DESTROYED)
            compose.onNodeWithText("Back to", substring = true).performClick()
            compose.waitUntil(10_000) { scenario.state == Lifecycle.State.DESTROYED }
            assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
            assertNotNull("no Uri in the answer", scenario.result.resultData.data)
        }
    }

    @Test
    fun anAskWithoutABookIsRefusedAndNoJobRuns() {
        assumeTrue("another job runs", !Job.status.value.running)
        val audio = "content://${app.packageName}.files/subtitles/none.m4b".toUri()
        val incomplete = Intent(AlignContract.ACTION)
            .setClass(app, MainActivity::class.java)
            .putExtra(AlignContract.EXTRA_AUDIO, audio)

        ActivityScenario.launchActivityForResult<MainActivity>(incomplete).use { scenario ->
            compose.onNodeWithText("cannot do it", substring = true).assertExists()
            assertFalse("a job started on a bad ask", Job.status.value.running)

            scenario.onActivity { it.finish() }
            val result = scenario.result
            assertEquals(Activity.RESULT_CANCELED, result.resultCode)
            val error = result.resultData.getStringExtra(AlignContract.EXTRA_ERROR)
            assertNotNull("no error in the answer", error)
            assertTrue(error!!, error.contains(AlignContract.EXTRA_BOOK))
        }
    }

    private fun ask(audio: Uri, book: Uri, language: String) =
        Intent(AlignContract.ACTION)
            .setClass(app, MainActivity::class.java)
            .putExtra(AlignContract.EXTRA_AUDIO, audio)
            .putExtra(AlignContract.EXTRA_BOOK, book)
            .putExtra(AlignContract.EXTRA_LANGUAGE, language)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /**
     * A content Uri of the test media, as the Uri of another app would be.
     * The files go where the FileProvider serves from (see res/xml/file_paths.xml).
     */
    private fun stage(name: String): Uri {
        val out = File(app.filesDir, "subtitles/asked").apply { mkdirs() }.resolve(name)
        instrumentation.context.assets.open("local/$name").use { src ->
            out.outputStream().use { src.copyTo(it) }
        }
        return FileProvider.getUriForFile(app, "${app.packageName}.files", out)
    }

    /** What the user picked by hand. The ask of another app must leave it alone. */
    private fun savePicks(): Map<String, Any?> {
        val prefs = app.getSharedPreferences("picks", Context.MODE_PRIVATE)
        if (!prefs.contains("audio")) {
            prefs.edit().putString("audio", "content://user/audio")
                .putString("book", "content://user/book").putString("language", "en").commit()
        }
        return HashMap(prefs.all)
    }
}
