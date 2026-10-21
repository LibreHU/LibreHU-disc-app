package org.librehu.disc.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Eject
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.librehu.disc.R
import org.librehu.disc.drive.DiscDrive
import org.librehu.disc.drive.DiscState
import org.librehu.disc.meta.CdInfo
import org.librehu.disc.meta.CdInfoStore
import org.librehu.disc.player.PlayerState
import org.librehu.disc.player.Playback
import org.librehu.disc.rip.Ripper
import java.io.File

internal val AUDIO = setOf("mp3", "flac", "ogg", "oga", "opus", "m4a", "aac", "wav", "mka")

internal fun prefs(context: Context) = context.getSharedPreferences("disc", Context.MODE_PRIVATE)

@Composable
fun DiscScreen(
    openFile: (File) -> Unit,
    openDvd: () -> Unit,
) {
    val context = LocalContext.current
    val disc by DiscDrive.state.collectAsStateWithLifecycle()
    val player by Playback.state.collectAsStateWithLifecycle()
    val info by CdInfoStore.info.collectAsStateWithLifecycle()
    val rip by Ripper.state.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf("main") }

    // A CD put in while nothing plays starts at its first track (option); not while it is being ripped.
    LaunchedEffect(disc) {
        val s = disc
        if (s is DiscState.AudioCd && prefs(context).getBoolean("autoplay", true) && player.current == null && !Ripper.busy) {
            Playback.playQueue(Playback.cdQueue(s.tracks), 0)
        }
    }

    var retro by remember { mutableStateOf(prefs(context).getBoolean("retro", false)) }
    var color by remember {
        mutableStateOf(runCatching { RetroColor.valueOf(prefs(context).getString("retro_color", null)!!) }.getOrDefault(RetroColor.AMBER))
    }
    val audioCd = disc as? DiscState.AudioCd
    if (page == "settings") {
        SettingsScreen(onBack = { page = "main" })
        return
    }
    if (page == "rip" && audioCd != null && !rip.running) {
        RipScreen(audioCd, info, onBack = { page = "main" }, onSettings = { page = "settings" })
        return
    }
    if (retro) {
        RetroScreen(
            disc,
            player,
            color,
            onColor = {
                color = RetroColor.entries[(color.ordinal + 1) % RetroColor.entries.size]
                prefs(context).edit().putString("retro_color", color.name).apply()
            },
            onModern = {
                retro = false
                prefs(context).edit().putBoolean("retro", false).apply()
            },
            openFile = openFile,
            openDvd = openDvd,
        )
        return
    }

    Row(
        Modifier.fillMaxSize().background(CarColors.Background).padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(0.42f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.app_name), color = CarColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Medium)
            StatusCard(disc, info)
            if (rip.running || rip.finished) RipCard(rip) else NowPlaying(player)
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill(stringResource(R.string.eject), icon = Icons.Default.Eject) {
                    Playback.eject()
                }
                if (disc == DiscState.TrayOpen || disc == DiscState.NoDisc) {
                    Pill(stringResource(R.string.close_short)) { DiscDrive.closeTray() }
                }
                Pill(stringResource(R.string.reload), icon = Icons.Default.Refresh) { DiscDrive.refresh() }
                if (audioCd != null && !rip.running) {
                    Pill(stringResource(R.string.rip), icon = Icons.Default.Download) { page = "rip" }
                }
                Pill(stringResource(R.string.settings), icon = Icons.Default.Settings) { page = "settings" }
                Pill(stringResource(R.string.retro), icon = Icons.Default.Radio) {
                    retro = true
                    prefs(context).edit().putBoolean("retro", true).apply()
                }
            }
        }
        Box(Modifier.weight(0.58f).fillMaxHeight()) {
            when (val s = disc) {
                is DiscState.AudioCd -> TrackList(s, player, info)
                is DiscState.DataDisc -> DataDisc(s, player, openFile, openDvd)
                else -> Hint(disc)
            }
        }
    }
}

