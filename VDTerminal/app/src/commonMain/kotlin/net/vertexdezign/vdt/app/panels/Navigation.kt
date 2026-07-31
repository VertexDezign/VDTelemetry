package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // No gps subtree means there is no heading to report — say so rather than reading out a due
        // north the vehicle never claimed.
        Text(
          gps?.heading?.toString() ?: "--",
          fontSize = 40.sp,
          fontWeight = FontWeight.Black,
          color = VdtColors.TextDark,
        )
        if (gps != null) {
          Text(
            "${gps.headingUnit.ifBlank { "°" }} ${directionFromHeading(gps.heading)}",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = VdtColors.DarkGray,
            modifier = Modifier.align(Alignment.CenterVertically),
          )
        }
      }

      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // Steering assist: dim when the vehicle has no steering spec at all, white when it has one
        // but is idle, green while it is actually steering.
        GuidanceLamp(
          Icons.Filled.SatelliteAlt,
          "Steering assist",
          enabled = gps?.enabled == true,
          active = gps?.active == true,
        )
        // Same three states: no ai subtree is *absent*, not idle.
        GuidanceLamp(
          Icons.Filled.Memory,
          "AI helper",
          enabled = vehicle.ai != null,
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

/**
 * Read-only subsystem lamp: greyed when absent, dark when idle, green when live.
 *
 * [showLabel] off is the map's guidance strip, where the row has to stay narrow enough not to cover
 * the terrain it sits on — the label survives as the icon's content description, so what a lamp means
 * is still reachable without reading it off the screen.
 */
@Composable
internal fun GuidanceLamp(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  enabled: Boolean,
  active: Boolean,
  showLabel: Boolean = true,
  size: Dp = 20.dp,
) {
  val tint = when {
    !enabled -> VdtColors.Gray
    active -> VdtColors.Accent
    else -> VdtColors.DarkGray
  }
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Icon(icon, label, tint = tint, modifier = Modifier.size(size))
    if (showLabel) {
      Text(label.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = tint)
    }
  }
}

/**
 * The navigation cluster as map chrome: heading, the two subsystem lamps and the guide-lines toggle,
 * on one line small enough to sit on top of the map instead of beside it.
 *
 * This is issue #43's "integrate Current navigation widget into Map" — on a guidance screen the
 * heading belongs *with* the terrain it points across, not in a tile somewhere else on the page. It
 * is the same state [Navigation] shows and the same command it sends, laid out for an overlay: no
 * panel chrome, no lamp captions, and the 40sp heading traded for something that leaves the map
 * visible. The standalone widget stays for pages that have no map on them.
 *
 * [heading] comes from the map itself, which already unifies the vehicle's GPS heading with the
 * on-foot player heading — so the strip still reads out a bearing when [vehicle] is null, and only
 * the lamps and the toggle need a vehicle.
 */
@Composable
fun BoxScope.GuidanceStrip(
  heading: Int,
  vehicle: Vehicle?,
  modifier: Modifier = Modifier,
  onCommand: (ClientMessage) -> Unit = {},
) {
  val gps = vehicle?.gps
  Row(
    modifier
      .align(Alignment.TopStart)
      .padding(6.dp)
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.Panel)
      .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(4.dp))
      .padding(horizontal = 8.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
      Text("$heading°", fontSize = 16.sp, fontWeight = FontWeight.Black, color = VdtColors.TextDark)
      Text(
        directionFromHeading(heading),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = VdtColors.DarkGray,
        modifier = Modifier.padding(bottom = 2.dp),
      )
    }

    // Only in a vehicle: on foot there is no steering assist to be idle, and no AI to be absent.
    if (vehicle != null) {
      GuidanceLamp(
        Icons.Filled.SatelliteAlt,
        "Steering assist",
        enabled = gps?.enabled == true,
        active = gps?.active == true,
        showLabel = false,
        size = 16.dp,
      )
      GuidanceLamp(
        Icons.Filled.Memory,
        "AI helper",
        enabled = vehicle.ai != null,
        active = vehicle.ai?.active == true,
        showLabel = false,
        size = 16.dp,
      )
      // Same rule as the widget's button: offered only where it has an effect, and it sends the
      // ABSOLUTE target rather than a toggle. Styled like the map's own header icons, because that
      // is what it now sits among.
      if (gps != null) {
        Icon(
          Icons.Filled.Timeline,
          "guide lines",
          tint = if (gps.linesVisible) VdtColors.Green else VdtColors.DarkGray,
          modifier =
          Modifier.size(16.dp).clickableNoRipple {
            onCommand(ClientMessage.SetGpsLinesVisible(on = !gps.linesVisible))
          },
        )
      }
    }
  }
}
