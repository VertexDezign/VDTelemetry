package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.vertexdezign.vdt.model.AdsLamp
import net.vertexdezign.vdt.model.Vehicle

/** Above this fraction of its own min..max span the coolant reads as overheating. */
private const val TEMP_WARN_FRACTION = 0.9f

/** …and at or above this much damage the machine reads as needing attention. Both are ours to pick. */
private const val DAMAGE_WARN_PERCENT = 75

/**
 * Where a lamp sits in the band.
 *
 * [Start] and [End] are pinned to the edges of the tile and everything else runs between them. It is
 * only the turn signals that want this, and they want it badly: left means left because it *is* on
 * the left, out at the corner of your eye on the side you are about to turn towards. A pair of
 * arrows sitting together in the middle of a row is two similar shapes to tell apart, which is a
 * fraction of a second spent on a lamp that exists to be read without looking at it.
 */
enum class BandSide { Start, Middle, End }

/**
 * One lamp in the cluster's telltale band.
 *
 * [key] is persisted in a tile's config, so it is stable API — renaming one silently drops that lamp
 * from every band a user has configured. [colour] is the lamp lit; off, it is the same colour at
 * [GHOST_ALPHA].
 *
 * [blinks] is for the lamps that are *signals*: on a real machine the indicator lamp flashes in time
 * with the relay, and a steady green arrow is a different claim from a flashing one. The rest are
 * states, and a state that flashed would read as a fault.
 *
 * There is no hazard lamp. Hazards are both indicators at once, so the band already shows them — as
 * both arrows flashing, at the two edges, which is exactly what the machine is doing. Its glyph is
 * still drawn ([ClusterIcons.Hazard]); the Lighting panel needs it for the button that turns them on.
 *
 * The work lights are two lamps sharing a subject, adjacent and ordered front-then-rear, which is
 * how a real cluster distinguishes them too. The pair is legible as a pair; each still carries its
 * own description for a screen reader.
 *
 * The diff locks are **one** lamp instead, because a lamp can change shape and two lamps cost two
 * slots of a band that is always visible now that off lamps are ghosted. [DiffLock] lights when
 * either differential is shut and its glyph says which end (see [iconIn]) — the same information in
 * half the band, and the answer lands in a slot the eye is already on rather than in whichever of
 * two neighbours happened to light. [icon] is what it draws at rest and in a picker, where there is
 * no vehicle to ask.
 *
 * [EngineWarning] used to be a *derived* lamp, lit on coolant temperature **or** damage. Those are now
 * [Temperature] and [GeneralWarning], said separately, which is how the cluster this copies shows
 * them — and three lamps drawn from two facts, one of them the union of the other two, would be a
 * band saying the same thing twice. The engine lamp keeps its key and its glyph, and takes its state
 * from Advanced Damage System's own engine fault, which is a different claim from either of the two.
 *
 * The six maintenance lamps are ADS's, and there [colour] is only their **resting** colour: what they
 * light in is the severity ADS reports (see [Reading.colour]).
 */
