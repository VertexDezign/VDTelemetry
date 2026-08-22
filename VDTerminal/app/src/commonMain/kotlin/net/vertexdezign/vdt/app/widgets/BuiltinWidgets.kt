package net.vertexdezign.vdt.app.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AccountBalance
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
import net.vertexdezign.vdt.app.panels.FinanceSummary
import net.vertexdezign.vdt.app.panels.InvoicesSummary
import net.vertexdezign.vdt.app.panels.Lighting
import net.vertexdezign.vdt.app.panels.MapPanel
import net.vertexdezign.vdt.app.panels.MissionsSummary
import net.vertexdezign.vdt.app.panels.Navigation
import net.vertexdezign.vdt.app.panels.RigSlot
import net.vertexdezign.vdt.app.panels.RigSlotPanel
import net.vertexdezign.vdt.app.panels.TaskListPanel
import net.vertexdezign.vdt.app.panels.WeatherIcons
import net.vertexdezign.vdt.app.panels.WeatherSummary
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

  /** The config key deciding whether this map carries the navigation strip. */
  const val GUIDANCE_KEY = "guidance"

  private val guidanceOption =
    ConfigOption(
      key = GUIDANCE_KEY,
      label = "Navigation strip",
      // "Off" first, so resolve()'s default keeps an unconfigured map exactly as it was — the strip
      // covers terrain, and a map placed as an overview never asked for it. A map placed as a run
      // screen turns it on and gets the heading where the driving happens.
      choices =
      listOf(
        ConfigOption.Choice(GUIDANCE_OFF, "Off"),
        ConfigOption.Choice(GUIDANCE_ON, "On"),
      ),
    )

  /** The config key deciding whether this map carries the boom along its bottom edge. */
  const val SECTIONS_KEY = "sections"

  private val sectionsOption =
    ConfigOption(
      key = SECTIONS_KEY,
      label = "Section bar",
      // Off first for the same reason as the navigation strip: it covers terrain, and it is only ever
      // wanted on a map placed as a run screen. Separate from that strip rather than folded into one
      // "run screen" switch — a sprayer wants the boom, and a map watched from the yard wants neither.
      choices =
      listOf(
        ConfigOption.Choice(SECTIONS_OFF, "Off"),
        ConfigOption.Choice(SECTIONS_ON, "On"),
      ),
    )

  @Composable
  override fun configOptions(): List<ConfigOption> = listOf(guidanceOption, sectionsOption)

  @Composable
  override fun Content(modifier: Modifier, config: WidgetConfig) {
    val store = LocalVdtStore.current
    val telemetry by store.telemetry.collectAsState()
    val sampleIntervalMs by store.sampleIntervalMs.collectAsState()
    val mapData by store.mapData.collectAsState()
    val mapVehicles by store.mapVehicles.collectAsState()
    val mapLayers by store.mapLayers.collectAsState()
    val fieldInfo by store.fieldInfo.collectAsState()
    val gpsCourse by store.gpsCourse.collectAsState()
    val missions by store.missions.collectAsState()

    val pda = telemetry?.environment?.pda
    // In a vehicle the heading is the vehicle's GPS; on foot it's the player's. Same compass
    // convention, so the marker behaves the same either way.
    val heading = telemetry?.vehicle?.gps?.heading ?: pda?.player?.heading ?: 0
    MapPanel(
      store.mapUrl,
      pda,
      heading,
      sampleIntervalMs,
      // Scoped to this tile: zoom, filters and the ground layer are per placed map, so a page can
      // hold an overview and a zoomed-in working view side by side.
      rememberWidgetSettings(store.settings),
      modifier = modifier,
      mapData = mapData,
      mapVehicles = mapVehicles,
      fieldInfo = fieldInfo,
      mapLayerUrl = store.mapLayerUrl,
      coverageResetUrl = store.coverageResetUrl,
      mapLayers = mapLayers,
      onShowLayers = { ids -> store.onCommand(ClientMessage.SetMapLayers(ids)) },
      vehicle = telemetry?.vehicle,
      // A stored value this widget no longer offers falls back to off: the choices are fixed, so
      // anything else is a stale key rather than a preference worth honouring.
      showGuidance = guidanceOption.resolve(config) == GUIDANCE_ON,
      showSections = sectionsOption.resolve(config) == SECTIONS_ON,
      onCommand = store.onCommand,
      gpsCourse = gpsCourse,
      missions = missions,
    )
  }
}

/** [MapWidget.GUIDANCE_KEY] values. Slugs, not booleans: they are persisted in saved layouts. */
private const val GUIDANCE_OFF = "off"
private const val GUIDANCE_ON = "on"

/** [MapWidget.SECTIONS_KEY] values, persisted in saved layouts like the guidance ones. */
private const val SECTIONS_OFF = "off"
private const val SECTIONS_ON = "on"

/** Engine / transmission — needs a vehicle; shows an empty tile when on foot. */
object EngineWidget : Widget {
  override val id = "engine"
  override val title = "Engine and Transmission"
  override val icon: ImageVector = Icons.Filled.Agriculture

