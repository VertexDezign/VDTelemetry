package net.vertexdezign.vdt.app.panels

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.model.DriveDirection
import net.vertexdezign.vdt.model.SteeringLayout
import net.vertexdezign.vdt.model.Vehicle
import kotlin.math.abs
import kotlin.math.roundToInt

/** Fraction of the rev range above which the rpm reads red. */
private const val REDLINE_FRACTION = 0.9f

/**
 * Cells each line reserves. Fixed, so a value getting shorter unlights a cell instead of moving the
 * ones beside it — see [ClusterDigits]. Four for the rpm covers every engine in the game; three for a
 * speed covers `99.9`, and anything past that simply runs over rather than being cut.
 */
private const val RPM_CELLS = 4
private const val SPEED_CELLS = 3
private const val GEAR_CELLS = 2

/**
 * Advance per cell, measured off the bundled faces, and used to size the type to the tile. One
 * constant covers both: DSEG7 is 0.8225em and DSEG14 0.83em, which is well inside the slack the
 * widest line leaves.
 */
private const val CELL_EM = 0.82f

/** One mark's slot, as a share of the digit size. */
private const val SYMBOL_EM = 0.8f

/**
 * How much of a line's width a set of values takes, in multiples of the digit size: its cells, its
 * marks' slots, and the [scale] its type is drawn at. The label column is fixed and sits outside this.
 *
 * A line with no marks still reserves one slot — see [Line], which holds it open so nothing shifts
 * sideways when a mark comes and goes.
 */
internal fun lineWidth(cells: Int, marks: Int, scale: Float = 1f): Float =
  scale * (cells * CELL_EM + maxOf(1, marks) * SYMBOL_EM)

/**
 * The label column, the same width on every line — wide enough for `CRUISE`, the longest of them, at
 * [ClusterLabel]'s size.
 *
 * Fixed rather than each line taking the width of its own label. Sized to its content, `CRUISE`'s
 * column is wider than `RPM`'s, which leaves that line less room for its digits and walks its right
 * edge inwards — so the four numbers no longer line up, which is the one thing this layout is for.
 * It also makes the width budget below exact instead of an estimate.
 */
private val LABEL_COLUMN = 46.dp

/** Between the digits and their label. */
private val LABEL_GAP = 6.dp

/**
 * The cluster's primary readout: engine speed over ground speed, with the cruise target under them
 * and the gear under that, all in amber where the driver set the value.
 *
 * The same numbers the Engine and Transmission panel carries, and a different instrument for them.
 * That panel is a readout you inspect — a gauge, temperatures, fuel rates, controls to press. This is
 * one you glance at from the corner of your eye while watching a headland come up, so it is four
 * lines of very large type and nothing else.
 *
 * rpm and speed are tweened over one [sampleIntervalMs] so they read continuously rather than
 * stepping at the telemetry rate — the same trick, and for a bigger reason here, since a number this
 * size makes every jump obvious.
 */
