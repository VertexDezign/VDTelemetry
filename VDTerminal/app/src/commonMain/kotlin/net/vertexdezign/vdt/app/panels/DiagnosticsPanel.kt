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
  Panel(title = "Channel Diagnostics", icon = Icons.Filled.Schedule, modifier = modifier) {
    if (stats == null) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("WAITING FOR STATS…", color = VdtColors.DarkGray, fontSize = 12.sp)
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
  Row(
    Modifier.fillMaxWidth().padding(vertical = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    HeaderCell("CHANNEL", 2.4f)
    HeaderCell("CADENCE", 1.3f)
    HeaderCell("MIN / MAX", 1.6f)
    HeaderCell("LAST SEEN", 1.3f)
    HeaderCell("WRITES", 1.0f)
  }
}

@Composable
private fun ChannelRow(channel: ChannelStat, serverNowEpochMs: Long) {
  val ageMs = channel.lastWriteEpochMs?.let { (serverNowEpochMs - it).coerceAtLeast(0) }
  val stale = isStale(channel, ageMs)
  Row(
    Modifier.fillMaxWidth().padding(vertical = 5.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Cell(friendlyName(channel.name), 2.4f, color = VdtColors.DarkGray, weightBold = true)
    Cell(formatInterval(channel.meanIntervalMs), 1.3f, color = if (stale) VdtColors.Amber else VdtColors.Green)
    Cell(formatMinMax(channel.minIntervalMs, channel.maxIntervalMs), 1.6f, color = VdtColors.DarkGray)
    Cell(formatAge(ageMs), 1.3f, color = if (stale) VdtColors.Amber else VdtColors.DarkGray)
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
private fun friendlyName(fileName: String): String = FRIENDLY_NAMES[fileName]
  ?: fileName.substringAfter("mapLayers/", "").takeIf { it.isNotEmpty() }?.let {
    "Map layer: ${it.removeSuffix(".json")}"
  }
  ?: fileName

/** ms interval -> "980 ms" / "2.0 s"; null (no interval yet) -> em dash. */
private fun formatInterval(ms: Double?): String {
  if (ms == null) return "—"
  return if (ms < 1000) "${ms.roundToInt()} ms" else formatSeconds(ms)
}

private fun formatMinMax(minMs: Long?, maxMs: Long?): String {
  if (minMs == null || maxMs == null) return "—"
  return "${compactMs(minMs)} / ${compactMs(maxMs)}"
}

/** ms age -> "just now" / "3s ago" / "2m ago"; null -> "never". */
private fun formatAge(ms: Long?): String {
  if (ms == null) return "never"
  return when {
    ms < 1500 -> "just now"
    ms < 60_000 -> "${(ms / 1000.0).roundToInt()}s ago"
    else -> "${(ms / 60_000.0).roundToInt()}m ago"
  }
}

private fun compactMs(ms: Long): String = if (ms < 1000) "${ms}ms" else formatSeconds(ms.toDouble())

private fun formatSeconds(ms: Double): String {
  val s = ms / 1000.0
  // one decimal, but drop a trailing ".0"
  val rounded = (s * 10).roundToInt() / 10.0
  return if (rounded == rounded.toLong().toDouble()) "${rounded.toLong()} s" else "$rounded s"
}

private const val STALE_FLOOR_MS = 4000

private val FRIENDLY_NAMES =
  mapOf(
    "vdTelemetry.json" to "Vehicle telemetry",
    "map.json" to "Map",
    "index.json" to "Map layers (catalogue)", // mapLayers/index.json; the only index.json there is
    "mapVehicles.json" to "Map vehicles",
    "gpsCourse.json" to "GPS course",
    "production.json" to "Production",
    "storage.json" to "Storage",
    "husbandry.json" to "Animals",
    "fleet.json" to "Fleet",
    "prices.json" to "Prices",
    "missions.json" to "Missions",
    "fieldInfo.json" to "Field info",
    "cropCalendar.json" to "Crop calendar",
    "weather.json" to "Weather",
    // Missing until now, so these two listed as their raw filenames.
    "finance.json" to "Finance",
    "invoices.json" to "Invoices",
    "taskList.json" to "Task list",
    "cropRotation.json" to "Crop rotation",
  )
