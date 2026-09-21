package space.subread.app.intent

/** The names another app uses to ask for subtitles. See docs/intent-api.md. */
object AlignContract {
    const val ACTION = "space.subread.app.action.ALIGN"
    const val EXTRA_AUDIO = "space.subread.extra.AUDIO"
    const val EXTRA_BOOK = "space.subread.extra.BOOK"

    /** The language to use, in the ask; the language that was used, in the answer. */
    const val EXTRA_LANGUAGE = "space.subread.extra.LANGUAGE"
    const val EXTRA_CUES = "space.subread.extra.CUES"
    const val EXTRA_MATCH_RATE = "space.subread.extra.MATCH_RATE"
    const val EXTRA_ERROR = "space.subread.extra.ERROR"
}

/** What the screen does with the ask of another app. */
sealed interface AlignRequest {
    /** The ask is good. Run the job on these two files. */
    data class Accepted(val audio: String, val book: String, val language: String) : AlignRequest

    /** SubRead cannot do the ask. [error] goes on the screen and back to the caller. */
    data class Refused(val error: String) : AlignRequest
}

/**
 * The decisions of the intent API, with no Android type in them, so that a JVM
 * test covers them without a device.
 */
object AlignRequests {
    /** A Whisper language code is two or three lower-case letters, as in "ja" or "yue". */
    private val CODE = Regex("[a-z]{2,3}")

    /** The default language: the speech model listens and decides. */
    const val AUTO = "auto"

    /**
     * Read the ask. [audio] and [book] are the Uris of the two extras, as text.
     * [busy] is true when a job already runs, because SubRead runs one job at a
     * time and must not stop that job for a new ask.
     */
    fun read(audio: String?, book: String?, language: String?, busy: Boolean): AlignRequest {
        if (busy) {
            return AlignRequest.Refused(
                "SubRead is busy with another job. Ask again when that job is done.",
            )
        }
        val code = language?.trim().orEmpty().ifEmpty { AUTO }
        val problem = badUri("audio file", AlignContract.EXTRA_AUDIO, audio)
            ?: badUri("book", AlignContract.EXTRA_BOOK, book)
            ?: badLanguage(code)
        return if (problem != null) AlignRequest.Refused(problem)
        else AlignRequest.Accepted(audio!!.trim(), book!!.trim(), code)
    }

    /**
     * The language the job used, read from the name of the file it wrote
     * ("book.ja.srt" gives "ja"). [asked] is the answer when the name holds no
     * code, which happens only if the naming changes.
     */
    fun languageOf(srtName: String, asked: String): String {
        val code = srtName.removeSuffix(".srt").substringAfterLast('.', "")
        return if (CODE.matches(code)) code else asked
    }

    private fun badUri(what: String, extra: String, uri: String?): String? {
        val text = uri?.trim().orEmpty()
        return when {
            text.isEmpty() -> "The ask gives no $what. Put its Uri in $extra."
            // Only a content Uri is granted by the caller. A file Uri would make
            // SubRead read its own private files for a stranger.
            !text.startsWith("content://") ->
                "The $what must be a content:// Uri that the asking app grants, not $text."

            else -> null
        }
    }

    private fun badLanguage(code: String): String? =
        if (code == AUTO || CODE.matches(code)) null
        else "$code is not a language. Use a Whisper code such as ja, or $AUTO."
}