@Composable
fun ClusterReadout(vehicle: Vehicle, sampleIntervalMs: Int, modifier: Modifier = Modifier) {
  val motor = vehicle.motor
  val spec = tween<Float>(durationMillis = sampleIntervalMs, easing = LinearEasing)
  val rpm by animateFloatAsState((motor?.rpm?.value ?: 0).toFloat(), spec, label = "cluster-rpm")
  val speed by animateFloatAsState(vehicle.speed?.value ?: 0f, spec, label = "cluster-speed")

  val redline =
    motor?.rpm?.let { it.max > it.min && (rpm - it.min) / (it.max - it.min).toFloat() >= REDLINE_FRACTION } == true

  // Switched off: the tile draws its ghost layer and nothing else. Every line below is still built,
  // so the panel that comes back when the key is turned is the same panel with its power on — see
  // [clusterDark], and [Line]'s `unlit`.
  val dark = clusterDark(vehicle)

  val cruise = vehicle.cruiseControl?.targetSpeed
  val gear = gearText(vehicle)
  val symbol = driveSymbol(vehicle)

  // The transmission says which way the machine will go; `speed.direction` says whether it is going.
  // Standing still in gear is the one state where those disagree, and a real transmission display
  // flashes the direction to say so — it is the difference between "we are going forwards" and "let
  // the brake off and we will". Neutral is excluded: an `N` at a standstill is simply true, and there
  // is nothing provisional about it to flash. Only built while it is actually needed, so the pillar
  // phone isn't animating a frame at a time all the while it is parked.
  //
  // Never on a dead machine, which is a standstill that will not end until someone turns a key. That
  // was the one thing on this tile that flashed *for ever* on a parked tractor, a promise about a
  // machine that cannot move.
  val holding = !dark && symbol?.icon != null && vehicle.speed?.direction == DriveDirection.STOPPED

  // How the machine is set up to be driven, which rides beside the gear — the two are one thought,
  // and this is the middle of the cluster where a driver looks for what the machine is *in*.
  //
  // Beside whatever line is actually drawn, though, rather than beside the gear specifically: a
  // machine with steering modes is quite likely to report no gear at all (a telehandler does), and
  // these must not disappear along with a field they have nothing to do with.
  val steering = steeringMarks(vehicle)
  val blink = if (!dark && (holding || steering.any { it.blinks })) clusterBlinkPhase() else null

  // Steering assist rides on the cruise line, where it belongs: both are the machine holding
  // something for the driver — a speed, a line — and both are switched on the same way, armed first
  // and engaged after. Read together they are one sentence about who is driving.
  val guidance = listOfNotNull(guidanceMark(vehicle))

  // Which line the marks land on, decided once so the width budget below can see it too. Guidance
  // leads the cruise line so it holds one place: the steering marks beside it come and go with the
  // gear line, and a lamp that slid sideways when they did would be a lamp you have to look for.
  //
  // It drops to the speed line only if the machine reports no cruise target at all — nothing with a
  // steering spec does today, since both come off `spec_drivable`, but losing the lamp entirely is
  // the wrong way to be wrong about that.
  val gearMarks = if (gear != null) steering else emptyList()
  val cruiseMarks = guidance + if (gear == null) steering else emptyList()
  val speedMarks =
    listOfNotNull(symbol?.mark(holding)) +
      (if (cruise == null) guidance else emptyList()) +
      (if (cruise == null && gear == null) steering else emptyList())

  ClusterSurface(modifier) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
      // Digits are sized from the tile rather than fixed, so the readout *fills* the panel instead of
      // floating in the middle of it. Width is the widest line the vehicle actually draws — cells plus
      // marks plus the labels; height is however many lines it has, since a machine with no gear and
      // no cruise should spend that space on the two numbers it does have.
      //
      // Every line is measured rather than the rpm being assumed the widest. It usually is — four
      // cells beats anything below it even before their smaller type — but a line's marks are part of
      // its width, and a line that carried more of them than the budget allowed for would run its
      // digits under the label column.
      val weights = 1f + 1f + (if (cruise != null) CRUISE_SCALE else 0f) + (if (gear != null) GEAR_SCALE else 0f)
      val fromHeight = maxHeight.value / (weights + LINE_AIR)
      val widest =
        maxOf(
          lineWidth(RPM_CELLS, 0),
          lineWidth(SPEED_CELLS, speedMarks.size),
          if (cruise != null) lineWidth(SPEED_CELLS, cruiseMarks.size, CRUISE_SCALE) else 0f,
          if (gear != null) lineWidth(GEAR_CELLS, gearMarks.size, GEAR_SCALE) else 0f,
        )
      val fromWidth = (maxWidth.value - LABEL_COLUMN.value) / widest
      val digit = minOf(fromHeight, fromWidth).coerceIn(14f, 120f)

      Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceEvenly,
      ) {
        // Right-aligned, as on the reference: the numbers share an edge, so each line's unit sits
        // immediately beside its value rather than across the panel from it.
        Line(
          value = rpm.roundToInt().toString(),
          cells = RPM_CELLS,
          valueColour = if (redline) ClusterColors.Warn else ClusterColors.Digits,
          size = digit,
          label = "RPM",
          unlit = dark,
        )
        // The reverser rides on the speed rather than on the gear below it, because a gear line is
        // not a given — a combine or a telehandler reports none — and the direction the machine is
        // pointed is not something to lose along with it. It belongs beside a speed anyway: the two
        // together are the whole answer to "where is this going, and how fast".
        Line(
          value = format1(speed),
          cells = SPEED_CELLS,
          valueColour = ClusterColors.Digits,
          size = digit,
          label = vehicle.speed?.unit.orEmpty().ifBlank { "SPEED" },
          marks = speedMarks,
          note = symbol?.letter,
          noteColour = symbol?.colour ?: ClusterColors.Digits,
          noteBlinks = holding,
          blink = blink,
          unlit = dark,
        )
        // Cruise, amber because it is a value the driver set rather than one the machine reports.
        // Dimmed when armed but not engaged, so "53 is what it will hold" and "53 is what it is
        // holding" are not the same picture.
        cruise?.let { target ->
          val engaged = vehicle.cruiseControl?.active == true
          Line(
            value = format1(target),
            cells = SPEED_CELLS,
            valueColour = if (engaged) ClusterColors.Set else ClusterColors.Set.copy(alpha = ARMED_ALPHA),
            size = digit * CRUISE_SCALE,
            label = "CRUISE",
            labelColour = if (engaged) ClusterColors.Set else ClusterColors.Label,
            marks = cruiseMarks,
            blink = blink,
            unlit = dark,
          )
        }
        // What the transmission is in. Amber like the cruise, and for the same reason — both are the
        // driver's selections rather than the engine's readings.
        //
        // Alphanumeric, because a gear is only sometimes a number: neutral is `N`, a range carries its
        // group's letter (`E2`), and some transmissions name theirs outright (`D`, `R`). Seven
        // segments render those as half-height lowercase, so the field is set in the fourteen-segment
        // face throughout rather than changing face with its own value — see [SegmentFace].
        gear?.let {
          Line(
            value = it,
            cells = GEAR_CELLS,
            valueColour = ClusterColors.Set,
            size = digit * GEAR_SCALE,
            label = "GEAR",
            face = SegmentFace.Alphanumeric,
            marks = gearMarks,
            blink = blink,
            unlit = dark,
          )
        }
        // The hour meter, and the one thing on the tile that is a *value* set in the caption face
        // rather than in segments — so a dark panel drops its text instead of ghosting it. The empty
        // caption stays in the layout, because the line it occupies is the line it will occupy again.
        vehicle.operatingTime?.let {
          ClusterLabel(if (dark) "" else "${it.value}${it.unit}", Modifier.fillMaxWidth(), align = TextAlign.End)
        }
      }
    }
  }
}