  @Composable
  override fun Content(modifier: Modifier, config: WidgetConfig) {
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

/**
 * One position on the rig — the vehicle itself, or its front or rear implement. Needs a vehicle.
 *
 * Which position is per-instance config, so a page places one of these per slot instead of taking a
 * fixed two-column panel: front on the left, the map in the middle, rear on the right.
 */
object RigSlotWidget : Widget {
  override val id = "rigSlot"
  override val title = "Rig Position"
  override val icon: ImageVector = Icons.Filled.Anchor

  // Taller than wide: the panel is a stack (name, condition, controls, load) that reads as a column
  // beside the map. It goes down to a single cell, where the panel stacks its controls instead of
  // rowing them — worth the extra row of height, because a column of these is a layout the two-column
  // panel this replaced could never have made.
  override val defaultColSpan = 3
  override val defaultRowSpan = 4
  override val minColSpan = 1
  override val minRowSpan = 3

  /** The config key naming the position this tile renders. */
  const val SLOT_KEY = "slot"

  private val slotOption =
    ConfigOption(
      key = SLOT_KEY,
      label = "Position",
      // Fixed choices — every rig has all three positions, whether or not something is in them — so
      // unlike the shortcut's apps these never narrow, and resolve()'s default is always meaningful.
      choices = RigSlot.entries.map { ConfigOption.Choice(it.name, it.label) },
    )

  @Composable
  override fun configOptions(): List<ConfigOption> = listOf(slotOption)

  @Composable
  override fun Content(modifier: Modifier, config: WidgetConfig) {
    val store = LocalVdtStore.current
    val telemetry by store.telemetry.collectAsState()
    val slot = RigSlot.entries.firstOrNull { it.name == slotOption.resolve(config) } ?: RigSlot.VEHICLE
    val vehicle = telemetry?.vehicle
    if (vehicle == null) {
      MissingPanel(slot.label, icon, modifier)
    } else {
      // Scoped to this tile: whether the load reads merged per fill type is per placed slot.
      RigSlotPanel(slot, vehicle, rememberWidgetSettings(store.settings), modifier, onCommand = store.onCommand)
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
  override fun Content(modifier: Modifier, config: WidgetConfig) {
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
  override fun Content(modifier: Modifier, config: WidgetConfig) {
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
  override fun Content(modifier: Modifier, config: WidgetConfig) {
    val store = LocalVdtStore.current
    val taskList by store.taskList.collectAsState()
    TaskListPanel(taskList, modifier, onCommand = store.onCommand)
  }
}

/** Contract progress at a glance; the full accept/collect flow lives in the Missions app. */
object MissionsWidget : Widget {
  override val id = "missions"
  override val title = "Contracts"
  override val icon: ImageVector = Icons.AutoMirrored.Filled.Assignment

  @Composable
  override fun Content(modifier: Modifier, config: WidgetConfig) {
    val missions by LocalVdtStore.current.missions.collectAsState()
    MissionsSummary(missions, modifier)
  }
}

/**
 * The farm's balance and this month's running total. The headline only — the finances table needs the
 * full page, and a tile that tried to hold it would be unreadable at any placeable size.
 */
object FinanceWidget : Widget {
  override val id = "finance"
  override val title = "Finance"
  override val icon: ImageVector = Icons.Filled.AccountBalance
  override val defaultColSpan = 3
  override val defaultRowSpan = 2

  @Composable
  override fun Content(modifier: Modifier, config: WidgetConfig) {
    val finance by LocalVdtStore.current.finance.collectAsState()
    FinanceSummary(finance, modifier)
  }
}

/**
 * What this farm owes and is owed (FS25_Invoices). The glanceable half of the invoices view — a total
 * and a count, red once anything has gone overdue. The tile renders its own "not installed" state, so
 * a page that carries it survives the mod being removed.
 */
object InvoicesWidget : Widget {
  override val id = "invoices"
  override val title = "Invoices"
  override val icon: ImageVector = Icons.AutoMirrored.Filled.ReceiptLong
  override val defaultColSpan = 3
  override val defaultRowSpan = 2

  @Composable
  override fun Content(modifier: Modifier, config: WidgetConfig) {
    val invoices by LocalVdtStore.current.invoices.collectAsState()
    InvoicesSummary(invoices, modifier)
  }
}

/** FS25_CropRotation planner (its own channel); the panel renders its own empty state. */
object CropRotationWidget : Widget {
  override val id = "cropRotation"
  override val title = "Crop Rotation"
  override val icon: ImageVector = Icons.Filled.Grass

  @Composable
  override fun Content(modifier: Modifier, config: WidgetConfig) {
    val store = LocalVdtStore.current
    val cropRotation by store.cropRotation.collectAsState()
    CropRotationPanel(cropRotation, modifier, onCommand = store.onCommand)
  }
}

/**
 * The forecast at a glance: now, then the next few two-hourly steps. The glanceable half of the
 * Calendar app — the crop grid needs a full page, and a tile that tried to hold twelve periods of it
 * would be unreadable at any placeable size.
 */
object WeatherWidget : Widget {
  override val id = "weather"
  override val title = "Weather"
  override val icon: ImageVector = WeatherIcons.PartiallyCloudy
  override val defaultColSpan = 4
  override val defaultRowSpan = 2
  override val minColSpan = 2

  @Composable
  override fun Content(modifier: Modifier, config: WidgetConfig) {
    val weather by LocalVdtStore.current.weather.collectAsState()
    WeatherSummary(weather, modifier)
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
