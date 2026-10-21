package org.librehu.disc.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.core.content.ContextCompat
import org.librehu.disc.R
import org.librehu.disc.drive.DiscState
import org.librehu.disc.meta.CdInfo
import org.librehu.disc.player.Playback
import org.librehu.disc.rip.RipOptions
import org.librehu.disc.rip.RipState
import org.librehu.disc.rip.Ripper

/** Tracks to rip (all by default) and the options in force; the rip starts from here. */
@Composable
fun RipScreen(
    cd: DiscState.AudioCd,
    info: CdInfo?,
    onBack: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    val queue = remember(cd, info) { Playback.cdQueue(cd.tracks) }
    var selected by remember(cd) { mutableStateOf(cd.tracks.map { it.number }.toSet()) }
    val o = remember { RipOptions.load(context) }

    fun go() {
        Ripper.start(context, cd.tracks.filter { it.number in selected })
        onBack()
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) go() }

    Row(Modifier.fillMaxSize().background(CarColors.Background).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(0.42f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Pill(stringResource(R.string.up), icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = onBack)
                Text(stringResource(R.string.rip), color = CarColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Medium)
            }
            Card(stringResource(R.string.settings_rip)) {
                Text(
                    listOf(
                        if (o.format == "wav") stringResource(R.string.rip_wav) else stringResource(R.string.rip_flac),
                        if (o.secure) stringResource(R.string.rip_secure) else null,
                        if (o.tags) stringResource(R.string.rip_tags) else null,
                    ).filterNotNull().joinToString(" · "),
                    color = CarColors.TextDim,
                    fontSize = 15.sp,
                )
                Text(
                    if (o.tree == null) stringResource(R.string.rip_dest_music) else stringResource(R.string.rip_dest_folder),
                    color = CarColors.TextDim,
                    fontSize = 15.sp,
                )
                Pill(stringResource(R.string.settings), onClick = onSettings)
            }
            Choice(listOf(true, false), selected.size == cd.tracks.size, { if (it) stringResource(R.string.all) else stringResource(R.string.none) }) {
                selected = if (it) cd.tracks.map { t -> t.number }.toSet() else emptySet()
            }
            Pill(stringResource(R.string.rip_start, selected.size), selected = selected.isNotEmpty(), icon = Icons.Default.Download) {
                if (selected.isEmpty()) return@Pill
                val needs =
                    o.tree == null && Build.VERSION.SDK_INT <= 28 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                if (needs) permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else go()
            }
        }
        LazyColumn(Modifier.weight(0.58f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(queue, key = { it.id }) { q ->
                val n = q.track!!.number
                val on = n in selected
                ListRow(
                    icon = if (on) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    title = q.title,
                    trailing = duration(q.durationMs),
                    selected = false,
                ) { selected = if (on) selected - n else selected + n }
            }
        }
    }
}

/** Progress of the rip (or its result), with Cancel / Close. */
@Composable
fun RipCard(s: RipState) {
    Card(stringResource(R.string.rip)) {
        if (s.running) {
            Text(stringResource(R.string.rip_progress, s.index, s.count) + " · " + s.title, color = CarColors.Text, fontSize = 17.sp, maxLines = 1)
            LinearProgressIndicator(
                progress = { s.fraction },
                modifier = Modifier.fillMaxWidth(),
                color = CarColors.Accent,
                trackColor = CarColors.SurfaceHigh,
            )
            Text(
                stringResource(R.string.rip_stats, "%.1f".format(s.speed), s.corrected, s.unreadable),
                color = CarColors.TextDim,
                fontSize = 14.sp,
            )
            Pill(stringResource(R.string.cancel)) { Ripper.cancel() }
        } else {
            Text(
                if (s.error == null) stringResource(R.string.rip_done, s.done) else stringResource(R.string.rip_failed) + " : " + s.error,
                color = CarColors.Text,
                fontSize = 17.sp,
            )
            if (s.destination.isNotEmpty()) Text(s.destination, color = CarColors.TextDim, fontSize = 14.sp, maxLines = 2)
            if (s.corrected > 0 || s.unreadable > 0) {
                Text(stringResource(R.string.rip_stats_done, s.corrected, s.unreadable), color = CarColors.TextDim, fontSize = 14.sp)
            }
            Pill(stringResource(R.string.close_short)) { Ripper.dismiss() }
        }
    }
}
