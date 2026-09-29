package net.vertexdezign.vdt.app.panels

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlinx.coroutines.delay
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.app.theme.VdtPalette
import net.vertexdezign.vdt.model.BaleDoor
import net.vertexdezign.vdt.model.FillUnits
import net.vertexdezign.vdt.model.WrapperState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// What the picture shows — derived from the machine, pure, and tested
// ---------------------------------------------------------------------------

/** The three machines the baler screen draws. A baler-wrapper is a [ROUND] with a [BalerPicture.wrapper]. */
internal enum class BalerKind { ROUND, SQUARE, WRAPPER }

/**
 * Everything the side view needs, read off the machine — and only off the machine: every moving part
 * in the picture follows a field the game exported, so nothing moves on screen that is not moving in
 * the game. The one thing the app adds is *motion between* two exported states (the door swinging,
 * the rotor turning), because the game exports where a part is, not how it gets there.
 */
internal data class BalerPicture(
  val kind: BalerKind,
  /** The machine is the vehicle itself (a Vermeer ZR5): draw a cab, no drawbar. */
  val selfPropelled: Boolean,
  /** Null on a machine without a pickup — a standalone wrapper. */
  val pickupLowered: Boolean?,
  /** Rotor and pickup turn. */
  val turnedOn: Boolean,
  /** Crop is coming in: the bale grows and the plunger strokes. */
  val working: Boolean,
  /** The bale being formed, 0..1 of the chamber. */
  val fill: Float,
  /** A round baler's tailgate is open or opening (where it is heading, not where it is). */
  val doorOpen: Boolean,
  /** A finished round bale is still in the chamber. */
  val baleInChamber: Boolean,
  /** A round baler has just dropped: door open, chamber empty — the bale lies behind the machine. */
  val baleOnGround: Boolean,
  /** A square bale's length, metres — how far each bale moves the ones ahead of it. */
  val baleLength: Float,
  /**
   * Finished square bales still in the channel or on the chute.
   *
   * A count, not the exported `position`s. Each bale's position runs along the machine's own bale
   * curve, whose origin the export does not say — on the captured BiG Pack a freshly finished bale
   * already sits at about 0.18 — so mapping it onto the picture's channel leaves gaps the machine does
   * not have. What the game guarantees is the order and the push: each new bale moves the ones ahead
   * of it exactly one bale length. So they are drawn nose to tail from the one forming.
   */
  val channelBales: Int,
  val collectorBales: Int,
  val collectorPlaces: Int,
  val wrapper: WrapperPicture?,
)

/** The wrapping table. [progress] is 0..1 of the film; [round] picks the bale drawn on it. */
internal data class WrapperPicture(val state: WrapperState, val progress: Float, val round: Boolean)

/** The picture for [machine], or null when it is neither a baler nor a bale wrapper. */
internal fun balerPicture(machine: IsoBusMachine): BalerPicture? {
  val baler = machine.baler
  val wrapper = machine.baleWrapper
  if (baler == null && wrapper == null) return null
  val units = FillUnits(machine.fillUnits)

  val chamber = baler?.chamberIn(units)
  val fill = if (chamber != null && chamber.capacity > 0) (chamber.value / chamber.capacity).coerceIn(0f, 1f) else 0f
  val doorOpen = baler?.door == BaleDoor.OPEN || baler?.door == BaleDoor.OPENING
  val collector = baler?.collector?.let { units.at(it.fillUnit) }

  return BalerPicture(
    kind = when {
      baler == null -> BalerKind.WRAPPER
      baler.round -> BalerKind.ROUND
      else -> BalerKind.SQUARE
    },
    selfPropelled = machine.position.isEmpty(),
    pickupLowered = if (baler == null) null else machine.lowered ?: true,
    turnedOn = machine.isTurnedOn == true,
    working = baler?.working == true,
    fill = fill,
    doorOpen = doorOpen,
    baleInChamber = baler?.round == true && baler.bales.isNotEmpty(),
    // OPEN with the chamber emptied is the moment after a drop; the bale is on the ground behind it.
    baleOnGround = baler?.door == BaleDoor.OPEN && baler.bales.isEmpty(),
    baleLength = (baler?.currentBaleType?.length ?: 2.2).toFloat(),
    channelBales = if (baler?.round == false) baler.bales.size else 0,
    collectorBales = collector?.value?.toInt() ?: 0,
    collectorPlaces = collector?.capacity ?: 0,
    wrapper = wrapper?.let {
      WrapperPicture(
        state = it.state,
        progress = (it.progress ?: 0.0).toFloat().coerceIn(0f, 1f),
        round = it.round,
      )
    },
  )
}

