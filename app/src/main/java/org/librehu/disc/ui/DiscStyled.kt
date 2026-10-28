package org.librehu.disc.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Eject
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.librehu.disc.R
import org.librehu.disc.drive.DiscDrive
import org.librehu.disc.drive.DiscState
import org.librehu.disc.meta.CdInfo
import org.librehu.disc.player.Playback
import org.librehu.disc.player.PlayerState
import org.librehu.disc.rip.RipState
import java.io.File

/*
 * The disc player in LibreHU Launcher's two car styles. Factory (Modularer Infotainment-Baukasten): title, artist and
 * album with their icons on the left, the cover on the right (a glow under it while playing), the progress on one
 * line, soft keys Eject / Browse / previous / play / next / Rip / Setup; Browse lists the tracks or the files under a
 * line of the accent colour. Ambient (LHU-Link): tabs Playback / Tracks, the framed cover, a framed progress bar,
 * previous / play / next keys side by side, and the bar Back / Eject / Options.
 */

/** What both styles need from [DiscScreen]. */
internal class DiscScreens(
    val disc: DiscState,
    val player: PlayerState,
    val info: CdInfo?,
    val rip: RipState,
    val openFile: (File) -> Unit,
    val openDvd: () -> Unit,
    val onRip: () -> Unit,
    val onSettings: () -> Unit,
)

/** The album when the CD is known, else what the drive holds. */
@Composable
private fun discTitle(d: DiscScreens): String {
    val cd = d.disc as? DiscState.AudioCd
    val known = d.info?.takeIf { cd != null && cd.discId == it.discId }
    return when (val s = d.disc) {
        is DiscState.AudioCd -> known?.album?.takeIf { it.isNotBlank() } ?: stringResource(R.string.audio_cd)
        is DiscState.DataDisc -> stringResource(if (s.dvdVideo) R.string.dvd_video else if (s.dvd) R.string.data_dvd else R.string.data_cd)
        DiscState.TrayOpen -> stringResource(R.string.tray_open)
        DiscState.NoDisc -> stringResource(R.string.no_disc)
        DiscState.NoDrive -> stringResource(R.string.no_drive)
        is DiscState.NoAccess -> stringResource(R.string.no_access)
        else -> stringResource(R.string.reading)
    }
}

private fun cover(d: DiscScreens) = d.info?.takeIf { (d.disc as? DiscState.AudioCd)?.discId == it.discId }?.cover

/** The tracks or files of the disc, or the hint of the drive's state. */
@Composable
private fun DiscContent(d: DiscScreens) {
    when (val s = d.disc) {
        is DiscState.AudioCd -> TrackList(s, d.player, d.info)
        is DiscState.DataDisc -> DataDisc(s, d.player, d.openFile, d.openDvd)
        else -> Hint(d.disc)
    }
}

// --- Factory -------------------------------------------------------------------------------------------------------

@Composable
internal fun FactoryDisc(d: DiscScreens) {
    var browse by rememberSaveable { mutableStateOf(false) }
    val p = d.player
    FactoryScreen(
        title = if (browse) discTitle(d) else null,
        back = false,
        keys =
            listOf(
                SoftKey(stringResource(R.string.eject), Icons.Outlined.Eject) { Playback.eject() },
                SoftKey(stringResource(R.string.disc_browse), Icons.AutoMirrored.Outlined.QueueMusic, browse) { browse = !browse },
                SoftKey(stringResource(R.string.previous), Icons.Outlined.ChevronLeft) { Playback.previous() },
                SoftKey(stringResource(if (p.playing) R.string.pause else R.string.play), if (p.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow) { Playback.toggle() },
                SoftKey(stringResource(R.string.next), Icons.Outlined.ChevronRight) { Playback.next() },
                if (d.disc is DiscState.AudioCd && !d.rip.running) SoftKey(stringResource(R.string.rip), Icons.Outlined.Download, onClick = d.onRip) else null,
                SoftKey(stringResource(R.string.car_setup), Icons.Outlined.Settings, onClick = d.onSettings),
            ),
    ) {
        if (browse) {
            Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) { DiscContent(d) }
        } else {
            FactoryNowPlaying(d)
        }
    }
}

