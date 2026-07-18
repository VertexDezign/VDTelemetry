package net.vertexdezign.vdt.app.apps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Factory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import net.vertexdezign.vdt.app.panels.ProductionsPanel
import net.vertexdezign.vdt.app.state.LocalVdtStore

/**
 * The Productions app: the local farm's owned production points and standalone storages. Base-game
 * data, so it is always available (the panel renders its own waiting/empty states). Full page only
 * for now — no dashboard tile yet.
 */
object ProductionsApp : VdtApp {
  override val id = "productions"
  override val title = "Productions"
  override val icon: ImageVector = Icons.Filled.Factory

  @Composable
  override fun FullPage(modifier: Modifier) {
    val store = LocalVdtStore.current
    val productions by store.productions.collectAsState()
    ProductionsPanel(productions, modifier, onCommand = store.onCommand)
  }
}