/** The 1-based [index] into this list, as the baler aspects point into it. */
internal fun FillUnits.at(index: Int?) = index?.let { fillUnit.getOrNull(it - 1) }

/**
 * Where the bale in a round chamber reaches, as a share of the chamber's radius. By √fill, so the
 * *area* of the drawn bale — what the eye reads as "how much" — is the fill: a linear radius makes a
 * half-full chamber look a quarter full.
 */
internal fun roundBaleRadius(fill: Float): Float = sqrt(fill.coerceIn(0f, 1f))

/**
 * The rear edge of the [newest]-th newest finished square bale (0 = the one just tied), in art units:
 * it sits against the front of the bale forming, which is [fill] of a [length] long, and every older
 * bale one length further on.
 */
internal fun channelRear(newest: Int, fill: Float, length: Float): Float =
  PLUNGER_FACE + fill.coerceIn(0f, 1f) * length + newest * length

/** How far the tailgate has swung, 0..1 → degrees. */
internal fun doorAngle(open: Float): Float = -DOOR_SWING * open.coerceIn(0f, 1f)

/** How far the wrapping table tips for [state], 0..1. */
internal fun tableTilt(state: WrapperState): Float = when (state) {
  WrapperState.DROPPING -> 1f
  WrapperState.RESETTING -> 0.5f
  else -> 0f
}

// ---------------------------------------------------------------------------
// The drawing
// ---------------------------------------------------------------------------

/**
 * The art is drawn in fixed units — [BALER_ART_WIDTH] by [BALER_ART_HEIGHT], ground at [GROUND] — and scaled to
 * fit, bottom-aligned, so the ground line sits in the same place whatever the tile's shape. One unit
 * is about 2.5 cm of machine: a 1.25 m round bale is 50 units across.
 */
internal const val BALER_ART_WIDTH = 340f
internal const val BALER_ART_HEIGHT = 124f
internal const val BALER_ART_ASPECT = BALER_ART_WIDTH / BALER_ART_HEIGHT
private const val GROUND = 118f

/** Square bales only: a square baler is twice a round one's length, so its bales are drawn shorter. */
private const val SQUARE_UNITS_PER_METRE = 30f

private const val DOOR_SWING = 78f
private const val PLUNGER_FACE = 112f
private const val CHANNEL_END = 252f
private const val CHUTE_END = 300f

/**
 * The machine, from the side, facing left: drawbar and pickup on the left, the bale leaving on the
 * right — the one driving direction every side view in the app shares.
 */