/**
 * Armed but not yet doing anything: the cruise target the machine *will* hold, the guidance lamp
 * waiting on a line. Drawn in its own colour at this alpha, and the lit state is the same colour at
 * full — so **the difference between armed and engaged is brightness, never hue**.
 *
 * That is the accessible way round and the only one this cluster uses for a two-state thing you read
 * at speed. Amber against green is the commonest confusion there is, and a driver who cannot separate
 * those two is left with no signal at all; dim against bright survives any colour vision, and survives
 * sunlight on the screen too. Where a hue *does* carry meaning here it is the only thing the mark
 * says — see [DriveSymbol], where the arrow points and prints its letter as well.
 *
 * Well above [GHOST_ALPHA], which is the unlit level: armed is a live state the driver put the
 * machine in, not an absence.
 */
internal const val ARMED_ALPHA = 0.45f

/** The cruise and gear lines, relative to the two numbers you drive by. */
private const val CRUISE_SCALE = 0.62f
private const val GEAR_SCALE = 0.8f

/** The direction letter, relative to the line it sits on. Big enough to read off-axis at a glance. */
private const val NOTE_SCALE = 0.55f

/** A mark that prints a number rather than drawing one, relative to the slot it has to sit inside. */
private const val MARK_TEXT_SCALE = 0.8f

