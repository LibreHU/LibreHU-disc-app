package org.librehu.disc.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaDescription
import android.media.browse.MediaBrowser
import android.os.Build
import android.os.Bundle
import android.service.media.MediaBrowserService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.librehu.disc.MainActivity
import org.librehu.disc.R
import org.librehu.disc.drive.DiscDrive
import org.librehu.disc.drive.DiscState

/**
 * Playback in the background: media session token, notification, and the browser tree that LibreHU EasySource and
 * Android Auto-style clients list (the tracks of the CD in the drive).
 */
class DiscService : MediaBrowserService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        Playback.init(this)
        sessionToken = Playback.session.sessionToken
        DiscDrive.start()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW),
        )
        scope.launch {
            Playback.state.combine(DiscDrive.state) { p, _ -> p.current to p.playing }.distinctUntilChanged().collect { refresh() }
        }
        scope.launch {
            DiscDrive.state.collect { s ->
                notifyChildrenChanged(ROOT)
                // Disc removed or changed under a playing CD track: stop.
                val cur = Playback.state.value.current
                if (cur?.track != null && s !is DiscState.AudioCd) Playback.stop()
            }
        }
        scope.launch {
            while (isActive) {
                Playback.tick()
                delay(500)
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // Started with startForegroundService(): foreground at once, even if playback already stopped.
        foreground(notification())
        refresh()
        return START_NOT_STICKY
    }

    private fun foreground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
        foreground = true
    }

    override fun onDestroy() {
        scope.cancel()
        DiscDrive.stop()
        super.onDestroy()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?,
    ): BrowserRoot = BrowserRoot(ROOT, null)

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowser.MediaItem>>,
    ) {
        val items =
            when (val s = DiscDrive.state.value) {
                is DiscState.AudioCd ->
                    Playback.cdQueue(s.tracks).map { q ->
                        MediaBrowser.MediaItem(
                            MediaDescription
                                .Builder()
                                .setMediaId(q.id)
                                .setTitle(q.title)
                                .setSubtitle(q.subtitle)
                                .build(),
                            MediaBrowser.MediaItem.FLAG_PLAYABLE,
                        )
                    }
                else -> emptyList()
            }
        result.sendResult(items.toMutableList())
    }

    /** Foreground with the notification while something plays; notification only when paused; nothing when idle. */
    private fun refresh() {
        val s = Playback.state.value
        val nm = getSystemService(NotificationManager::class.java)
        if (s.current == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            stopSelf()
            return
        }
        val n = notification()
        if (s.playing) {
            foreground(n)
        } else {
            if (foreground) stopForeground(STOP_FOREGROUND_DETACH)
            foreground = false
            nm.notify(NOTIFICATION_ID, n)
        }
    }

    private fun notification(): Notification {
        val s = Playback.state.value
        val item = s.current
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )

        fun action(
            icon: Int,
            title: Int,
            act: String,
        ) = Notification.Action
            .Builder(
                android.graphics.drawable.Icon.createWithResource(this, icon),
                getString(title),
                PendingIntent.getBroadcast(this, act.hashCode(), Intent(act).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE),
            ).build()
        return Notification
            .Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_mono)
            .setContentTitle(item?.title ?: getString(R.string.app_name))
            .setContentText(item?.subtitle.orEmpty())
            .setContentIntent(open)
            .setOngoing(s.playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(R.drawable.ic_prev, R.string.previous, ACTION_PREVIOUS))
            .addAction(
                if (s.playing) {
                    action(R.drawable.ic_pause, R.string.pause, ACTION_TOGGLE)
                } else {
                    action(R.drawable.ic_play, R.string.play, ACTION_TOGGLE)
                },
            ).addAction(action(R.drawable.ic_next, R.string.next, ACTION_NEXT))
            .setStyle(
                Notification
                    .MediaStyle()
                    .setMediaSession(Playback.session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            ).build()
    }

    companion object {
        private const val ROOT = "root"
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 1
        const val ACTION_TOGGLE = "org.librehu.disc.action.TOGGLE"
        const val ACTION_NEXT = "org.librehu.disc.action.NEXT"
        const val ACTION_PREVIOUS = "org.librehu.disc.action.PREVIOUS"
    }
}
