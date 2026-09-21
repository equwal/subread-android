package space.subread.app.job

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import space.subread.app.MainActivity
import space.subread.app.R
import kotlin.concurrent.thread

/**
 * Keeps the job alive with the screen off and the app in the background.
 *
 * A foreground service of type "special use": transcription runs for hours,
 * and the other candidate types (data sync, media processing) are cut off by
 * the system after six.
 */
class AlignService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            Job.cancel()
            return START_NOT_STICKY
        }
        val audio = intent?.getStringExtra(EXTRA_AUDIO)
        val book = intent?.getStringExtra(EXTRA_BOOK)
        if (audio == null || book == null || Job.status.value.running) {
            if (!Job.status.value.running) stopSelf()
            return START_NOT_STICKY
        }
        val language = intent.getStringExtra(EXTRA_LANGUAGE) ?: "auto"

        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(Job.status.value),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )

        // Without this the CPU sleeps a few minutes after the screen goes off,
        // and a four-hour job becomes a four-day one.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "subread:align")
            .apply { acquire(24 * 60 * 60 * 1000L) }

        scope.launch {
            val manager = getSystemService(NotificationManager::class.java)
            Job.status.collectLatest { manager.notify(NOTIFICATION_ID, notification(it)) }
        }

        thread(name = "subread-job") {
            Job.run(applicationContext, Uri.parse(audio), Uri.parse(book), language)
            wakeLock?.let { if (it.isHeld) it.release() }
            // Leave the final notification up: "done" is worth seeing hours later.
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, getString(R.string.channel_progress),
            NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(status: JobStatus): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, AlignService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val eta = status.etaSeconds?.let { " · about ${Job.clock(it.toDouble())} left" }.orEmpty()
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status.detail + eta)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(status.running)
            .apply {
                if (status.running) {
                    setProgress(1000, (status.fraction * 1000).toInt(), status.phase == Phase.PREPARING)
                    addAction(0, getString(R.string.stop), cancel)
                }
            }
            .build()
    }

    companion object {
        private const val CHANNEL = "progress"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_CANCEL = "space.subread.app.CANCEL"
        private const val EXTRA_AUDIO = "audio"
        private const val EXTRA_BOOK = "book"
        private const val EXTRA_LANGUAGE = "language"

        fun start(context: Context, audio: Uri, book: Uri, language: String) {
            val intent = Intent(context, AlignService::class.java)
                .putExtra(EXTRA_AUDIO, audio.toString())
                .putExtra(EXTRA_BOOK, book.toString())
                .putExtra(EXTRA_LANGUAGE, language)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
