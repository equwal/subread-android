package space.subread.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.subread.app.job.Job
import space.subread.app.job.JobStatus
import space.subread.app.job.Phase
import space.subread.app.job.TranscriptStore
import space.subread.app.video.VideoExport
import space.subread.app.video.VideoMaker
import space.subread.app.video.VideoStatus
import java.io.File
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

/**
 * Black on white and nothing that moves: this has to be as usable on an e-ink
 * reader - where an animation is a smear - as on a phone.
 */
private val Paper = lightColorScheme(
    primary = Color.Black, onPrimary = Color.White,
    secondary = Color.Black, onSecondary = Color.White,
    background = Color.White, onBackground = Color.Black,
    surface = Color.White, onSurface = Color.Black,
    surfaceVariant = Color(0xFFEDEDED), onSurfaceVariant = Color(0xFF333333),
    outline = Color.Black,
)

/** Whisper's codes. "auto" listens to the first two minutes and decides. */
private val LANGUAGES = listOf(
    "auto" to "Detect automatically",
    "ja" to "Japanese", "es" to "Spanish", "pt" to "Portuguese", "ru" to "Russian",
    "fi" to "Finnish", "en" to "English", "de" to "German", "fr" to "French",
    "it" to "Italian", "zh" to "Chinese", "ko" to "Korean", "nl" to "Dutch",
    "sv" to "Swedish", "pl" to "Polish", "uk" to "Ukrainian", "tr" to "Turkish",
)

@Composable
private fun App() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("picks", Context.MODE_PRIVATE) }
    val status by Job.status.collectAsStateWithLifecycle()

    var audio by remember { mutableStateOf(prefs.getString("audio", null)?.let(Uri::parse)) }
    var book by remember { mutableStateOf(prefs.getString("book", null)?.let(Uri::parse)) }
    var language by remember { mutableStateOf(prefs.getString("language", "auto")!!) }

    fun keep(key: String, uri: Uri) {
        // So the pick survives the process being killed mid-job and can resume.
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        prefs.edit().putString(key, uri.toString()).apply()
    }

    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { audio = uri; keep("audio", uri); Job.clear() }
    }
    val pickBook = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { book = uri; keep("book", uri); Job.clear() }
    }
    val saveSrt = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-subrip")
    ) { dest ->
        val srt = status.srt
        if (dest != null && srt != null) {
            context.contentResolver.openOutputStream(dest)?.use { out -> srt.inputStream().use { it.copyTo(out) } }
        }
    }
    val video by VideoExport.status.collectAsStateWithLifecycle()
    val sizes = remember { VideoMaker.sizes() }
    var videoSize by remember { mutableStateOf(sizes.firstOrNull { it.label == prefs.getString("video_size", null) } ?: sizes.first()) }
    var videoFps by remember { mutableStateOf(prefs.getInt("video_fps", 1).takeIf { it in VideoMaker.FRAME_RATES } ?: 1) }
    val pickVideoFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folder ->
        val a = audio
        val srt = status.srt
        if (folder != null && a != null && srt != null) {
            val app = context.applicationContext
            thread(name = "subread-video") { VideoExport.run(app, a, book, srt, folder, videoSize, videoFps) }
        }
    }
    // The job runs only while this screen is open. Keep the display on, or the
    // system sleeps and stops the work. No background service: the job saves
    // each finished chunk, so an interrupted run continues from that chunk.
    val view = LocalView.current
    DisposableEffect(status.running || video.running) {
        view.keepScreenOn = status.running || video.running
        onDispose { view.keepScreenOn = false }
    }

    MaterialTheme(colorScheme = Paper) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("SubRead", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Times an audiobook against its ebook, on this device. Nothing is uploaded. " +
                        "The result is an .srt for Hoshi Reader's read-along, or a video + subtitles " +
                        "that any video player plays.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (status.phase == Phase.IDLE) Capabilities()
                HorizontalDivider(color = Color.Black)

                Pick("Audiobook", audio?.let { TranscriptStore.describe(context, it).first }, !status.running) {
                    pickAudio.launch(arrayOf("audio/*", "video/mp4", "application/ogg", "application/octet-stream"))
                }
                Pick("Book", book?.let { TranscriptStore.describe(context, it).first }, !status.running) {
                    pickBook.launch(arrayOf("application/epub+zip", "text/plain", "application/zip",
                        "application/octet-stream"))
                }
                LanguagePick(language, !status.running) {
                    language = it
                    prefs.edit().putString("language", it).apply()
                }

                HorizontalDivider(color = Color.Black)

                if (status.running) {
                    Progress(status)
                    OutlinedButton(onClick = { Job.cancel() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Stop (progress is kept)")
                    }
                } else {
                    val a = audio
                    val b = book
                    val resumable = remember(a, status.phase) {
                        a != null && TranscriptStore.forAudio(context, a).doneUntil > 0
                    }
                    Button(
                        enabled = a != null && b != null,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val app = context.applicationContext
                            thread(name = "subread-job") { Job.run(app, a!!, b!!, language) }
                        },
                    ) { Text(if (resumable) "Continue" else "Start") }

                    Outcome(status)
                }

                // Each output and its options, from the first screen on. The buttons
                // wait for the job; a job takes hours, and nobody must run one to
                // find out what the app makes.
                HorizontalDivider(color = Color.Black)
                Outputs(status, video,
                    videoOptions = {
                        Choice("Size", sizes.map { it to it.label }, videoSize) {
                            videoSize = it
                            prefs.edit { putString("video_size", it.label) }
                        }
                        Choice("Frames a second", VideoMaker.FRAME_RATES.map { it to "$it" }, videoFps) {
                            videoFps = it
                            prefs.edit { putInt("video_fps", it) }
                        }
                    },
                    onSave = { status.srt?.let { saveSrt.launch(it.name) } },
                    onShare = { status.srt?.let { share(context, it) } },
                    onVideo = { pickVideoFolder.launch(null) })


            }
        }
    }
}