/**
 * A caption's type size for a mark cell [cell] dp across, or **null when the cell is too small to
 * set one** and the mark should keep its glyph alone.
 *
 * Scaled off the cell like everything else on this instrument, then held between two limits. The
 * ceiling is [LABEL_SP], the line labels' own size, which a word captioning a mark half their height
 * has no business exceeding. The floor is where the letters stop resolving into a word: below it the
 * caption is a grey smudge under the glyph, so it is dropped instead — the mark is legible without
 * it, which is why it is a caption and not the mark itself.
 *
 * [CAPTION_EM] is set by **width**, not by taste: the caption sits under a cell it may not be wider
 * than, and four bold capitals run about 2.8em, which puts the ceiling near a third of the cell. That
 * is the reason a caption cannot simply be made bigger — past this it has to be given a wider slot.
 */
internal fun captionSp(cell: Float): Float? = (cell * CAPTION_EM).coerceAtMost(LABEL_SP).takeIf { it >= CAPTION_MIN_SP }

private const val CAPTION_EM = 0.3f
internal const val CAPTION_MIN_SP = 6f

/** Slack left over the lines for the operating-time caption and the air between them. */
private const val LINE_AIR = 1f

/**
 * One thing in a line's leading slot: the reverser's arrow, the steering mode, the seat.
 *
 * Either an [icon] or a short [text] — the text is the way out for a steering mode whose shape the
 * mod couldn't work out, where the mode's *number* is all there is to show and drawing a guess at
 * its geometry would be worse than printing it.
 *
 * [alpha] is how a mark that is present but not doing anything is drawn: the seat of a machine that
 * has a reversible position and is facing the normal way is ghosted rather than dropped, on the same
 * convention as an unlit telltale and an unlit segment.
 */
internal data class LineMark(
  val icon: ImageVector?,
  val label: String,
  val colour: Color,
  val text: String? = null,
  val alpha: Float = 1f,
  val blinks: Boolean = false,
  /**
   * A word printed under the glyph, as the reference cluster captions its own lamps — see
   * [guidanceMark]'s AUTO, the only one so far.
   *
   * Worth the room it costs on a mark whose shape is borrowed rather than obvious: a driver who has
   * not met this glyph before can read what it is instead of inferring it, and the word carries the
   * lamp's identity without depending on its colour. It costs the glyph nothing — it hangs under the
   * mark's cell rather than sharing it — and is dropped rather than shrunk to mush on a tile too
   * small to set it. See [captionSp].
   */
  val caption: String? = null,
)

/**
 * One line of the readout: the value in its cells, right-aligned, its [marks] out on the left, and
 * its unit with an optional second line of detail stacked in a column beside it — the direction
 * letter as [note].
 *
 * [face] is the line's own, since not every line is a number: see the gear. The [note] is always
 * alphanumeric, being a letter by definition.
 *
 * [blink] drives whatever has asked to flash — a mark that set `blinks`, and the note when
 * [noteBlinks] — and leaves the value alone: it is the direction that is provisional at a standstill,
 * not the speed it sits beside.
 *
 * [unlit] is the whole line with no power behind it, for a machine that is switched off (see
 * [clusterDark]). Every field keeps its place and loses its contents: the cells and the note keep
 * their shapes with nothing lit in them, the labels and the marks fall to the ghost level, and
 * nothing flashes. It is drawn here rather than by the caller blanking things itself so that a line
 * cannot go half dark — and so the layout is bit for bit the one that comes back when the key turns.
 */
