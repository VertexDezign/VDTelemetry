package net.vertexdezign.vdt.app.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.ControlTarget
import net.vertexdezign.vdt.model.FoldableState

/**
 * Stacked, the three controls are the tallest thing in a panel, and at the default 48dp they take the
 * whole of a three-row tile before the name and condition get any. 40dp is still a comfortable target
 * on a tablet and leaves the rest of the panel room to exist.
 */
private val STACKED_HEIGHT = 40.dp
private val ROW_HEIGHT = 48.dp

/**
 * The fold / power / raise trio — the three actions any machine on the rig might have, and the only
 * three the command channel can address today.
 *
 * Shared by `RigSlotPanel` (one fixed position on a page) and `IsoBusPanel` (whatever the rig diagram
 * has selected), which is the extraction `FUTURE.md` asks for rather than a second copy: the two
 * panels differ in *which* machine they are pointed at, never in what these buttons do.
 *
 * Each control is clickable only when the machine has that aspect **and** [target] is non-null. A tap
 * sends the ABSOLUTE state for the target, computed from what is rendered — idempotent over the lossy
 * command channel, where a dropped or doubled toggle would desync (see [ClientMessage]). Front and
 * back are routed mod-side through FS25_additionalInputs.
 *
 * A **null [target] means the machine is not addressable**: `ControlTarget` reaches the vehicle and
 * its front and rear implements, so anything deeper in a hitch chain can be shown but not commanded.
 * The buttons then render the state and refuse the tap, rather than pretending to work.
 *
 * The two arrangements place identical buttons — only the container changes — so a resize can flip
 * between them without the controls themselves knowing. Stacked, they take their natural full-width
 * height instead of splitting the row three ways.
 */
@Composable
fun ImplementControls(
  foldable: FoldableState?,
  isTurnedOn: Boolean?,
  lowered: Boolean?,
  target: ControlTarget?,
  onCommand: (ClientMessage) -> Unit,
  modifier: Modifier = Modifier,
  stacked: Boolean = false,
) {
  val height = if (stacked) STACKED_HEIGHT else ROW_HEIGHT

  // Declared once and placed by whichever container wins, so the two arrangements can't drift apart.
  val buttons = listOf<@Composable (Modifier) -> Unit>(
    { mod ->
      StatusIconButton(
        Icons.Filled.UnfoldMore,
        mod,
        height = height,
        active = foldable != null,
        color = if (foldable == FoldableState.EXTENDED) StatusColor.Green else StatusColor.White,
        onClick =
        target?.let { t ->
          foldable?.let { { onCommand(ClientMessage.SetFolded(t, on = it == FoldableState.EXTENDED)) } }
        },
      )
    },
    { mod ->
      StatusIconButton(
        Icons.Filled.PowerSettingsNew,
        mod,
        height = height,
        active = isTurnedOn != null,
        color = if (isTurnedOn == true) StatusColor.Green else StatusColor.White,
        onClick = target?.let { t -> isTurnedOn?.let { { onCommand(ClientMessage.SetActivated(t, on = !it)) } } },
      )
    },
    { mod ->
      StatusIconButton(
        if (lowered == true) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
        mod,
        height = height,
        active = lowered != null,
        color = if (lowered == true) StatusColor.Green else StatusColor.White,
        onClick = target?.let { t -> lowered?.let { { onCommand(ClientMessage.SetLowered(t, on = !it)) } } },
      )
    },
  )

  if (stacked) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      for (button in buttons) button(Modifier)
    }
  } else {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      for (button in buttons) button(Modifier.weight(1f))
    }
  }
}