enum class Telltale(
  val key: String,
  val label: String,
  val icon: ImageVector,
  val colour: Color,
  val blinks: Boolean = false,
  val side: BandSide = BandSide.Middle,
) {
  TurnLeft("turnLeft", "Turn left", ClusterIcons.TurnLeft, ClusterColors.Go, blinks = true, side = BandSide.Start),
  TurnRight("turnRight", "Turn right", ClusterIcons.TurnRight, ClusterColors.Go, blinks = true, side = BandSide.End),
  HighBeam("highBeam", "High beam", ClusterIcons.HighBeam, ClusterColors.Beam),
  LowBeam("lowBeam", "Low beam", ClusterIcons.LowBeam, ClusterColors.Go),
  WorkFront("workFront", "Work lights front", ClusterIcons.WorkFront, ClusterColors.Set),
  WorkRear("workRear", "Work lights rear", ClusterIcons.WorkRear, ClusterColors.Set),
  Beacon("beacon", "Beacon", ClusterIcons.Beacon, ClusterColors.Set),
  ParkingBrake("parkingBrake", "Parking brake", ClusterIcons.ParkingBrake, ClusterColors.Go),
  DiffLock("diffLock", "Diff lock", ClusterIcons.DiffLockBoth, ClusterColors.Set),
  Awd("awd", "All-wheel drive", ClusterIcons.Awd, ClusterColors.Go),

  // The maintenance family, at the end because that is the order a cluster reads in: what you are
  // doing, then what the machine is doing, then what is wrong with it. All six come from Advanced
  // Damage System and are absent without it — the same rule the drivetrain lamps follow, and one that
  // costs nothing but a line in the band's config dialog on a game that doesn't run the mod.
  EngineWarning("engineWarning", "Engine warning", ClusterIcons.EngineWarning, ClusterColors.Warn),
  Temperature("temperature", "Coolant temperature", ClusterIcons.Temperature, ClusterColors.Warn),
  Battery("battery", "Charging system", ClusterIcons.Battery, ClusterColors.Warn),
  BrakeSystem("brakeSystem", "Brake system", ClusterIcons.BrakeSystem, ClusterColors.Warn),
  Service("service", "Service due", ClusterIcons.Service, ClusterColors.Set),
  GeneralWarning("generalWarning", "Needs attention", ClusterIcons.GeneralWarning, ClusterColors.Set),
  ;

  /** The lamp Advanced Damage System drives this one from, or null for the lamps that are ours. */
  internal fun adsLampIn(vehicle: Vehicle): AdsLamp? = vehicle.ads?.lamps?.let {
    when (this) {
      EngineWarning -> it.engine
      GeneralWarning -> it.warning
      BrakeSystem -> it.brakes
      Battery -> it.battery
      Temperature -> it.coolant
      Service -> it.service
      else -> null
    }
  }
}

/**
 * Whether [vehicle] has this lamp lit — or **null when we have no state for it**, which the band
 * renders as an absent lamp rather than an unlit one.
 *
 * That distinction is the whole reason the drivetrain telltales exist as nullables in the model: the
 * parking brake, the diff locks and AWD come from Enhanced Vehicle, which is optional and only
 * decorates the vehicle you're controlling. An unlit diff-lock lamp is a claim about the drivetrain,
 * and without the mod we have no standing to make it — so we say nothing instead.
 *
 * The maintenance six are the same rule taken to its end, twice over. Without Advanced Damage System
 * there is nothing to say at all; *with* it, a lamp the machine is too old to have (ADS gates each on
 * the vehicle's production year) is null too, so a 1960s tractor's band does not grow an engine-fault
 * lamp its dashboard never had.
 */
fun Telltale.stateIn(vehicle: Vehicle): Boolean? = when (this) {
  Telltale.TurnLeft -> vehicle.lights?.indicator?.left

  Telltale.TurnRight -> vehicle.lights?.indicator?.right

  Telltale.HighBeam -> vehicle.lights?.light?.highBeam

  Telltale.LowBeam -> vehicle.lights?.light?.lowBeam

  Telltale.WorkFront -> vehicle.lights?.workLight?.front

  Telltale.WorkRear -> vehicle.lights?.workLight?.back

  Telltale.Beacon -> vehicle.lights?.beaconLight

  Telltale.ParkingBrake -> vehicle.motor?.parkingBrake

  Telltale.DiffLock -> diffLockEngaged(vehicle)

  Telltale.Awd -> vehicle.motor?.awd

  // ADS's answer where there is one, and otherwise the two the base game can still support on its
  // own: a coolant gauge in the red, and vanilla damage. Those are what these lamps were lit from
  // before ADS existed, and a game without the mod has no reason to lose them.
  else -> adsLampIn(vehicle)?.let { it != AdsLamp.OFF } ?: when (this) {
    Telltale.Temperature -> overheating(vehicle)
    Telltale.GeneralWarning -> needsAttention(vehicle)
    else -> null
  }
}

