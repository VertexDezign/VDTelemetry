package net.vertexdezign.vdt.app.widgets

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import net.vertexdezign.vdt.app.panels.ClusterEmpty
import net.vertexdezign.vdt.app.panels.ClusterLevels
import net.vertexdezign.vdt.app.panels.ClusterReadout
import net.vertexdezign.vdt.app.panels.Telltale
import net.vertexdezign.vdt.app.panels.TelltaleBand
import net.vertexdezign.vdt.app.state.LocalVdtStore

// The three tiles that make an A-pillar instrument cluster — the lamps, the numbers and the levels.
//
// Three widgets rather than one "cluster" widget, so they can be stacked into the cluster *and*
// placed individually on the tablet's pages: a telltale band above a map is a perfectly good thing to
// want, and it matches how widgets work everywhere else, where a tile is a tile and its configuration
// is per instance.
//
// They all render dark (see ClusterColors) rather than in the panel chrome the other widgets use,
// because the cluster is a different instrument: read off at a glance, off-axis, in a moving cab.

/** The band of warning and status lamps; which lamps is per instance. */
object TelltaleWidget : Widget {
  override val id = "telltales"
  override val title = "Telltales"
  override val icon: ImageVector = Icons.Filled.WarningAmber

  // A row of lamps that wraps: it needs width far more than height, and one row of them is a
  // perfectly useful tile.
  override val defaultColSpan = 6
  override val defaultRowSpan = 1
  override val minColSpan = 2
  override val minRowSpan = 1

  /** The config key holding which lamps this band shows. */
  const val LAMPS_KEY = "lamps"

  private val lampsOption =
    ConfigOption(
      key = LAMPS_KEY,
      label = "Lamps",
      choices = Telltale.entries.map { ConfigOption.Choice(it.key, it.label, it.icon) },
      // Which lamps matter differs per rig, and without this the band is the part of the cluster that
      // would grow forever as the model gains states.
      multi = true,
    )

  @Composable
  override fun configOptions(): List<ConfigOption> = listOf(lampsOption)

  @Composable
  override fun Content(modifier: Modifier, config: WidgetConfig) {
    val store = LocalVdtStore.current
    val telemetry by store.telemetry.collectAsState()
    val vehicle = telemetry?.vehicle
    if (vehicle == null) {
      ClusterEmpty(modifier)
    } else {
      // Resolved against the enum, so a key from a lamp that has since been renamed away simply
      // isn't shown — for a set, a stale entry is one missing lamp rather than a broken tile.
      val chosen = lampsOption.resolveAll(config).toSet()
      TelltaleBand(vehicle, Telltale.entries.filter { it.key in chosen }, modifier)
    }
  }
}

/** Engine speed, ground speed, gear and cruise, in the largest type that fits. */
object ClusterReadoutWidget : Widget {
  override val id = "clusterReadout"
  override val title = "Cluster Readout"
  override val icon: ImageVector = Icons.Filled.Speed

  override val defaultColSpan = 4
  override val defaultRowSpan = 3
  override val minColSpan = 2
  override val minRowSpan = 2

  @Composable
  override fun Content(modifier: Modifier, config: WidgetConfig) {
    val store = LocalVdtStore.current
    val telemetry by store.telemetry.collectAsState()
    val sampleIntervalMs by store.sampleIntervalMs.collectAsState()
    val vehicle = telemetry?.vehicle
    if (vehicle == null) {
      ClusterEmpty(modifier)
    } else {
      ClusterReadout(vehicle, sampleIntervalMs, modifier)
    }
  }
}

/** Vertical bars for the engine's own levels — coolant temperature and its tanks. Never cargo. */
object ClusterLevelsWidget : Widget {
  override val id = "clusterLevels"
  override val title = "Level Strip"
  override val icon: ImageVector = Icons.Filled.Straighten

  // Bars are read by height, so height is what it wants; it narrows to two cells before the bars
  // stop being comparable.
  override val defaultColSpan = 3
  override val defaultRowSpan = 3
  override val minColSpan = 2
  override val minRowSpan = 2

  @Composable
  override fun Content(modifier: Modifier, config: WidgetConfig) {
    val store = LocalVdtStore.current
    val telemetry by store.telemetry.collectAsState()
    val vehicle = telemetry?.vehicle
    if (vehicle == null) {
      ClusterEmpty(modifier)
    } else {
      ClusterLevels(vehicle, modifier)
    }
  }
}
