package space.subread.app.recents

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.TileService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

/**
 * A recent-apps switcher in the stock Android manner: a row of cards, most
 * recent first, that slides sideways and settles on one. Tap to go there,
 * long-press for the app's settings page.
 */
class RecentsActivity : ComponentActivity() {

    private var apps by mutableStateOf<List<RecentApp>>(emptyList())
    private var allowed by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Screen() }
    }

    override fun onResume() {
        super.onResume()
        allowed = RecentApps.hasAccess(this)
        apps = if (allowed) RecentApps.list(this) else emptyList()
    }

    private fun open(app: RecentApp) {
        startActivity(app.launch)
        finish()
    }

    private fun details(app: RecentApp) {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")))
    }

    @Composable
    private fun Screen() {
        MaterialTheme(colorScheme = lightColorScheme(
            primary = Color.Black, onPrimary = Color.White, background = Color.White,
            onBackground = Color.Black, surface = Color.White, onSurface = Color.Black,
        )) {
            Surface(Modifier.fillMaxSize()) {
                Box(Modifier.safeDrawingPadding().fillMaxSize(), contentAlignment = Alignment.Center) {
                    when {
                        !allowed -> Column(
                            Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("To list recent apps, SubRead needs usage access.", textAlign = TextAlign.Center)
                            Button(onClick = { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) {
                                Text("Open the setting")
                            }
                        }
                        apps.isEmpty() -> Text("Nothing recent.")
                        else -> Cards()
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Cards() {
        val state = rememberLazyListState()
        val screen = LocalConfiguration.current.screenWidthDp.dp
        val card = screen * 0.62f
        LazyRow(
            state = state,
            // One card settles in the middle; its neighbours peek in at the sides.
            flingBehavior = rememberSnapFlingBehavior(state),
            contentPadding = PaddingValues(horizontal = (screen - card) / 2),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(apps, key = { it.packageName }) { app ->
                val icon = remember(app.packageName) { app.icon.toBitmap(192, 192).asImageBitmap() }
                Column(
                    Modifier
                        .size(width = card, height = card * 1.35f)
                        .border(2.dp, Color.Black, RoundedCornerShape(18.dp))
                        .combinedClickable(onClick = { open(app) }, onLongClick = { details(app) })
                        .padding(18.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(icon, contentDescription = null, modifier = Modifier.size(96.dp))
                    Text(app.label, fontWeight = FontWeight.Bold, maxLines = 2, textAlign = TextAlign.Center,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp).fillMaxWidth())
                    Text(ago(app.lastUsed), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }

    private fun ago(at: Long): String {
        val minutes = (System.currentTimeMillis() - at) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 48 * 60 -> "${minutes / 60} h ago"
            else -> "${minutes / (24 * 60)} days ago"
        }
    }
}

/** Quick-settings tile: the switcher from anywhere, one swipe and a tap. */
class RecentsTile : TileService() {
    override fun onClick() {
        val intent = Intent(this, RecentsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(android.app.PendingIntent.getActivity(
                this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
