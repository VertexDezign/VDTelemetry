package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.components.StatusColor
import net.vertexdezign.vdt.app.components.StatusIconButton
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.Vehicle
import kotlin.math.floor

/** Compass point for a heading in degrees, matching the game's own 8-point rounding. */
internal fun directionFromHeading(heading: Int): String {
  val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
  val index = floor(heading / 22.5 + 0.5).toInt() % 8
  return dirs[(index + 8) % 8]
}

/**
 * Steering and guidance: heading, GPS/steering-assist state, AI helper state, and the guide-lines
 * toggle.
 *
 * This is where the old bottom bar's navigation cluster moved to when the bar became a shell surface
 * (launcher + page position + alerts). It is deliberately a widget rather than chrome: it is the
 * status of one subsystem, only meaningful in a vehicle, and so belongs on a page the user chooses to
 * place it on rather than in a band that is always on screen.
 */
@Composable
fun Navigation(vehicle: Vehicle, modifier: Modifier = Modifier, onCommand: (ClientMessage) -> Unit = {}) {
  Panel("Navigation", modifier, icon = Icons.Filled.Explore) {
    val gps = vehicle.gps
    val heading = gps?.heading ?: 0

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          "$heading",
          fontSize = 40.sp,
          fontWeight = FontWeight.Black,
          color = VdtColors.TextDark,
        )
        Text(
          "${gps?.headingUnit ?: "°"} ${directionFromHeading(heading)}",
          fontSize = 16.sp,
          fontWeight = FontWeight.Bold,
          color = VdtColors.DarkGray,
          modifier = Modifier.align(Alignment.CenterVertically),
        )
      }

      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // Steering assist: dim when the vehicle has no steering spec at all, white when it has one
        // but is idle, green while it is actually steering.
        Indicator(
          Icons.Filled.SatelliteAlt,
          "Steering assist",
          enabled = gps?.enabled == true,
          active = gps?.active == true,
        )
        Indicator(
          Icons.Filled.Memory,
          "AI helper",
          enabled = true,
          active = vehicle.ai?.active == true,
        )
      }

      // Only offered where it has an effect: no gps subtree means the vehicle has no steering spec,
      // so it draws no guide lines to hide. The tap sends the ABSOLUTE target, like every other
      // command (see ClientMessage) — never a toggle.
      if (gps != null) {
        StatusIconButton(
          Icons.Filled.Timeline,
          Modifier.fillMaxWidth(),
          active = true,
          color = if (gps.linesVisible) StatusColor.Green else StatusColor.White,
          onClick = { onCommand(ClientMessage.SetGpsLinesVisible(on = !gps.linesVisible)) },
        )
      }
    }
  }
}

/** Read-only subsystem lamp: greyed when absent, dark when idle, green when live. */
@Composable
private fun Indicator(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  enabled: Boolean,
  active: Boolean,
) {
  val tint = when {
    !enabled -> VdtColors.Gray
    active -> VdtColors.Accent
    else -> VdtColors.DarkGray
  }
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp))
    Text(label.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = tint)
  }
}
