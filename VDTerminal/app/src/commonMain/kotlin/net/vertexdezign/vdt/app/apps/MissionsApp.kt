package net.vertexdezign.vdt.app.apps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import net.vertexdezign.vdt.app.panels.MissionsPanel
import net.vertexdezign.vdt.app.state.LocalVdtStore
import net.vertexdezign.vdt.app.widgets.MissionsWidget
import net.vertexdezign.vdt.app.widgets.Widget

/**
 * The Missions app: the farm's contracts — what is on offer, what it is running, what is waiting to
 * be collected — and the actions the game itself offers on them. Base-game data, so it is always
 * available (the panel renders its own waiting/empty states).
 */
object MissionsApp : VdtApp {
  override val id = "missions"
  override val title = "Contracts"
  override val icon: ImageVector = Icons.AutoMirrored.Filled.Assignment
  override val widgets: List<Widget> = listOf(MissionsWidget)

  @Composable
  override fun FullPage(modifier: Modifier) {
    val store = LocalVdtStore.current
    val missions by store.missions.collectAsState()
    MissionsPanel(missions, modifier, onCommand = store.onCommand)
  }
}