@Composable
internal fun BalerArt(state: BalerPicture, modifier: Modifier = Modifier) {
  val picture = state.copy(working = heldWorking(state.working))
  val palette = VdtColors.palette
  // Where the door and the table are heading comes from the game; the swing between is ours.
  val door by animateFloatAsState(if (picture.doorOpen) 1f else 0f, tween(1400), label = "baler-door")
  val tilt by animateFloatAsState(
    picture.wrapper?.let { tableTilt(it.state) } ?: 0f,
    tween(900),
    label = "wrapper-table",
  )
  // The loading arm makes ONE trip per bale: up with it when the game starts loading, held at the top
  // until the game puts the bale on the table, then back down empty. A repeating clock here had it
  // bouncing for as long as the state read LOADING.
  val loading = picture.wrapper?.state == WrapperState.LOADING
  val arm = remember { Animatable(0f) }
  LaunchedEffect(loading) {
    if (loading) {
      arm.snapTo(0f)
      arm.animateTo(1f, tween(ARM_LIFT_MS, easing = FastOutSlowInEasing))
    } else {
      arm.animateTo(0f, tween(ARM_LIFT_MS / 2))
    }
  }
  val armLift = arm.value

  // One clock for everything that turns. It only runs while something is turning: an idle machine
  // is a still picture and costs no frames.
  val wrapperMoving = picture.wrapper?.state?.let { it in MOVING_WRAPPER_STATES } == true
  val moving = picture.turnedOn || picture.working || wrapperMoving
  val phase = if (moving) {
    val clock = rememberInfiniteTransition(label = "baler-clock")
    val value by clock.animateFloat(
      0f,
      1f,
      infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
      label = "baler-phase",
    )
    value
  } else {
    0f
  }

  Canvas(modifier) {
    val scale = min(size.width / BALER_ART_WIDTH, size.height / BALER_ART_HEIGHT)
    val left = (size.width - BALER_ART_WIDTH * scale) / 2f
    val top = size.height - BALER_ART_HEIGHT * scale
    withTransform({
      translate(left, top)
      scale(scale, scale, Offset.Zero)
    }) {
      val ink = Ink(palette)
      drawLine(ink.ground, Offset(0f, GROUND), Offset(BALER_ART_WIDTH, GROUND), strokeWidth = 1.5f)
      when (picture.kind) {
        BalerKind.ROUND -> drawRoundBaler(picture, ink, door, tilt, phase)
        BalerKind.SQUARE -> drawSquareBaler(picture, ink, phase)
        BalerKind.WRAPPER -> drawStandaloneWrapper(picture, ink, tilt, armLift, phase)
      }
    }
  }
}

/**
 * How long "crop is coming in" outlasts the last export that said so. The mod already applies the
 * game's own 500 ms hold, but the export is written on its own interval and a gap in the swath can
 * still fall between two writes — this keeps the rotor and the "Baling" chip from stuttering through
 * a pass while still going quiet within a couple of seconds of the pickup running dry.
 */
private const val WORKING_HOLD_MS = 1500L

/** [working], held true for [WORKING_HOLD_MS] after it last was. Shared with the header chip. */
@Composable
internal fun heldWorking(working: Boolean): Boolean {
  var held by remember { mutableStateOf(working) }
  LaunchedEffect(working) {
    if (working) {
      held = true
    } else {
      delay(WORKING_HOLD_MS)
      held = false
    }
  }
  return working || held
}

/** How long the standalone wrapper's arm takes to lift a bale onto the table. */
private const val ARM_LIFT_MS = 2200

private val MOVING_WRAPPER_STATES = setOf(WrapperState.WRAPPING)

/** The colours the art uses, by role — see the design rules: hue never carries a state on its own. */
private class Ink(p: VdtPalette) {
  val body = p.surface
  val line = p.textSecondary
  val dark = p.text
  val ground = p.panelBorder
  val tyre = p.textSecondary
  val rim = p.panel
  val bale = p.amber
  val baleLine = p.panel
  val film = p.green
  val filmLine = p.panel
}

// ---- Shared parts ----------------------------------------------------------

private fun DrawScope.wheel(ink: Ink, cx: Float, r: Float) {
  drawCircle(ink.tyre, r, Offset(cx, GROUND - r))
  drawCircle(ink.rim, r * 0.45f, Offset(cx, GROUND - r))
}

private fun DrawScope.outlined(ink: Ink, path: Path, fill: Color = ink.body) {
  drawPath(path, fill)
  drawPath(path, ink.line, style = Stroke(1.6f))
}

private fun poly(vararg points: Float): Path = Path().apply {
  moveTo(points[0], points[1])
  var i = 2
  while (i < points.size) {
    lineTo(points[i], points[i + 1])
    i += 2
  }
  close()
}

/** Drawbar to the hitch at the far left, or a cab on a self-propelled machine. */
private fun DrawScope.front(picture: BalerPicture, ink: Ink, bodyFront: Float) {
  if (picture.selfPropelled) {
    val cab = poly(
      bodyFront - 46f, 88f, bodyFront - 46f, 44f, bodyFront - 38f, 22f,
      bodyFront - 4f, 22f, bodyFront, 44f, bodyFront, 88f,
    )
    outlined(ink, cab)
    drawRect(ink.rim, Offset(bodyFront - 40f, 28f), Size(30f, 18f))
    drawRect(ink.line, Offset(bodyFront - 40f, 28f), Size(30f, 18f), style = Stroke(1.2f))
    wheel(ink, bodyFront - 10f, 16f)
  } else {
    drawLine(ink.line, Offset(6f, 70f), Offset(bodyFront, 78f), strokeWidth = 4f)
    drawCircle(ink.line, 3.5f, Offset(6f, 70f))
  }
}

