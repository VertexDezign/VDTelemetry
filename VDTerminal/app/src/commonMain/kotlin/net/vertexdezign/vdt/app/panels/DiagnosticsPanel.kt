package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.ChannelStat
import net.vertexdezign.vdt.ChannelStatsData
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.i18n.LocalStrings
import net.vertexdezign.vdt.app.i18n.Numbers
import net.vertexdezign.vdt.app.i18n.Strings
import net.vertexdezign.vdt.app.theme.VdtColors
import kotlin.math.roundToInt

/**
 * Diagnostics: the server-measured **observed** write cadence of every channel file — how often each
 * file actually changes on disk, which is what the app receives. Lets the mod's configured
 * intervals/profile be verified end to end. A stalled channel (age >> its cadence) is usually one the
 * player disabled or a base-game feature with no data yet.
 *
 * Data is [net.vertexdezign.vdt.app.state.VdtStore.channelStats]; resolution is floored by the
 * server's file-watch debounce, so treat sub-~50 ms readings as approximate.
 */
@Composable
fun DiagnosticsPanel(stats: ChannelStatsData?, modifier: Modifier = Modifier) {
  val s = LocalStrings.current.diagnostics
  Panel(title = s.title, icon = Icons.Filled.Schedule, modifier = modifier) {
    if (stats == null) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(s.waiting, color = VdtColors.DarkGray, fontSize = 12.sp)
      }
      return@Panel
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
      HeaderRow()
      for (channel in stats.channels) {
        ChannelRow(channel, stats.serverNowEpochMs)
      }
    }
  }
}

@Composable
private fun HeaderRow() {
  val s = LocalStrings.current.diagnostics
  Row(
    Modifier.fillMaxWidth().padding(vertical = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    HeaderCell(s.channel, 2.4f)
    HeaderCell(s.cadence, 1.3f)
    HeaderCell(s.minMax, 1.6f)
    HeaderCell(s.lastSeen, 1.3f)
    HeaderCell(s.writes, 1.0f)
  }
}

@Composable
private fun ChannelRow(channel: ChannelStat, serverNowEpochMs: Long) {
  val strings = LocalStrings.current
  val numbers = strings.numbers
  val ageMs = channel.lastWriteEpochMs?.let { (serverNowEpochMs - it).coerceAtLeast(0) }
  val stale = isStale(channel, ageMs)
  Row(
    Modifier.fillMaxWidth().padding(vertical = 5.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Cell(friendlyName(strings, channel.name), 2.4f, color = VdtColors.DarkGray, weightBold = true)
    Cell(numbers.formatInterval(channel.meanIntervalMs), 1.3f, color = if (stale) VdtColors.Amber else VdtColors.Green)
    Cell(numbers.formatMinMax(channel.minIntervalMs, channel.maxIntervalMs), 1.6f, color = VdtColors.DarkGray)
    Cell(formatAge(strings, ageMs), 1.3f, color = if (stale) VdtColors.Amber else VdtColors.DarkGray)
    Cell(channel.writes.toString(), 1.0f, color = VdtColors.DarkGray)
  }
}

@Composable
private fun RowScope.HeaderCell(text: String, weight: Float) {
  Text(
    text,
    modifier = Modifier.weight(weight),
    color = VdtColors.DarkGray,
    fontSize = 9.sp,
    fontWeight = FontWeight.Bold,
  )
}

@Composable
private fun RowScope.Cell(text: String, weight: Float, color: Color, weightBold: Boolean = false) {
  Text(
    text,
    modifier = Modifier.weight(weight),
    color = color,
    fontSize = 11.sp,
    fontWeight = if (weightBold) FontWeight.SemiBold else FontWeight.Normal,
  )
}

// A channel counts as stale (likely disabled / no data) when it hasn't been seen for well beyond its
// own observed cadence — or, lacking a cadence yet, for a few seconds.
private fun isStale(channel: ChannelStat, ageMs: Long?): Boolean {
  if (ageMs == null) return true // never written
  val mean = channel.meanIntervalMs
  val threshold = if (mean != null) (mean * 3).roundToInt().coerceAtLeast(STALE_FLOOR_MS) else STALE_FLOOR_MS
  return ageMs > threshold
}

// The ground-layer rasters are one file per plane inside mapLayers/, and which planes exist is the
// mod's to decide (Precision Farming will add more), so they are named by their folder rather than
// listed above.
private fun friendlyName(strings: Strings, fileName: String): String = strings.diagnostics.channelName(fileName)
  ?: fileName.substringAfter("mapLayers/", "").takeIf { it.isNotEmpty() }?.let {
    strings.diagnostics.mapLayer(it.removeSuffix(".json"))
  }
  ?: fileName

/** ms interval -> "980 ms" / "2.0 s"; null (no interval yet) -> em dash. */
private fun Numbers.formatInterval(ms: Double?): String {
  if (ms == null) return "—"
  return if (ms < 1000) "${ms.roundToInt()} ms" else formatSeconds(ms)
}

private fun Numbers.formatMinMax(minMs: Long?, maxMs: Long?): String {
  if (minMs == null || maxMs == null) return "—"
  return "${compactMs(minMs)} / ${compactMs(maxMs)}"
}

/** ms age -> "just now" / "3s ago" / "2m ago"; null -> "never". */
private fun formatAge(strings: Strings, ms: Long?): String {
  val s = strings.diagnostics
  if (ms == null) return s.never
  return when {
    ms < 1500 -> s.justNow
    ms < 60_000 -> s.secondsAgo((ms / 1000.0).roundToInt())
    else -> s.minutesAgo((ms / 60_000.0).roundToInt())
  }
}

private fun Numbers.compactMs(ms: Long): String = if (ms < 1000) "${ms}ms" else formatSeconds(ms.toDouble())

private fun Numbers.formatSeconds(ms: Double): String = "${trimmed(ms / 1000.0, 1)} s"

private const val STALE_FLOOR_MS = 4000
