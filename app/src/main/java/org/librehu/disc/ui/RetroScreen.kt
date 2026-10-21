package org.librehu.disc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.librehu.disc.R
import org.librehu.disc.drive.DiscDrive
import org.librehu.disc.drive.DiscState
import org.librehu.disc.meta.CdInfoStore
import org.librehu.disc.player.PlayerState
import org.librehu.disc.player.Playback
import org.librehu.disc.player.Spectrum
import java.io.File
import java.text.Normalizer

/** Colours of the display, as the fluorescent / LCD displays of 1990s-2000s car stereos. */
enum class RetroColor(
    val lit: Color,
) {
    AMBER(Color(0xFFFFA42B)),
    ICE(Color(0xFF5AD7FF)),
    GREEN(Color(0xFF63FF8A)),
    RED(Color(0xFFFF5A3C)),
}

private val Seg7 = FontFamily(Font(R.font.dseg7_classic_bold))
private val Seg14 = FontFamily(Font(R.font.dseg14_classic_bold))

/** Characters of the 14-segment display; anything else becomes a blank. */
private val SEG14_CHARS = ('A'..'Z').toSet() + ('0'..'9').toSet() + "-_'./:!?".toSet()

/** Visible characters of the scrolling text. */
private const val TEXT_CELLS = 14

/** LED rows of the analyser. */
private const val LED_ROWS = 14

/**
 * The player as a car stereo faceplate of the late 1990s / 2000s: segment display with unlit segments showing
 * through, indicators, track and time, scrolling text, 16-band analyser of the CD audio, and hardware-like keys.
 */
@Composable
fun RetroScreen(
    disc: DiscState,
    player: PlayerState,
    color: RetroColor,
    onColor: () -> Unit,
    onModern: () -> Unit,
    openFile: (File) -> Unit,
    openDvd: () -> Unit,
) {
    val lit = color.lit
    var list by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF2C2C30), Color(0xFF141416), Color(0xFF0A0A0B))))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "LibreHU",
                color = Color(0xFFBDBDC2),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Italic,
            )
            Spacer(Modifier.width(14.dp))
            Text("DISC PLAYER", color = lit.copy(alpha = 0.75f), fontSize = 13.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("LHU-D1", color = Color(0xFF8A8A90), fontSize = 13.sp, letterSpacing = 2.sp)
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Display(disc, player, lit, Modifier.weight(if (list) 0.56f else 1f).fillMaxHeight())
            if (list) ListPanel(disc, player, lit, openFile, openDvd, Modifier.weight(0.44f).fillMaxHeight())
        }
        Row(Modifier.fillMaxWidth().height(76.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val dvd = disc is DiscState.DataDisc && disc.dvdVideo && player.current == null
            Key("◄◄", "TRACK", lit, Modifier.weight(1f)) { Playback.previous() }
            Key("►‖", if (dvd) "DVD" else "PLAY", lit, Modifier.weight(1.3f)) { if (dvd) openDvd() else Playback.toggle() }
            Key("►►", "TRACK", lit, Modifier.weight(1f)) { Playback.next() }
            val closable = disc == DiscState.TrayOpen || disc == DiscState.NoDisc
            Key("▲", if (closable) "CLOSE" else "EJECT", lit, Modifier.weight(1f)) {
                if (closable) {
                    DiscDrive.closeTray()
                } else {
                    Playback.stop()
                    DiscDrive.eject()
                }
            }
            Key("☰", "LIST", lit, Modifier.weight(1f), active = list) { list = !list }
            Key("◐", "COLOR", lit, Modifier.weight(1f), onClick = onColor)
            Key("▣", "MODE", lit, Modifier.weight(1f), onClick = onModern)
        }
    }
}

