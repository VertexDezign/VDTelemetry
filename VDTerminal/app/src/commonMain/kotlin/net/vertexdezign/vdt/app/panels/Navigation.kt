package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.components.StatusColor
import net.vertexdezign.vdt.app.components.StatusIconButton
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.GpsCourseState
import net.vertexdezign.vdt.model.Vehicle
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Compass point for a heading in degrees, matching the game's own 8-point rounding. */
internal fun directionFromHeading(heading: Int): String {
  val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
  val index = floor(heading / 22.5 + 0.5).toInt() % 8
  return dirs[(index + 8) % 8]
}

/** Cells in the lightbar. Odd, so there is a true centre cell to sit on when you are on the line. */
internal const val LIGHTBAR_CELLS = 11

/** Cross-track error (m) at which the bar is pinned to its end. Beyond this you are on another line. */
internal const val LIGHTBAR_FULL_SCALE_M = 1f

/** Within this much error (m) the bar reads as on-line and goes green. */
private const val ON_LINE_M = 0.1f

/**
 * The game sounds its own line-end warning this far out
 * (`AIAutomaticSteering.LINE_END_SOUND_DISTANCE`), so the countdown turns amber at the same distance
 * the tractor starts beeping rather than at a number of our own invention.
 */
private const val LINE_END_WARN_M = 10f

/**
 * Which cell the marker sits in for a cross-track error of [deviationM], 0-based.
 *
 * A bubble level, not a steer-this-way arrow: the marker shows where the **vehicle** is relative to
 * the line, so positive (right of the line) moves it right. Terminals differ on this and let you flip
 * it; showing position rather than correction is the reading that cannot be misinterpreted, because it
 * matches the map right above it.
 */
internal fun lightbarCell(
  deviationM: Float,
  cells: Int = LIGHTBAR_CELLS,
  fullScaleM: Float = LIGHTBAR_FULL_SCALE_M,
): Int {
  val centre = cells / 2
  val steps = ((deviationM / fullScaleM) * centre).roundToInt().coerceIn(-centre, centre)
  return centre + steps
}

/**
 * Cross-track error as a driver reads it: centimetres up to a metre, then metres, with the side it is
 * on. Dead centre drops the side rather than flickering L/R around zero.
 */
internal fun deviationLabel(deviationM: Float): String {
  val cm = (abs(deviationM) * 100).roundToInt()
  if (cm == 0) return "0 cm"
  val side = if (deviationM > 0) "R" else "L"
  if (cm < 100) return "$side $cm cm"
  // Built from integer tenths rather than formatting a Double: Double.toString() is target-dependent
  // ("1.0" on the JVM the tests run on, "1" in the browser this actually renders in), and a lightbar
  // that reads "1 m" here and "1.0 m" there is a difference the driver would notice.
  val tenths = (abs(deviationM) * 10).roundToInt()
  return "$side ${tenths / 10}.${tenths % 10} m"
}

/**
 * Steering and guidance: heading, GPS/steering-assist state, AI helper state, the guide-lines toggle,
 * and — on a steering course — the lightbar and how far through the field the driver is.
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

      // The steering course, when the vehicle is on one: how far off the line, how far to its end,
      // and how much of the field is done. Absent off a field, which is the honest thing to show —
      // there is no line to be off.
      gps?.course?.let { course ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Lightbar(course.deviationM ?: 0f, Modifier.fillMaxWidth())
          CourseReadout(course)
        }
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
 * The lightbar: cross-track error as a row of cells, lit from the centre out to where the vehicle is.
 *
 * Green while within [ON_LINE_M], amber once the error is worth correcting — the two states a driver
 * acts on. The centre cell is always marked, so the bar reads as a line to sit on even at a glance and
 * even when the error is zero.
 */
@Composable
internal fun Lightbar(deviationM: Float, modifier: Modifier = Modifier, cellHeight: Dp = 12.dp) {
  val centre = LIGHTBAR_CELLS / 2
  val marker = lightbarCell(deviationM)
  val onLine = abs(deviationM) <= ON_LINE_M
  val lit = if (onLine) VdtColors.Accent else VdtColors.Amber
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
    for (cell in 0 until LIGHTBAR_CELLS) {
      // Lit from the centre out to the marker inclusive: the length of the lit run IS the error.
      val isLit = if (marker >= centre) cell in centre..marker else cell in marker..centre
      Box(
        Modifier
          .size(width = 6.dp, height = cellHeight)
          .clip(RoundedCornerShape(1.dp))
          .background(if (isLit) lit else VdtColors.Gray)
          .then(if (cell == centre) Modifier.border(1.dp, VdtColors.DarkGray, RoundedCornerShape(1.dp)) else Modifier),
      )
    }
  }
}

/**
 * The numbers beside the bar: how far off the line, how much of this line is left, and where you are
 * in the field.
 *
 * The distance counts down to the headland and turns amber inside the game's own line-end warning
 * distance. The progress pair is the honest measure of a field's state — the game marks a line worked
 * once you have steered it for 2.5 s, so "23 of 47" is its own bookkeeping, not ours.
 */
@Composable
internal fun CourseReadout(course: GpsCourseState, modifier: Modifier = Modifier, fontSize: TextUnit = 12.sp) {
  Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    course.deviationM?.let {
      Text(
        deviationLabel(it),
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
        color = if (abs(it) <= ON_LINE_M) VdtColors.Green else VdtColors.TextDark,
      )
    }
    course.distanceToEndM?.let {
      Text(
        "${it.roundToInt()} m",
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
        color = if (it <= LINE_END_WARN_M) VdtColors.Amber else VdtColors.DarkGray,
      )
    }
    if (course.segmentCount > 0) {
      val line = if (course.segmentIndex > 0) course.segmentIndex.toString() else "–"
      Text(
        "$line/${course.segmentCount} · ${course.workedCount} done",
        fontSize = fontSize,
        color = VdtColors.DarkGray,
      )
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
    !enabled -> VdtColors.TextDisabled
    active -> VdtColors.AccentText
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
 * The navigation cluster as map chrome: heading, the two subsystem lamps and the guide-lines toggle
 * on one line, with the lightbar and course readout on a second when there is a course to fly.
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
  Column(
    modifier
      .align(Alignment.TopStart)
      .padding(6.dp)
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.Panel)
      .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(4.dp))
      .padding(horizontal = 8.dp, vertical = 4.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(
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

    // Second line, only on a course: the run-screen half — how far off the line, how far to its end,
    // where you are in the field. Off a field the strip stays the single row it was, so a map used
    // for driving to somewhere doesn't carry a bar that has nothing to say.
    gps?.course?.let { course ->
      Lightbar(course.deviationM ?: 0f, cellHeight = 8.dp)
      CourseReadout(course, fontSize = 10.sp)
    }
  }
}
