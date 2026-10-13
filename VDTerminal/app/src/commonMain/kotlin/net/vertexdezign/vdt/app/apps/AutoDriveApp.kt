package net.vertexdezign.vdt.app.apps

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import net.vertexdezign.vdt.app.panels.AutoDrivePanel
import net.vertexdezign.vdt.app.panels.MapPanel
import net.vertexdezign.vdt.app.state.LocalVdtStore
import net.vertexdezign.vdt.app.widgets.AutoDriveWidget
import net.vertexdezign.vdt.app.widgets.Widget
import net.vertexdezign.vdt.app.widgets.WidgetSettings

/** Below this width the app is the controls alone; the map is the Map app's, with the network filter. */
private val SIDE_BY_SIDE_WIDTH = 720.dp

/**
 * AutoDrive (issue #97): its HUD as a panel, beside a map of the route network with the machine's
 * destinations marked. Available while AutoDrive is installed — the channel's presence, like the other
 * optional mods'.
 *
 * The map here is its own instance (`autoDrive.map`), so it keeps its own zoom, and starts with the
 * whole network switched on, where the ordinary map keeps it behind a filter row.
 */
object AutoDriveApp : VdtApp {
  override val id = "autoDrive"
  override val title = "AutoDrive"
  override val icon: ImageVector = Icons.Filled.Route
  override val widgets: List<Widget> = listOf(AutoDriveWidget)

  @Composable
  override fun isAvailable(): Boolean {
    val network by LocalVdtStore.current.autoDrive.collectAsState()
    return network != null
  }

  @Composable
  override fun FullPage(modifier: Modifier) {
    val store = LocalVdtStore.current
    val telemetry by store.telemetry.collectAsState()
    val network by store.autoDrive.collectAsState()
    BoxWithConstraints(modifier.fillMaxSize()) {
      if (maxWidth < SIDE_BY_SIDE_WIDTH) {
        AutoDrivePanel(telemetry?.vehicle, network, onCommand = store.onCommand)
      } else {
        Row {
          AutoDrivePanel(telemetry?.vehicle, network, Modifier.width(360.dp).fillMaxHeight(), store.onCommand)
          NetworkMap(Modifier.weight(1f).fillMaxHeight())
        }
      }
    }
  }
}

@Composable
private fun NetworkMap(modifier: Modifier = Modifier) {
  val store = LocalVdtStore.current
  val telemetry by store.telemetry.collectAsState()
  val sampleIntervalMs by store.sampleIntervalMs.collectAsState()
  val mapData by store.mapData.collectAsState()
  val mapVehicles by store.mapVehicles.collectAsState()
  val network by store.autoDrive.collectAsState()
  val pda = telemetry?.environment?.pda
  MapPanel(
    store.mapUrl,
    pda,
    telemetry?.vehicle?.gps?.heading ?: pda?.player?.heading ?: 0,
    sampleIntervalMs,
    remember(store.settings) { WidgetSettings(store.settings, "autoDrive.map") },
    modifier = modifier,
    mapData = mapData,
    mapVehicles = mapVehicles,
    vehicle = telemetry?.vehicle,
    onCommand = store.onCommand,
    autoDrive = network,
    showAutoDriveByDefault = true,
  )
}
