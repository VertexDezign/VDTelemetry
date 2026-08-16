package net.vertexdezign.vdt.app.panels

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.resources.Res
import net.vertexdezign.vdt.app.resources.dseg14_classic_bold
import net.vertexdezign.vdt.app.resources.dseg7_classic_bold
import net.vertexdezign.vdt.model.MotorState
import net.vertexdezign.vdt.model.Vehicle
import org.jetbrains.compose.resources.Font

/**
 * The look the pillar-cluster tiles share: a black panel carrying bright, chunky readouts, after the
 * A-pillar display a modern tractor puts between the windscreen and the right-hand window.
 *
 * Its own palette rather than [net.vertexdezign.vdt.app.theme.VdtColors], which is tuned for dark
 * marks on the terminal's light panels. Those greens and ambers are legible on white and muddy on
 * black, and this is a different instrument: something read off at a glance, off-axis, in a cab
 * that's moving — so the colours are the saturated ones a real cluster uses, and they carry meaning
 * rather than decoration. Amber is a value *you* set, green is go, red is wrong, blue is high beam.
 */
object ClusterColors {
  /** Not pure black: a hair of lift keeps the tile visible as a panel on a dark page. */
  val Surface = Color(0xFF0A0D0B)
  val Digits = Color(0xFFF2F5F2)

  /** A target the driver asked for — cruise, and the lamps for a lock you engaged. */
  val Set = Color(0xFFFF9F0A)
  val Go = Color(0xFF32D74B)
  val Warn = Color(0xFFFF453A)
  val Beam = Color(0xFF409CFF)

  /**
   * A tile with nothing to say — barely there.
   *
   * The dash a panel shows off the machine, at just enough contrast to read as a panel that is
   * switched on and empty rather than as one that has failed. Nothing that carries a value is drawn
   * in it — an unlit *thing* is its own colour at [GHOST_ALPHA] instead, which is what keeps a dark
   * segment and a dark lamp looking like the same panel.
   */
  val Dim = Color(0xFF1F2420)

  /**
   * The level itself inside a bar — light and neutral, as on the reference cluster, because there
   * the colour is on the bar's *frame* and says where the trouble is, not on the contents.
   */
  val Fill = Color(0xFFCFD4D0)
  val Label = Color(0xFF8B948D)
}

/**
 * The two segment faces the cluster reads from, both DSEG Classic and both bundled under the SIL Open
 * Font License (`VDTerminal/licenses/DSEG-OFL-1.1.txt`). Monospace stood in for them while the cluster
 * was being built; the shapes are the point of an instrument meant to look like the panel it copies.
 *
 * Two faces rather than one because **seven segments cannot draw a capital `R` or `N`**. DSEG7 falls
 * back to a lowercase `r` and `n`, which next to a full-height `F` reads as a smaller letter rather
 * than a different one — so the direction letter is set in the fourteen-segment face, which has real
 * capitals. That split is what a real dashboard does too: the numeric fields are seven-segment and the
 * alphanumeric ones are not.
 *
 * Three DSEG conventions are load-bearing here, and [ClusterDigits] is built on them:
 * - `!` is an **all-off cell** — a glyph's width with none of its segments lit.
 * - [allOn] is an **all-on cell**, which is what the ghost layer is made of. It differs per face: `8`
 *   lights all seven, but on the fourteen-segment face it would leave the diagonals dark, so that one
 *   uses `~`.
 * - `.` and `,` have **zero advance**: they hang off the cell before them, exactly as the decimal
 *   point on a real display does, so `12.4` is three cells and not four.
 */
enum class SegmentFace(internal val allOn: Char) {
  /** Seven segments, for anything numeric. */
  Numeric('8'),

  /** Fourteen, for the letters. */
  Alphanumeric('~'),
}

@Composable
internal fun SegmentFace.font(): FontFamily = when (this) {
  SegmentFace.Numeric -> FontFamily(Font(Res.font.dseg7_classic_bold))
  SegmentFace.Alphanumeric -> FontFamily(Font(Res.font.dseg14_classic_bold))
}

