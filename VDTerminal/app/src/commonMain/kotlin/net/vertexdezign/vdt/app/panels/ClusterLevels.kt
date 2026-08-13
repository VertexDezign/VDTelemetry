package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import net.vertexdezign.vdt.model.FillUnit
import net.vertexdezign.vdt.model.Temperatur
import net.vertexdezign.vdt.model.Vehicle

/** At or beyond this share of the gauge a level is critical… */
private const val CRITICAL_FRACTION = 0.1f

/** …and beyond this, worth watching. */
private const val LOW_FRACTION = 0.25f

/**
 * A bar is read against its neighbour, so past a point it stops being a bar and becomes a slab. The
 * limit is a share of its own height rather than a fixed width, because the tile is two very
 * different shapes: tall and narrow under a portrait cluster, short and wide beside a landscape one.
 * A fixed cap sized for the second left four bars stranded in the middle of the first with half the
 * tile empty either side.
 */
private const val BAR_MAX_ASPECT = 0.34f

/** …but never thinner than this, however short the tile gets. */
private val BAR_MIN_WIDTH = 10.dp

/**
 * Which end of a bar the trouble is at.
 *
 * A tank is in trouble when it is nearly empty and a temperature when it is nearly full, and both are
 * on this strip. Without this the coolant gauge would read as a fuel tank with plenty left in it —
 * which is the wrong way round in the one case where it matters.
 */
internal enum class Danger { Low, High }

/** One bar: a level, which end of it is bad, what it is, and the icon that says so at a glance. */
internal data class Level(
  val label: String,
  val fraction: Float,
  val icon: ImageVector,
  val danger: Danger = Danger.Low,
) {
  /** How far into the bad end this level is, 0 at the safe end and 1 at the bad one. */
  val severity: Float get() = if (danger == Danger.Low) 1f - fraction else fraction
}

/**
 * The strip of vertical bars along the bottom of the cluster: what the engine needs to keep running,
 * and only that.
 *
 * The compact form of [net.vertexdezign.vdt.app.components.FillUnitsDisplay], which is horizontal and
 * labelled and belongs on a page you read. Here a level is a column you compare against its
 * neighbours without reading anything: height is the level, the frame around it says where the
 * trouble starts, and the icon underneath says which gauge it is.
 *
 * Cargo is deliberately not here — not the vehicle's, not the implements'. This strip answers "can
 * the machine keep going", which is a fixed handful of bars that mean the same thing on every rig.
 * What is in the hopper is a different question, it changes shape as you hitch things up, and the
 * rig-slot tiles already answer it properly with names and figures.
 */
@Composable
fun ClusterLevels(vehicle: Vehicle, modifier: Modifier = Modifier) {
  val levels = levelsOf(vehicle)
  ClusterSurface(modifier) {
    if (levels.isEmpty()) {
      Text("-", color = ClusterColors.Dim, fontFamily = clusterDigitFont(), modifier = Modifier.align(Alignment.Center))
      return@ClusterSurface
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
      // The bar itself, less the icon under it — which is what the cap is a share of.
      val barHeight = maxHeight - ICON_SIZE - ICON_GAP
      val cap = (barHeight * BAR_MAX_ASPECT).coerceAtLeast(BAR_MIN_WIDTH)
      Row(
        Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(BAR_GAP, Alignment.CenterHorizontally),
      ) {
        // An equal share of the width each, up to that cap: a bar's job is to be compared with its
        // neighbour, not to be big.
        for (level in levels) LevelBar(level, Modifier.weight(1f).widthIn(max = cap).fillMaxHeight())
      }
    }
  }
}

/**
 * The gauges, in a fixed order — so a bar is always in the same place, whatever this machine happens
 * to carry.
 *
 * Condition before contents, as on the reference cluster: the temperatures sit to the left of the
 * tanks. That ordering is what lets the strip grow without moving anything — the CVT bar arrived
 * beside the coolant one rather than shoving the fuel bar the driver has learned the position of, and
 * anything else Advanced Damage System comes to export belongs in the same place.
 */
internal fun levelsOf(vehicle: Vehicle): List<Level> = buildList {
  vehicle.motor?.temperatur?.level("TEMP", ClusterIcons.Temperature)?.let { add(it) }
  // A CVT's own oil, which Advanced Damage System models separately — on slow heavy work it is the
  // one that cooks while the coolant still reads fine. Straight after the coolant it is compared
  // against, and only on a machine that has one, which is most of the point of a bar being here at
  // all. Its glyph is a thermometer over a gear rather than a second plain thermometer: this strip is
  // read by icon alone, so two identical marks would be two bars nobody could tell apart.
  vehicle.ads?.transmissionTemperatur?.level("TRANS", ClusterIcons.TemperatureTransmission)?.let { add(it) }
  val engine = vehicle.motor?.fillUnits ?: return@buildList
  engine.fuel?.let { add(it.level(ClusterIcons.Fuel, "FUEL")) }
  engine.def?.let { add(it.level(ClusterIcons.Def, "DEF")) }
  engine.air?.let { add(it.level(ClusterIcons.Air, "AIR")) }
}

