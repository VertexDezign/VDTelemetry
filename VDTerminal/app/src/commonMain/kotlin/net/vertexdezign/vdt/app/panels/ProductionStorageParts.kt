package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.theme.VdtColors

// Small pieces shared by the sibling ProductionPanel and StoragePanel master/detail views — both list
// owned placeables the same way (a selectable name + subtitle row) and format liter counts the same
// way. Kept here so the two panels stay independent files without duplicating them.

/** One selectable row in a master list: a bold name over a muted subtitle, green when selected. */
@Composable
internal fun OwnedRow(name: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
  val bg = if (selected) VdtColors.Green else VdtColors.TrackGray
  val fg = if (selected) VdtColors.White else VdtColors.TextDark
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(bg)
      .clickable(onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 8.dp),
  ) {
    Text(
      name,
      color = fg,
      fontSize = 13.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      subtitle,
      color = if (selected) VdtColors.White.copy(alpha = 0.85f) else VdtColors.Gray,
      fontSize = 10.sp,
    )
  }
}

/** Group a non-negative liter/object count with thousands separators (e.g. 145000 -> "145,000"). */
internal fun formatInt(value: Int): String {
  val digits = value.toString()
  if (digits.length <= 3) return digits
  val sb = StringBuilder()
  val firstGroup = digits.length % 3
  if (firstGroup > 0) {
    sb.append(digits, 0, firstGroup)
  }
  var i = firstGroup
  while (i < digits.length) {
    if (sb.isNotEmpty()) sb.append(',')
    sb.append(digits, i, i + 3)
    i += 3
  }
  return sb.toString()
}