/** The numeric face, for the callers that only ever want digits. */
@Composable
fun clusterDigitFont(): FontFamily = SegmentFace.Numeric.font()

/** An all-off cell — holds a glyph's width without lighting anything. */
private const val BLANK = '!'

/**
 * How dim an unlit thing is: a segment that isn't lit, and a telltale that is off (see
 * [TelltaleBand]). Enough to see it is there, far too little to misread as lit — which is roughly
 * where a real LCD sits with the backlight behind it.
 *
 * One value across the whole instrument on purpose. The cluster's ghost is a look, not a per-widget
 * setting, and a band whose off lamps sat at a different level from the digits behind them would
 * read as two panels bolted together.
 */
internal const val GHOST_ALPHA = 0.09f

/** This colour as an unlit thing — the ghost level every dark mark on the cluster is drawn at. */
internal fun Color.ghosted(): Color = copy(alpha = GHOST_ALPHA)

/**
 * Whether the cluster is **dark**: the machine is switched off, so its display is too.
 *
 * This is how the cluster answers "is it running", and it answers it the way the panel it copies
 * does — by being off (issue #93). A tile whose engine has stopped draws its ghost layer and nothing
 * else: unlit segments where the numbers were, an empty frame where the level was, and nothing
 * moving anywhere. It cannot be misread, it needs no colour, and there is no state to learn: a lit
 * panel is a running machine and a dark one is not.
 *
 * The alternative tried first was printing the state as a word in the rpm field, which works and is
 * not what the instrument is. A display that is on and says `OFF` is a different object from one
 * that is off.
 *
 * **[MotorState.OFF] alone.** The key rested at the ignition lock ([MotorState.IGNITION]) lights a
 * real dashboard — that is the whole point of the position — and it is where the telltale band runs
 * its bulb check (see [lampCheck]); cranking is lit for the same reason. So the panel wakes the
 * instant the key is turned and reads zeros until the engine catches, which is what the machine
 * does.
 *
 * The **telltale band is deliberately not dark**, on a machine that is: `Lights:onStopMotor` in the
 * engine re-applies the light mask rather than clearing it, so a parked machine can genuinely have
 * its beacon lit or its hazards going, and the band is the only place that shows it. A lamp there is
 * reporting something the machine is doing right now, whereas every number on the dark tiles is
 * reporting something the *engine* is doing, and the engine is doing nothing.
 *
 * A vehicle with no motor at all is not switched off — it has nothing to switch — so its tiles stay
 * lit and keep whatever they can say.
 */
fun clusterDark(vehicle: Vehicle): Boolean = vehicle.motor?.state == MotorState.OFF

/**
 * A value on a segment display of [cells] cells.
 *
 * Two layers: every cell with all its segments lit, very dim, and the value itself over the top,
 * right-aligned into them. That is what the panel this copies actually looks like — the unlit
 * segments do not disappear, they sit there faintly — and it is also what makes the readout hold
 * perfectly still. The cell count is fixed, so an rpm falling from 1450 to 950 lights one cell fewer
 * instead of moving the other three, and nothing beside it shifts either.
 *
 * A value too long for [cells] simply runs over; padding never truncates, because a clipped number is
 * a wrong number.
 *
 * An **empty** [value] is therefore the field switched off: every cell keeps its shape at the ghost
 * level with nothing lit in it, which is what a segment display looks like with no power behind it.
 * See [clusterDark].
 */
