package net.vertexdezign.vdt.app.panels

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.model.DriveDirection
import net.vertexdezign.vdt.model.Vehicle
import kotlin.math.roundToInt

/** Fraction of the rev range above which the rpm reads red. */
private const val REDLINE_FRACTION = 0.9f

/**
 * The cluster's primary readout: engine speed over ground speed, with the gear beside them and the
 * cruise target under them in amber.
 *
 * The same numbers the Engine and Transmission panel carries, and a different instrument for them.
 * That panel is a readout you inspect — a gauge, temperatures, fuel rates, controls to press. This is
 * one you glance at from the corner of your eye while watching a headland come up, so it is three
 * lines of very large type and nothing else.
 *
 * rpm and speed are tweened over one [sampleIntervalMs] so they read continuously rather than
 * stepping at the telemetry rate — the same trick, and for a bigger reason here, since a number this
 * size makes every jump obvious.
 */
@Composable
fun ClusterReadout(vehicle: Vehicle, sampleIntervalMs: Int, modifier: Modifier = Modifier) {
  val motor = vehicle.motor
  val spec = tween<Float>(durationMillis = sampleIntervalMs, easing = LinearEasing)
  val rpm by animateFloatAsState((motor?.rpm?.value ?: 0).toFloat(), spec, label = "cluster-rpm")
  val speed by animateFloatAsState(vehicle.speed?.value ?: 0f, spec, label = "cluster-speed")

  val redline =
    motor?.rpm?.let { it.max > it.min && (rpm - it.min) / (it.max - it.min).toFloat() >= REDLINE_FRACTION } == true

  ClusterSurface(modifier) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
      // Digits are sized from the tile rather than fixed, so the readout *fills* the panel instead of
      // floating in the middle of it. Height sets the ceiling — three stacked lines plus a caption —
      // and width sets the other, since a four-digit rpm in monospace is about 0.6em per character
      // and has to leave room for the labels beside it.
      val fromHeight = maxHeight.value * 0.22f
      val fromWidth = maxWidth.value * 0.20f
      val digit = minOf(fromHeight, fromWidth).coerceIn(18f, 130f)

      Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceEvenly,
      ) {
        // Right-aligned, as on the reference: the numbers share an edge, so a digit appearing or
        // dropping moves nothing, and each line's unit sits immediately beside its value rather than
        // across the panel from it.
        Line(
          value = rpm.roundToInt().toString(),
          valueColour = if (redline) ClusterColors.Warn else ClusterColors.Digits,
          size = digit,
          label = "RPM",
          note = gearText(vehicle),
          noteColour = ClusterColors.Digits,
        )
        Line(
          value = format1(speed),
          valueColour = ClusterColors.Digits,
          size = digit,
          label = vehicle.speed?.unit.orEmpty().ifBlank { "SPEED" },
          note = directionText(vehicle),
          noteColour = ClusterColors.Go,
        )
        // Cruise, amber because it is a value the driver set rather than one the machine reports.
        // Dimmed when armed but not engaged, so "53 is what it will hold" and "53 is what it is
        // holding" are not the same picture.
        vehicle.cruiseControl?.targetSpeed?.let { target ->
          val engaged = vehicle.cruiseControl?.active == true
          Line(
            value = format1(target),
            valueColour = if (engaged) ClusterColors.Set else ClusterColors.Set.copy(alpha = 0.45f),
            size = digit * 0.62f,
            label = "CRUISE",
            labelColour = if (engaged) ClusterColors.Set else ClusterColors.Label,
          )
        }
        vehicle.operatingTime?.let {
          ClusterLabel("${it.value}${it.unit}", Modifier.fillMaxWidth(), align = TextAlign.End)
        }
      }
    }
  }
}

/**
 * One line of the readout: the number, right-aligned, with its unit and an optional second line of
 * detail (the gear, the direction) stacked in a column beside it.
 */
@Composable
private fun Line(
  value: String,
  valueColour: Color,
  size: Float,
  label: String,
  labelColour: Color = ClusterColors.Label,
  note: String? = null,
  noteColour: Color = ClusterColors.Digits,
) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
    Digits(value, size = size.sp, colour = valueColour, modifier = Modifier.weight(1f), align = TextAlign.End)
    Column(
      Modifier.padding(start = 6.dp).width(IntrinsicSize.Min),
      horizontalAlignment = Alignment.Start,
    ) {
      ClusterLabel(label, color = labelColour)
      note?.let { Digits(it, size = (size * 0.42f).sp, colour = noteColour) }
    }
  }
}

/**
 * What the transmission is in: the gear, or `N` in neutral, prefixed by its group where the vehicle
 * has one (`E2`, the group's own letter and the gear within it). Null when there is no gear at all,
 * which is most non-tractors.
 */
internal fun gearText(vehicle: Vehicle): String? {
  val gear = vehicle.motor?.gear ?: return null
  if (gear.isNeutral) return "N"
  val value = gear.value.takeIf { it.isNotBlank() } ?: return null
  return gear.group.takeIf { it.isNotBlank() }?.let { it + value } ?: value
}

/** `F` / `R` while moving; null when stopped, where a direction would be a claim about nothing. */
internal fun directionText(vehicle: Vehicle): String? = when (vehicle.speed?.direction) {
  DriveDirection.FORWARD -> "F"
  DriveDirection.BACKWARD -> "R"
  else -> null
}

/** One decimal, without `String.format` (JVM-only; this also runs on wasmJs). */
internal fun format1(value: Float): String {
  val scaled = (value * 10).roundToInt()
  return "${scaled / 10}.${(scaled % 10).let { if (it < 0) -it else it }}"
}

@Composable
private fun Digits(
  text: String,
  size: TextUnit,
  colour: Color,
  modifier: Modifier = Modifier,
  align: TextAlign = TextAlign.Start,
) {
  Text(
    text,
    modifier,
    color = colour,
    fontSize = size,
    fontFamily = ClusterDigitFont,
    fontWeight = FontWeight.Black,
    maxLines = 1,
    textAlign = align,
  )
}
