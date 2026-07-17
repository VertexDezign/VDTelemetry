package net.vertexdezign.vdt.app.components

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.theme.VdtColors

/**
 * Modal confirmation for a destructive action: a scrim (tap to dismiss) over a card with a [title], a
 * [message] explaining the consequence, and Cancel / confirm buttons. The confirm button is styled in
 * [VdtColors.Red] and labelled [confirmLabel]; [onConfirm] runs only on explicit confirmation, while
 * both the scrim and Cancel call [onDismiss]. Mirrors the [net.vertexdezign.vdt.app.widgets.WidgetPicker]
 * scrim/card idiom so overlays feel consistent.
 */
@Composable
fun ConfirmDialog(
  title: String,
  message: String,
  confirmLabel: String,
  onConfirm: () -> Unit,
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
        .width(300.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(VdtColors.Panel)
        .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(8.dp))
        // Swallow taps on the card so they don't fall through to the dismiss scrim.
        .clickable(interactionSource = null, indication = null) {}
        .padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = VdtColors.TextDark)
      Text(message, fontSize = 12.sp, color = VdtColors.DarkGray)
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
      ) {
        DialogButton("CANCEL", accent = VdtColors.DarkGray, filled = false, onClick = onDismiss)
        DialogButton(confirmLabel, accent = VdtColors.Red, filled = true, onClick = onConfirm)
      }
    }
  }
}

@Composable
private fun DialogButton(label: String, accent: Color, filled: Boolean, onClick: () -> Unit) {
  Text(
    label.uppercase(),
    fontSize = 11.sp,
    fontWeight = FontWeight.Bold,
    color = if (filled) VdtColors.White else accent,
    modifier =
    Modifier
      .clip(RoundedCornerShape(4.dp))
      .background(if (filled) accent else VdtColors.White)
      .border(1.dp, if (filled) accent else VdtColors.PanelBorder, RoundedCornerShape(4.dp))
      .clickable(interactionSource = null, indication = null, onClick = onClick)
      .padding(horizontal = 14.dp, vertical = 7.dp),
  )
}
