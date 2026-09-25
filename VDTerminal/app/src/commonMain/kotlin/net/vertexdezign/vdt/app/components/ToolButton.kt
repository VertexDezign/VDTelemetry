package net.vertexdezign.vdt.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.vertexdezign.vdt.app.theme.VdtColors

/** How big a [ToolButton] is drawn. The touch area is at least 48dp either way — see [ToolButton]. */
object ToolButtonSize {
  /** In a panel header, beside the title. */
  val Header = 32.dp

  /** On a surface of its own — over the map, in a dialog. */
  val Standalone = 40.dp
}

/**
 * An icon you tap: a panel header's zoom, filter or merge, a list row's remove.
 *
 * It is drawn as a button — a raised, outlined square — because a bare 16dp glyph is a thing you have to
 * know is tappable, and in a moving cab you aim at what you can see. Compose widens a small target's
 * *touch* area to 48dp on its own, so [size] is about being seen and hit first time, not about whether
 * a finger lands at all; two neighbours both widened are split at the midpoint, which is why the
 * visible size still matters when they sit in a row.
 *
 * [active] makes it a toggle: null is a plain action, true/false an on/off state. On fills the button
 * and knocks the icon out in [VdtColors.OnFill], off is the outlined surface — the "fill the chip" rule
 * from `VDTerminal/README.md` → "Design rules", so the state never rests on hue. It is in the semantics
 * too, since the fill is not something a screen reader can see.
 */
@Composable
fun ToolButton(
  icon: ImageVector,
  description: String,
  // Ahead of `modifier`, per the ktlint rule ActionIcon documents.
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  active: Boolean? = null,
  enabled: Boolean = true,
  size: Dp = ToolButtonSize.Header,
  /** The icon's ink when off, for a button whose kind is its colour — a red delete. */
  tint: Color = VdtColors.DarkGray,
) {
  val shape = RoundedCornerShape(6.dp)
  val on = active == true
  val ink =
    when {
      !enabled -> VdtColors.TextDisabled
      on -> VdtColors.OnFill
      else -> tint
    }
  val interaction =
    if (active != null) {
      Modifier.toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = { onClick() })
    } else {
      Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    }
  Box(
    modifier
      .size(size)
      .clip(shape)
      .background(if (on) VdtColors.Green else VdtColors.Surface)
      .border(1.dp, if (on) VdtColors.Green else VdtColors.PanelBorder, shape)
      .then(interaction),
    contentAlignment = Alignment.Center,
  ) {
    Icon(icon, contentDescription = description, tint = ink, modifier = Modifier.size(size * ICON_SHARE))
  }
}

/** The icon's share of the button: 20dp in a header button, 25dp standing alone. */
private const val ICON_SHARE = 0.625f
