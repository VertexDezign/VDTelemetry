package net.vertexdezign.vdt.app.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.material.icons.filled.Anchor
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.panels.CropRotationPanel
import net.vertexdezign.vdt.app.panels.EngineTransmission
import net.vertexdezign.vdt.app.panels.Implements
import net.vertexdezign.vdt.app.panels.Lighting
import net.vertexdezign.vdt.app.panels.MapPanel
import net.vertexdezign.vdt.app.panels.Navigation
import net.vertexdezign.vdt.app.panels.TaskListPanel
import net.vertexdezign.vdt.app.state.LocalVdtStore
import net.vertexdezign.vdt.app.theme.VdtColors

/** The PDA map with overlays; unifies the vehicle-GPS and on-foot player heading into one widget. */
object MapWidget : Widget {
  override val id = "map"
  override val title = "Map"
  override val icon: ImageVector = Icons.Filled.Map

  // The one widget that is mostly picture: it wants area, and below roughly a third of the page the
  // overlays (vehicles, field info, the layer filter) start covering the terrain they annotate.
  override val defaultColSpan = 6
  override val defaultRowSpan = 4
  override val minColSpan = 4
  override val minRowSpan = 3

  @Composable
  override fun Content(modifier: Modifier) {
    val store = LocalVdtStore.current
    val telemetry by store.telemetry.collectAsState()
    val sampleIntervalMs by store.sampleIntervalMs.collectAsState()
    val mapData by store.mapData.collectAsState()
    val mapVehicles by store.mapVehicles.collectAsState()
    val mapLayers by store.mapLayers.collectAsState()
    val fieldInfo by store.fieldInfo.collectAsState()

    val pda = telemetry?.environment?.pda
    // In a vehicle the heading is the vehicle's GPS; on foot it's the player's. Same compass
    // convention, so the marker behaves the same either way.
    val heading = telemetry?.vehicle?.gps?.heading ?: pda?.player?.heading ?: 0
    MapPanel(
      store.mapUrl,
      pda,
      heading,
      sampleIntervalMs,
      store.settings,
      modifier = modifier,
      mapData = mapData,
      mapVehicles = mapVehicles,
      fieldInfo = fieldInfo,
      mapLayerUrl = store.mapLayerUrl,
      mapLayers = mapLayers,
      onShowLayers = { ids -> store.onCommand(ClientMessage.SetMapLayers(ids)) },
    )
  }
}

/** Engine / transmission — needs a vehicle; shows an empty tile when on foot. */
object EngineWidget : Widget {
  override val id = "engine"
  override val title = "Engine and Transmission"
  override val icon: ImageVector = Icons.Filled.Agriculture

  @Composable
  override fun Content(modifier: Modifier) {
    val store = LocalVdtStore.current
    val telemetry by store.telemetry.collectAsState()
    val sampleIntervalMs by store.sampleIntervalMs.collectAsState()
    val vehicle = telemetry?.vehicle
    if (vehicle == null) {
      MissingPanel(title, icon, modifier)
    } else {
      EngineTransmission(vehicle, sampleIntervalMs, modifier, onCommand = store.onCommand)
    }
  }
}

/** Front/back implements — needs a vehicle. */
object ImplementsWidget : Widget {
  override val id = "implements"
  override val title = "Implements"
  override val icon: ImageVector = Icons.Filled.Anchor

  @Composable
  override fun Content(modifier: Modifier) {
    val store = LocalVdtStore.current
    val telemetry by store.telemetry.collectAsState()
    val vehicle = telemetry?.vehicle
    if (vehicle == null) {
      MissingPanel(title, icon, modifier)
    } else {
      Implements(vehicle, modifier, onCommand = store.onCommand)
    }
  }
}

/**
 * Heading, steering assist, AI helper and the guide-lines toggle — needs a vehicle.
 *
 * This is the old bottom bar's navigation cluster. It became a widget when the bar turned into shell
 * chrome: it's the status of one subsystem, so it belongs on a page you chose to put it on.
 */
object NavigationWidget : Widget {
  override val id = "navigation"
  override val title = "Navigation"
  override val icon: ImageVector = Icons.Filled.Explore

  // A heading and a row of status icons — it squeezes further than the readout panels do.
  override val minColSpan = 2
  override val minRowSpan = 2

  @Composable
  override fun Content(modifier: Modifier) {
    val store = LocalVdtStore.current
    val telemetry by store.telemetry.collectAsState()
    val vehicle = telemetry?.vehicle
    if (vehicle == null) {
      MissingPanel(title, icon, modifier)
    } else {
      Navigation(vehicle, modifier, onCommand = store.onCommand)
    }
  }
}

/** Vehicle lighting — needs a vehicle. */
object LightingWidget : Widget {
  override val id = "lighting"
  override val title = "Lighting"
  override val icon: ImageVector = Icons.Filled.Lightbulb

  // A grid of toggle buttons, so it stays usable small — it just fits fewer per row.
  override val minColSpan = 2
  override val minRowSpan = 2

  @Composable
  override fun Content(modifier: Modifier) {
    val store = LocalVdtStore.current
    val telemetry by store.telemetry.collectAsState()
    val vehicle = telemetry?.vehicle
    if (vehicle == null) {
      MissingPanel(title, icon, modifier)
    } else {
      Lighting(vehicle, modifier, onCommand = store.onCommand)
    }
  }
}

/** FS25_TaskList tasks (its own channel); the panel renders its own empty state. */
object TaskListWidget : Widget {
  override val id = "tasks"
  override val title = "Tasks"
  override val icon: ImageVector = Icons.Filled.Checklist

  @Composable
  override fun Content(modifier: Modifier) {
    val store = LocalVdtStore.current
    val taskList by store.taskList.collectAsState()
    TaskListPanel(taskList, modifier, onCommand = store.onCommand)
  }
}

/** FS25_CropRotation planner (its own channel); the panel renders its own empty state. */
object CropRotationWidget : Widget {
  override val id = "cropRotation"
  override val title = "Crop Rotation"
  override val icon: ImageVector = Icons.Filled.Grass

  @Composable
  override fun Content(modifier: Modifier) {
    val store = LocalVdtStore.current
    val cropRotation by store.cropRotation.collectAsState()
    CropRotationPanel(cropRotation, modifier, onCommand = store.onCommand)
  }
}

/** Panel chrome with a centered "not available" message, for widgets whose data is currently absent. */
@Composable
private fun MissingPanel(title: String, icon: ImageVector, modifier: Modifier = Modifier) {
  Panel(title = title, icon = icon, modifier = modifier) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text("No vehicle connected", color = VdtColors.DarkGray, fontSize = 12.sp)
    }
  }
}