/**
 * The pickup: a tine reel under the front of the machine, down on the swath while working and lifted
 * for the road. The tines turn with the machine.
 */
private fun DrawScope.pickup(picture: BalerPicture, ink: Ink, cx: Float, phase: Float) {
  val lowered = picture.pickupLowered ?: return
  val cy = if (lowered) GROUND - 11f else GROUND - 24f
  drawCircle(ink.body, 10f, Offset(cx, cy))
  drawCircle(ink.line, 10f, Offset(cx, cy), style = Stroke(1.6f))
  val turn = if (picture.turnedOn) -phase * 2f * PI.toFloat() else 0f
  for (i in 0 until 6) {
    val a = turn + i * (PI.toFloat() / 3f)
    drawLine(ink.dark, Offset(cx, cy), Offset(cx + cos(a) * 14f, cy + sin(a) * 14f), strokeWidth = 1.4f)
  }
  // The guard it hangs from.
  drawLine(ink.line, Offset(cx, cy), Offset(cx + 22f, cy - 18f), strokeWidth = 3f)
}

/**
 * A round bale: straw-coloured, with the spiral a chamber rolls into it. [turn] spins the spiral while
 * the chamber turns; the rings stay put, so a still bale and a spinning one are told apart by motion.
 */
private fun DrawScope.roundBale(ink: Ink, center: Offset, r: Float, turn: Float, netted: Boolean) {
  if (r < 1f) return
  drawCircle(ink.bale, r, center)
  val rings = 3
  for (i in 1..rings) drawCircle(ink.baleLine, r * i / (rings + 1f), center, style = Stroke(1.1f))
  rotate(turn * 360f, center) {
    drawLine(ink.baleLine, center, Offset(center.x + r, center.y), strokeWidth = 1.4f)
  }
  // A finished bale carries its net: a heavy rim, the shape a netted bale has and a forming one has not.
  drawCircle(if (netted) ink.dark else ink.baleLine, r, center, style = Stroke(if (netted) 2.6f else 1.2f))
}

/** A square bale seen from the side, with the strings a knotter ties round it. */
private fun DrawScope.squareBale(ink: Ink, rect: Rect) {
  if (rect.width < 1f) return
  drawRect(ink.bale, rect.topLeft, rect.size)
  for (i in 1..3) {
    val x = rect.left + rect.width * i / 4f
    drawLine(ink.baleLine, Offset(x, rect.top), Offset(x, rect.bottom), strokeWidth = 1.2f)
  }
  drawRect(ink.dark, rect.topLeft, rect.size, style = Stroke(1.2f))
}

// ---- Round baler -----------------------------------------------------------

private val CHAMBER = Offset(150f, 64f)
private const val CHAMBER_R = 38f
private val HINGE = Offset(168f, 22f)

/** A baler-wrapper's table, behind the tailgate. */
private const val COMBO_TABLE_X = 262f
private const val COMBO_TABLE_Y = 90f

