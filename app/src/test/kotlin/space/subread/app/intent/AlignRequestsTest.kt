package space.subread.app.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decisions of the intent API. No Android, so this runs on a laptop. */
class AlignRequestsTest {

    private val audio = "content://other.app.files/audio/book.m4b"
    private val book = "content://other.app.files/books/book.epub"

    private fun refusal(ask: AlignRequest): String {
        assertTrue("expected a refusal, got $ask", ask is AlignRequest.Refused)
        return (ask as AlignRequest.Refused).error
    }

    @Test
    fun aGoodAskIsAccepted() {
        val ask = AlignRequests.read(audio, book, "ja", busy = false)
        assertEquals(AlignRequest.Accepted(audio, book, "ja"), ask)
    }

    @Test
    fun noLanguageMeansAuto() {
        assertEquals(AlignRequest.Accepted(audio, book, "auto"), AlignRequests.read(audio, book, null, false))
        assertEquals(AlignRequest.Accepted(audio, book, "auto"), AlignRequests.read(audio, book, "  ", false))
        assertEquals(AlignRequest.Accepted(audio, book, "auto"), AlignRequests.read(audio, book, "auto", false))
    }

    @Test
    fun aThreeLetterCodeIsALanguageToo() {
        assertEquals(AlignRequest.Accepted(audio, book, "yue"), AlignRequests.read(audio, book, "yue", false))
    }

    @Test
    fun aMissingFileIsRefusedAndTheErrorNamesTheExtra() {
        assertTrue(refusal(AlignRequests.read(null, book, "ja", false)).contains(AlignContract.EXTRA_AUDIO))
        assertTrue(refusal(AlignRequests.read("", book, "ja", false)).contains(AlignContract.EXTRA_AUDIO))
        assertTrue(refusal(AlignRequests.read(audio, null, "ja", false)).contains(AlignContract.EXTRA_BOOK))
    }

    @Test
    fun onlyAContentUriIsTaken() {
        // A file Uri would make SubRead read its own private files for a stranger.
        assertTrue(refusal(AlignRequests.read("file:///data/data/space.subread.app/x", book, "ja", false))
            .contains("content://"))
        assertTrue(refusal(AlignRequests.read(audio, "/sdcard/book.epub", "ja", false)).contains("content://"))
    }

    @Test
    fun aWordThatIsNotALanguageCodeIsRefused() {
        assertTrue(refusal(AlignRequests.read(audio, book, "Japanese", false)).contains("Japanese"))
        assertTrue(refusal(AlignRequests.read(audio, book, "j", false)).contains("Whisper"))
    }

    @Test
    fun anAskDuringAJobIsRefused() {
        val error = refusal(AlignRequests.read(audio, book, "ja", busy = true))
        assertTrue(error, error.contains("busy"))
    }

    @Test
    fun theLanguageOfTheAnswerComesFromTheNameOfTheFile() {
        assertEquals("ja", AlignRequests.languageOf("book.ja.srt", "auto"))
        assertEquals("ru", AlignRequests.languageOf("part 1. the start.ru.srt", "auto"))
        assertEquals("yue", AlignRequests.languageOf("book.yue.srt", "auto"))
        // No code in the name: say what was asked for.
        assertEquals("ja", AlignRequests.languageOf("book.srt", "ja"))
        assertEquals("auto", AlignRequests.languageOf("book.subtitles.srt", "auto"))
    }
}
