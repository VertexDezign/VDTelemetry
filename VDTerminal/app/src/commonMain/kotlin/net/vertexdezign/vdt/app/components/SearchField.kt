package net.vertexdezign.vdt.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.theme.VdtColors

/**
 * A panel's search box: one line, a placeholder while empty.
 *
 * A [BasicTextField] with a `decorationBox` rather than Material's `OutlinedTextField` — the terminal's
 * fields are 13sp on a 4dp radius, well under the ~56dp minimum height Material's own decoration
 * imposes, and every text input in the app is built this way.
 *
 * Lifted out of `MapPanel`'s field/POI search when the calendar became its second caller.
 */
@Composable
fun SearchField(
  value: String,
  placeholder: String,
  // Ahead of `modifier`, per the same ktlint rule ActionIcon documents.
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  BasicTextField(
    value = value,
    onValueChange = onValueChange,
    singleLine = true,
    textStyle = TextStyle(fontSize = 13.sp, color = VdtColors.TextDark),
    modifier =
    modifier
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.White)
      .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(4.dp))
      .padding(horizontal = 8.dp, vertical = 6.dp),
    decorationBox = { inner ->
      Box {
        if (value.isEmpty()) {
          Text(placeholder, fontSize = 13.sp, color = VdtColors.DarkGray)
        }
        inner()
      }
    },
  )
}