/**
 * The colour this lamp lights in for [vehicle] — [Telltale.colour] unless Advanced Damage System is
 * driving it, where the severity it reports is the colour, exactly as ADS's own dashboard shows it.
 *
 * Severity is also carried by [blinksIn], because a ladder told apart by hue alone is a ladder some
 * people cannot read. Colour and flash say the same thing twice, on purpose.
 */
fun Telltale.colourIn(vehicle: Vehicle): Color = when (adsLampIn(vehicle)) {
  AdsLamp.COLD -> ClusterColors.Beam
  AdsLamp.WARN -> ClusterColors.Set
  AdsLamp.CRIT -> ClusterColors.Warn
  else -> colour
}

/**
 * Whether this lamp flashes for [vehicle]: the signals always, and any ADS lamp at its top severity.
 *
 * The band's rule is that a state that flashed would read as a fault — and this *is* the fault. A
 * critical lamp is the one thing on the band you are meant to stop for, so it gets the one treatment
 * the rest of the band never uses.
 */
fun Telltale.blinksIn(vehicle: Vehicle): Boolean = blinks || adsLampIn(vehicle) == AdsLamp.CRIT

/**
 * Either differential shut, over the two ends Enhanced Vehicle reports separately.
 *
 * Null only when it reports *neither* end, so a machine whose rear lock we can see keeps its lamp —
 * saying which end is not this function's job but [Telltale.iconIn]'s, and an end the mod is silent
 * about is one we have no standing to call shut.
 */
fun diffLockEngaged(vehicle: Vehicle): Boolean? = vehicle.motor?.diffLock?.let { lock ->
  if (lock.front == null && lock.back == null) null else lock.front == true || lock.back == true
}

/**
 * The glyph this lamp draws for [vehicle] — [Telltale.icon] for all but two of them.
 *
 * [Telltale.DiffLock] is the first exception, and the reason this exists: it is one lamp over two
 * independent differentials, so *which* end is shut is a difference in the symbol rather than in
 * which of two lamps came on. Front-only and rear-only get the axle in question drawn solid with the
 * other left open; both — and the resting state, where the lamp is ghosted — get both.
 *
 * An axle Enhanced Vehicle says nothing about is drawn as not-locked, which is the honest reading:
 * the lamp is only on this band at all because it reports at least one end (see [stateIn]), and an
 * end it does not report is one we cannot claim is shut.
 *
 * [Telltale.Temperature] is the second, for the same reason: under Advanced Damage System one lamp
 * covers both ends of the gauge, and *not warmed up yet* is not a milder version of *boiling*. The
 * cold state is a real machine's blue lamp, and it gets its own symbol so that being cold and being
 * hot are not one shape in two colours.
 */
fun Telltale.iconIn(vehicle: Vehicle): ImageVector = when {
  this == Telltale.DiffLock -> {
    val lock = vehicle.motor?.diffLock
    when {
      lock?.front == true && lock.back != true -> ClusterIcons.DiffLockFront
      lock?.back == true && lock.front != true -> ClusterIcons.DiffLockRear
      else -> ClusterIcons.DiffLockBoth
    }
  }

  this == Telltale.Temperature && adsLampIn(vehicle) == AdsLamp.COLD -> ClusterIcons.TemperatureCold

  else -> icon
}

/**
 * Coolant over temperature. Null when the vehicle reports none, so the lamp is absent rather than
 * confidently unlit.
 *
 * Read as a fraction of the gauge's own min..max rather than as an absolute, because that span is per
 * vehicle (20..120°C on the machines we have captures for, but that is the mod's to say). A working
 * combine sits around 89°C, so the threshold has to clear normal load comfortably.
 */
