package net.vertexdezign.vdt.app.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.vertexdezign.vdt.model.FillUnit

/** Renders a list of fill units as labeled progress bars. Port of `FillUnitsDisplay`. */
@Composable
fun FillUnitsDisplay(fillUnits: List<FillUnit>, modifier: Modifier = Modifier, spacing: Int = 8) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing.dp)) {
    for (fu in fillUnits) {
      val title = fu.title.ifBlank { fu.type.orEmpty() }
      // Skip empty/placeholder units (no type, no title, zero level).
      if (fu.type.isNullOrBlank() && fu.title.isBlank() && fu.value == 0) continue
      // Drive the bar from the fine-grained liters/capacity rather than the pre-rounded integer
      // `fillLevelPercentage`, which staircases ~1% at a time and looks jumpy even while the
      // liters climb smoothly (e.g. a baler filling ~4%/s). Fall back to the percent if the mod
      // reports no capacity.
      val fraction = if (fu.capacity > 0) fu.value.toFloat() / fu.capacity else fu.fillLevelPercentage / 100f
      ProgressBar(
        fraction = fraction,
        leftLabel = title.ifBlank { "Fill" },
        rightLabel = "${fu.value}${fu.unit}",
      )
    }
  }
}
