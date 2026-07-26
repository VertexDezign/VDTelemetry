package net.vertexdezign.vdt.app.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.vertexdezign.vdt.model.FillUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Formats a fill level with the unit's own [precision], since `String.format` is JVM-only and this
 * also runs on wasmJs. Fill levels are non-negative, so the sign is not handled.
 */
private fun formatLevel(value: Float, precision: Int): String {
  if (precision <= 0) return value.roundToInt().toString()
  var scale = 1
  repeat(precision) { scale *= 10 }
  val scaled = (value * scale).roundToInt()
  return "${scaled / scale}.${(scaled % scale).toString().padStart(precision, '0')}"
}

/** Renders a list of fill units as labeled progress bars. Port of `FillUnitsDisplay`. */
@Composable
fun FillUnitsDisplay(fillUnits: List<FillUnit>, modifier: Modifier = Modifier, spacing: Int = 8) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing.dp)) {
    for (fu in fillUnits) {
      val title = fu.title.ifBlank { fu.type.orEmpty() }
      // Skip empty/placeholder units (no type, no title, zero level). `value` is fractional, so this
      // is a tolerance rather than an equality — the mod rounds to 3 decimals.
      if (fu.type.isNullOrBlank() && fu.title.isBlank() && abs(fu.value) < 0.001f) continue
      // Drive the bar from the fine-grained liters/capacity rather than the pre-rounded integer
      // `fillLevelPercentage`, which staircases ~1% at a time and looks jumpy even while the
      // liters climb smoothly (e.g. a baler filling ~4%/s). Fall back to the percent if the mod
      // reports no capacity.
      val fraction = if (fu.capacity > 0) fu.value / fu.capacity else fu.fillLevelPercentage / 100f
      // NOTE: `fu.display == STEP` (consumables — capacity is a slot count, and the game draws one
      // segment per slot with the current one part-filled) still renders as a continuous bar here.
      // The segmented renderer is deferred to the UI redesign; the level itself is correct either way.
      ProgressBar(
        fraction = fraction,
        leftLabel = title.ifBlank { "Fill" },
        rightLabel = "${formatLevel(fu.value, fu.precision)}${fu.unit}",
      )
    }
  }
}
