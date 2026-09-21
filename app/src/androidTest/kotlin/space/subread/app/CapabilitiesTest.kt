package space.subread.app

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.subread.app.job.Job
import space.subread.app.video.VideoMaker

/** A new user sees all that the app can do on the first screen, with no button pressed. */
@RunWith(AndroidJUnit4::class)
class CapabilitiesTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun theFirstScreenSaysAllThatTheAppCanDo() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        app.getSharedPreferences("picks", Context.MODE_PRIVATE).edit().clear().commit()
        Job.clear()

        ActivityScenario.launch(MainActivity::class.java).use {
            for (words in listOf(
                "Subtitles (.srt)", "Video + subtitles", "upload it to YouTube", "Size", "Frames a second",
                "What it takes", "m4b, m4a, mp3", "epub, plain text", "Japanese, Spanish, Portuguese, Russian, Finnish",
                "How it works", "No network, no account, no permissions", "Continue starts from where it was",
            )) compose.onNodeWithText(words, substring = true).assertExists()
            // The outputs are real controls, not only words. The options work now;
            // the save buttons wait for a job.
            for (button in listOf("Save .srt", "Share", "Save video + .srt")) {
                compose.onNodeWithText(button).assertExists().assertIsNotEnabled()
            }
            compose.onNodeWithText(VideoMaker.sizes().first().label).performScrollTo().performClick()
            compose.waitForIdle()
            // The menu holds the sizes of this device.
            assertTrue(compose.onAllNodesWithText(VideoMaker.sizes().last().label).fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithText("Start").assertExists().assertIsNotEnabled()      // nothing is picked
        }
    }
}