@Composable
private fun FactoryNowPlaying(d: DiscScreens) {
    val p = d.player
    val item = p.current
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
        if (d.rip.running || d.rip.finished) {
            RipCard(d.rip)
            Spacer(Modifier.height(12.dp))
        }
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 32.dp)) {
                if (item == null) {
                    Text(
                        if (d.disc is DiscState.AudioCd || d.disc is DiscState.DataDisc) stringResource(R.string.nothing_playing) else discTitle(d),
                        color = Color.White,
                        fontSize = 28.sp,
                    )
                } else {
                    FactoryLine(Icons.Outlined.MusicNote, item.title, 32, bold = true)
                    if (item.subtitle.isNotBlank()) FactoryLine(Icons.Outlined.Person, item.subtitle, 26)
                    if (d.disc is DiscState.AudioCd) FactoryLine(Icons.Outlined.Album, discTitle(d), 26)
                }
                p.error?.let { Text(stringResource(R.string.play_error, it), color = Color(0xFFFF6E66), fontSize = 20.sp, modifier = Modifier.padding(top = 12.dp)) }
            }
            FactoryCover(d, p.playing)
        }
        val dur = item?.durationMs ?: 0
        if (dur > 0) FactoryProgress(p.positionMs, dur)
    }
}

@Composable
private fun FactoryLine(
    icon: ImageVector,
    text: String,
    size: Int,
    bold: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 10.dp)) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(18.dp))
        Text(text, color = Color.White, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, fontSize = size.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The cover (or a large thin note on a dark square), a glow of the accent colour under it while playing. */
@Composable
private fun FactoryCover(
    d: DiscScreens,
    playing: Boolean,
) {
    val accent = CarColors.Accent
    val art = cover(d)
    Box(
        Modifier
            .fillMaxHeight(0.85f)
            .aspectRatio(1f)
            .drawBehind {
                if (playing) {
                    drawOval(
                        Brush.radialGradient(listOf(accent.copy(alpha = 0.55f), Color.Transparent), center = Offset(size.width / 2, size.height), radius = size.width * 0.6f),
                        topLeft = Offset(-size.width * 0.1f, size.height * 0.82f),
                        size = androidx.compose.ui.geometry.Size(size.width * 1.2f, size.height * 0.36f),
                    )
                }
            },
    ) {
        if (art != null) {
            Image(art.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().border(1.dp, Color(0xFF8A8C90)))
        } else {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2A2B2F), Color(0xFF0E0E10)))), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.MusicNote, null, tint = Color(0xFF55575C), modifier = Modifier.fillMaxSize(0.5f))
            }
        }
    }
}

/** Elapsed time, the bar (a touch seeks), the time left. */
@Composable
private fun FactoryProgress(
    position: Long,
    duration: Long,
) {
    val f = (position.toFloat() / duration).coerceIn(0f, 1f)
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(duration(position), color = Color.White, fontSize = 20.sp, modifier = Modifier.width(72.dp))
        Box(
            Modifier
                .weight(1f)
                .height(28.dp)
                .pointerInput(duration) { detectTapGestures { o -> Playback.seekTo(((o.x / size.width).coerceIn(0f, 1f) * duration).toLong()) } },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0xFF3A3B3F)))
            Box(Modifier.fillMaxWidth(f).height(3.dp).background(CarColors.Accent))
        }
        Text("-" + duration(duration - position), color = Color.White, fontSize = 20.sp, textAlign = TextAlign.End, modifier = Modifier.width(80.dp))
    }
}

// --- Ambient -------------------------------------------------------------------------------------------------------