private fun DrawScope.drawRoundBaler(picture: BalerPicture, ink: Ink, door: Float, tilt: Float, phase: Float) {
  val bodyFront = 102f
  front(picture, ink, bodyFront)
  // Under the cab on a self-propelled machine, ahead of its front wheel.
  pickup(picture, ink, if (picture.selfPropelled) 64f else 86f, phase)

  // The fixed front half of the chamber.
  val shell = poly(bodyFront, 100f, bodyFront, 44f, 122f, 22f, HINGE.x, HINGE.y, HINGE.x, 104f, bodyFront + 10f, 104f)
  outlined(ink, shell)

  // The chamber's own outline, dashed, so a forming bale reads against the size it will reach.
  drawCircle(
    ink.line,
    CHAMBER_R * 0.95f,
    CHAMBER,
    style = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))),
  )

  // The bale: forming inside, waiting when finished, rolling out through the door as it opens.
  val turn = if (picture.working) phase else 0f
  if (picture.baleInChamber) {
    // Out through the door as it lifts: rolled back onto the ground behind a plain baler, straight
    // onto the wrapping table on a baler-wrapper — its bale never touches the ground before it is
    // wrapped.
    val out = ((door - 0.35f) / 0.65f).coerceIn(0f, 1f)
    val full = CHAMBER_R * 0.95f
    val to = if (picture.wrapper != null) {
      // Landing at the size the table draws its bale, so the hand-over does not jump.
      Offset(COMBO_TABLE_X, COMBO_TABLE_Y - TABLE_BALE_R)
    } else {
      Offset(CHAMBER.x + 74f, GROUND - full)
    }
    val r = if (picture.wrapper != null) full + out * (TABLE_BALE_R - full) else full
    val x = CHAMBER.x + out * (to.x - CHAMBER.x)
    val y = CHAMBER.y + out * (to.y - CHAMBER.y)
    roundBale(ink, Offset(x, y), r, turn, netted = true)
  } else {
    roundBale(ink, CHAMBER, CHAMBER_R * 0.95f * roundBaleRadius(picture.fill), turn, netted = false)
  }
  if (picture.baleOnGround && picture.wrapper == null) {
    val r = CHAMBER_R * 0.95f
    roundBale(ink, Offset(CHAMBER.x + 74f + 40f * door, GROUND - r), r, 0f, netted = true)
  }

  // The tailgate, hinged at the top and swinging up and back.
  rotate(doorAngle(door), HINGE) {
    val gate = poly(HINGE.x, HINGE.y, 190f, 34f, 196f, 64f, 188f, 96f, HINGE.x + 6f, 106f, HINGE.x, 104f)
    drawPath(gate, ink.body.copy(alpha = 0.92f))
    drawPath(gate, ink.line, style = Stroke(1.6f))
  }
  wheel(ink, 148f, 14f)

  picture.wrapper?.let {
    drawWrapperTable(it, ink, tilt, phase, tableX = COMBO_TABLE_X, tableY = COMBO_TABLE_Y, postAtFront = false)
  }
}

// ---- Square baler ----------------------------------------------------------

private fun DrawScope.drawSquareBaler(picture: BalerPicture, ink: Ink, phase: Float) {
  val bodyFront = 86f
  // The bale's own section: 0.9 m high against 1.2-2.4 m long, at the same scale as its length.
  val top = 50f
  val bottom = 80f

  // Drawbar up to the flywheel, which sits high on the front of a square baler and turns with it.
  if (!picture.selfPropelled) {
    drawLine(ink.line, Offset(6f, 64f), Offset(bodyFront - 10f, 60f), strokeWidth = 4f)
    drawCircle(ink.line, 3.5f, Offset(6f, 64f))
  } else {
    front(picture, ink, bodyFront)
  }
  pickup(picture, ink, 64f, phase)

  // Feeder duct from the pickup up into the channel.
  outlined(ink, poly(bodyFront - 8f, 96f, bodyFront - 4f, 82f, PLUNGER_FACE, bottom, PLUNGER_FACE, 100f, 96f, 106f))

  // The body: tall over the plunger and knotters at the front, falling towards the rear.
  val body = poly(
    bodyFront, bottom + 4f, bodyFront, 34f, bodyFront + 10f, 22f, 176f, 20f,
    190f, 34f, CHANNEL_END, top - 6f, CHANNEL_END, bottom + 4f,
  )
  outlined(ink, body)
  // The knotter hood's edge, where the twine is tied.
  drawLine(ink.line, Offset(150f, 21f), Offset(150f, top - 4f), strokeWidth = 1.2f)
  // The channel the bales ride in, a shade under the body.
  drawRect(ink.rim, Offset(PLUNGER_FACE, top - 2f), Size(CHANNEL_END - PLUNGER_FACE, bottom - top + 4f))

  // The flywheel.
  val flywheel = Offset(bodyFront - 2f, 46f)
  drawCircle(ink.body, 17f, flywheel)
  drawCircle(ink.line, 17f, flywheel, style = Stroke(2f))
  val spin = if (picture.turnedOn) phase * 360f else 0f
  rotate(spin, flywheel) {
    drawLine(ink.line, Offset(flywheel.x - 14f, flywheel.y), Offset(flywheel.x + 14f, flywheel.y), strokeWidth = 2f)
    drawLine(ink.line, Offset(flywheel.x, flywheel.y - 14f), Offset(flywheel.x, flywheel.y + 14f), strokeWidth = 2f)
  }

  // The chute beyond the body, sloping to the ground.
  drawLine(ink.line, Offset(CHANNEL_END, bottom + 2f), Offset(CHUTE_END, GROUND - 10f), strokeWidth = 3f)

  // Finished bales nose to tail ahead of the one forming against the plunger.
  val length = picture.baleLength * SQUARE_UNITS_PER_METRE
  clipPath(poly(PLUNGER_FACE, 0f, BALER_ART_WIDTH, 0f, BALER_ART_WIDTH, GROUND, PLUNGER_FACE, GROUND)) {
    for (newest in 0 until picture.channelBales) {
      val rear = channelRear(newest, picture.fill, length)
      // Once its middle is past the end of the channel it tips down the chute.
      val drop = ((rear + length / 2f - CHANNEL_END) / (length * 0.6f)).coerceIn(0f, 1f)
      rotate(drop * 30f, Offset(CHANNEL_END, bottom)) {
        squareBale(ink, Rect(rear, top, rear + length, bottom))
      }
    }
  }
  squareBale(ink, Rect(PLUNGER_FACE, top, PLUNGER_FACE + length * picture.fill, bottom))

  // The plunger, stroking while crop is fed.
  val stroke = if (picture.working) (1f - cos(phase * 2f * PI.toFloat() * 3f)) * 4f else 0f
  drawRect(ink.dark, Offset(bodyFront + 8f + stroke, top), Size(PLUNGER_FACE - bodyFront - 10f, bottom - top))

  // Tandem axle towards the rear.
  wheel(ink, 204f, 14f)
  wheel(ink, 234f, 14f)

  if (picture.collectorPlaces > 0) collector(picture, ink)
}

