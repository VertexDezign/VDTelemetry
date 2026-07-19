package net.vertexdezign.vdt.app.apps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Factory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import net.vertexdezign.vdt.app.panels.ProductionPanel
import net.vertexdezign.vdt.app.state.LocalVdtStore

/**
 * The Production app: the local farm's owned production points (incl. factories). Base-game data, so
 * it is always available (the panel renders its own waiting/empty states). Sibling of [StorageApp],
 * split off the former "Production & Storage" app so each has its own channel. Full page only for now
 * — no dashboard tile yet.
 */
object ProductionApp : VdtApp {
  override val id = "production"
  override val title = "Production"
  override val icon: ImageVector = Icons.Filled.Factory

  @Composable
  override fun FullPage(modifier: Modifier) {
    val store = LocalVdtStore.current
    val production by store.production.collectAsState()
    ProductionPanel(production, modifier, onCommand = store.onCommand)
  }
}
