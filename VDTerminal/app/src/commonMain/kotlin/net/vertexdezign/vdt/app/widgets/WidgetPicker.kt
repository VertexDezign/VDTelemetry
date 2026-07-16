package net.vertexdezign.vdt.app.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.theme.VdtColors

/**
 * Modal widget picker: a scrim (tap to dismiss) over a card listing the [available] widgets (those
 * not already on the screen). Picking one calls [onPick] with its id. Shown when an empty grid slot
 * is tapped in edit mode.
 */
@Composable
fun WidgetPicker(
  available: List<Widget>,
  onPick: (String) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier
      .fillMaxSize()
      .background(VdtColors.Black.copy(alpha = 0.55f))
      .clickable(interactionSource = null, indication = null, onClick = onDismiss),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      Modifier
        .width(280.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(VdtColors.Panel)
        .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(8.dp))
        // Swallow taps on the card so they don't fall through to the dismiss scrim.
        .clickable(interactionSource = null, indication = null) {}
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text("ADD WIDGET", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = VdtColors.DarkGray)
      if (available.isEmpty()) {
        Text("All widgets are already placed.", fontSize = 12.sp, color = VdtColors.DarkGray)
      } else {
        Column(
          Modifier.verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          for (widget in available) {
            WidgetRow(widget, onClick = { onPick(widget.id) })
          }
        }
      }
    }
  }
}

@Composable
private fun WidgetRow(widget: Widget, onClick: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .background(VdtColors.White.copy(alpha = 0.5f))
      .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(6.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Icon(widget.icon, contentDescription = null, tint = VdtColors.DarkGray, modifier = Modifier.size(20.dp))
    Text(widget.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = VdtColors.TextDark)
  }
}
