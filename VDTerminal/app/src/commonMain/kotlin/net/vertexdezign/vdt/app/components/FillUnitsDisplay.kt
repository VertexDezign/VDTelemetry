package net.vertexdezign.vdt.app.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.vertexdezign.vdt.FillUnit

/** Renders a list of fill units as labeled progress bars. Port of `FillUnitsDisplay`. */
@Composable
fun FillUnitsDisplay(
    fillUnits: List<FillUnit>,
    modifier: Modifier = Modifier,
    spacing: Int = 8,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing.dp)) {
        for (fu in fillUnits) {
            val title = fu.title.ifBlank { fu.type.orEmpty() }
            // Skip empty/placeholder units (no type, no title, zero level).
            if (fu.type.isNullOrBlank() && fu.title.isBlank() && fu.value == 0) continue
            ProgressBar(
                percentage = fu.fillLevelPercentage,
                leftLabel = title.ifBlank { "Fill" },
                rightLabel = "${fu.value}${fu.unit}",
            )
        }
    }
}
