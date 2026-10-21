package org.librehu.disc.rip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.librehu.disc.MainActivity
import org.librehu.disc.R
import org.librehu.disc.drive.DiscDrive

/** Keeps a rip going with the screen elsewhere: foreground, notification with the progress and a Cancel button. */
class RipService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // The drive stays watched (disc removed) while no screen of the app is open.
        DiscDrive.start()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.rip), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_CANCEL) Ripper.cancel()
        foreground(notification(Ripper.state.value))
        if (!started) {
            started = true
            scope.launch {
                Ripper.state.collect { s ->
                    if (!s.running) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        if (s.finished) getSystemService(NotificationManager::class.java).notify(ID_DONE, done(s))
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java).notify(ID, notification(s))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        DiscDrive.stop()
        super.onDestroy()
    }

    private fun foreground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
    }

    private fun open() =
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)

    private fun notification(s: RipState): Notification {
        val cancel =
            PendingIntent.getService(this, 1, Intent(this, RipService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
        return Notification
            .Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_mono)
            .setContentTitle(getString(R.string.rip_progress, s.index, s.count))
            .setContentText(s.title)
            .setProgress(1000, (((s.index - 1).coerceAtLeast(0) + s.fraction) / s.count.coerceAtLeast(1) * 1000).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open())
            .addAction(Notification.Action.Builder(null, getString(R.string.cancel), cancel).build())
            .build()
    }

    private fun done(s: RipState): Notification =
        Notification
            .Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_mono)
            .setContentTitle(if (s.error == null) getString(R.string.rip_done, s.done) else getString(R.string.rip_failed))
            .setContentText(s.error ?: s.destination)
            .setContentIntent(open())
            .setAutoCancel(true)
            .build()

    companion object {
        private const val CHANNEL = "rip"
        private const val ID = 2
        private const val ID_DONE = 3
        private const val ACTION_CANCEL = "org.librehu.disc.action.RIP_CANCEL"
    }
}
