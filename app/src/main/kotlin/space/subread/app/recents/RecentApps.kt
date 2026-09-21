package space.subread.app.recents

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Process

/** One app in the switcher. */
class RecentApp(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val lastUsed: Long,
    val launch: Intent,
)

/**
 * What an ordinary app can know about "recent apps".
 *
 * The real recents screen belongs to the system launcher: only it may see live
 * task thumbnails or swipe a task away. What is open to anyone the user grants
 * usage access to is the *order* apps were last in the foreground - which is
 * the part that matters for switching. So: no screenshots, no kill; icon,
 * name, tap to go back to it.
 */
object RecentApps {

    fun hasAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Most recently used first. Skips this app, home screens and anything with no launcher entry. */
    fun list(context: Context, limit: Int = 24): List<RecentApp> {
        val pm = context.packageManager
        val usage = context.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()

        // Foreground events give the true order; the daily aggregates only
        // say "some time today". Look back further only if the day is empty.
        val lastSeen = LinkedHashMap<String, Long>()
        for (window in longArrayOf(DAY, 7 * DAY)) {
            val events = usage.queryEvents(now - window, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) lastSeen[e.packageName] = e.timeStamp
            }
            if (lastSeen.size > 2) break
        }

        val homes = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        ).map { it.activityInfo.packageName }.toSet()

        return lastSeen.entries
            .sortedByDescending { it.value }
            .asSequence()
            .filter { it.key != context.packageName && it.key !in homes }
            .mapNotNull { (pkg, at) ->
                val launch = pm.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
                val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
                // Bring the existing task forward as it was, rather than restarting the app.
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                RecentApp(pkg, pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info), at, launch)
            }
            .take(limit)
            .toList()
    }

    // ------------------------------------------------------------ on / off

    private val entryPoints = listOf(".recents.RecentsLauncherAlias", ".recents.RecentsTile")

    /**
     * The switcher's launcher icon and quick-settings tile exist only while it
     * is switched on. Off, the app is exactly the subtitle tool and nothing else.
     */
    fun setEnabled(context: Context, on: Boolean) {
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        for (name in entryPoints) {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context.packageName, "space.subread.app$name"), state, PackageManager.DONT_KILL_APP)
        }
        context.getSharedPreferences("picks", Context.MODE_PRIVATE).edit().putBoolean("recents", on).apply()
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("picks", Context.MODE_PRIVATE).getBoolean("recents", false)

    private const val DAY = 24 * 60 * 60 * 1000L
}
