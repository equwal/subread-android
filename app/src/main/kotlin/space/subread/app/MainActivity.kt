package space.subread.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.subread.app.job.AlignService
import space.subread.app.job.Job
import space.subread.app.job.JobStatus
import space.subread.app.job.Phase
import space.subread.app.job.TranscriptStore
import space.subread.app.recents.RecentApps
import space.subread.app.recents.RecentsActivity
import java.io.File

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
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    MaterialTheme(colorScheme = Paper) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("SubRead", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Times an audiobook against its ebook, on this device. Nothing is uploaded. " +
                        "The result is an .srt for Hoshi Reader's read-along.",
                    style = MaterialTheme.typography.bodyMedium,
                )
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
                            if (Build.VERSION.SDK_INT >= 33) {
                                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            AlignService.start(context, a!!, b!!, language)
                        },
                    ) { Text(if (resumable) "Continue" else "Start") }

                    if (a == null || b == null) {
                        Text("Pick both files to begin. A long book takes hours; it carries on with the " +
                            "screen off, and picks up where it stopped if interrupted.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Outcome(status,
                        onSave = { status.srt?.let { saveSrt.launch(it.name) } },
                        onShare = { status.srt?.let { share(context, it) } })
                }

                HorizontalDivider(color = Color.Black)
                RecentsSetting()
            }
        }
    }
}

/** Nothing to do with subtitles: an optional recent-apps switcher, off unless asked for. */
@Composable
private fun RecentsSetting() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(RecentApps.isEnabled(context)) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = on, onCheckedChange = {
                on = it
                RecentApps.setEnabled(context, it)
                if (it && !RecentApps.hasAccess(context)) {
                    context.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            })
            Text("Recent apps switcher", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        }
        Text(
            "Adds a \"Recent apps\" icon and a quick-settings tile that show your recent apps as cards to " +
                "swipe through. It needs usage access, which you grant on the screen that opens.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (on) {
            OutlinedButton(onClick = { context.startActivity(Intent(context, RecentsActivity::class.java)) }) {
                Text("Open it now")
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
private fun Outcome(status: JobStatus, onSave: () -> Unit, onShare: () -> Unit) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onSave) { Text("Save .srt") }
                OutlinedButton(onClick = onShare) { Text("Share") }
            }
            Text("In Hoshi Reader: long-press the book, Match, and choose the saved .srt.",
                style = MaterialTheme.typography.bodySmall)
        }
        Phase.FAILED -> Text("Failed: ${status.detail}", fontWeight = FontWeight.Bold)
        Phase.CANCELLED -> Text(status.detail)
        else -> {}
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