@Composable
private fun Line(
  value: String,
  cells: Int,
  valueColour: Color,
  size: Float,
  label: String,
  labelColour: Color = ClusterColors.Label,
  face: SegmentFace = SegmentFace.Numeric,
  note: String? = null,
  noteColour: Color = ClusterColors.Digits,
  noteBlinks: Boolean = false,
  marks: List<LineMark> = emptyList(),
  blink: (() -> Float)? = null,
  unlit: Boolean = false,
) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
    // The marks' slot, out on the left and held open on every line whether or not anything is in it.
    // These are the things in this tile that come and go, and inside the label column they dragged
    // the unit and the digits sideways every time the machine started or stopped moving. Nothing here
    // should move for a reason other than the number changing.
    //
    // A second mark widens the slot instead of halving the first one: the digits are right-aligned
    // against a fixed label column, so what the slot takes comes out of the slack between them and
    // not out of the numbers' position.
    val cell = size * SYMBOL_EM
    Row(Modifier.width((cell * maxOf(1, marks.size)).dp)) {
      for (mark in marks) {
        // In the draw layer, so a flashing mark costs a repaint per frame and not a recomposition of
        // the line it is on. On the whole mark rather than on the glyph inside it, so a captioned one
        // fades as a piece — the word is part of the lamp, not a label beside it.
        // Named rather than read off `mark` inside the layer block: `alpha` in there is the layer's
        // own property, so an unqualified read would be the layer's and not this mark's.
        val markAlpha = if (unlit) GHOST_ALPHA else mark.alpha
        val flashing = mark.blinks && !unlit
        val fade = Modifier.graphicsLayer { alpha = markAlpha * if (flashing) blinkAlpha(blink) else 1f }
        Column(Modifier.width(cell.dp).then(fade), horizontalAlignment = Alignment.CenterHorizontally) {
          // The glyph keeps the whole cell whether or not it is captioned. The caption hangs *below*
          // the cell instead of dividing it — a line is a good half taller than its marks' slot (the
          // digits set the height, and [SYMBOL_EM] is well under 1), so the word costs slack the row
          // already had rather than costing the picture, which is the thing being read.
          Box(Modifier.size(cell.dp), contentAlignment = Alignment.Center) {
            if (mark.icon != null) {
              Icon(mark.icon, mark.label, tint = mark.colour, modifier = Modifier.fillMaxSize())
            } else if (mark.text != null) {
              ClusterDigits(
                mark.text,
                cells = mark.text.length,
                size = (cell * MARK_TEXT_SCALE).sp,
                colour = mark.colour,
                face = SegmentFace.Alphanumeric,
              )
            }
          }
          mark.caption?.let { caption ->
            captionSp(cell)?.let { sp ->
              ClusterLabel(caption, color = mark.colour, align = TextAlign.Center, size = sp.sp, tight = true)
            }
          }
        }
      }
    }
    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
      // Blank rather than absent: [ClusterDigits] then draws the cells with nothing lit in them,
      // which is the field switched off rather than the field taken away.
      ClusterDigits(if (unlit) "" else value, cells, size.sp, valueColour, face = face)
    }
    Column(
      Modifier.padding(start = LABEL_GAP).width(LABEL_COLUMN - LABEL_GAP),
      horizontalAlignment = Alignment.Start,
    ) {
      ClusterLabel(label, color = if (unlit) labelColour.ghosted() else labelColour)
      note?.let {
        Box(Modifier.graphicsLayer { alpha = if (noteBlinks && !unlit) blinkAlpha(blink) else 1f }) {
          // The fourteen-segment face: seven cannot draw a capital R or N, and a lowercase one beside
          // a full-height F reads as a smaller letter rather than a different one. See [SegmentFace].
          //
          // The cell count is the letter's either way, so an unlit note is a blank cell of exactly
          // the size the letter will come back at.
          ClusterDigits(
            if (unlit) "" else it,
            cells = it.length,
            size = (size * NOTE_SCALE).sp,
            colour = noteColour,
            face = SegmentFace.Alphanumeric,
          )
        }
      }
    }
  }
}

/** Lit for the first half of the flasher's period; solid when nothing is flashing. */
private fun blinkAlpha(blink: (() -> Float)?): Float = if (blink == null) {
  1f
} else if (blink() < 0.5f) {
  1f
} else {
  0f
}

/**
 * What the transmission is set to: an arrow for the way it will take the machine, and the letter a
 * dashboard prints beside it — the same F / R / N the game itself shows.
 *
 * A picture as well as the letter, and moved here from the telltale band, where a direction sat oddly
 * among lamps that are all faults and settings. This is the middle of the cluster, out to the left of
 * the numbers it belongs to — which is where the display it copies puts it.
 *
 * [Neutral] has no arrow, because there is no direction to point in; it keeps the slot and prints its
 * letter. That is why [icon] is nullable rather than there being a third arrow.
 */
