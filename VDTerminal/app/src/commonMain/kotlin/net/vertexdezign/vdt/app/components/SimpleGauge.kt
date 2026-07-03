package net.vertexdezign.vdt.app.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import net.vertexdezign.vdt.app.theme.VdtColors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A 270° gauge (open at the bottom) rendered on a Canvas. Port of the React `SimpleGauge`
 * SVG arc: screen angle = svgAngle - 90, so start 225°/end 495° become Compose 135°..405°.
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
) {
    val range = (max - min)
    val percentage = if (range > 0f) ((value.coerceIn(min, max) - min) / range) else 0f
    val trackColor = Color(0xFFE2E8F0)
    val activeColor = if (isActive) VdtColors.Green else Color(0xFF94A3B8)

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val minDim = this.size.minDimension
            val strokeWidth = minDim * 0.08f
            val radius = (minDim - strokeWidth) / 2f - 10f
            val center = Offset(minDim / 2f, minDim / 2f)
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2f, radius * 2f)
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)

            drawArc(trackColor, startAngle = 135f, sweepAngle = 270f, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
            drawArc(activeColor, startAngle = 135f, sweepAngle = 270f * percentage, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
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

private fun format2(v: Float): String {
    val scaled = (v * 100f).roundToInt()
    val whole = scaled / 100
    val frac = abs(scaled % 100)
    return "$whole.${frac.toString().padStart(2, '0')}"
}
