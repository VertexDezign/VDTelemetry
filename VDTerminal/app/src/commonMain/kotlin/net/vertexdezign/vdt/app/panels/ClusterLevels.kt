package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import net.vertexdezign.vdt.model.FillUnit
import net.vertexdezign.vdt.model.Vehicle

/** At or below this share of capacity a level reads red… */
private const val CRITICAL_FRACTION = 0.1f

/** …and below this, amber. Above it, green. */
private const val LOW_FRACTION = 0.25f

/** A bar is read against its neighbour, so past this it stops being a bar and becomes a slab. */
private val BAR_MAX_WIDTH = 40.dp

/** One bar: a level, what it is, and the icon that says so at a glance. */
internal data class Level(val label: String, val fraction: Float, val icon: ImageVector)

/**
 * The strip of vertical bars along the bottom of the cluster: the engine's own tanks, and only those.
 *
 * The compact form of [net.vertexdezign.vdt.app.components.FillUnitsDisplay], which is horizontal and
 * labelled and belongs on a page you read. Here a level is a column you compare against its
 * neighbours without reading anything: height is the level, colour is whether it is a problem, and
 * the icon underneath says which tank it is.
 *
 * Cargo is deliberately not here — not the vehicle's, not the implements'. This strip answers "can
 * the machine keep going", which is a fixed three bars that mean the same thing on every rig. What is
 * in the hopper is a different question, it changes shape as you hitch things up, and the rig-slot
 * tiles already answer it properly with names and figures.
 */
@Composable
fun ClusterLevels(vehicle: Vehicle, modifier: Modifier = Modifier) {
  val levels = levelsOf(vehicle)
  ClusterSurface(modifier) {
    if (levels.isEmpty()) {
      Text("—", color = ClusterColors.Dim, modifier = Modifier.align(Alignment.Center))
      return@ClusterSurface
    }
    Row(
      Modifier.fillMaxSize(),
      horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
      for (level in levels) {
        // An equal share of the width each, but capped: three bars across a landscape tile would
        // otherwise become slabs, and a bar's job is to be compared with its neighbour, not to be big.
        LevelBar(level, Modifier.weight(1f).widthIn(max = BAR_MAX_WIDTH).fillMaxHeight())
      }
    }
  }
}

/** The engine's fuel, DEF and air, in that fixed order — so a bar is always in the same place. */
internal fun levelsOf(vehicle: Vehicle): List<Level> = buildList {
  val engine = vehicle.motor?.fillUnits ?: return@buildList
  engine.fuel?.let { add(it.level(Icons.Filled.LocalGasStation, "FUEL")) }
  engine.def?.let { add(it.level(Icons.Filled.WaterDrop, "DEF")) }
  engine.air?.let { add(it.level(Icons.Filled.Air, "AIR")) }
}

/** A named engine tank always shows, even full — a missing air bar reads as a fault, not as "fine". */
private fun FillUnit.level(icon: ImageVector, label: String) = Level(label, fraction(), icon)

/**
 * Prefer litres over capacity to the pre-rounded percentage: the integer percent staircases about 1%
 * at a time and visibly judders on a bar this size while the litres climb smoothly.
 */
private fun FillUnit.fraction(): Float =
  (if (capacity > 0) value / capacity else fillLevelPercentage / 100f).coerceIn(0f, 1f)

@Composable
private fun LevelBar(level: Level, modifier: Modifier = Modifier) {
  val colour = when {
    level.fraction <= CRITICAL_FRACTION -> ClusterColors.Warn
    level.fraction < LOW_FRACTION -> ClusterColors.Set
    else -> ClusterColors.Go
  }
  Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Box(
      Modifier
        .weight(1f)
        .fillMaxWidth()
        .clip(RoundedCornerShape(2.dp))
        .background(ClusterColors.Track),
    ) {
      // The reserve mark: the bottom tenth of every track is red whatever the level, so the point at
      // which a bar becomes a problem is visible before it gets there rather than only once it does.
      Box(
        Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth()
          .fillMaxHeight(CRITICAL_FRACTION)
          .background(ClusterColors.Warn),
      )
      Box(
        Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth()
          .fillMaxHeight(level.fraction)
          .background(colour),
      )
    }
    // Icon only, as on the reference. A pump, a droplet and a draught of air need no caption, and the
    // three bars are in a fixed order anyway; the label lives on for the screen reader.
    Icon(
      level.icon,
      contentDescription = level.label,
      tint = colour,
      modifier = Modifier.padding(top = 6.dp).size(16.dp),
    )
  }
}
