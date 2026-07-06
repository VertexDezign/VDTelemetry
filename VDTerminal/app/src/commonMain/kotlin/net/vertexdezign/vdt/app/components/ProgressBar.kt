package net.vertexdezign.vdt.app.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.theme.VdtColors

// Tight, centred line box so 9sp text sits vertically centred in the 16dp bar (the default line
// height leaves asymmetric padding that pushes the glyphs upward).
private val LabelStyle =
  TextStyle(
    fontSize = 9.sp,
    fontWeight = FontWeight.Bold,
    lineHeight = 9.sp,
    lineHeightStyle =
    LineHeightStyle(
      alignment = LineHeightStyle.Alignment.Center,
      trim = LineHeightStyle.Trim.Both,
    ),
  )

/**
 * Horizontal fill bar with left/right labels. Port of the React `ProgressBar`: the labels are drawn
 * twice at the full bar width — dark over the empty track, and a white copy revealed only inside the
 * blue fill (via a draw-time clip to the fill fraction). Because both copies share the exact same
 * full-width layout, they line up perfectly and each label flips dark→white as the fill passes it.
 */
@Composable
fun ProgressBar(
  fraction: Float,
  modifier: Modifier = Modifier,
  leftLabel: String? = null,
  rightLabel: String? = null,
) {
  // Ease toward each new fill fraction with a spring. The caller feeds a fine-grained fraction
  // (liters/capacity, not the pre-rounded integer percent), so this already moves smoothly; the
  // spring is timing-independent, cushioning the occasional big jump (a tank filling at once, or a
  // baler chamber resetting after an eject) without snapping.
  val frac by animateFloatAsState(
    targetValue = fraction.coerceIn(0f, 1f),
    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
    label = "fill",
  )
  Box(
    modifier
      .fillMaxWidth()
      .height(16.dp)
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.TrackGray)
      .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(4.dp)),
  ) {
    // Base layer: dark labels, visible over the empty track.
    Labels(leftLabel, rightLabel, VdtColors.TextDark)

    // Blue fill.
    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(VdtColors.ProgressBlue))

    // White labels, same full-width layout as the dark copy, but only drawn within the fill.
    Box(
      Modifier.fillMaxSize().drawWithContent {
        clipRect(right = size.width * frac) { this@drawWithContent.drawContent() }
      },
    ) {
      Labels(leftLabel, rightLabel, VdtColors.White)
    }
  }
}

@Composable
private fun Labels(leftLabel: String?, rightLabel: String?, color: Color) {
  Row(
    Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      leftLabel.orEmpty(),
      style = LabelStyle,
      color = color,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f, fill = true),
    )
    Text(
      rightLabel.orEmpty(),
      style = LabelStyle,
      color = color,
      maxLines = 1,
    )
  }
}