/**
 * A temperature as a bar, read against its own gauge's ends and in trouble at the top.
 *
 * Null for a gauge with no span, which cannot be read at all — better no bar than a bar sat at zero.
 */
private fun Temperatur.level(label: String, icon: ImageVector): Level? {
  val span = (max - min).toFloat()
  if (span <= 0f) return null
  return Level(label, ((value - min) / span).coerceIn(0f, 1f), icon, Danger.High)
}

/** A named engine tank always shows, even full — a missing air bar reads as a fault, not as "fine". */
private fun FillUnit.level(icon: ImageVector, label: String) = Level(label, fraction(), icon)

/**
 * Prefer litres over capacity to the pre-rounded percentage: the integer percent staircases about 1%
 * at a time and visibly judders on a bar this size while the litres climb smoothly.
 */
private fun FillUnit.fraction(): Float =
  (if (capacity > 0) value / capacity else fillLevelPercentage / 100f).coerceIn(0f, 1f)

private val BAR_GAP = 8.dp
private val FRAME_WIDTH = 2.dp
private val ICON_SIZE = 16.dp

/** Air between a bar and the icon that captions it. */
private val ICON_GAP = 6.dp

/** Segments the bar is divided into, so the bottom one is exactly the reserve. */
private const val SEGMENTS = 10

/** The dark line between two segments. */
private val SEGMENT_GAP = 1.5.dp

/**
 * One bar, after the reference cluster: an open-topped frame with a light, segmented level standing
 * inside it.
 *
 * The colour is on the **frame**, not on the level. A frame that is green down to its last tenth and
 * red across it says where the trouble starts *before* you are in it — which a bar that only turns
 * red on arrival cannot do, since by then there is nothing left to compare it against. The level goes
 * coloured too, but only once it is actually in trouble, so a glance sees "how much" and "is that a
 * problem" as two separate marks.
 *
 * Segmented rather than solid because a solid column has no scale on it: ten bands make a half-full
 * tank readable as five without a number beside it, and put the reserve at exactly one band.
 */
@Composable
private fun LevelBar(level: Level, modifier: Modifier = Modifier) {
  val state = when {
    level.severity >= 1f - CRITICAL_FRACTION -> ClusterColors.Warn
    level.severity > 1f - LOW_FRACTION -> ClusterColors.Set
    else -> null
  }
  Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Canvas(Modifier.weight(1f).fillMaxWidth()) {
      val frame = FRAME_WIDTH.toPx()
      val gap = SEGMENT_GAP.toPx()

      // The frame first, and the level inside it, so the two never overlap and the level's own width
      // stays the same whatever the frame is doing.
      drawFrame(level.danger, frame)
      val inner = Offset(frame, 0f)
      val innerSize = Size(size.width - 2 * frame, size.height - frame)
      drawLevel(level.fraction, state ?: ClusterColors.Fill, inner, innerSize, gap)
    }
    // Icon only, as on the reference: a pump, a droplet and a thermometer need no caption, and the
    // bars are in a fixed order anyway. The label lives on for the screen reader — and it is the icon
    // that carries the state colour, since the frame's colours are about the scale, not the value.
    Icon(
      level.icon,
      contentDescription = level.label,
      tint = state ?: ClusterColors.Fill,
      modifier = Modifier.padding(top = ICON_GAP).size(ICON_SIZE),
    )
  }
}

/**
 * The frame: left, bottom and right, open at the top exactly as the reference draws it, green over
 * the working range and red across the tenth where the gauge is in trouble — the bottom for a tank,
 * the top for a temperature.
 */
private fun DrawScope.drawFrame(danger: Danger, frame: Float) {
  val height = size.height - frame
  val bad = height * CRITICAL_FRACTION
  val badTop = if (danger == Danger.Low) height - bad else 0f

  for (x in listOf(0f, size.width - frame)) {
    drawRect(ClusterColors.Go, Offset(x, 0f), Size(frame, height))
    drawRect(ClusterColors.Warn, Offset(x, badTop), Size(frame, bad))
  }
  // The bottom edge closes the frame, and belongs to whichever end the trouble is at.
  drawRect(
    if (danger == Danger.Low) ClusterColors.Warn else ClusterColors.Go,
    Offset(0f, height),
    Size(size.width, frame),
  )
}

/** The level standing in the frame, cut into [SEGMENTS] bands by gaps of the surface showing through. */
private fun DrawScope.drawLevel(fraction: Float, colour: Color, origin: Offset, area: Size, gap: Float) {
  val filled = area.height * fraction
  if (filled <= 0f) return
  drawRect(colour, Offset(origin.x, origin.y + area.height - filled), Size(area.width, filled))

  // Cut afterwards rather than drawing band by band, so the band the level stops part-way up is
  // simply a short one instead of an all-or-nothing step.
  val band = area.height / SEGMENTS
  for (i in 1 until SEGMENTS) {
    drawRect(ClusterColors.Surface, Offset(origin.x, origin.y + i * band - gap / 2), Size(area.width, gap))
  }
}