fun overheating(vehicle: Vehicle): Boolean? = vehicle.motor?.temperatur?.let { t ->
  val span = (t.max - t.min).toFloat()
  if (span <= 0f) null else (t.value - t.min) / span >= TEMP_WARN_FRACTION
}

/** Damaged enough to want looking at. Null when the vehicle reports no wear at all. */
fun needsAttention(vehicle: Vehicle): Boolean? = vehicle.wearable?.let { it.damage >= DAMAGE_WARN_PERCENT }

/**
 * The band of lamps at the top of the cluster: [lamps], in enum order, each drawn from [vehicle] and
 * each simply missing where the vehicle has nothing to say (see [stateIn]).
 *
 * **A lamp that is off is a ghost of itself** — its own colour at [GHOST_ALPHA], the same level the
 * unlit segments behind it sit at (see [ClusterDigits]). That is what the panel this copies looks
 * like: the symbol is printed on the lens, so it is faintly there in the dark and it *lights up*,
 * rather than appearing out of nowhere. Lit against ghosted is a difference in brightness of about a
 * factor of ten, which is not a distinction anyone has to make on purpose — the band is still read
 * by what is bright on it.
 *
 * The ghost is dim enough that this stays true, and it buys two things a blank slot could not: the
 * band says what it is *able* to tell you before anything happens on it, and a lamp we have no state
 * for at all (see [stateIn]) is now visibly different from one that is merely off, rather than both
 * being empty space.
 *
 * Wrapping rather than scrolling. The band is glanceable or it is nothing — a lamp you have to swipe
 * to see is a lamp that will not be seen — so a band too wide for its tile becomes a second row, and
 * which lamps are worth the space is the tile's own configuration.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TelltaleBand(vehicle: Vehicle, lamps: List<Telltale>, modifier: Modifier = Modifier) {
  ClusterSurface(modifier) {
    val shown = lamps.mapNotNull { lamp -> lamp.readingIn(vehicle) }
    val checking = lampCheck()
    // Only run the flasher when something is actually flashing. The pillar display is a screen that
    // stays awake on a clamped phone for a whole session, and an infinite transition nobody can see
    // is a wake-up every frame for the entire time you are not indicating.
    val blink = if (!checking && shown.any { it.blinks && it.lit }) clusterBlinkPhase() else null

    val start = shown.filter { it.lamp.side == BandSide.Start }
    val end = shown.filter { it.lamp.side == BandSide.End }
    val middle = shown.filter { it.lamp.side == BandSide.Middle }
    BoxWithConstraints(Modifier.fillMaxSize()) {
      val size = lampSize(middle.size, maxWidth, maxHeight, edges = start.size + end.size)
      Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        for (reading in start) {
          Lamp(reading, size, checking, blink, Modifier.padding(end = LAMP_GAP))
        }
        FlowRow(
          Modifier.weight(1f),
          horizontalArrangement = Arrangement.spacedBy(LAMP_GAP, Alignment.CenterHorizontally),
          verticalArrangement = Arrangement.spacedBy(LAMP_GAP, Alignment.CenterVertically),
        ) {
          for (reading in middle) Lamp(reading, size, checking, blink)
        }
        for (reading in end) {
          Lamp(reading, size, checking, blink, Modifier.padding(start = LAMP_GAP))
        }
      }
    }
  }
}

/**
 * What one lamp has to say about a vehicle: whether it is [lit], and the glyph, colour and flash
 * that say it — all of it read in one go, because they are answers to the same question and asking
 * for them apart would draw a lamp lit for one state while showing another. [Telltale.DiffLock] has
 * a glyph per state; the ADS lamps have a colour and a flash per severity.
 */
private data class Reading(
  val lamp: Telltale,
  val lit: Boolean,
  val icon: ImageVector,
  val colour: Color,
  val blinks: Boolean,
)

/** Null for a lamp this vehicle has no state for, which is the band leaving it out entirely. */
private fun Telltale.readingIn(vehicle: Vehicle): Reading? =
  stateIn(vehicle)?.let { Reading(this, it, iconIn(vehicle), colourIn(vehicle), blinksIn(vehicle)) }