@Composable
private fun Display(
    disc: DiscState,
    p: PlayerState,
    lit: Color,
    modifier: Modifier,
) {
    val item = p.current
    val trackDigits = if (p.queue.size > 99) 3 else 2
    val trk = if (item != null) (p.index + 1).toString().padStart(trackDigits, '0') else "!".repeat(trackDigits)
    val time =
        if (item != null) {
            val s = p.positionMs / 1000
            "%02d:%02d".format((s / 60).coerceAtMost(99), s % 60)
        } else {
            "!!:!!"
        }
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF0B0E0C), Color(0xFF040504))))
            .border(3.dp, Brush.verticalGradient(listOf(Color(0xFF55555B), Color(0xFF1A1A1C))), RoundedCornerShape(14.dp))
            .padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Indicator("LOAD", disc is DiscState.Reading, lit)
            Indicator("CD", disc is DiscState.AudioCd, lit)
            Indicator("DVD", disc is DiscState.DataDisc && disc.dvd, lit)
            Indicator("DATA", disc is DiscState.DataDisc, lit)
            Indicator("MP3", item?.file != null, lit)
            Spacer(Modifier.weight(1f))
            Indicator("▶", p.playing, lit)
            Indicator("‖", item != null && !p.playing, lit)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text("TRK", color = lit.copy(alpha = if (item != null) 1f else 0.15f), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Segments(trk, "8".repeat(trackDigits), Seg7, 64.sp, lit)
            Spacer(Modifier.weight(1f))
            Segments(time, "88:88", Seg7, 64.sp, lit)
        }
        Marquee(displayText(disc, p), lit)
        Analyser(lit, Modifier.fillMaxWidth().weight(1f))
    }
}

/** What the scrolling line says: the playing item, else the disc or drive state. */
private fun displayText(
    disc: DiscState,
    p: PlayerState,
): String {
    p.current?.let { return it.title }
    return when (disc) {
        DiscState.NoDrive -> "NO DRIVE"
        is DiscState.NoAccess -> "ROOT ?"
        DiscState.TrayOpen -> "OPEN"
        DiscState.NoDisc -> "NO DISC"
        DiscState.Reading -> "READING"
        is DiscState.AudioCd -> "CD AUDIO ${disc.tracks.size} TRK"
        is DiscState.DataDisc ->
            when {
                disc.dvdVideo -> "DVD VIDEO"
                disc.dvd -> "DATA DVD"
                else -> "DATA CD"
            }
    }
}

/** Upper-case ASCII the 14-segment font has; '!' is DSEG's blank of a full cell. */
private fun seg14(s: String): String =
    Normalizer
        .normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .uppercase()
        .map { if (it in SEG14_CHARS) it else '!' }
        .joinToString("")

@Composable
private fun Marquee(
    text: String,
    lit: Color,
) {
    val full = seg14(text)
    var offset by remember(full) { mutableIntStateOf(0) }
    if (full.length > TEXT_CELLS) {
        LaunchedEffect(full) {
            val loop = full + "!!!!"
            delay(1500)
            while (true) {
                offset = (offset + 1) % loop.length
                delay(320)
            }
        }
    }
    val shown =
        if (full.length <= TEXT_CELLS) {
            full.padEnd(TEXT_CELLS, '!')
        } else {
            val loop = full + "!!!!"
            (loop + loop).substring(offset, offset + TEXT_CELLS)
        }
    Segments(shown, "~".repeat(TEXT_CELLS), Seg14, 40.sp, lit)
}

/** Lit text over its unlit segments, with the glow of a fluorescent display. */
@Composable
private fun Segments(
    text: String,
    ghost: String,
    font: FontFamily,
    size: TextUnit,
    lit: Color,
) {
    Box {
        Text(ghost, color = lit.copy(alpha = 0.07f), fontFamily = font, fontSize = size, maxLines = 1, softWrap = false)
        Text(
            text,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            style =
                TextStyle(
                    color = lit,
                    fontFamily = font,
                    fontSize = size,
                    shadow = Shadow(lit.copy(alpha = 0.85f), Offset.Zero, blurRadius = 18f),
                ),
        )
    }
}

@Composable
private fun Indicator(
    label: String,
    on: Boolean,
    lit: Color,
) {
    val c = if (on) lit else lit.copy(alpha = 0.12f)
    Box(
        Modifier
            .border(1.5.dp, c, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            label,
            style =
                TextStyle(
                    color = c,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    shadow = if (on) Shadow(lit.copy(alpha = 0.7f), Offset.Zero, 10f) else null,
                ),
        )
    }
}

