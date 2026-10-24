package org.librehu.disc.bridge

import android.content.Context
import android.content.Intent
import org.librehu.disc.R
import org.librehu.disc.meta.CdInfoStore
import org.librehu.disc.player.DiscService
import org.librehu.disc.player.Playback
import org.librehu.disc.rip.RipOptions

/**
 * The disc player for LibreHU's phone app (through LibreHU-service's bridge, [BridgeKitProvider]): play / pause,
 * tracks, and every setting (autoplay, anti-skip, MusicBrainz, ripping). Same labels and same preferences as the
 * app's screen; the rip folder is picked on the head unit.
 */
class BridgeProvider : BridgeKitProvider() {
    override fun pages(context: Context): List<KitPage> {
        // Started with the player; here too when the bridge asks first (its options need it).
        CdInfoStore.start(context)
        val p = context.getSharedPreferences("disc", Context.MODE_PRIVATE)
        val rip = RipOptions.load(context)

        fun saveRip(o: RipOptions) =
            p
                .edit()
                .putString("rip_format", o.format)
                .putBoolean("rip_secure", o.secure)
                .putInt("rip_retries", o.retries)
                .putString("rip_naming", o.naming)
                .putBoolean("rip_folders", o.folders)
                .putBoolean("rip_tags", o.tags)
                .apply()

        fun send(action: String) {
            runCatching { context.startService(Intent(context, DiscService::class.java).setAction(action)) }
        }
        return listOf(
            page(context, "disc", context.getString(R.string.app_name), "disc") {
                if (Playback.state.value.current != null) {
                    button("toggle", s(R.string.play) + " / " + s(R.string.pause)) { send(DiscService.ACTION_TOGGLE) }
                    button("previous", s(R.string.previous)) { send(DiscService.ACTION_PREVIOUS) }
                    button("next", s(R.string.next)) { send(DiscService.ACTION_NEXT) }
                }
                section(s(R.string.settings_playback))
                switch("autoplay", s(R.string.autoplay), p.getBoolean("autoplay", true)) { v -> p.edit().putBoolean("autoplay", v).apply() }
                choice(
                    "antiskip",
                    s(R.string.antiskip),
                    p.getInt("antiskip", Playback.ANTISKIP_DEFAULT),
                    listOf(0, 5, 10, 20, 40).map { it to if (it == 0) s(R.string.off) else "$it s" },
                    s(R.string.antiskip_hint),
                ) { v -> p.edit().putInt("antiskip", v).apply() }
                section(s(R.string.settings_info))
                switch("musicbrainz", s(R.string.musicbrainz), CdInfoStore.enabled(context)) { v ->
                    p.edit().putBoolean("musicbrainz", v).apply()
                    if (v) CdInfoStore.retry()
                }
                switch("mb_keep", s(R.string.mb_keep), CdInfoStore.keep(context)) { v ->
                    p.edit().putBoolean("mb_keep", v).apply()
                    CdInfoStore.keepChanged()
                }
                switch("mb_offline", s(R.string.mb_offline), CdInfoStore.offline(context), s(R.string.mb_offline_hint)) { v ->
                    p.edit().putBoolean("mb_offline", v).apply()
                    if (!v) CdInfoStore.retry()
                }
                button("mb_lookup", s(R.string.mb_lookup_now)) { CdInfoStore.lookUpPending() }
                button("mb_clear", s(R.string.mb_clear), confirm = true) { CdInfoStore.clear() }
                section(s(R.string.settings_rip))
                choice("rip_format", s(R.string.rip_format), rip.format, listOf("flac" to s(R.string.rip_flac), "wav" to s(R.string.rip_wav))) { v ->
                    saveRip(rip.copy(format = v))
                }
                switch("rip_secure", s(R.string.rip_secure), rip.secure, s(R.string.rip_secure_hint)) { v -> saveRip(rip.copy(secure = v)) }
                choice("rip_retries", s(R.string.rip_retries), rip.retries, listOf(3, 10, 30).map { it to "$it" }) { v -> saveRip(rip.copy(retries = v)) }
                choice(
                    "rip_naming",
                    s(R.string.rip_naming),
                    rip.naming,
                    listOf("nt" to s(R.string.rip_naming_nt), "nat" to s(R.string.rip_naming_nat), "at" to s(R.string.rip_naming_at)),
                ) { v -> saveRip(rip.copy(naming = v)) }
                switch("rip_folders", s(R.string.rip_folders), rip.folders) { v -> saveRip(rip.copy(folders = v)) }
                switch("rip_tags", s(R.string.rip_tags), rip.tags) { v -> saveRip(rip.copy(tags = v)) }
            },
        )
    }
}
