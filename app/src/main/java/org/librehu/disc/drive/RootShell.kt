package org.librehu.disc.drive

import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Root commands (`su` of KernelSU / KernelSU Next, Magisk, APatch, SuperSU): the drive node is root-only
 * (`/dev/block/sr0`, 0600) and Android does not mount optical discs.
 */
object RootShell {
    private const val TAG = "LibreHU-Disc"

    /** Output of [cmd] run as root, or null when `su` is missing, refused or the command failed. */
    fun run(
        cmd: String,
        mountMaster: Boolean = false,
        timeoutS: Long = 20,
    ): String? =
        try {
            // -mm: in the global mount namespace, so that a mounted disc is seen by the other apps too.
            val args = if (mountMaster) arrayOf("su", "-mm", "-c", cmd) else arrayOf("su", "-c", cmd)
            val p = ProcessBuilder(*args).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) {
                p.destroy()
                null
            } else if (p.exitValue() == 0) {
                out
            } else {
                Log.w(TAG, "su: $cmd -> ${p.exitValue()}: ${out.trim()}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "su: $cmd: ${e.message}")
            null
        }
}
