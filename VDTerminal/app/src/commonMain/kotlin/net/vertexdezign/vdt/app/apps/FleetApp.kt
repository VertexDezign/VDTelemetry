package net.vertexdezign.vdt.app.apps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import net.vertexdezign.vdt.app.LocalNavigator
import net.vertexdezign.vdt.app.Screen
import net.vertexdezign.vdt.app.panels.FleetPanel
import net.vertexdezign.vdt.app.state.LocalVdtStore
import net.vertexdezign.vdt.app.state.MapFocus

/**
 * The Fleet app: every machine the farm owns, its condition, its hours and — where Advanced Damage
 * System is installed — what maintenance it is due. Base-game data, so it is always available; the
 * panel renders its own waiting/empty states, and the maintenance card simply isn't there without
 * ADS.
 *
 * Read-only, with one exception that is not really an action on the machine: a row can put itself on
 * the map. That hand-off is why the app rather than the panel owns the navigation — it hands the
 * position to [MapFocus] and opens the map, which consumes it once (see `MapPanel`).
 */
object FleetApp : VdtApp {
  override val id = "fleet"
  override val title = "Fleet"
  override val icon: ImageVector = Icons.Filled.Agriculture

  @Composable
  override fun FullPage(modifier: Modifier) {
    val fleet by LocalVdtStore.current.fleet.collectAsState()
    val navigate = LocalNavigator.current
    FleetPanel(fleet, modifier) { vehicle ->
      val x = vehicle.posX
      val z = vehicle.posZ
      if (x != null && z != null) {
        MapFocus.request(x, z)
        navigate(Screen.OpenApp(MapApp.id))
      }
    }
  }
}
