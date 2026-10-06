package net.vertexdezign.vdt.app.apps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Construction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import net.vertexdezign.vdt.app.panels.LoaderPanel
import net.vertexdezign.vdt.app.state.LocalVdtStore
import net.vertexdezign.vdt.app.widgets.LoaderWidget
import net.vertexdezign.vdt.app.widgets.Widget

/**
 * The Loader app (issue #169): a front loader's, wheel loader's, telehandler's or skid steer's tool —
 * how far off level it is, how high, and where each cylinder is — with "set level" for the tool.
 *
 * The same screen the ISOBUS app opens on a loader. Its own app because the terminal follows the
 * game's selection and this does not: see [LoaderPanel].
 *
 * Base-game data on the main telemetry channel, so it is always available; it says so when the rig
 * has no loader on it.
 */
object LoaderApp : VdtApp {
  override val id = "loader"
  override val title = "Loader"
  override val icon: ImageVector = Icons.Filled.Construction

  override val widgets: List<Widget> = listOf(LoaderWidget)

  @Composable
  override fun FullPage(modifier: Modifier) {
    val store = LocalVdtStore.current
    val telemetry by store.telemetry.collectAsState()
    LoaderPanel(telemetry?.vehicle, modifier, onCommand = store.onCommand)
  }
}