enum class DriveSymbol(val icon: ImageVector?, val letter: String, val label: String, val colour: Color) {
  Forward(ClusterIcons.DriveForward, "F", "Transmission in forward", ClusterColors.Go),
  Reverse(ClusterIcons.DriveReverse, "R", "Transmission in reverse", ClusterColors.Set),

  // Not [ClusterColors.Label]: neutral is a current state, as true as the other two, and the label
  // grey sits close enough to its own ghost that the N came out barely legible.
  Neutral(null, "N", "Transmission in neutral", ClusterColors.Digits),
  ;

  /** As a mark in the line's leading slot. [flashing] while the machine is held at a standstill in gear. */
  internal fun mark(flashing: Boolean): LineMark? =
    icon?.let { LineMark(icon = it, label = label, colour = colour, blinks = flashing) }
}

/**
 * How the machine is set up to be driven: the steering mode it is in, and whether the seat has been
 * swung round to face the back. Both are the driver's own selections, so both go on in amber, on the
 * cruise line's convention — and both are absent on the great majority of machines, which have one
 * steering mode and a seat that only faces one way.
 *
 * The seat stays in the slot when it is the normal way round, ghosted. That distinction is worth the
 * space: a machine that *has* a reversible position and isn't using it is a different thing from one
 * that hasn't got one, and only the first is something the driver can act on. The steering mode has
 * no such resting state — its glyph always says something — so it is simply absent when there is
 * nothing to choose between.
 */
internal fun steeringMarks(vehicle: Vehicle): List<LineMark> {
  val steering = vehicle.steering ?: return emptyList()
  val marks = mutableListOf<LineMark>()

  // One mode is no choice, and the game hides its own steering-mode box on the same test — see
  // [net.vertexdezign.vdt.model.SteeringMode] for the small print on what `count` counts.
  steering.mode?.takeIf { it.count > 1 }?.let { mode ->
    val icon = steeringLayoutIcon(mode.layout)
    marks +=
      LineMark(
        icon = icon,
        // Where the shape couldn't be derived, the mode's number — which is what the driver of that
        // machine knows it by, and which claims nothing about geometry we couldn't read.
        text = if (icon == null) mode.index.toString() else null,
        label = mode.name.ifBlank { "Steering mode ${mode.index} of ${mode.count}" },
        // Mode 1 is the one every machine loads in, so anything else is a mode the driver picked.
        colour = if (mode.index == 1) ClusterColors.Digits else ClusterColors.Set,
      )
  }

  steering.reversed?.let { reversed ->
    marks +=
      LineMark(
        icon = ClusterIcons.SeatReversed,
        label = if (reversed) "Driving position reversed" else "Driving position facing forward",
        colour = if (reversed) ClusterColors.Set else ClusterColors.Digits,
        alpha = if (reversed) 1f else GHOST_ALPHA,
        // Mid-swivel the machine is neither way round, and the seat says so rather than jumping.
        blinks = steering.changing,
      )
  }

  return marks
}

/**
 * Steering assist, as one mark: **absent** on a machine that isn't in guidance mode, dim once the
 * driver has selected it, bright while it is actually holding the line.
 *
 * The three states are the two flags the mod sends. `enabled` is the AI mode selection sitting on
 * steering assist — the driver has chosen to be guided; `active` is the assist having a line and
 * steering to it. Nothing at all is drawn for a machine that *has* the spec but isn't in the mode,
 * which is the one place this departs from the seat mark's ghosted rest state: guidance is off far
 * more often than it is on, and a lamp lit on every tractor in the yard is a lamp nobody reads.
 *
 * **One colour, two brightnesses** — [ARMED_ALPHA] and full — rather than the amber-then-green a
 * cluster usually reaches for, and rather than the reference lens's red. Armed against engaged is a
 * distinction the driver has to make at a glance and often out of the corner of an eye, so it cannot
 * rest on hue: amber against green is the commonest confusion there is, and red on this cluster
 * already means [ClusterColors.Warn] — a fault, something to stop for — which armed guidance is not.
 *
 * [ClusterColors.Set] because it is exactly the cruise line's own state, in the mark slot of that
 * same line: a thing the driver set, holding or about to hold. The two now dim and brighten together.
 */
