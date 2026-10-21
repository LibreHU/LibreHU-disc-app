package org.librehu.disc.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Buttons of the notification. */
class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        Playback.init(context)
        when (intent.action) {
            DiscService.ACTION_TOGGLE -> Playback.toggle()
            DiscService.ACTION_NEXT -> Playback.next()
            DiscService.ACTION_PREVIOUS -> Playback.previous()
        }
    }
}