/**
 * The bale collector: a rack under the end of the chute with a place per bale it holds, drawn side by
 * side because the count is what matters, not how the rack stacks them.
 */
private fun DrawScope.collector(picture: BalerPicture, ink: Ink) {
  val places = picture.collectorPlaces.coerceAtMost(6)
  val slot = 12f
  val width = places * slot + 4f
  val x0 = CHUTE_END - width + 6f
  drawRect(ink.body, Offset(x0, GROUND - 22f), Size(width, 16f))
  drawRect(ink.line, Offset(x0, GROUND - 22f), Size(width, 16f), style = Stroke(1.4f))
  for (i in 0 until places) {
    val r = Rect(x0 + 2f + i * slot + 1f, GROUND - 20f, x0 + 2f + (i + 1) * slot - 1f, GROUND - 8f)
    if (i < picture.collectorBales) {
      squareBale(ink, r)
    } else {
      drawRect(ink.line, r.topLeft, r.size, style = Stroke(0.8f))
    }
  }
}

// ---- Wrapping table ----------------------------------------------------------

/**
 * A standalone wrapper: drawbar, the loading arm that lifts the bale off the ground in front, and the
 * table. LOADING swings the arm up with a bale in it; otherwise it rests down, empty.
 */
private fun DrawScope.drawStandaloneWrapper(picture: BalerPicture, ink: Ink, tilt: Float, lift: Float, phase: Float) {
  val wrapper = picture.wrapper ?: return
  val tableX = 212f
  front(picture, ink, 124f)
  outlined(ink, poly(124f, 96f, 124f, 88f, tableX + 40f, 88f, tableX + 40f, 96f))

  // The loading arm, from its pivot on the frame to a hand that travels from a bale lying in front of
  // the machine up to the edge of the table.
  val pivot = Offset(156f, 90f)
  val down = Offset(112f, GROUND - baleHalfHeight(wrapper))
  val up = Offset(tableX - 30f, 46f)
  val hand = Offset(down.x + (up.x - down.x) * lift, down.y + (up.y - down.y) * lift)
  drawLine(ink.dark, pivot, hand, strokeWidth = 4f)
  if (wrapper.state == WrapperState.LOADING) wrapperBale(wrapper, ink, hand, wrap = 0f)

  val loading = wrapper.state == WrapperState.LOADING
  drawWrapperTable(wrapper, ink, tilt, phase, tableX = tableX, tableY = 80f, carriesLoad = !loading)
}