/**
 * One lamp, lit or ghosted at [GHOST_ALPHA]. The alpha is set in the draw layer so a blinking lamp
 * costs a repaint per frame and not a recomposition; the lamp keeps its slot either way, so the band
 * never reflows out from under the eye already on it.
 */
@Composable
private fun Lamp(reading: Reading, size: Dp, checking: Boolean, blink: (() -> Float)?, modifier: Modifier = Modifier) {
  val (lamp, lit, icon, colour, blinks) = reading
  val on = lit || checking
  // Flashing is gated on being handed a [blink] at all, and under the lamp check it isn't: there,
  // every lamp is simply proving that it works, the flashers included. It flashes back to the ghost
  // rather than to nothing, which is a lens going dark and not a symbol being taken away.
  //
  // Only a lit lamp is described. The ghost is the panel it is printed on — the same chrome as the
  // unlit segments, which are not announced either — and a band that read out every lamp it carries
  // regardless of state would bury the two that are actually saying something.
  Icon(
    icon,
    contentDescription = if (on) lamp.label else null,
    tint = colour,
    modifier = modifier.size(size).graphicsLayer {
      alpha = when {
        !on -> GHOST_ALPHA
        blinks && blink != null -> if (blink() < 0.5f) 1f else GHOST_ALPHA
        else -> 1f
      }
    },
  )
}

/**
 * True for the first moment the band is on screen, which is what lights every lamp it has at once.
 *
 * The bulb check a machine does at ignition, and it is doing the same job here: it is the only time
 * you see the band whole, so it is the only time you can tell that the tile is configured the way
 * you meant and that the display is alive at all. The band renders nothing at all when there is no
 * vehicle, so this runs again each time you climb into one.
 *
 * It lights the lamps this band *has* — not every lamp in the enum. A lamp with no state behind it
 * has no bulb to check, and lighting it would mean the band changing shape as the check ended.
 */
@Composable
private fun lampCheck(): Boolean {
  var checking by remember { mutableStateOf(true) }
  LaunchedEffect(Unit) {
    delay(LAMP_CHECK_MS)
    checking = false
  }
  return checking
}

/** Internal because [lampSize]'s packing is tested against it — a test hard-coding 8 would drift. */
internal val LAMP_GAP = 8.dp
private val LAMP_MIN = 14.dp
private val LAMP_MAX = 48.dp

/** How long every lamp stays lit when the band appears. About as long as a machine takes to crank. */
private const val LAMP_CHECK_MS = 2200L

/**
 * The largest lamp that still packs [count] of them into [width] × [height], leaving room for
 * [edges] more pinned along the sides.
 *
 * Sized to the tile rather than fixed, because the band is the one part of the cluster whose content
 * count is the user's: a band of four lamps in the space of thirteen should be four big lamps, not
 * four small ones adrift in a mostly empty strip. Searched down in whole dp rather than solved,
 * since the packing is a step function of how many fit per row.
 */
internal fun lampSize(count: Int, width: Dp, height: Dp, edges: Int = 0): Dp {
  if (count <= 0 && edges <= 0) return LAMP_MIN
  val gap = LAMP_GAP.value
  for (px in LAMP_MAX.value.toInt() downTo LAMP_MIN.value.toInt()) {
    val size = px.toFloat()
    if (size > height.value) continue
    // Each edge lamp takes its own width plus the gap holding it off the run in the middle.
    val inner = width.value - edges * (size + gap)
    if (count <= 0) {
      if (inner >= 0f) return size.dp else continue
    }
    if (inner < size) continue
    val perRow = ((inner + gap) / (size + gap)).toInt().coerceAtLeast(1)
    val rows = (count + perRow - 1) / perRow
    if (rows * (size + gap) - gap <= height.value) return size.dp
  }
  return LAMP_MIN
}