@Composable
private fun StatusCard(
    disc: DiscState,
    info: CdInfo?,
) {
    val cdInfo = info?.takeIf { disc is DiscState.AudioCd && disc.discId == it.discId }
    val (title, detail) =
        when (disc) {
            DiscState.NoDrive -> stringResource(R.string.no_drive) to stringResource(R.string.no_drive_hint)
            is DiscState.NoAccess -> stringResource(R.string.no_access) to stringResource(R.string.no_access_hint, disc.device)
            DiscState.TrayOpen -> stringResource(R.string.tray_open) to ""
            DiscState.NoDisc -> stringResource(R.string.no_disc) to ""
            DiscState.Reading -> stringResource(R.string.reading) to ""
            is DiscState.AudioCd ->
                (cdInfo?.album?.takeIf { it.isNotBlank() } ?: stringResource(R.string.audio_cd)) to
                    listOfNotNull(cdInfo?.artist?.takeIf { it.isNotBlank() }, cdInfo?.year?.takeIf { it.isNotBlank() })
                        .joinToString(" · ", postfix = if (cdInfo != null) "\n" else "") +
                    stringResource(R.string.cd_detail, disc.tracks.size, duration(disc.tracks.sumOf { it.durationMs })) +
                    if (disc.dataTracks > 0) " · " + stringResource(R.string.data_tracks, disc.dataTracks) else ""
            is DiscState.DataDisc ->
                stringResource(
                    when {
                        disc.dvdVideo -> R.string.dvd_video
                        disc.dvd -> R.string.data_dvd
                        else -> R.string.data_cd
                    },
                ) to "%.0f Mo".format(disc.capacity / 1048576.0)
        }
    Card(stringResource(R.string.drive)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val cover = cdInfo?.cover
            if (cover != null) {
                Image(
                    cover.asImageBitmap(),
                    null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Icon(Icons.Default.Album, null, tint = CarColors.Accent, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, color = CarColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                if (detail.isNotEmpty()) Text(detail, color = CarColors.TextDim, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun NowPlaying(p: PlayerState) {
    val item = p.current
    Card(stringResource(R.string.now_playing)) {
        Text(
            item?.title ?: stringResource(R.string.nothing_playing),
            color = CarColors.Text,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item != null) Text(item.subtitle, color = CarColors.TextDim, fontSize = 15.sp, maxLines = 1)
        p.error?.let { Text(stringResource(R.string.play_error, it), color = CarColors.TextDim, fontSize = 14.sp) }
        val dur = item?.durationMs ?: 0
        var dragging by remember { mutableStateOf(false) }
        var drag by remember { mutableFloatStateOf(0f) }
        Slider(
            value = if (dragging) drag else if (dur > 0) (p.positionMs.toFloat() / dur).coerceIn(0f, 1f) else 0f,
            onValueChange = {
                dragging = true
                drag = it
            },
            onValueChangeFinished = {
                dragging = false
                if (dur > 0) Playback.seekTo((drag * dur).toLong())
            },
            enabled = dur > 0,
            colors = SliderDefaults.colors(thumbColor = CarColors.Accent, activeTrackColor = CarColors.Accent),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${duration(p.positionMs)} / ${duration(dur)}", color = CarColors.TextDim, fontSize = 15.sp, modifier = Modifier.weight(1f))
            RoundButton(Icons.Default.SkipPrevious) { Playback.previous() }
            Spacer(Modifier.width(12.dp))
            RoundButton(if (p.playing) Icons.Default.Pause else Icons.Default.PlayArrow, big = true) { Playback.toggle() }
            Spacer(Modifier.width(12.dp))
            RoundButton(Icons.Default.SkipNext) { Playback.next() }
        }
    }
}

@Composable
private fun TrackList(
    cd: DiscState.AudioCd,
    p: PlayerState,
    info: CdInfo?,
) {
    val queue = remember(cd, info) { Playback.cdQueue(cd.tracks) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        itemsIndexed(queue, key = { _, q -> q.id }) { i, q ->
            val current = p.current?.id == q.id
            ListRow(
                icon = Icons.Default.MusicNote,
                title = q.title,
                trailing = duration(q.durationMs),
                selected = current,
            ) { if (current) Playback.toggle() else Playback.playQueue(queue, i) }
        }
    }
}

@Composable
private fun DataDisc(
    d: DiscState.DataDisc,
    p: PlayerState,
    openFile: (File) -> Unit,
    openDvd: () -> Unit,
) {
    val root = d.mountPoint
    var dir by remember(root) { mutableStateOf(root?.let { File(it) }) }
    val entries =
        remember(dir) {
            dir?.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })).orEmpty()
        }
    val audio = entries.filter { it.isFile && it.extension.lowercase() in AUDIO }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        if (d.dvdVideo) {
            item {
                Card(stringResource(R.string.dvd_video)) {
                    Text(stringResource(R.string.dvd_hint), color = CarColors.TextDim, fontSize = 15.sp)
                    Pill(stringResource(R.string.play_dvd), selected = true, icon = Icons.Default.Movie, onClick = openDvd)
                }
            }
        }
        if (root == null) {
            item { Hint(text = stringResource(R.string.mount_failed)) }
            return@LazyColumn
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (dir?.path != root) {
                    Pill(stringResource(R.string.up), icon = Icons.AutoMirrored.Filled.ArrowBack) { dir = dir?.parentFile }
                }
                Text(
                    dir?.path?.removePrefix(root)?.ifEmpty { "/" } ?: "/",
                    color = CarColors.TextDim,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (audio.isNotEmpty()) {
                    Pill(stringResource(R.string.play_all), icon = Icons.Default.PlayArrow) {
                        Playback.playQueue(Playback.fileQueue(audio), 0)
                    }
                }
            }
        }
        items(entries, key = { it.path }) { f ->
            val isAudio = f.isFile && f.extension.lowercase() in AUDIO
            ListRow(
                icon =
                    when {
                        f.isDirectory -> Icons.Default.Folder
                        isAudio -> Icons.Default.MusicNote
                        else -> Icons.AutoMirrored.Filled.InsertDriveFile
                    },
                title = f.name,
                trailing = if (f.isFile) size(f.length()) else "",
                selected = p.current?.file == f,
            ) {
                when {
                    f.isDirectory -> dir = f
                    isAudio -> Playback.playQueue(Playback.fileQueue(audio), audio.indexOf(f))
                    else -> openFile(f)
                }
            }
        }
    }
}

@Composable
private fun Hint(
    disc: DiscState? = null,
    text: String? = null,
) {
    val msg =
        text ?: when (disc) {
            DiscState.NoDrive -> stringResource(R.string.no_drive_hint)
            is DiscState.NoAccess -> stringResource(R.string.no_access_hint, disc.device)
            DiscState.TrayOpen -> stringResource(R.string.tray_open_hint)
            DiscState.NoDisc -> stringResource(R.string.no_disc_hint)
            else -> stringResource(R.string.reading)
        }
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(CarColors.Surface).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Album, null, tint = CarColors.TextDim, modifier = Modifier.size(96.dp))
        Spacer(Modifier.size(16.dp))
        Text(msg, color = CarColors.TextDim, fontSize = 18.sp)
        if (disc is DiscState.TrayOpen || disc is DiscState.NoDisc) {
            Spacer(Modifier.size(16.dp))
            Pill(stringResource(R.string.close_tray)) { DiscDrive.closeTray() }
        }
    }
}

