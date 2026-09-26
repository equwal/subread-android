package space.subread.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.subread.app.intent.AlignContract
import space.subread.app.intent.AlignRequest
import space.subread.app.intent.AlignRequests
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

    /** The ask of another app, when one started this screen. Null on a normal start. */
    private var ask by mutableStateOf<AlignRequest?>(null)

    /** A word to the user about a second ask that arrived while a job runs. */
    private var notice by mutableStateOf<String?>(null)

    /** True when this screen started the job for [ask]. It survives a rotation. */
    private var started = false

    /** When the job for [ask] started, on the clock of SystemClock.elapsedRealtime. */
    private var takenAt = 0L

    /**
     * True when the answer is set and the screen stays for the user to read it. A job that
     * SubRead did before is done in less than a second; to open and close that fast looks
     * like a fault. It survives a rotation.
     */
    private var ready by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        started = savedInstanceState?.getBoolean(STARTED) == true
        ready = savedInstanceState?.getBoolean(READY) == true
        val refused = savedInstanceState?.getString(REFUSED)
        // A rotation must not turn a refusal into a second try.
        if (refused != null) refuse(refused) else take(intent)
        val asker = asker()
        setContent { App(ask, notice, asker, ::answer, ready, ::finish) }
    }

    /** The screen is singleTop, so a second ask arrives here, not in a new screen. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (Job.status.value.running) {
            // Refuse politely and keep the job. The job belongs to the ask before this one.
            notice = "SubRead is busy with the job it runs now. The new ask was not taken."
            return
        }
        setIntent(intent)
        started = false
        ready = false
        notice = null
        take(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STARTED, started)
        outState.putBoolean(READY, ready)
        (ask as? AlignRequest.Refused)?.let { outState.putString(REFUSED, it.error) }
    }

    /** Read the ask of another app and, if it is good, run the job for it. */
    private fun take(from: Intent) {
        if (from.action != AlignContract.ACTION) return
        val audio = IntentCompat.getParcelableExtra(from, AlignContract.EXTRA_AUDIO, Uri::class.java)
        val book = IntentCompat.getParcelableExtra(from, AlignContract.EXTRA_BOOK, Uri::class.java)
        val state = Job.status.value
        val read = AlignRequests.read(
            audio?.toString(), book?.toString(), from.getStringExtra(AlignContract.EXTRA_LANGUAGE),
            busy = !started && state.running,
        )
        if (read !is AlignRequest.Accepted) {
            refuse((read as AlignRequest.Refused).error)
            return
        }
        ask = read
        keep(from, audio!!)
        keep(from, book!!)
        // Start at once: the other app sent the user here for this one job, the
        // screen names the asker and the two files, and Stop is on it. After a
        // rotation the job is already there, so do not start a second one.
        if (!state.running && (!started || state.phase == Phase.IDLE)) {
            started = true
            takenAt = SystemClock.elapsedRealtime()
            Job.clear()
            val app = applicationContext
            val language = read.language
            thread(name = "subread-job") { Job.run(app, audio, book, language) }
        }
    }

    /** Say no, now, and stay on the screen so that the user can read why. */
    private fun refuse(error: String) {
        ask = AlignRequest.Refused(error)
        setResult(RESULT_CANCELED, Intent().putExtra(AlignContract.EXTRA_ERROR, error))
    }

    /** Hold the permission when the caller offers it, so that a killed job can start again. */
    private fun keep(from: Intent, uri: Uri) {
        if (from.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Give the asking app its answer. [asked] is the language it asked for. */
    private fun answer(status: JobStatus, asked: String) {
        val srt = status.srt
        if (status.phase != Phase.DONE || srt == null) {
            // Back sends this. The screen stays, so the user can read the error.
            val error = status.detail.ifEmpty { "The job failed." }
            setResult(RESULT_CANCELED, Intent().putExtra(AlignContract.EXTRA_ERROR, error))
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", srt)
        setResult(
            RESULT_OK,
            Intent().setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(AlignContract.EXTRA_CUES, status.cues)
                .putExtra(AlignContract.EXTRA_MATCH_RATE, status.matchRate ?: 0.0)
                .putExtra(AlignContract.EXTRA_LANGUAGE, AlignRequests.languageOf(srt.name, asked)),
        )
        if (ready || SystemClock.elapsedRealtime() - takenAt < QUICK_ANSWER_MS) ready = true else finish()
    }

    /** The name of the app that asked, for the screen. */
    private fun asker(): String {
        val who = callingPackage ?: return "Another app"
        return runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(who, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") // The flags class is Android 13 and later.
                packageManager.getApplicationInfo(who, 0)
            }
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(who)
    }

    private companion object {
        const val STARTED = "ask_started"
        const val REFUSED = "ask_refused"
        const val READY = "ask_ready"

        /** A job that is done sooner than this was done before. The screen stays. */
        const val QUICK_ANSWER_MS = 3_000L
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
private fun App(
    ask: AlignRequest? = null,
    notice: String? = null,
    asker: String = "",
    onAnswer: (JobStatus, String) -> Unit = { _, _ -> },
    ready: Boolean = false,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("picks", Context.MODE_PRIVATE) }
    val status by Job.status.collectAsStateWithLifecycle()

    // An ask of another app gives the picks of this run only. What the user
    // picked by hand stays in the preferences, untouched.
    val accepted = ask as? AlignRequest.Accepted
    var audio by remember(accepted) {
        mutableStateOf(accepted?.audio?.toUri() ?: prefs.getString("audio", null)?.let(Uri::parse))
    }
    var book by remember(accepted) {
        mutableStateOf(accepted?.book?.toUri() ?: prefs.getString("book", null)?.let(Uri::parse))
    }
    var language by remember(accepted) {
        mutableStateOf(accepted?.language ?: prefs.getString("language", "auto")!!)
    }
    if (accepted != null) {
        LaunchedEffect(accepted, status.phase) {
            if (status.phase == Phase.DONE || status.phase == Phase.FAILED) onAnswer(status, accepted.language)
        }
    }

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
                if (status.phase == Phase.IDLE && ask == null) Capabilities()
                if (ask != null) {
                    HorizontalDivider(color = Color.Black)
                    Asked(asker, ask, notice,
                        audio?.let { TranscriptStore.describe(context, it).first },
                        book?.let { TranscriptStore.describe(context, it).first },
                        ready, onBack)
                }
                HorizontalDivider(color = Color.Black)

                // The two files of an ask belong to the app that asked. The user
                // must not change them under it, so the buttons are off.
                val canPick = !status.running && accepted == null
                Pick("Audiobook", audio?.let { TranscriptStore.describe(context, it).first }, canPick) {
                    pickAudio.launch(arrayOf("audio/*", "video/mp4", "application/ogg", "application/octet-stream"))
                }
                Pick("Book", book?.let { TranscriptStore.describe(context, it).first }, canPick) {
                    pickBook.launch(arrayOf("application/epub+zip", "text/plain", "application/zip",
                        "application/octet-stream"))
                }
                LanguagePick(language, canPick) {
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

                // The Google Play build (-PplayStore=true) has no Ko-fi link, and no More apps either.
                if (BuildConfig.DONATE_LINK) {
                    HorizontalDivider(color = Color.Black)
                    MoreApps()
                }

            }
        }
    }
}

/** Who asked for subtitles, for which files, and what SubRead does with the ask. */
@Composable
private fun Asked(
    asker: String, ask: AlignRequest, notice: String?, audio: String?, book: String?,
    ready: Boolean, onBack: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (ask) {
            is AlignRequest.Accepted -> {
                Text("$asker asked for subtitles", fontWeight = FontWeight.Bold)
                Text("Audio: ${audio ?: ask.audio}", style = MaterialTheme.typography.bodySmall)
                Text("Book: ${book ?: ask.book}", style = MaterialTheme.typography.bodySmall)
                Text("Language: ${ask.language}", style = MaterialTheme.typography.bodySmall)
                if (ready) {
                    Text("The subtitles were ready at once: SubRead had the work for this audiobook " +
                        "from an earlier job.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onBack) { Text("Back to $asker with the .srt") }
                } else {
                    Text("SubRead sends the .srt back to $asker when the job is done. " +
                        "Stop, or Back, sends nothing.", style = MaterialTheme.typography.bodySmall)
                }
            }
            is AlignRequest.Refused -> {
                Text("$asker asked for subtitles, and SubRead cannot do it", fontWeight = FontWeight.Bold)
                Text(ask.error, style = MaterialTheme.typography.bodySmall)
                Text("Back returns to $asker with this error.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (notice != null) Text(notice, style = MaterialTheme.typography.bodySmall)
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
        if (BuildConfig.HONJIMAKU_LINK) {
            val context = LocalContext.current
            Text("Japanese book? honjimaku.com is a free library of subtitles for Japanese audiobooks. " +
                "Save the .srt, then add it there, so that other learners do not have to make it again. " +
                "The subtitles hold the words of the book: share them only if you may.",
                style = MaterialTheme.typography.bodySmall)
            // Opens the browser. The app itself still has no network permission.
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "https://honjimaku.com/".toUri())) }
            }) { Text("Open honjimaku.com") }
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

        if (BuildConfig.DONATE_LINK) {
            HorizontalDivider(color = Color.Black)
            Text("SubRead is free and has no ads. If it helps you, you can support its development.",
                style = MaterialTheme.typography.bodySmall)
            val context = LocalContext.current
            // Opens the browser. The app itself still has no network permission.
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "https://ko-fi.com/truex".toUri())) }
            }) { Text("Support on Ko-fi") }
        }
    }
}

/** The other sites and apps of the same author. A tap opens the page in the browser. */
@Composable
private fun MoreApps() {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("More apps", fontWeight = FontWeight.Bold)
        for (app in MORE_APPS) {
            Column(
                Modifier.fillMaxWidth().clickable {
                    // Opens the browser. The app itself still has no network permission.
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, app.url.toUri())) }
                },
            ) {
                Text(app.name, style = MaterialTheme.typography.bodyMedium, textDecoration = TextDecoration.Underline)
                Text(app.line, style = MaterialTheme.typography.bodySmall)
            }
        }
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
