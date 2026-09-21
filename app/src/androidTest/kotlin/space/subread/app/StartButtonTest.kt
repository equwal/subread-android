package space.subread.app

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.subread.app.job.Job
import space.subread.app.job.Phase
import space.subread.app.job.TranscriptStore
import java.io.File

/**
 * The job runs from the open screen. There is no background service.
 * Start must begin the job, the display must stay on while the job runs, and
 * the display lock must go when the job ends. Uses the same local media as
 * OnDeviceJobTest, and skips without it.
 */
@RunWith(AndroidJUnit4::class)
class StartButtonTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun startRunsTheJobFromTheScreenAndKeepsTheDisplayOn() {
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
        TranscriptStore.forAudio(app, audio).reset()
        // The screen reads its picks from here, as it does after a restart.
        app.getSharedPreferences("picks", Context.MODE_PRIVATE).edit()
            .putString("audio", audio.toString()).putString("book", book.toString())
            .putString("language", "auto").commit()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun keepScreenOn(): Boolean {
                var on = false
                scenario.onActivity { on = it.findViewById<android.view.View>(android.R.id.content).let { root ->
                    generateSequence(listOf(root)) { level ->
                        level.flatMap { v -> (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).map(g::getChildAt) } ?: emptyList() }
                            .takeIf { it.isNotEmpty() }
                    }.flatten().any { it.keepScreenOn }
                } }
                return on
            }

            assertFalse("display lock before the job", keepScreenOn())
            compose.onNodeWithText("Start").performClick()

            compose.waitUntil(30_000) { Job.status.value.running }
            compose.waitUntil(10_000) { keepScreenOn() }

            compose.waitUntil(300_000) { !Job.status.value.running }
            assertEquals(Job.status.value.detail, Phase.DONE, Job.status.value.phase)
            compose.waitUntil(10_000) { !keepScreenOn() }
            compose.onNodeWithText("Save .srt").assertExists()
            assertTrue((Job.status.value.matchRate ?: 0.0) > 0.8)
        }
    }
}