@Composable
internal fun ClusterDigits(
  value: String,
  cells: Int,
  size: TextUnit,
  colour: Color,
  modifier: Modifier = Modifier,
  face: SegmentFace = SegmentFace.Numeric,
) {
  val font = face.font()
  val padded = BLANK.toString().repeat((cells - cellsOf(value)).coerceAtLeast(0)) + value
  // Built from the padded string rather than from `cells` so the two layers keep identical advance
  // widths whatever punctuation the value carries.
  val ghost = padded.map { if (it.isLetterOrDigit() || it == BLANK) face.allOn else it }.joinToString("")
  Box(modifier) {
    Segments(ghost, font, size, colour.copy(alpha = GHOST_ALPHA))
    Segments(padded, font, size, colour)
  }
}

/** Cells a value occupies. The decimal point rides on the cell before it, so it costs nothing. */
private fun cellsOf(text: String): Int = text.count { it != '.' && it != ',' }

@Composable
private fun Segments(text: String, font: FontFamily, size: TextUnit, colour: Color) {
  Text(text, color = colour, fontSize = size, fontFamily = font, maxLines = 1, softWrap = false)
}

/** Panel chrome for a cluster tile: the black surface, and nothing else. */
@Composable
fun ClusterSurface(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
  Box(
    modifier.clip(RoundedCornerShape(6.dp)).background(ClusterColors.Surface).padding(8.dp),
    content = content,
  )
}

/**
 * What a cluster tile shows with no vehicle to report on.
 *
 * A dark panel with a dash, not the light "No vehicle connected" card the other widgets use. A
 * cluster page is mostly these tiles, so borrowing that card would turn stepping out of the tractor
 * into a screen of white boxes announcing an absence — where a real cluster simply goes dark. The
 * dash is a segment cell with only its middle bar lit, which is what such a panel does show.
 */
@Composable
fun ClusterEmpty(modifier: Modifier = Modifier) {
  ClusterSurface(modifier) {
    Text(
      "-",
      color = ClusterColors.Dim,
      fontSize = 28.sp,
      fontFamily = clusterDigitFont(),
      modifier = Modifier.align(Alignment.Center),
    )
  }
}

/** One flash, on and off: 75 a minute, inside the 60–120 a road-legal flasher runs at. */
internal const val BLINK_PERIOD_MS = 800

/**
 * A 0→1 sawtooth on the flasher's period; whatever it drives is lit for the first half of it.
 *
 * Handed out as a function rather than as the state itself so that reading it happens where it is
 * used — in the draw layer — instead of recomposing the caller sixty times a second. Only ever call
 * it when something is actually flashing: the pillar display is a screen that stays awake on a
 * clamped phone for a whole session, and an infinite transition nobody can see is a wake-up every
 * frame for the entire time nothing is blinking.
 */
@Composable
internal fun clusterBlinkPhase(): () -> Float {
  val phase =
    rememberInfiniteTransition(label = "cluster-blink").animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(BLINK_PERIOD_MS, easing = LinearEasing), RepeatMode.Restart),
      label = "cluster-blink-phase",
    )
  return { phase.value }
}

/** A caption beside a readout or under a bar. */
@Composable
internal fun ClusterLabel(
  text: String,
  modifier: Modifier = Modifier,
  color: Color = ClusterColors.Label,
  align: TextAlign = TextAlign.Start,
  size: TextUnit = LABEL_SP.sp,
  tight: Boolean = false,
) {
  // One line always: the readout's label column is a fixed width now, and a speed unit long enough
  // to wrap would push that line's digits up out of alignment with the others.
  Text(
    text,
    modifier,
    color = color,
    fontSize = size,
    // [tight] drops the font's own leading, which is dead space above and below a single line of
    // capitals — worth reclaiming where the label is squeezed in under something else rather than
    // set on a line of its own. See the mark captions in ClusterReadout.
    lineHeight = if (tight) size else TextUnit.Unspecified,
    fontWeight = FontWeight.Bold,
    maxLines = 1,
    textAlign = align,
  )
}

/**
 * The caption size on this instrument: the line labels, and the ceiling for anything captioning
 * something smaller. Nothing here is set larger — a label that outshouts the value it names is a
 * label you read first and a value you read second, which is backwards.
 */
internal const val LABEL_SP = 9f
