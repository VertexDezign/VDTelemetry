package net.vertexdezign.vdt.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.theme.VdtColors

/**
 * Titled panel with an icon, an optional header-actions slot, and body content.
 * Port of the React `Panel` component.
 */
@Composable
fun Panel(
  title: String,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
  headerActions: @Composable RowScope.() -> Unit = {},
  content: @Composable BoxScope.() -> Unit,
) {
  Column(
    modifier
      .fillMaxSize()
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.Panel)
      .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(4.dp)),
  ) {
    Row(
      Modifier
        .fillMaxWidth()
        .background(VdtColors.White.copy(alpha = 0.5f))
        .padding(horizontal = 12.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon !=
          null
        ) {
          Icon(icon, contentDescription = null, tint = VdtColors.DarkGray, modifier = Modifier.size(16.dp))
        }
        Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = VdtColors.DarkGray)
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        headerActions()
        Box(
          Modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(VdtColors.TrackGray)
            .border(1.dp, VdtColors.PanelBorder, CircleShape),
        )
      }
    }
    Box(Modifier.fillMaxSize().padding(8.dp)) { content() }
  }
}
