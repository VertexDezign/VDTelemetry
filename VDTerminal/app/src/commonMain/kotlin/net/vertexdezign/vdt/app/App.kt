package net.vertexdezign.vdt.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.russhwolf.settings.Settings
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.net.ConnectionState
import net.vertexdezign.vdt.app.panels.EmptyPanel
import net.vertexdezign.vdt.app.panels.EngineTransmission
import net.vertexdezign.vdt.app.panels.Footer
import net.vertexdezign.vdt.app.panels.Header
import net.vertexdezign.vdt.app.panels.Implements
import net.vertexdezign.vdt.app.panels.Lighting
import net.vertexdezign.vdt.app.panels.MapPanel
import net.vertexdezign.vdt.app.panels.TaskListPanel
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.TaskListData
import net.vertexdezign.vdt.model.VdtData

/** The dashboard's two top-level views: the live vehicle page and the on-foot farm page. */
enum class Page { Vehicle, Farm }

@Composable
fun App(
  telemetry: VdtData?,
  connection: ConnectionState,
  mapUrl: String,
  settings: Settings,
  modifier: Modifier = Modifier,
  sampleIntervalMs: Int = 100,
  wakeLock: WakeLockStatus = WakeLockStatus.Unsupported,
  onToggleWakeLock: () -> Unit = {},
  onCommand: (ClientMessage) -> Unit = {},
  taskList: TaskListData? = null,
) {
  // Auto-switch pages on each enter/leave transition. Keying the effect on the *boolean* presence
  // (not the vehicle object) means a manual pick via the header Menu stays put until the next
  // enter/leave, and the first composition already lands on the right page.
  var page by remember { mutableStateOf(Page.Vehicle) }
  val vehiclePresent = telemetry?.vehicle != null
  LaunchedEffect(vehiclePresent) { page = if (vehiclePresent) Page.Vehicle else Page.Farm }

  MaterialTheme {
    Box(modifier.fillMaxSize().background(VdtColors.Light)) {
      when {
        telemetry == null -> LoadingScreen()

        else ->
          Dashboard(
            telemetry,
            page,
            onTogglePage = { page = if (page == Page.Vehicle) Page.Farm else Page.Vehicle },
            mapUrl,
            settings,
            sampleIntervalMs,
            wakeLock,
            onToggleWakeLock,
            onCommand,
            taskList,
          )
      }

      if (connection != ConnectionState.Connected) {
        Box(
          Modifier.fillMaxSize().background(VdtColors.Black.copy(alpha = 0.55f)),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            if (connection ==
              ConnectionState.Connecting
            ) {
              "CONNECTING…"
            } else {
              "CONNECTION LOST — RECONNECTING…"
            },
            color = VdtColors.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
          )
        }
      }
    }
  }
}

@Composable
private fun LoadingScreen() {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text("VDTERMINAL LOADING…", color = VdtColors.Green, fontSize = 28.sp, fontWeight = FontWeight.Bold)
  }
}

@Composable
private fun Dashboard(
  data: VdtData,
  page: Page,
  onTogglePage: () -> Unit,
  mapUrl: String,
  settings: Settings,
  sampleIntervalMs: Int,
  wakeLock: WakeLockStatus,
  onToggleWakeLock: () -> Unit,
  onCommand: (ClientMessage) -> Unit,
  taskList: TaskListData?,
) {
  Column(Modifier.fillMaxSize()) {
    Header(
      data.environment,
      data.vehicle,
      wakeLock = wakeLock,
      onToggleWakeLock = onToggleWakeLock,
      onTogglePage = onTogglePage,
    )

    when (page) {
      Page.Vehicle -> VehiclePage(data, mapUrl, settings, sampleIntervalMs, onCommand)
      Page.Farm -> FarmPage(data, mapUrl, settings, sampleIntervalMs, taskList)
    }
  }
}

/** The live vehicle dashboard: 3x2 panel grid, or a placeholder when a page switch left us here on foot. */
@Composable
private fun ColumnScope.VehiclePage(
  data: VdtData,
  mapUrl: String,
  settings: Settings,
  sampleIntervalMs: Int,
  onCommand: (ClientMessage) -> Unit,
) {
  val vehicle = data.vehicle
  if (vehicle == null) {
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
      Text("No vehicle connected", color = VdtColors.Green, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
    Footer(null)
    return
  }

  Column(Modifier.fillMaxWidth().weight(1f).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Cell(Modifier.weight(1f)) {
        MapPanel(mapUrl, data.environment?.pda, vehicle.gps?.heading ?: 0, sampleIntervalMs, settings)
      }
      Cell(Modifier.weight(1f)) { EngineTransmission(vehicle, sampleIntervalMs, onCommand = onCommand) }
      Cell(Modifier.weight(1f)) { Implements(vehicle, onCommand = onCommand) }
    }
    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Cell(Modifier.weight(1f)) { Lighting(vehicle, onCommand = onCommand) }
      Cell(Modifier.weight(1f)) { EmptyPanel() }
      Cell(Modifier.weight(1f)) { EmptyPanel() }
    }
  }

  Footer(vehicle, onCommand = onCommand)
}

/** Everything that isn't the current vehicle: a large map plus the farm panels (placeholders for now). */
@Composable
private fun ColumnScope.FarmPage(
  data: VdtData,
  mapUrl: String,
  settings: Settings,
  sampleIntervalMs: Int,
  taskList: TaskListData?,
) {
  Column(Modifier.fillMaxWidth().weight(1f).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      // On foot there's no vehicle heading, so the marker points north (heading = 0). A player-
      // rotation field on the environment collector is a later addition (see farm-page plan).
      Cell(Modifier.weight(2f)) {
        MapPanel(mapUrl, data.environment?.pda, heading = 0, sampleIntervalMs, settings)
      }
      Column(Modifier.fillMaxHeight().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().weight(1f)) { TaskListPanel(taskList) }
        // CropRotation stays a placeholder: its planner data isn't reachable on a dedicated-server
        // client, so that channel needs a redesign (see farm-page-plan.md).
        Box(Modifier.fillMaxWidth().weight(1f)) { PlaceholderPanel("Crop Rotation", Icons.Filled.Agriculture) }
      }
    }
  }

  Footer(null)
}

/** Titled panel with no data yet — the farm panels are wired up in a later step. */
@Composable
private fun PlaceholderPanel(title: String, icon: ImageVector) {
  Panel(title = title, icon = icon) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text("COMING SOON", color = VdtColors.Gray, fontSize = 12.sp)
    }
  }
}

@Composable
private fun RowScope.Cell(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  Box(modifier.fillMaxHeight()) { content() }
}