internal fun guidanceMark(vehicle: Vehicle): LineMark? {
  val gps = vehicle.gps ?: return null
  if (!gps.enabled) return null
  return LineMark(
    icon = ClusterIcons.AutoSteer,
    label = if (gps.active) "Steering assist engaged" else "Steering assist armed",
    colour = ClusterColors.Set,
    alpha = if (gps.active) 1f else ARMED_ALPHA,
    // Captioned like the lamp it is copied from, and it earns the room twice over here: the glyph is
    // borrowed rather than self-evident, and a word is one more thing about this mark that survives
    // being read in any colour.
    caption = "AUTO",
  )
}

/**
 * The glyph for a steering mode's shape, or null when the mod couldn't read one off the machine —
 * see [net.vertexdezign.vdt.model.SteeringMode.layout] for when that happens.
 */
internal fun steeringLayoutIcon(layout: SteeringLayout?): ImageVector? = when (layout) {
  SteeringLayout.FRONT -> ClusterIcons.SteerFront

  SteeringLayout.BACK -> ClusterIcons.SteerBack

  SteeringLayout.ALL_WHEEL -> ClusterIcons.SteerAllWheel

  // A crab with no side of its own borrows the left one's picture; see [ClusterIcons.SteerCrabLeft].
  SteeringLayout.CRAB -> ClusterIcons.SteerCrabLeft

  SteeringLayout.CRAB_LEFT -> ClusterIcons.SteerCrabLeft

  SteeringLayout.CRAB_RIGHT -> ClusterIcons.SteerCrabRight

  null -> null
}

/**
 * Which way the transmission is **set**, from `motor.direction` (mod version 6) — not the way the
 * machine is moving, so it keeps saying so at a standstill, which is the whole reason that field
 * exists.
 *
 * The obvious-looking `vehicle:getReverserDirection()` is deliberately *not* its source. In the engine
 * that value is written only by the reversible-driving-position specialization — the seat swivelled
 * round — so on an ordinary tractor it reads forward for ever and the readout never changes.
 *
 * A capture from an older mod has no motor direction at all and falls back to the way the machine is
 * travelling, which is the pre-version-6 behaviour: a direction while moving, nothing once stopped.
 */
internal fun driveSymbol(vehicle: Vehicle): DriveSymbol? = when (vehicle.motor?.direction) {
  DriveDirection.FORWARD -> DriveSymbol.Forward
  DriveDirection.BACKWARD -> DriveSymbol.Reverse
  DriveDirection.STOPPED -> DriveSymbol.Neutral
  null -> legacyDriveSymbol(vehicle)
}

/** Pre-version-6 fallback: the travel direction, which says nothing at a standstill. */
private fun legacyDriveSymbol(vehicle: Vehicle): DriveSymbol? = when (vehicle.speed?.direction) {
  DriveDirection.FORWARD -> DriveSymbol.Forward
  DriveDirection.BACKWARD -> DriveSymbol.Reverse
  else -> null
}

/**
 * What the transmission is in: the gear, or `N` in neutral, prefixed by its group where the vehicle
 * has one (`E2`, the group's own letter and the gear within it). Null when there is no gear at all,
 * which is most non-tractors.
 */
internal fun gearText(vehicle: Vehicle): String? {
  val gear = vehicle.motor?.gear ?: return null
  if (gear.isNeutral) return "N"
  val value = gear.value.takeIf { it.isNotBlank() } ?: return null
  return gear.group.takeIf { it.isNotBlank() }?.let { it + value } ?: value
}

/**
 * One decimal, without `String.format` (JVM-only; this also runs on wasmJs).
 *
 * Signed off the whole value rather than off its parts: integer division truncates towards zero, so
 * `-0.5` would otherwise lose its sign along with its integer digit and read as `0.5`.
 */
internal fun format1(value: Float): String {
  val scaled = (value * 10).roundToInt()
  val magnitude = abs(scaled)
  return "${if (scaled < 0) "-" else ""}${magnitude / 10}.${magnitude % 10}"
}
