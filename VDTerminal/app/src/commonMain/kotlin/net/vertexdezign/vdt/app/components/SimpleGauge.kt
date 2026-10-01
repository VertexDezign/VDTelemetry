package net.vertexdezign.vdt.app.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.theme.VdtColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A 270° gauge (open at the bottom) rendered on a Canvas. Port of the React `SimpleGauge`
 * SVG arc: screen angle = svgAngle - 90, so start 225°/end 495° become Compose 135°..405°.
 *
 * [marker] is a value to mark on the scale — a radial tick cut across the arc, the way a speedometer
 * marks a limit — or null for none. It is told from the fill by **shape**, a line across the track
 * rather than a stretch of it, so it reads the same whatever colour the fill is; [markerDescription]
 * is what a screen reader hears for it.
 */
@Composable
fun SimpleGauge(
  value: Float,
  min: Float,
  max: Float,
  unit: String,
  modifier: Modifier = Modifier,
  size: Dp = 130.dp,
  isActive: Boolean = false,
  onClick: (() -> Unit)? = null,
  marker: Float? = null,
  markerDescription: String? = null,
) {
  val range = (max - min)
  val percentage = if (range > 0f) ((value.coerceIn(min, max) - min) / range) else 0f
  val trackColor = VdtColors.TrackGray
  val activeColor = if (isActive) VdtColors.Green else VdtColors.TextDisabled
  val markerColor = VdtColors.TextDark

  val boxModifier = modifier
    .size(size)
    .let { if (onClick != null) it.clickable(onClick = onClick) else it }
    .let { box -> markerDescription?.takeIf { marker != null }?.let { box.semantics { stateDescription = it } } ?: box }
  Box(boxModifier, contentAlignment = Alignment.Center) {
    Canvas(Modifier.size(size)) {
      val minDim = this.size.minDimension
      val strokeWidth = minDim * 0.08f
      val radius = (minDim - strokeWidth) / 2f - 10f
      val center = Offset(minDim / 2f, minDim / 2f)
      val topLeft = Offset(center.x - radius, center.y - radius)
      val arcSize = Size(radius * 2f, radius * 2f)
      val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)

      drawArc(
        trackColor,
        startAngle = 135f,
        sweepAngle = 270f,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = stroke,
      )
      drawArc(
        activeColor,
        startAngle = 135f,
        sweepAngle = 270f * percentage,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = stroke,
      )
      // Last, so it cuts across the fill and the track alike. Reaching past the track on both sides
      // is what makes it a mark on the scale rather than a gap in the bar.
      if (marker != null && range > 0f) {
        val at = (135f + 270f * ((marker.coerceIn(min, max) - min) / range)) * (PI.toFloat() / 180f)
        val inner = radius - strokeWidth * MARKER_REACH
        val outer = radius + strokeWidth * MARKER_REACH
        drawLine(
          markerColor,
          Offset(center.x + inner * cos(at), center.y + inner * sin(at)),
          Offset(center.x + outer * cos(at), center.y + outer * sin(at)),
          strokeWidth = strokeWidth * MARKER_WIDTH,
          cap = StrokeCap.Butt,
        )
      }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(
        text = if (max < 100f) format2(value) else value.roundToInt().toString(),
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        color = if (isActive) VdtColors.Green else VdtColors.TextDark,
      )
      Text(unit, fontSize = 13.sp, color = VdtColors.DarkGray)
    }
  }
}

/** How far the [SimpleGauge] marker reaches either side of the arc's centre line, in track widths. */
private const val MARKER_REACH = 0.9f

/** The marker's thickness, in track widths. */
private const val MARKER_WIDTH = 0.28f

internal fun format2(v: Float): String {
  val scaled = (v * 100f).roundToInt()
  val whole = scaled / 100
  val frac = abs(scaled % 100)
  return "$whole.${frac.toString().padStart(2, '0')}"
}
