package org.librehu.disc.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.librehu.disc.R
import org.librehu.disc.meta.CdInfoStore
import org.librehu.disc.player.Playback
import org.librehu.disc.rip.RipOptions

/** Playback (autoplay, anti-skip), disc info (MusicBrainz online / kept offline / offline mode) and rip options. */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val p = remember { prefs(context) }
    BackHandler(onBack = onBack)

    var autoplay by remember { mutableStateOf(p.getBoolean("autoplay", true)) }
    var antiskip by remember { mutableStateOf(p.getInt("antiskip", Playback.ANTISKIP_DEFAULT)) }
    var online by remember { mutableStateOf(CdInfoStore.enabled(context)) }
    var keep by remember { mutableStateOf(CdInfoStore.keep(context)) }
    var offline by remember { mutableStateOf(CdInfoStore.offline(context)) }
    val stats by CdInfoStore.stats.collectAsStateWithLifecycle()
    var rip by remember { mutableStateOf(RipOptions.load(context)) }

    fun saveRip(o: RipOptions) {
        rip = o
        p
            .edit()
            .putString("rip_format", o.format)
            .putBoolean("rip_secure", o.secure)
            .putInt("rip_retries", o.retries)
            .putString("rip_naming", o.naming)
            .putBoolean("rip_folders", o.folders)
            .putBoolean("rip_tags", o.tags)
            .putString("rip_tree", o.tree)
            .apply()
    }

    val pickFolder =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                saveRip(rip.copy(tree = uri.toString()))
            }
        }

    Column(Modifier.fillMaxSize().background(CarColors.Background).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Pill(stringResource(R.string.up), icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = onBack)
            Text(stringResource(R.string.settings), color = CarColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Medium)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            item {
                Card(stringResource(R.string.settings_playback)) {
                    SwitchRow(stringResource(R.string.autoplay), autoplay) {
                        autoplay = it
                        p.edit().putBoolean("autoplay", it).apply()
                    }
                    Text(stringResource(R.string.antiskip), color = CarColors.Text, fontSize = 17.sp)
                    Text(stringResource(R.string.antiskip_hint), color = CarColors.TextDim, fontSize = 14.sp)
                    Choice(listOf(0, 5, 10, 20, 40), antiskip, { if (it == 0) stringResource(R.string.off) else "$it s" }) {
                        antiskip = it
                        p.edit().putInt("antiskip", it).apply()
                    }
                }
            }
            item {
                Card(stringResource(R.string.settings_info)) {
                    SwitchRow(stringResource(R.string.musicbrainz), online) {
                        online = it
                        p.edit().putBoolean("musicbrainz", it).apply()
                        if (it) CdInfoStore.retry()
                    }
                    SwitchRow(stringResource(R.string.mb_keep), keep) {
                        keep = it
                        p.edit().putBoolean("mb_keep", it).apply()
                        CdInfoStore.keepChanged()
                    }
                    SwitchRow(stringResource(R.string.mb_offline), offline) {
                        offline = it
                        p.edit().putBoolean("mb_offline", it).apply()
                        if (!it) CdInfoStore.retry()
                    }
                    Text(stringResource(R.string.mb_offline_hint), color = CarColors.TextDim, fontSize = 14.sp)
                    Text(
                        stringResource(R.string.mb_stats, stats.discs, size(stats.bytes), stats.pending),
                        color = CarColors.TextDim,
                        fontSize = 15.sp,
                    )
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (stats.pending > 0 && online && !offline) {
                            Pill(stringResource(R.string.mb_lookup_now), icon = Icons.Default.Refresh) { CdInfoStore.lookUpPending() }
                        }
                        Pill(stringResource(R.string.mb_clear), icon = Icons.Default.Delete) { CdInfoStore.clear() }
                    }
                }
            }
            item {
                Card(stringResource(R.string.settings_rip)) {
                    Text(stringResource(R.string.rip_format), color = CarColors.Text, fontSize = 17.sp)
                    Choice(listOf("flac", "wav"), rip.format, { if (it == "flac") stringResource(R.string.rip_flac) else stringResource(R.string.rip_wav) }) {
                        saveRip(rip.copy(format = it))
                    }
                    SwitchRow(stringResource(R.string.rip_secure), rip.secure) { saveRip(rip.copy(secure = it)) }
                    Text(stringResource(R.string.rip_secure_hint), color = CarColors.TextDim, fontSize = 14.sp)
                    Text(stringResource(R.string.rip_retries), color = CarColors.Text, fontSize = 17.sp)
                    Choice(listOf(3, 10, 30), rip.retries, { "$it" }) { saveRip(rip.copy(retries = it)) }
                    Text(stringResource(R.string.rip_naming), color = CarColors.Text, fontSize = 17.sp)
                    Choice(listOf("nt", "nat", "at"), rip.naming, {
                        when (it) {
                            "nat" -> stringResource(R.string.rip_naming_nat)
                            "at" -> stringResource(R.string.rip_naming_at)
                            else -> stringResource(R.string.rip_naming_nt)
                        }
                    }) { saveRip(rip.copy(naming = it)) }
                    SwitchRow(stringResource(R.string.rip_folders), rip.folders) { saveRip(rip.copy(folders = it)) }
                    SwitchRow(stringResource(R.string.rip_tags), rip.tags) { saveRip(rip.copy(tags = it)) }
                    Text(stringResource(R.string.rip_destination), color = CarColors.Text, fontSize = 17.sp)
                    Text(
                        rip.tree?.let { Uri.decode(Uri.parse(it).lastPathSegment ?: it) } ?: stringResource(R.string.rip_dest_music),
                        color = CarColors.TextDim,
                        fontSize = 15.sp,
                    )
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Pill(stringResource(R.string.rip_dest_music), selected = rip.tree == null) { saveRip(rip.copy(tree = null)) }
                        Pill(stringResource(R.string.rip_pick_folder), selected = rip.tree != null, icon = Icons.Default.Folder) {
                            runCatching { pickFolder.launch(null) }
                        }
                    }
                }
            }
        }
    }
}

/** One of [options], as pills. */
@Composable
internal fun <T> Choice(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (o in options) Pill(label(o), selected = o == selected) { onSelect(o) }
    }
}