@Composable
internal fun AmbientDisc(d: DiscScreens) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val p = d.player
    AmbientScreen(
        title = discTitle(d),
        keys = listOf(AmbientKey(stringResource(R.string.eject)) { Playback.eject() }),
        options =
            buildList {
                if (d.disc is DiscState.AudioCd && !d.rip.running) add(AmbientOption(stringResource(R.string.rip), onClick = d.onRip))
                if (d.disc == DiscState.TrayOpen || d.disc == DiscState.NoDisc) add(AmbientOption(stringResource(R.string.close_tray)) { DiscDrive.closeTray() })
                add(AmbientOption(stringResource(R.string.reload)) { DiscDrive.refresh() })
                add(AmbientOption(stringResource(R.string.settings), onClick = d.onSettings))
            },
    ) {
        val accent = CarColors.Accent
        Column(Modifier.fillMaxSize()) {
            AmbientTabs(listOf(stringResource(R.string.disc_playback), stringResource(R.string.disc_tracks)), tab) { tab = it }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .drawBehind {
                        val w = 1.dp.toPx()
                        drawLine(accent, Offset(w / 2, 0f), Offset(w / 2, size.height), w)
                        drawLine(accent, Offset(size.width - w / 2, 0f), Offset(size.width - w / 2, size.height), w)
                        drawLine(accent, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
                    },
            ) {
                if (tab == 1) {
                    Box(Modifier.fillMaxSize().padding(12.dp)) { DiscContent(d) }
                } else {
                    AmbientPlayback(d, p)
                }
            }
        }
    }
}

/** The framed cover, title and artist, the framed progress bar with the times, previous / play / next side by side. */
@Composable
private fun AmbientPlayback(
    d: DiscScreens,
    p: PlayerState,
) {
    val item = p.current
    Row(Modifier.fillMaxSize().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
        val art = cover(d)
        Box(Modifier.fillMaxHeight(0.92f).aspectRatio(1f).border(BorderStroke(1.dp, CarColors.Accent)), contentAlignment = Alignment.Center) {
            if (art != null) {
                Image(art.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().background(Color(0xFF111824)), contentAlignment = Alignment.Center) { MetalIcon(Icons.Outlined.Album, 110.dp) }
            }
        }
        Spacer(Modifier.width(32.dp))
        Column(Modifier.weight(1f)) {
            if (d.rip.running || d.rip.finished) {
                RipCard(d.rip)
                Spacer(Modifier.height(12.dp))
            }
            Text(item?.title ?: stringResource(R.string.nothing_playing), color = KitLight, fontSize = 28.sp, fontWeight = FontWeight.Light, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!item?.subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Person, null, tint = KitLight, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(item!!.subtitle, color = KitLight, fontSize = 18.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            p.error?.let { Text(stringResource(R.string.play_error, it), color = Color(0xFFFF6E66), fontSize = 17.sp, fontWeight = FontWeight.Light) }
            Spacer(Modifier.height(20.dp))
            val dur = item?.durationMs ?: 0
            if (dur > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(duration(p.positionMs), color = KitLight, fontSize = 15.sp, fontWeight = FontWeight.Light)
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .weight(1f)
                            .height(12.dp)
                            .border(BorderStroke(1.dp, CarColors.Accent))
                            .padding(2.dp)
                            .pointerInput(dur) { detectTapGestures { o -> Playback.seekTo(((o.x / size.width).coerceIn(0f, 1f) * dur).toLong()) } },
                    ) { Box(Modifier.fillMaxWidth((p.positionMs.toFloat() / dur).coerceIn(0f, 1f)).fillMaxHeight().background(CarColors.Accent)) }
                    Spacer(Modifier.width(8.dp))
                    Text(duration(dur), color = KitLight, fontSize = 15.sp, fontWeight = FontWeight.Light)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().height(60.dp)) {
                AmbientBarButton(AmbientKey(stringResource(R.string.previous), Icons.Outlined.SkipPrevious) { Playback.previous() })
                AmbientBarButton(
                    AmbientKey(stringResource(if (p.playing) R.string.pause else R.string.play), if (p.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow) { Playback.toggle() },
                )
                AmbientBarButton(AmbientKey(stringResource(R.string.next), Icons.Outlined.SkipNext) { Playback.next() })
            }
        }
    }
}
