package net.vertexdezign.vdt.app.apps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import net.vertexdezign.vdt.app.panels.IsoBusPanel
import net.vertexdezign.vdt.app.state.LocalVdtStore
import net.vertexdezign.vdt.app.widgets.IsoBusWidget
import net.vertexdezign.vdt.app.widgets.Widget

/**
 * The ISOBUS app: the terminal a machine borrows from the tractor, showing what *that machine* is
 * rather than the same six fields about anything hitched.
 *
 * It **adds to** the rig panels rather than replacing them. `RigSlotPanel` answers "what is on this
 * position and how full is it", which is the right thing for a slot tile and stays; this answers
 * "what is this machine doing", which only its own class can say.
 *
 * Base-game data on the main telemetry channel, so it is always available — the panel renders its own
 * "nothing on the bus" state, exactly as an empty rig position does.
 *
 * Controls: the lower / fold / activate trio, shared with `RigSlotPanel` and addressed at whatever the
 * diagram has selected — where the command channel can name it. Pipe, cover, tip side, unloading and a
 * combine's straw choice are settable too, through commands of their own: they have no counterpart in
 * FS25_additionalInputs, so the mod calls the engine setter directly.
 *
 * The straw toggle is the one control that is *not* aimed at the selection. It belongs to the machine
 * that threshes, and on a harvesting rig the game usually has the header selected instead — see
 * `CombineSection`.
 */
object IsoBusApp : VdtApp {
  override val id = "isobus"
  override val title = "ISOBUS"
  override val icon: ImageVector = Icons.Filled.Memory

  override val widgets: List<Widget> = listOf(IsoBusWidget)

  @Composable
  override fun FullPage(modifier: Modifier) {
    val store = LocalVdtStore.current
    val telemetry by store.telemetry.collectAsState()
    // Auto: the full page has no placement to configure, so the rig diagram is the picker and it
    // opens on whatever the game has selected — the way a terminal does.
    IsoBusPanel(telemetry?.vehicle, slot = null, modifier = modifier, onCommand = store.onCommand)
  }
}
