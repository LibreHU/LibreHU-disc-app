package org.librehu.disc

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.Color
import org.librehu.disc.drive.DiscDrive
import org.librehu.disc.player.Playback
import org.librehu.disc.ui.CarColors
import org.librehu.disc.ui.CarPalette
import org.librehu.disc.ui.CarTheme
import org.librehu.disc.ui.DiscScreen
import org.librehu.disc.ui.ThemeFollower
import java.io.File

/** The drive, the disc and the player. */
class MainActivity : ComponentActivity() {
    private val theme =
        lazy {
            ThemeFollower(this) { dark, accent ->
                CarColors.palette = CarPalette.of(dark, if (accent != 0) Color(accent) else null)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Playback.init(this)
        theme.value.start()
        setContent {
            CarTheme {
                DiscScreen(
                    openFile = { open(DiscFileProvider.fileUri(it), mime(it)) },
                    openDvd = { open(DiscFileProvider.RAW, "video/*", preferVlc = true) },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        DiscDrive.start()
    }

    override fun onStop() {
        DiscDrive.stop()
        super.onStop()
    }

    override fun onDestroy() {
        theme.value.stop()
        super.onDestroy()
    }

    private fun mime(f: File): String =
        android.webkit.MimeTypeMap
            .getSingleton()
            .getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"

    /** In another app (VLC first for a DVD), with a read grant on the disc file. */
    private fun open(
        uri: Uri,
        type: String,
        preferVlc: Boolean = false,
    ) {
        val view =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, type)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (preferVlc) {
            val vlc = Intent(view).setPackage(VLC)
            if (vlc.resolveActivity(packageManager) != null) {
                Playback.pause()
                startActivity(vlc)
                return
            }
        }
        try {
            Playback.pause()
            startActivity(Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val VLC = "org.videolan.vlc"
    }
}
