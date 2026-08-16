package net.vertexdezign.vdt.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.theme.VdtColors

/**
 * A list filter's on/off chip — "incoming", "sow now", "harvest now".
 *
 * The on state does **not** shift hue: it fills the chip and knocks the label out in [VdtColors.White],
 * so off-vs-on differs in ink brightness (5.0:1 grey on grey vs white on green) as well as in the fill.
 * That is the sanctioned mechanism for a two-state mark on a light panel (see `VDTerminal/README.md` →
 * "Design rules"), and the padding is spent in both states so nothing shifts when it toggles.
 *
 * Lifted out of `InvoicesSection` when the calendar became its second caller. `WidgetDashboard`'s own
 * `Chip` is deliberately left alone: it is a visually different control (bordered, on white, no
 * ripple) for the page editor, and folding it in here would restyle that screen for no reason.
 */
@Composable
fun FilterChip(
  label: String,
  active: Boolean,
  // Ahead of `modifier`, per the same ktlint rule ActionIcon documents: a required event lambda may
  // not be trailing, and a filter chip without a click does nothing.
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Text(
    label.uppercase(),
    color = if (active) VdtColors.White else VdtColors.DarkGray,
    fontSize = 10.sp,
    fontWeight = FontWeight.Bold,
    modifier =
    modifier
      .clip(RoundedCornerShape(4.dp))
      .background(if (active) VdtColors.Green else VdtColors.TrackGray)
      .clickable(role = Role.Button, onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 5.dp),
  )
}
