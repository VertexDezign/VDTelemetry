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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.theme.VdtColors

/**
 * Modal editor for one placed tile's [WidgetConfig]: a scrim over a card with a group of choices per
 * [ConfigOption] the widget declares.
 *
 * Edits are held locally and only handed to [onConfirm] on SAVE, so both the scrim and CANCEL abandon
 * them. That is what lets the same dialog serve placement and reconfiguration: cancelling a new tile
 * places nothing, cancelling an existing one leaves it as it was.
 *
 * Follows the [WidgetPicker] / [net.vertexdezign.vdt.app.components.ConfirmDialog] scrim-and-card
 * idiom so the overlays feel like one family.
 */
@Composable
fun WidgetConfigDialog(
  title: String,
  options: List<ConfigOption>,
  initial: WidgetConfig,
  confirmLabel: String,
  onConfirm: (WidgetConfig) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // Seeded through resolve(), which keeps a stored value even when it is not among the choices: no
  // row is then highlighted, and SAVE without touching anything writes the same value straight back.
  // Choices narrow whenever an app is briefly unavailable, and opening the dialog in that moment must
  // not be a way to silently retarget a tile.
  val selection = remember(options) {
    mutableStateMapOf<String, String>().apply {
      for (option in options) option.resolve(initial)?.let { put(option.key, it) }
    }
  }

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
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(title.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = VdtColors.DarkGray)

      // Bounded and scrollable: an option over a long list (every installed app, say) must not push
      // the buttons off a short landscape screen.
      Column(
        Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        for (option in options) {
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(option.label.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = VdtColors.Gray)
            if (option.choices.isEmpty()) {
              Text("Nothing to choose from right now.", fontSize = 12.sp, color = VdtColors.DarkGray)
            } else {
              for (choice in option.choices) {
                ChoiceRow(
                  choice = choice,
                  selected = selection[option.key] == choice.value,
                  onClick = { selection[option.key] = choice.value },
                )
              }
            }
          }
        }
      }

      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        DialogButton("CANCEL", accent = VdtColors.DarkGray, filled = false, onClick = onDismiss)
        DialogButton(confirmLabel, accent = VdtColors.Green, filled = true) { onConfirm(selection.toMap()) }
      }
    }
  }
}

@Composable
private fun ChoiceRow(choice: ConfigOption.Choice, selected: Boolean, onClick: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .background(if (selected) VdtColors.Green.copy(alpha = 0.14f) else VdtColors.White.copy(alpha = 0.5f))
      .border(1.dp, if (selected) VdtColors.Green else VdtColors.PanelBorder, RoundedCornerShape(6.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    choice.icon?.let {
      Icon(
        it,
        contentDescription = null,
        tint = if (selected) VdtColors.Green else VdtColors.DarkGray,
        modifier = Modifier.size(20.dp),
      )
    }
    Text(
      choice.label,
      fontSize = 13.sp,
      fontWeight = FontWeight.Bold,
      color = if (selected) VdtColors.TextDark else VdtColors.DarkGray,
    )
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
