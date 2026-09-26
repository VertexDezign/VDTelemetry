package net.vertexdezign.vdt.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.vertexdezign.vdt.app.theme.VdtColors

enum class StatusColor { White, Green }

/**
 * Status toggle button (active/inactive, round or full-width). Port of `StatusIconButton`.
 *
 * [height] applies to the full-width form only, [diameter] to the [round] one. They exist for callers
 * that fit several of these into a tile whose size they don't choose — three full-width ones stacked in
 * a narrow tile, eight round ones over the Lighting panel's schematic — where the default 48dp overruns
 * the space before the rest of the panel gets any.
 */
@Composable
fun StatusIconButton(
  icon: ImageVector,
  modifier: Modifier = Modifier,
  active: Boolean = false,
  color: StatusColor = StatusColor.White,
  round: Boolean = false,
  height: Dp = 48.dp,
  diameter: Dp = 48.dp,
  onClick: (() -> Unit)? = null,
) {
  val shape = if (round) CircleShape else RoundedCornerShape(4.dp)

  val background: Brush
  val contentColor: Color
  val borderColor: Color
  when {
    active && color == StatusColor.Green -> {
      background = Brush.verticalGradient(listOf(VdtColors.Accent, VdtColors.Green))
      contentColor = VdtColors.OnFill
      borderColor = VdtColors.Green
    }

    active -> {
      background = Brush.verticalGradient(listOf(VdtColors.Surface, VdtColors.Surface))
      contentColor = VdtColors.Green
      borderColor = VdtColors.PanelBorder
    }

    else -> {
      background = Brush.verticalGradient(listOf(VdtColors.Panel, VdtColors.TrackGray))
      contentColor = VdtColors.DarkGray
      borderColor = VdtColors.PanelBorder
    }
  }

  var base =
    modifier
      .then(if (round) Modifier.size(diameter) else Modifier.fillMaxWidth().height(height))
      .clip(shape)
      .background(background)
      .border(1.dp, borderColor, shape)
  if (onClick != null) base = base.clickable(onClick = onClick)

  Box(base, contentAlignment = Alignment.Center) {
    Icon(
      icon,
      contentDescription = null,
      tint = contentColor,
      modifier = Modifier.size(if (round) diameter * 0.42f else 20.dp),
    )
  }
}