/**
 * The table and the bale on it. Film goes on as bands across the bale, one per share of [progress],
 * and a finished bale is covered — so "wrapped" is a pattern and a word, not only a colour. The
 * satellite arm on its post at the front sweeps over the bale while wrapping. The table tips back,
 * rear edge down, to drop.
 */
private fun DrawScope.drawWrapperTable(
  wrapper: WrapperPicture,
  ink: Ink,
  tilt: Float,
  phase: Float,
  tableX: Float,
  tableY: Float,
  carriesLoad: Boolean = true,
  postAtFront: Boolean = true,
) {
  // The post first, so the bale on the table is drawn in front of it. Behind the table on a
  // baler-wrapper, where the tailgate swings up through the space in front of it.
  val side = if (postAtFront) -1f else 1f
  val post = tableX + side * 46f
  drawLine(ink.line, Offset(post, GROUND - 2f), Offset(post, 18f), strokeWidth = 3f)
  val sweep = if (wrapper.state == WrapperState.WRAPPING) sin(phase * 2f * PI.toFloat()) else side
  val armEnd = Offset(tableX + sweep * 34f, 18f)
  drawLine(ink.dark, Offset(post, 18f), armEnd, strokeWidth = 3f)
  drawCircle(ink.film, 4f, Offset(armEnd.x, armEnd.y + 6f))

  wheel(ink, tableX, 12f)
  val hasBale = carriesLoad && wrapper.state in BALE_ON_TABLE
  rotate(tilt * 30f, Offset(tableX + 40f, tableY + 8f)) {
    drawRect(ink.body, Offset(tableX - 40f, tableY), Size(80f, 8f))
    drawRect(ink.line, Offset(tableX - 40f, tableY), Size(80f, 8f), style = Stroke(1.4f))
    if (hasBale) {
      val wrap = when (wrapper.state) {
        WrapperState.WRAPPING -> wrapper.progress
        WrapperState.WRAPPED, WrapperState.DROPPING -> 1f
        else -> 0f
      }
      // Rolling towards the low end as the table tips.
      wrapperBale(wrapper, ink, Offset(tableX + tilt * 18f, tableY - baleHalfHeight(wrapper)), wrap)
    }
  }
}

private val BALE_ON_TABLE =
  setOf(WrapperState.LOADED, WrapperState.WRAPPING, WrapperState.WRAPPED, WrapperState.DROPPING)

private fun baleHalfHeight(wrapper: WrapperPicture): Float = if (wrapper.round) TABLE_BALE_R else 20f

/** A round bale's radius on the wrapping table. */
private const val TABLE_BALE_R = 30f

/** The bale on the table or in the arm, with [wrap] 0..1 of its film on. */
private fun DrawScope.wrapperBale(wrapper: WrapperPicture, ink: Ink, center: Offset, wrap: Float) {
  val clip = if (wrapper.round) {
    Path().apply { addOval(Rect(center, TABLE_BALE_R)) }
  } else {
    Path().apply { addRect(Rect(center.x - 36f, center.y - 20f, center.x + 36f, center.y + 20f)) }
  }
  // Wrapped through, the film covers the bale; on the way there it goes on band by band, each on its
  // own slant as successive turns of film do, until the bands overlap into the whole.
  drawPath(clip, if (wrap >= 1f) ink.film else ink.bale)
  clipPath(clip) {
    val bands = 10
    val on = if (wrap >= 1f) bands else (wrap * bands).toInt()
    for (i in 0 until on) {
      rotate(i * (180f / bands), center) {
        if (wrap < 1f) drawRect(ink.film, Offset(center.x - 60f, center.y - 7f), Size(120f, 14f))
        val edge = center.y - 7f
        drawLine(ink.filmLine, Offset(center.x - 60f, edge), Offset(center.x + 60f, edge), strokeWidth = 0.8f)
      }
    }
  }
  drawPath(clip, ink.dark, style = Stroke(if (wrap >= 1f) 2.6f else 1.2f))
}
