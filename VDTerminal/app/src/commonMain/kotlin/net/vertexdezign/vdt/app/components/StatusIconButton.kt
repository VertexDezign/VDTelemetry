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
import androidx.compose.ui.unit.dp
import net.vertexdezign.vdt.app.theme.VdtColors

enum class StatusColor { White, Green }

/** Status toggle button (active/inactive, round or full-width). Port of `StatusIconButton`. */
@Composable
fun StatusIconButton(
  icon: ImageVector,
  modifier: Modifier = Modifier,
  active: Boolean = false,
  color: StatusColor = StatusColor.White,
  round: Boolean = false,
  onClick: (() -> Unit)? = null,
) {
  val shape = if (round) CircleShape else RoundedCornerShape(4.dp)
  val gray100 = Color(0xFFF3F4F6)
  val gray200 = Color(0xFFE5E7EB)
  val gray300 = Color(0xFFD1D5DB)

  val background: Brush
  val contentColor: Color
  val borderColor: Color
  when {
    active && color == StatusColor.Green -> {
      background = Brush.verticalGradient(listOf(VdtColors.Accent, VdtColors.Green))
      contentColor = VdtColors.White
      borderColor = VdtColors.Green
    }

    active -> {
      background = Brush.verticalGradient(listOf(VdtColors.White, VdtColors.White))
      contentColor = VdtColors.Green
      borderColor = gray300
    }

    else -> {
      background = Brush.verticalGradient(listOf(gray100, gray200))
      contentColor = VdtColors.DarkGray
      borderColor = gray300
    }
  }

  var base =
    modifier
      .then(if (round) Modifier.size(48.dp) else Modifier.fillMaxWidth().height(48.dp))
      .clip(shape)
      .background(background)
      .border(1.dp, borderColor, shape)
  if (onClick != null) base = base.clickable(onClick = onClick)

  Box(base, contentAlignment = Alignment.Center) {
    Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
  }
}
