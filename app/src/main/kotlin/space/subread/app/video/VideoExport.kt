package space.subread.app.video

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import space.subread.app.job.TranscriptStore
import space.subread.core.BookText
import java.io.File

data class VideoStatus(val running: Boolean = false, val fraction: Float = 0f, val message: String = "")

/**
 * Writes the video and its subtitles into a folder the user picked. The two
 * files have the same name, which is how a video player finds the subtitles.
 */
object VideoExport {
    private val state = MutableStateFlow(VideoStatus())
    val status: StateFlow<VideoStatus> = state

    /** Blocks until the files are written; call from a worker thread. */
    fun run(context: Context, audio: Uri, book: Uri?, srt: File, folder: Uri, size: VideoSize, fps: Int) {
        state.value = VideoStatus(running = true)
        val resolver = context.contentResolver
        var video: Uri? = null
        try {
            val parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
            val base = TranscriptStore.describe(context, audio).first.substringBeforeLast('.')
            video = DocumentsContract.createDocument(resolver, parent, "video/mp4", "$base.mp4")
                ?: throw IllegalStateException("The folder did not accept a new file.")
            // The folder can change the name (a file with this name is there already). The subtitles follow it.
            val written = TranscriptStore.describe(context, video).first.substringBeforeLast('.')

            val cover = book?.let { uri -> runCatching { resolver.openInputStream(uri)?.use(BookText::cover) }.getOrNull() }
            VideoMaker.make(context, audio, cover, video, size, fps) { state.value = VideoStatus(true, it) }

            val subtitles = DocumentsContract.createDocument(resolver, parent, "application/x-subrip", "$written.srt")
                ?: throw IllegalStateException("The folder did not accept the subtitles file.")
            resolver.openOutputStream(subtitles)!!.use { out -> srt.inputStream().use { it.copyTo(out) } }

            state.value = VideoStatus(message = "Saved $written.mp4 and $written.srt.")
        } catch (e: Throwable) {
            Log.w("SubRead", "video export failed", e)
            // Half a video is of no use to anyone.
            video?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            state.value = VideoStatus(message = "The video failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