/** 16 bands of LEDs with falling bars and peak dots, fed by [Spectrum] (CD audio). */
@Composable
private fun Analyser(
    lit: Color,
    modifier: Modifier,
) {
    val target by Spectrum.levels.collectAsStateWithLifecycle()
    val bars = remember { FloatArray(Spectrum.BANDS) }
    val peaks = remember { FloatArray(Spectrum.BANDS) }
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(33)
            for (b in 0 until Spectrum.BANDS) {
                val t = target.getOrElse(b) { 0f }
                bars[b] = if (t > bars[b]) t else (bars[b] - 0.035f).coerceAtLeast(t)
                peaks[b] = if (bars[b] > peaks[b]) bars[b] else (peaks[b] - 0.008f).coerceAtLeast(0f)
            }
            frame++
        }
    }
    Canvas(modifier) {
        frame.let { }
        val gap = 4.dp.toPx()
        val cw = (size.width - gap * (Spectrum.BANDS - 1)) / Spectrum.BANDS
        val ch = (size.height - gap * (LED_ROWS - 1)) / LED_ROWS
        val r = CornerRadius(2.dp.toPx())
        for (b in 0 until Spectrum.BANDS) {
            val on = (bars[b] * LED_ROWS).toInt()
            val peak = (peaks[b] * LED_ROWS).toInt().coerceAtMost(LED_ROWS - 1)
            for (row in 0 until LED_ROWS) {
                val x = b * (cw + gap)
                val y = size.height - (row + 1) * ch - row * gap
                val c =
                    when {
                        row < on -> lit
                        row == peak && peak > 0 -> lit.copy(alpha = 0.8f)
                        else -> lit.copy(alpha = 0.07f)
                    }
                drawRoundRect(c, Offset(x, y), Size(cw, ch), r)
            }
        }
    }
}

/** A faceplate key: dark plastic with a highlight, symbol and label; lit when pressed or [active]. */
@Composable
private fun Key(
    symbol: String,
    label: String,
    lit: Color,
    modifier: Modifier,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .fillMaxHeight()
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    if (pressed) listOf(Color(0xFF151517), Color(0xFF26262A)) else listOf(Color(0xFF3E3E43), Color(0xFF1B1B1E)),
                ),
            ).border(1.dp, Brush.verticalGradient(listOf(Color(0xFF6A6A72), Color(0xFF111113))), shape)
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(symbol, color = if (pressed || active) lit else Color(0xFFE2E2E6), fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(label, color = lit.copy(alpha = if (pressed || active) 1f else 0.7f), fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
    }
}

/** Tracks, or folders and files of a data disc, in the display's colours. */
@Composable
private fun ListPanel(
    disc: DiscState,
    p: PlayerState,
    lit: Color,
    openFile: (File) -> Unit,
    openDvd: () -> Unit,
    modifier: Modifier,
) {
    val cdInfo by CdInfoStore.info.collectAsStateWithLifecycle()
    val info = cdInfo?.takeIf { disc is DiscState.AudioCd && disc.discId == it.discId }
    val cdQueue = remember(disc, info) { (disc as? DiscState.AudioCd)?.let { Playback.cdQueue(it.tracks) }.orEmpty() }
    val root = (disc as? DiscState.DataDisc)?.mountPoint
    var dir by remember(root) { mutableStateOf(root?.let { File(it) }) }
    val entries =
        remember(dir) {
            dir?.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })).orEmpty()
        }
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF050605))
            .border(3.dp, Brush.verticalGradient(listOf(Color(0xFF55555B), Color(0xFF1A1A1C))), RoundedCornerShape(14.dp))
            .padding(10.dp),
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (disc) {
                is DiscState.AudioCd -> {
                    val queue = cdQueue
                    items(queue.size) { i ->
                        val q = queue[i]
                        val name = if (info != null) "  " + q.title.uppercase() else ""
                        Line("%02d  %s%s".format(i + 1, duration(q.durationMs), name), p.current?.id == q.id, lit) {
                            if (p.current?.id == q.id) Playback.toggle() else Playback.playQueue(queue, i)
                        }
                    }
                }
                is DiscState.DataDisc -> {
                    if (disc.dvdVideo) item { Line("► DVD VIDEO (VLC)", false, lit, onClick = openDvd) }
                    if (root != null && dir?.path != root) item { Line("◄ ..", false, lit) { dir = dir?.parentFile } }
                    val audio = entries.filter { it.isFile && it.extension.lowercase() in AUDIO }
                    items(entries, key = { it.path }) { f ->
                        val isAudio = f.isFile && f.extension.lowercase() in AUDIO
                        Line((if (f.isDirectory) "▸ " else "  ") + f.name, p.current?.file == f, lit) {
                            when {
                                f.isDirectory -> dir = f
                                isAudio -> Playback.playQueue(Playback.fileQueue(audio), audio.indexOf(f))
                                else -> openFile(f)
                            }
                        }
                    }
                }
                else -> item { Line(displayText(disc, p), false, lit) {} }
            }
        }
    }
}

@Composable
private fun Line(
    text: String,
    selected: Boolean,
    lit: Color,
    onClick: () -> Unit,
) {
    Text(
        text,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(if (selected) lit else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        color = if (selected) Color.Black else lit,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