@Composable
private fun Capabilities() {
    val named = LANGUAGES.drop(1).take(5).joinToString(", ") { it.second }
    val sections = listOf(
        "What it takes" to listOf(
            "Audio: m4b, m4a, mp3, opus, ogg, flac, wav.",
            "Book: epub, plain text, or an Aozora Bunko zip.",
            "Language: $named and ${LANGUAGES.size - 6} more, or let the app detect it.",
        ),
        "How it works" to listOf(
            "All on this device. No network, no account, no permissions.",
            "The words come from the book, so there are no mistakes of a speech model in them. " +
                "Pages that nobody reads aloud (front matter, notes) are left out.",
            "A long book takes hours. Keep the app open. If it stops, Continue starts from where it was.",
        ),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((title, lines) in sections) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                for (line in lines) Text("• $line", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Pick(label: String, chosen: String?, enabled: Boolean, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onClick, enabled = enabled) { Text(if (chosen == null) "Choose" else "Change") }
            Text(chosen ?: "Nothing chosen", modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LanguagePick(code: String, enabled: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Language", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Box {
            OutlinedButton(onClick = { open = true }, enabled = enabled) {
                Text(LANGUAGES.firstOrNull { it.first == code }?.second ?: code)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for ((c, name) in LANGUAGES) {
                    DropdownMenuItem(text = { Text(name) }, onClick = { onPick(c); open = false })
                }
            }
        }
    }
}

@Composable
private fun <T> Choice(label: String, options: List<Pair<T, String>>, chosen: T, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Box {
            OutlinedButton(onClick = { open = true }) { Text(options.first { it.first == chosen }.second) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for ((value, name) in options) {
                    DropdownMenuItem(text = { Text(name) }, onClick = { onPick(value); open = false })
                }
            }
        }
    }
}

@Composable
private fun Progress(status: JobStatus) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(status.detail, fontWeight = FontWeight.Bold)
        LinearProgressIndicator(
            progress = { status.fraction },
            modifier = Modifier.fillMaxWidth(),
            color = Color.Black, trackColor = Color(0xFFCCCCCC),
        )
        val bits = listOfNotNull(
            "${(status.fraction * 100).toInt()}%",
            status.etaSeconds?.let { "about ${Job.clock(it.toDouble())} left" },
            status.speed?.let { "%.1f× real time".format(it) },
        )
        Text(bits.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Outcome(status: JobStatus) {
    when (status.phase) {
        Phase.DONE -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val rate = ((status.matchRate ?: 0.0) * 100).toInt()
            Text("Done: ${status.cues} lines, $rate% found in the book.", fontWeight = FontWeight.Bold)
            if (rate < 80) {
                Text("That is low. Usually it means a different edition or translation of the book, " +
                    "or the wrong language.", style = MaterialTheme.typography.bodySmall)
            }
            if (status.paragraphsDropped > 0) {
                Text("${status.paragraphsDropped} paragraphs of the book were never narrated " +
                    "(front matter, notes) and were left out.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Phase.FAILED -> Text("Failed: ${status.detail}", fontWeight = FontWeight.Bold)
        Phase.CANCELLED -> Text(status.detail)
        else -> {}
    }
}

/** What the app makes. The save buttons work when a job is done. */
@Composable
private fun Outputs(
    status: JobStatus, video: VideoStatus, videoOptions: @Composable () -> Unit,
    onSave: () -> Unit, onShare: () -> Unit, onVideo: () -> Unit,
) {
    val done = status.phase == Phase.DONE && status.srt != null
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Subtitles (.srt)", fontWeight = FontWeight.Bold)
        Text("Each line of the book with the time it is read. In Hoshi Reader: long-press the book, " +
            "Match, and choose the saved .srt.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onSave, enabled = done) { Text("Save .srt") }
            OutlinedButton(onClick = onShare, enabled = done) { Text("Share") }
        }

        HorizontalDivider(color = Color.Black)
        Text("Video + subtitles", fontWeight = FontWeight.Bold)
        Text("An .mp4 of the cover and the audio, with the .srt beside it. It plays with subtitles " +
            "in any video player, and you can upload it to YouTube.", style = MaterialTheme.typography.bodySmall)
        if (video.running) {
            LinearProgressIndicator(
                progress = { video.fraction },
                modifier = Modifier.fillMaxWidth(),
                color = Color.Black, trackColor = Color(0xFFCCCCCC),
            )
            Text("Making the video: ${(video.fraction * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { videoOptions() }
            Text("The picture is still, so more frames a second add almost nothing to the file " +
                "(about 60 MB of video for a 10-hour book). A larger size adds a little.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onVideo, enabled = done) { Text("Save video + .srt") }
            if (video.message.isNotEmpty()) Text(video.message, style = MaterialTheme.typography.bodySmall)
        }
        if (!done) Text("The save buttons work when the job is done.", style = MaterialTheme.typography.bodySmall)
    }
}

private fun share(context: Context, srt: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", srt)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/x-subrip")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, srt.name))
}
