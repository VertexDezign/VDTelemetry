package net.vertexdezign.vdt.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
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
 * One tab in a panel header's mode switch — Books/Invoices, Prices/Stock. A whole view of the same
 * panel, which is why it lives in the header beside the title rather than in a settings dialog: it is
 * a thing the reader flips while looking at the screen, not a thing they configure.
 *
 * Same two-state mechanism as [FilterChip], one size down to sit in a header: the active tab fills and
 * knocks its label out in white, so on and off differ in ink brightness and not only in fill.
 * `selectable` rather than `clickable` for the same reason it uses: which of the two is showing is
 * carried by fill and text colour, which a screen reader cannot see, so the selected state has to be
 * in the semantics as well.
 *
 * Lifted out of `FinancePanel` when `MarketPanel` became its second caller.
 */
@Composable
fun ViewTab(
  label: String,
  active: Boolean,
  // Ahead of `modifier`, per the same ktlint rule ActionIcon documents.
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Text(
    label.uppercase(),
    color = if (active) VdtColors.White else VdtColors.DarkGray,
    fontSize = 9.sp,
    fontWeight = FontWeight.Bold,
    modifier = modifier
      .clip(RoundedCornerShape(4.dp))
      .background(if (active) VdtColors.Green else VdtColors.TrackGray)
      .selectable(selected = active, role = Role.Tab, onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 4.dp),
  )
}
