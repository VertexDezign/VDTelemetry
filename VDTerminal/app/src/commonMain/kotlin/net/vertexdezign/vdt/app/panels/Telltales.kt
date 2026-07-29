package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ChangeHistory
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WbIncandescent
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.vertexdezign.vdt.model.Vehicle

/** Above this fraction of its own min..max span the coolant reads as overheating. */
private const val TEMP_WARN_FRACTION = 0.9f

/** …and at or above this much damage the machine reads as needing attention. Both are ours to pick. */
private const val DAMAGE_WARN_PERCENT = 75

/**
 * One lamp in the cluster's telltale band.
 *
 * [key] is persisted in a tile's config, so it is stable API — renaming one silently drops that lamp
 * from every band a user has configured. [colour] is the lamp lit; unlit it is [ClusterColors.Dim].
 *
 * Where two lamps share an icon (the work lights, the two diff locks) they are adjacent and ordered
 * front-then-rear, which is how a real cluster distinguishes them too. The pair is legible as a pair;
 * each still carries its own description for a screen reader.
 */
enum class Telltale(val key: String, val label: String, val icon: ImageVector, val colour: Color) {
  TurnLeft("turnLeft", "Turn left", Icons.AutoMirrored.Filled.ArrowBack, ClusterColors.Go),
  TurnRight("turnRight", "Turn right", Icons.AutoMirrored.Filled.ArrowForward, ClusterColors.Go),
  Hazard("hazard", "Hazard", Icons.Filled.ChangeHistory, ClusterColors.Warn),
  HighBeam("highBeam", "High beam", Icons.Filled.Highlight, ClusterColors.Beam),
  LowBeam("lowBeam", "Low beam", Icons.Filled.Lightbulb, ClusterColors.Go),
  WorkFront("workFront", "Work lights front", Icons.Filled.WbIncandescent, ClusterColors.Set),
  WorkRear("workRear", "Work lights rear", Icons.Filled.WbTwilight, ClusterColors.Set),
  Beacon("beacon", "Beacon", Icons.Filled.Warning, ClusterColors.Set),
  ParkingBrake("parkingBrake", "Parking brake", Icons.Filled.LocalParking, ClusterColors.Go),
  DiffLockFront("diffLockFront", "Diff lock front", Icons.Filled.Lock, ClusterColors.Set),
  DiffLockRear("diffLockRear", "Diff lock rear", Icons.Filled.Lock, ClusterColors.Set),
  Awd("awd", "All-wheel drive", Icons.Filled.Sync, ClusterColors.Go),
  EngineWarning("engineWarning", "Engine warning", Icons.Filled.PriorityHigh, ClusterColors.Warn),
}

/**
 * Whether [vehicle] has this lamp lit — or **null when we have no state for it**, which the band
 * renders as an absent lamp rather than an unlit one.
 *
 * That distinction is the whole reason the drivetrain telltales exist as nullables in the model: the
 * parking brake, the diff locks and AWD come from Enhanced Vehicle, which is optional and only
 * decorates the vehicle you're controlling. An unlit diff-lock lamp is a claim about the drivetrain,
 * and without the mod we have no standing to make it — so we say nothing instead.
 */
fun Telltale.stateIn(vehicle: Vehicle): Boolean? = when (this) {
  Telltale.TurnLeft -> vehicle.lights?.indicator?.left
  Telltale.TurnRight -> vehicle.lights?.indicator?.right
  Telltale.Hazard -> vehicle.lights?.indicator?.hazard
  Telltale.HighBeam -> vehicle.lights?.light?.highBeam
  Telltale.LowBeam -> vehicle.lights?.light?.lowBeam
  Telltale.WorkFront -> vehicle.lights?.workLight?.front
  Telltale.WorkRear -> vehicle.lights?.workLight?.back
  Telltale.Beacon -> vehicle.lights?.beaconLight
  Telltale.ParkingBrake -> vehicle.motor?.parkingBrake
  Telltale.DiffLockFront -> vehicle.motor?.diffLock?.front
  Telltale.DiffLockRear -> vehicle.motor?.diffLock?.back
  Telltale.Awd -> vehicle.motor?.awd
  Telltale.EngineWarning -> engineWarning(vehicle)
}

/**
 * The one derived lamp: hot, or damaged enough to want looking at. Null when the vehicle reports
 * neither temperature nor wear, so the lamp is absent rather than confidently unlit.
 *
 * Temperature is read as a fraction of the gauge's own min..max rather than an absolute, because that
 * span is per vehicle (20..120°C on the machines we have captures for, but that is the mod's to say).
 * A working combine sits around 89°C, so the threshold has to clear normal load comfortably.
 */
fun engineWarning(vehicle: Vehicle): Boolean? {
  val hot =
    vehicle.motor?.temperatur?.let { t ->
      val span = (t.max - t.min).toFloat()
      if (span <= 0f) null else (t.value - t.min) / span >= TEMP_WARN_FRACTION
    }
  val damaged = vehicle.wearable?.let { it.damage >= DAMAGE_WARN_PERCENT }
  if (hot == null && damaged == null) return null
  return hot == true || damaged == true
}

/**
 * The band of lamps at the top of the cluster: [lamps], in enum order, each lit or dimmed from
 * [vehicle], and each simply missing where the vehicle has nothing to say (see [stateIn]).
 *
 * Wrapping rather than scrolling. The band is glanceable or it is nothing — a lamp you have to swipe
 * to see is a lamp that will not be seen — so a band too wide for its tile becomes a second row, and
 * which lamps are worth the space is the tile's own configuration.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TelltaleBand(vehicle: Vehicle, lamps: List<Telltale>, modifier: Modifier = Modifier) {
  ClusterSurface(modifier) {
    val shown = lamps.mapNotNull { lamp -> lamp.stateIn(vehicle)?.let { lamp to it } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
      val size = lampSize(shown.size, maxWidth, maxHeight)
      FlowRow(
        Modifier.fillMaxWidth().align(Alignment.Center),
        horizontalArrangement = Arrangement.spacedBy(LAMP_GAP, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(LAMP_GAP, Alignment.CenterVertically),
      ) {
        for ((lamp, lit) in shown) {
          Icon(
            lamp.icon,
            contentDescription = "${lamp.label}: ${if (lit) "on" else "off"}",
            tint = if (lit) lamp.colour else ClusterColors.Dim,
            modifier = Modifier.size(size),
          )
        }
      }
    }
  }
}

private val LAMP_GAP = 8.dp
private val LAMP_MIN = 14.dp
private val LAMP_MAX = 48.dp

/**
 * The largest lamp that still packs [count] of them into [width] × [height].
 *
 * Sized to the tile rather than fixed, because the band is the one part of the cluster whose content
 * count is the user's: a band of four lamps in the space of thirteen should be four big lamps, not
 * four small ones adrift in a mostly empty strip. Searched down in whole dp rather than solved,
 * since the packing is a step function of how many fit per row.
 */
internal fun lampSize(count: Int, width: Dp, height: Dp): Dp {
  if (count <= 0) return LAMP_MIN
  val gap = LAMP_GAP.value
  for (size in LAMP_MAX.value.toInt() downTo LAMP_MIN.value.toInt()) {
    val perRow = ((width.value + gap) / (size + gap)).toInt().coerceAtLeast(1)
    val rows = (count + perRow - 1) / perRow
    if (rows * (size + gap) - gap <= height.value) return size.dp
  }
  return LAMP_MIN
}