@Composable
internal fun ListRow(
    icon: ImageVector,
    title: String,
    trailing: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) CarColors.Accent else CarColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (selected) CarColors.OnAccent else CarColors.Text
        Icon(icon, null, tint = if (selected) CarColors.OnAccent else CarColors.Accent, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, color = color, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(trailing, color = if (selected) CarColors.OnAccent else CarColors.TextDim, fontSize = 15.sp)
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    big: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(if (big) 64.dp else 52.dp)
            .clip(CircleShape)
            .background(if (big) CarColors.Accent else CarColors.SurfaceHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (big) CarColors.OnAccent else CarColors.Text, modifier = Modifier.size(if (big) 34.dp else 28.dp))
    }
}

@Composable
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = CarColors.Text, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = CarColors.Accent, checkedThumbColor = CarColors.OnAccent),
        )
    }
}

@Composable
internal fun Card(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(CarColors.Surface)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = CarColors.Accent, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        content()
    }
}

@Composable
internal fun Pill(
    label: String,
    selected: Boolean = false,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) CarColors.Accent else CarColors.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (selected) CarColors.OnAccent else CarColors.Text
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = color, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

internal fun duration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

internal fun size(bytes: Long): String =
    when {
        bytes >= 1L shl 30 -> "%.1f Go".format(bytes / 1073741824.0)
        bytes >= 1L shl 20 -> "%.1f Mo".format(bytes / 1048576.0)
        else -> "%d Ko".format(bytes / 1024)
    }
