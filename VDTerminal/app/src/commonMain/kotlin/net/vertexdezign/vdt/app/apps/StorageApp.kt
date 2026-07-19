package net.vertexdezign.vdt.app.apps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import net.vertexdezign.vdt.app.panels.StoragePanel
import net.vertexdezign.vdt.app.state.LocalVdtStore

/**
 * The Storage app: the local farm's owned standalone storages (liter silos + object storages).
 * Base-game data, so it is always available (the panel renders its own waiting/empty states). Sibling
 * of [ProductionApp], split off the former "Production & Storage" app so each has its own channel.
 * Full page only for now — no dashboard tile yet.
 */
object StorageApp : VdtApp {
  override val id = "storage"
  override val title = "Storage"
  override val icon: ImageVector = Icons.Filled.Warehouse

  @Composable
  override fun FullPage(modifier: Modifier) {
    val store = LocalVdtStore.current
    val storage by store.storage.collectAsState()
    StoragePanel(storage, modifier, onCommand = store.onCommand)
  }
}
