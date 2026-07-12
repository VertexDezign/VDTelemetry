package net.vertexdezign.vdt.app.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.theme.VdtColors

// The small pieces the farm-page panels (TaskListPanel, CropRotationPanel) share: a tappable row
// action and a centered message. They lived as identical private copies in both panels; one
// definition keeps a tweak to the hit area or the empty-state colour from reaching only half the
// farm page.

/** A tappable icon inside a list row (complete / delete / add …). Sized for a touch target. */
@Composable
fun ActionIcon(
  icon: ImageVector,
  description: String,
  tint: Color,
  // Ahead of `modifier` (which is the first *optional* parameter, per convention): an action icon
  // without a click does nothing, so onClick stays required — and a required event lambda may not be
  // the trailing parameter (ktlint compose:lambda-param-event-trailing). Callers name it.
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Icon(
    icon,
    contentDescription = description,
    tint = tint,
    modifier = modifier.size(20.dp).clip(CircleShape).clickable(onClick = onClick).padding(1.dp),
  )
}

/** A panel's centered one-liner: "not installed", "no tasks", … */
@Composable
fun Centered(text: String, modifier: Modifier = Modifier) {
  Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(text, color = VdtColors.Gray, fontSize = 12.sp)
  }
}
