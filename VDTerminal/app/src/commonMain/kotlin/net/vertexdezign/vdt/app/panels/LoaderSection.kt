package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.FillUnit
import net.vertexdezign.vdt.model.LoaderCylinder
import net.vertexdezign.vdt.model.LoaderCylinderRole
import net.vertexdezign.vdt.model.LoaderJoint
import net.vertexdezign.vdt.model.LoaderTool
import net.vertexdezign.vdt.model.LoaderToolKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Inside this many degrees of the player's level, the tool reads LEVEL. A real loader display's bubble
 * is about this coarse; the figure beside it is there for anyone who wants the decimal.
 */
internal const val LEVEL_TOLERANCE_DEG = 1f

/** Side by side from this width; stacked, with the picture the first thing to go, below it. */
private val SIDE_BY_SIDE_FROM = 300.dp
private val READOUT_WIDTH = 130.dp
private val MIN_GLYPH_HEIGHT = 60.dp

/**
 * The loader, as the ISOBUS screen treats it: **one screen over two or three machines**.
 *
 * On the wire a tractor's front loader is three nodes — the tractor, the loader carrying the lift and
 * tilt cylinders, and the tool on the loader carrying the angle and the distance — and a wheel
 * loader or telehandler is two. A tool's own clamp is a cylinder too, on the tool. None of them is
 * worth a screen alone (the arm cannot say whether the shovel is level, the shovel cannot say how
 * high the arm is), so every node that carries either aspect resolves to this — the combine's rule
 * ([CombineRig]), for the same reason.
 *
 * [toolNode] is the rig diagram's path to the tool, which is what "set level" is addressed by. Null on
 * a tile pinned to a slot, where there is no diagram and so no path: the button then stays inert.
 */
internal data class LoaderRig(
  val tool: IsoBusMachine?,
  val toolNode: String?,
  /** Every loader cylinder on the rig, the arm's first and the tool's own after, in rig order. */
  val cylinders: List<LoaderCylinder>,
) {
  val reading: LoaderTool? get() = tool?.loaderTool

  /**
   * How far a high-tip bucket's own cylinder has turned it on its frame, degrees nose up; 0 on any other
   * tool. The tool's pitch is read off its root node, which is that frame, so it cannot see this turn.
   */
  val tipAngle: Float get() = tool?.loaderCylinders?.firstOrNull { it.role == LoaderCylinderRole.TIP }?.angle ?: 0f

  /** A forklift: forks on a mast rather than a tool on an arm, so the side view and the names change. */
  val forklift: Boolean get() = reading?.joint == LoaderJoint.FORKLIFT

  /** The bucket's angle off level as the screen shows it: the tool's, plus a high tip's turn. */
  val inclination: Float? get() = reading?.let { it.inclination + tipAngle }

  /**
   * What the tool is carrying, when it has a fill unit to say: a shovel's bucket. A fork's pallet or a
   * grab's bale is a separate object the game mounts, not a fill level, so they have none.
   */
  val load: FillUnit? get() = tool?.fillUnits?.firstOrNull { it.capacity > 0 }

  /** The tool's own clamp, when one of its cylinders says it opens and closes ([isClamp]). */
  val clamp: LoaderCylinder? get() = tool?.loaderCylinders?.firstOrNull { it.isClamp }

  /**
   * How far open a grab is, 1 open: its [clamp], or on a capture from before the mod named the
   * control's icon, its first AUX cylinder — on a bale or log grab that is the grab arm. Null on a tool
   * with neither.
   */
  val toolOpen: Float? get() =
    (clamp ?: tool?.loaderCylinders?.firstOrNull { it.role == LoaderCylinderRole.AUX })?.travel
}

/** The engine's control icon for a working width, which on a fork is how far apart its tines sit. */
private const val WIDTH_ICON = "WORKING_WIDTH_TRANSLATE_X"

/** The engine's control icons for a part that opens and closes, which on a loader tool is its clamp. */
private val CLAMP_ICONS = setOf("GRABBER_OPEN_CLOSE", "TOOL_OPEN_CLOSE")

/**
 * Whether a cylinder is a tool's clamp: a muck grab's top-hold, a bale or log grab's arms. Read off the
 * icon the author gave its control, because the axis does not say — `TOOL2` is also a pallet fork's
 * tine spread and a skid steer bucket's high tip, neither of which may be drawn as a clamp.
 */
internal val LoaderCylinder.isClamp: Boolean get() = role == LoaderCylinderRole.AUX && icon in CLAMP_ICONS

/**
 * The loader on this rig, or null when nothing on it is one. [machines] pairs every machine with its
 * diagram path, or with null where there is no diagram.
 */
internal fun loaderRigOf(machines: List<Pair<String?, IsoBusMachine>>): LoaderRig? {
  val tool = machines.firstOrNull { it.second.loaderTool != null }
  val cylinders = machines.flatMap { it.second.loaderCylinders }
  if (tool == null && cylinders.isEmpty()) return null
  return LoaderRig(tool?.second, tool?.first, cylinders)
}

/** Whether a machine is part of a loader — the test that decides the loader screen opens on it. */
internal val IsoBusMachine.isLoaderPart: Boolean get() = loaderTool != null || loaderCylinders.isNotEmpty()

/**
 * Degrees off level in the words the screen uses: `LEVEL` inside [LEVEL_TOLERANCE_DEG], otherwise the
 * signed angle to a tenth. ASCII `-`: the wasm build has no glyph for U+2212.
 */
internal fun inclinationLabel(degrees: Float): String {
  if (abs(degrees) < LEVEL_TOLERANCE_DEG) return "LEVEL"
  val tenths = (degrees * 10).roundToInt()
  val sign = if (tenths > 0) "+" else "-"
  return "$sign${abs(tenths) / 10}.${abs(tenths) % 10}°"
}

/** Metres to a centimetre, to a decimetre past ten metres where centimetres stop meaning anything. */
internal fun heightLabel(metres: Float): String {
  val sign = if (metres < 0) "-" else ""
  val m = abs(metres)
  return if (m >= 10f) {
    val tenths = (m * 10).roundToInt()
    "$sign${tenths / 10}.${tenths % 10} m"
  } else {
    val cm = (m * 100).roundToInt()
    "$sign${cm / 100}.${(cm % 100).toString().padStart(2, '0')} m"
  }
}

/**
 * A cylinder's name on the screen: what it does, and for a tool's own, which one it is. On a [forklift]
 * the second arm axis slides the mast out rather than running a boom out, so it is the reach.
 */
internal fun cylinderLabel(cylinder: LoaderCylinder, forklift: Boolean = false): String = when (cylinder.role) {
  LoaderCylinderRole.LIFT -> "Lift"

  LoaderCylinderRole.TELESCOPE -> if (forklift) "Reach" else "Telescope"

  LoaderCylinderRole.SHIFT -> "Side shift"

  LoaderCylinderRole.TILT -> "Tilt"

  LoaderCylinderRole.TIP -> "High tip"

  LoaderCylinderRole.AUX -> when {
    cylinder.isClamp -> "Clamp"
    cylinder.icon == WIDTH_ICON -> "Width"
    else -> "Tool " + cylinder.axis.removePrefix("AXIS_FRONTLOADER_TOOL").ifEmpty { "1" }
  }
}

/** Which way off level, as a glyph: hue never carries it alone, and a word goes beside it. */
private fun inclinationMark(degrees: Float): ImageVector = when {
  abs(degrees) < LEVEL_TOLERANCE_DEG -> Icons.Filled.Check
  degrees > 0 -> Icons.Filled.ArrowUpward
  else -> Icons.Filled.ArrowDownward
}

/** The header's one line: how far off level the tool is, the glance a driver makes between buckets. */
@Composable
internal fun RowScope.LoaderStateChip(rig: LoaderRig) {
  val inclination = rig.inclination ?: return
  Chip(inclinationMark(inclination), inclinationLabel(inclination), VdtColors.TextDark)
}

/**
 * The loader screen: a side view of the arm and the tool, how far off level the tool is and how high,
 * each cylinder's travel, and "set level".
 *
 * **The root node is the default level.** Nothing in the engine makes it parallel to a shovel floor,
 * but on every captured tool it is within a degree or so, so the screen reads off it until the player
 * sets a level for that tool model — and says which of the two it is reading off.
 */
@Composable
internal fun LoaderSection(rig: LoaderRig, onCommand: (ClientMessage) -> Unit, modifier: Modifier = Modifier) {
  BoxWithConstraints(modifier) {
    val sideBySide = maxWidth >= SIDE_BY_SIDE_FROM
    val glyphHeight = maxHeight - 40.dp
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      if (sideBySide) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          if (glyphHeight >= MIN_GLYPH_HEIGHT) {
            LoaderGlyph(rig, Modifier.weight(1f).fillMaxHeight())
          }
          LoaderReadouts(rig, Modifier.width(READOUT_WIDTH).fillMaxHeight())
        }
      } else {
        LoaderReadouts(rig, Modifier.fillMaxWidth())
        if (glyphHeight >= MIN_GLYPH_HEIGHT * 2) LoaderGlyph(rig, Modifier.weight(1f).fillMaxWidth())
      }
      LoaderControls(rig, onCommand, Modifier.fillMaxWidth())
    }
  }
}

/**
 * The figures beside (or above) the side view. A shovel with a clamp on a front loader has the most to
 * say — angle, height, load and three cylinders — and the column is as tall as the tile, so it scrolls
 * rather than cutting the last row off; it is centred whenever it fits.
 */
@Composable
private fun LoaderReadouts(rig: LoaderRig, modifier: Modifier = Modifier) {
  Box(modifier, contentAlignment = Alignment.Center) {
    LoaderReadoutColumn(rig, Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
  }
}

@Composable
private fun LoaderReadoutColumn(rig: LoaderRig, modifier: Modifier = Modifier) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    rig.reading?.let { reading ->
      AngleReadout(reading, rig.inclination ?: reading.inclination)
      val metres = reading.height
      Figure(
        "Height",
        metres?.let(::heightLabel) ?: "-",
        when {
          metres == null -> "nothing below in reach"
          reading.reference?.distance != null -> "above your zero"
          else -> "above what is below"
        },
      )
    }
    // The tool's own fill units are not drawn by the panel's generic block once the loader screen is
    // open (it stands down for any machine with a section), so the load is printed here.
    rig.load?.let { unit ->
      Figure("Load", "${unit.fillLevelPercentage}%", unit.title.takeIf { unit.value > 0f && it.isNotBlank() })
    }
    if (rig.cylinders.isNotEmpty()) {
      @OptIn(ExperimentalLayoutApi::class)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rig.cylinders.forEach {
          Figure(cylinderLabel(it, rig.forklift), "${(it.travel * 100).roundToInt()}%", null, compact = true)
        }
      }
    }
  }
}

/**
 * The angle, with its glyph and its word — and whose level it is measured from, because the default
 * (the tool's root node) and the player's own read the same and are not the same thing.
 */
@Composable
private fun AngleReadout(reading: LoaderTool, inclination: Float) {
  Column {
    Text("ANGLE", style = figureStyle(9.sp, VdtColors.DarkGray, FontWeight.Bold), maxLines = 1)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      Icon(
        inclinationMark(inclination),
        contentDescription = null,
        tint = VdtColors.TextDark,
        modifier = Modifier.size(18.dp),
      )
      Text(
        inclinationLabel(inclination),
        style = figureStyle(20.sp, VdtColors.TextDark, FontWeight.Bold),
        maxLines = 1,
      )
    }
    Text(
      angleCaption(inclination, reading.reference != null),
      style = figureStyle(10.sp, VdtColors.DarkGray, FontWeight.Normal),
      maxLines = 1,
    )
  }
}

/** The line under the angle: which way it is off, and off whose level. */
internal fun angleCaption(inclination: Float, ownLevel: Boolean): String {
  val off = when {
    abs(inclination) < LEVEL_TOLERANCE_DEG -> if (ownLevel) "on your level" else "on the default level"
    inclination > 0 -> "nose up"
    else -> "nose down"
  }
  return if (abs(inclination) < LEVEL_TOLERANCE_DEG || ownLevel) off else "$off, default level"
}

/**
 * "Set level" — the tool's pose right now becomes this tool model's zero, for every copy of it, on
 * every save and every screen — and, once there is one, "Clear level", back to the default.
 *
 * Inert without a diagram path ([LoaderRig.toolNode]): the command is addressed by it, and a pinned
 * tile has none.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LoaderControls(rig: LoaderRig, onCommand: (ClientMessage) -> Unit, modifier: Modifier = Modifier) {
  val reading = rig.reading ?: return
  val node = rig.toolNode
  FlowRow(
    modifier,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Chip(
      Icons.Filled.Straighten,
      if (reading.reference == null) "Set level" else "Set level here",
      VdtColors.TextDark,
      onClick = node?.let { { onCommand(ClientMessage.SetLoaderReference(it, on = true)) } },
      control = true,
    )
    if (reading.reference != null) {
      Chip(
        Icons.Filled.Close,
        "Clear level",
        VdtColors.TextDark,
        onClick = node?.let { { onCommand(ClientMessage.SetLoaderReference(it, on = false)) } },
        control = true,
      )
    }
  }
}

// ---------------------------------------------------------------------------
// The side view
// ---------------------------------------------------------------------------

/** The arm's angle above horizontal at the bottom and the top of its lift — a schematic, not a measurement. */
private const val ARM_LOW_DEG = -28f
private const val ARM_HIGH_DEG = 42f

/**
 * The side view in model units, the arm's retracted length being 1: how much a telescope adds, how big
 * a tool is, how far its farthest point can sit from the hinge at any angle, and the margin kept clear
 * for line widths. The reach is set by the log grab's top arm fully open, the one part that stands
 * out past a tool length (a bucket's cutting edge is exactly one); the bounds test walks every kind.
 */
private const val TELESCOPE_GAIN = 0.45f

/**
 * A forklift's mast, in the same units: its height, how high the carriage sits at the bottom and the
 * top of the lift, and how far the reach slides the whole mast forward. Schematic, like the arm.
 */
private const val MAST_HEIGHT = 1.1f
private const val MAST_LOW = 0.05f
private const val MAST_HIGH = 1f
private const val MAST_REACH = 0.6f
private const val MAST_LEAN_MAX_DEG = 15f
private const val TOOL_SIZE = 0.44f
private const val TOOL_REACH = TOOL_SIZE * 1.25f
private const val MARGIN = 0.08f

/**
 * Where the side view sits in a canvas of [width] x [height]: the pivot, the scale from model units,
 * and the ground line.
 *
 * Fitted to the **whole reach** of the machine, not to where the arm is now: the lowest and highest
 * lift, retracted and (on a [telescopic] machine) fully out, with the bucket turned any way at the
 * tip. So nothing the loader can do takes the picture out of its box — a fixed pivot and scale drew a
 * raised telehandler's fork over the panel's header and the control-group chips — and the picture
 * does not rescale as the arm moves.
 */
internal data class GlyphFrame(val pivot: Offset, val scale: Float, val ground: Float)

internal fun glyphFrame(width: Float, height: Float, telescopic: Boolean, forklift: Boolean = false): GlyphFrame {
  var minX = 0f
  var maxX = 0f
  var minY = 0f
  var maxY = 0f
  // The arm's tip is farthest out at the two ends of the lift and, horizontally, at level; a mast's
  // carriage at its two ends, under the mast's top. Either fully in and fully out.
  val lifts = if (forklift) listOf(0f, 1f) else listOf(0f, 1f, -ARM_LOW_DEG / (ARM_HIGH_DEG - ARM_LOW_DEG))
  val leans = if (forklift) listOf(-MAST_LEAN_MAX_DEG, 0f, MAST_LEAN_MAX_DEG) else listOf(0f)
  for (lift in lifts) {
    for (telescope in if (telescopic) listOf(0f, 1f) else listOf(0f)) {
      for (lean in leans) {
        val tip = tipOffset(lift, telescope, forklift, lean)
        minX = minOf(minX, tip.x - TOOL_REACH)
        maxX = maxOf(maxX, tip.x + TOOL_REACH)
        minY = minOf(minY, tip.y - TOOL_REACH)
        maxY = maxOf(maxY, tip.y + TOOL_REACH)
        if (forklift) {
          val top = mastPoint(telescope, MAST_HEIGHT, lean)
          minX = minOf(minX, top.x)
          maxX = maxOf(maxX, top.x)
          minY = minOf(minY, top.y)
        }
      }
    }
  }
  minX -= MARGIN
  maxX += MARGIN
  minY -= MARGIN
  maxY += MARGIN
  val scale = minOf(width / (maxX - minX), height / (maxY - minY))
  val pivot = Offset(
    (width - (maxX - minX) * scale) / 2f - minX * scale,
    (height - (maxY - minY) * scale) / 2f - minY * scale,
  )
  return GlyphFrame(pivot, scale, pivot.y + (maxY - MARGIN / 2) * scale)
}

/** The arm's tip relative to its pivot, in model units: to the LEFT (facing left), up for a positive angle. */
private fun armOffset(degrees: Float, length: Float): Offset {
  val rad = degrees * PI.toFloat() / 180f
  return Offset(-length * cos(rad), -length * sin(rad))
}

/**
 * Where the tool hangs, relative to the pivot in model units, for a [lift] and [telescope] travel: at
 * the arm's tip, or on a [forklift] at the carriage — [MAST_LOW]..[MAST_HIGH] up a mast standing level
 * with the pivot, slid out to the left (forward) by its reach and leaning back by [lean] degrees.
 */
private fun tipOffset(lift: Float, telescope: Float, forklift: Boolean, lean: Float = 0f): Offset {
  val l = lift.coerceIn(0f, 1f)
  val t = telescope.coerceIn(0f, 1f)
  if (forklift) return mastPoint(t, MAST_LOW + (MAST_HIGH - MAST_LOW) * l, lean)
  return armOffset(ARM_LOW_DEG + (ARM_HIGH_DEG - ARM_LOW_DEG) * l, 1f + TELESCOPE_GAIN * t)
}

/**
 * A point [height] up a forklift's mast, in model units from the pivot: the foot slid forward by the
 * reach, the mast leaning back (toward the machine, so right) by [lean] degrees — clamped to
 * [MAST_LEAN_MAX_DEG], a mast's real tilt being a few degrees either way.
 */
private fun mastPoint(telescope: Float, height: Float, lean: Float): Offset {
  val rad = lean.coerceIn(-MAST_LEAN_MAX_DEG, MAST_LEAN_MAX_DEG) * PI.toFloat() / 180f
  return Offset(-MAST_REACH * telescope.coerceIn(0f, 1f) + height * sin(rad), -height * cos(rad))
}

/** The arm's tip — a forklift's carriage — on the canvas for a [lift] and [telescope] travel. */
internal fun armTip(
  frame: GlyphFrame,
  lift: Float,
  telescope: Float,
  forklift: Boolean = false,
  lean: Float = 0f,
): Offset = frame.at(tipOffset(lift, telescope, forklift, lean))

/** The foot of a forklift's mast on the canvas. */
internal fun mastFoot(frame: GlyphFrame, telescope: Float) = frame.at(mastPoint(telescope, 0f, 0f))

/** The top of a forklift's mast on the canvas, leaning back by [lean] degrees. */
internal fun mastTop(frame: GlyphFrame, telescope: Float, lean: Float = 0f) =
  frame.at(mastPoint(telescope, MAST_HEIGHT, lean))

/** A point in model units relative to the pivot, on the canvas. */
private fun GlyphFrame.at(offset: Offset) = Offset(pivot.x + offset.x * scale, pivot.y + offset.y * scale)

/** Turns a point given in the tool's own frame (edge to the left, up negative) about the hinge at [tip]. */
private class ToolFrame(val tip: Offset, val size: Float, degrees: Float) {
  private val rad = degrees.coerceIn(-90f, 90f) * PI.toFloat() / 180f

  /** [x], [y] in tool lengths. Screen y grows downward, so a positive angle SUBTRACTS from the edge's y. */
  fun at(x: Float, y: Float): Offset {
    val px = x * size
    val py = y * size
    return Offset(tip.x + px * cos(rad) - py * sin(rad), tip.y + px * sin(rad) + py * cos(rad))
  }
}

/**
 * The tool in profile as polylines hinged at [tip] — working edge to the LEFT (design rule), turned
 * nose-up for a positive [degrees] — one shape per [kind]:
 *
 * - **Shovel**: floor, back wall and the lip of the back wall: a bucket.
 * - **Fork**: tines along the floor line and the carriage frame standing at the hinge.
 * - **Bale grab**: the frame with two arms reaching forward, top and bottom, around where a bale sits.
 * - **Log grab**: the fork's tines and carriage, with a top arm hinged on the carriage that swings
 *   down onto the tines as it closes.
 *
 * Both grabs open with [open], the tool's own cylinder travel — 1 open, as the mod's input-sense
 * orientation reads both captured grabs (see `LoaderCylinders.lua`).
 * - A shovel or fork with a [clamp] — a muck grab, a silage grab — gets the log grab's top arm too,
 *   hinged at the top of its back wall or carriage and opening with the clamp's travel. Without one
 *   it is drawn bare: an unnamed tool cylinder may be a fork's tine spread, which is no arm at all.
 * - **Other**, and a capture that names no kind: a plain plate.
 *
 * Every point stays within the reach [glyphFrame] fits the picture to — so no kind can take the side
 * view out of its box.
 */
internal fun toolStrokes(
  frame: GlyphFrame,
  tip: Offset,
  degrees: Float,
  kind: LoaderToolKind?,
  open: Float = DEFAULT_OPEN,
  clamp: Float? = null,
): List<List<Offset>> {
  val t = ToolFrame(tip, TOOL_SIZE * frame.scale, degrees)
  val o = open.coerceIn(0f, 1f)
  return when (kind) {
    LoaderToolKind.SHOVEL -> listOfNotNull(
      listOf(t.at(-1f, 0f), t.at(0f, 0f), t.at(0f, -0.75f), t.at(-0.35f, -0.85f)),
      clamp?.let { topArm(t, SHOVEL_CLAMP_HINGE_Y, it) },
    )

    LoaderToolKind.FORK -> listOfNotNull(
      listOf(t.at(-0.95f, 0f), t.at(0f, 0f)),
      listOf(t.at(0f, 0.1f), t.at(0f, -0.7f), t.at(-0.12f, -0.7f)),
      clamp?.let { topArm(t, LOG_ARM_HINGE_Y, it) },
    )

    // The arms swing apart at their tips as the grab opens.
    LoaderToolKind.BALE_GRAB -> {
      val spread = 0.12f * o
      listOf(
        listOf(t.at(0f, -0.05f), t.at(0f, -0.55f)),
        listOf(t.at(0f, -0.55f), t.at(-0.7f, -0.55f - spread), t.at(-0.7f, -0.43f - spread)),
        listOf(t.at(0f, -0.05f), t.at(-0.7f, -0.05f + spread), t.at(-0.7f, -0.17f + spread)),
      )
    }

    // A fork whose top arm, hinged on the carriage, swings down onto the tines to hold the logs.
    LoaderToolKind.LOG_GRAB -> listOf(
      listOf(t.at(-0.95f, 0f), t.at(0f, 0f)),
      listOf(t.at(0f, 0.1f), t.at(0f, LOG_ARM_HINGE_Y)),
      topArm(t, LOG_ARM_HINGE_Y, o),
    )

    LoaderToolKind.OTHER, null -> listOf(
      listOf(t.at(0f, 0f), t.at(-0.15f, 0f)),
      listOf(t.at(-0.15f, 0.25f), t.at(-0.15f, -0.65f)),
    )
  }
}

/**
 * A top arm hinged at the back of the tool [hingeY] up, swinging down over the floor as it closes
 * ([open] 0) — with a claw at its end bent down toward the floor. The log grab's, and a clamp's on a
 * shovel or fork.
 */
private fun topArm(t: ToolFrame, hingeY: Float, open: Float): List<Offset> {
  val armDeg = LOG_ARM_CLOSED_DEG + (LOG_ARM_OPEN_DEG - LOG_ARM_CLOSED_DEG) * open.coerceIn(0f, 1f)
  val armRad = armDeg * PI.toFloat() / 180f
  val armTipX = -LOG_ARM_LENGTH * cos(armRad)
  val armTipY = hingeY - LOG_ARM_LENGTH * sin(armRad)
  return listOf(t.at(0f, hingeY), t.at(armTipX, armTipY), t.at(armTipX + 0.06f, armTipY + 0.14f))
}

/** Where a shovel's clamp is hinged: the top of its back wall. */
private const val SHOVEL_CLAMP_HINGE_Y = -0.75f

/** The log grab's top arm: where it is hinged on the carriage, how long it is, and its swing. */
private const val LOG_ARM_HINGE_Y = -0.65f
private const val LOG_ARM_LENGTH = 0.85f
private const val LOG_ARM_CLOSED_DEG = -28f
private const val LOG_ARM_OPEN_DEG = 20f

/** How open a grab is drawn when it reports no cylinder of its own. */
private const val DEFAULT_OPEN = 0.3f

/**
 * What is in a shovel, as the polygon to fill: the bucket's inside from the floor up to [fraction] of
 * the back wall, **turning with the bucket**. A level that stayed horizontal as the bucket tipped
 * would read empty mid-tip while the game is still emptying it gradually; this reads what the fill
 * unit says. Null when there is nothing to draw.
 */
internal fun shovelFill(frame: GlyphFrame, tip: Offset, degrees: Float, fraction: Float): List<Offset>? {
  val f = fraction.coerceIn(0f, 1f)
  if (f <= 0f) return null
  val t = ToolFrame(tip, TOOL_SIZE * frame.scale, degrees)
  val h = 0.75f * f
  // The front of the bucket runs from the cutting edge (-1, 0) to the lip (-0.35, -0.85).
  val front = -1f + 0.65f * (h / 0.85f)
  return listOf(t.at(-1f, 0f), t.at(0f, 0f), t.at(0f, -h), t.at(front, -h))
}

/**
 * The loader from the side, **facing left** (design rule): the arm from its pivot on the right, raised
 * by its lift travel and lengthened by its telescope, and the tool at its tip turned to the angle the
 * screen prints. A forklift instead stands a mast on a rail from the pivot, slid out by its reach, with
 * the forks on a carriage that rides up it — and where its tilt carries the lift, the whole mast leans
 * with the forks. A sideshift moves across the view, so it is only a figure. A dashed line through the tool is level, so the picture says what the number says.
 *
 * Schematic on purpose, and accepted as one (2026-10-03, after seeing it in game). The arm's real
 * geometry is not exported and differs on every machine, so its swing ([ARM_LOW_DEG]..[ARM_HIGH_DEG]),
 * the telescope's extra length and the tool shapes are chosen to read well, not measured; what a driver
 * reads off it is "up or down, tipped or not", and the figures beside it are the measurement.
 * Fitted to the machine's whole reach ([glyphFrame]) and clipped besides, so it never leaves its box.
 */
@Composable
private fun LoaderGlyph(rig: LoaderRig, modifier: Modifier = Modifier) {
  val lift = rig.cylinders.firstOrNull { it.role == LoaderCylinderRole.LIFT }?.travel ?: 0.3f
  val telescopic = rig.cylinders.any { it.role == LoaderCylinderRole.TELESCOPE }
  val telescope = rig.cylinders.firstOrNull { it.role == LoaderCylinderRole.TELESCOPE }?.travel ?: 0f
  val forklift = rig.forklift
  // A forklift whose tilt carries the lift leans the whole mast, by the forks' own angle.
  val mastLeans = forklift && rig.cylinders.any { it.role == LoaderCylinderRole.TILT && it.carriesLift }
  val reading = rig.reading
  val angle = rig.inclination ?: 0f
  val armInk = VdtColors.DarkGray
  val toolInk = VdtColors.TextDark
  val groundInk = VdtColors.PanelBorder
  val levelInk = VdtColors.Gray
  // A quantity, told by how high it stands and printed beside the picture: the fill bars' own role.
  val fillInk = VdtColors.ProgressBlue
  val kind = reading?.kind
  val load = rig.load?.let { it.fillLevelPercentage / 100f }

  Canvas(modifier.clipToBounds()) {
    val frame = glyphFrame(size.width, size.height, telescopic, forklift)
    val unit = frame.scale

    drawLine(groundInk, Offset(0f, frame.ground), Offset(size.width, frame.ground), strokeWidth = 2f)

    // The mast leans by the forks' raw pitch: a player's level is a zero for the readout, not a pose.
    val lean = if (mastLeans) reading?.pitch ?: 0f else 0f
    val tip = armTip(frame, lift, telescope, forklift, lean)
    if (forklift) {
      // The reach rail from the machine out to the mast, and the mast standing on it.
      val foot = mastFoot(frame, telescope)
      drawLine(armInk, frame.pivot, foot, strokeWidth = unit * 0.05f, cap = StrokeCap.Round)
      drawLine(armInk, foot, mastTop(frame, telescope, lean), strokeWidth = unit * 0.07f, cap = StrokeCap.Round)
    } else {
      drawLine(armInk, frame.pivot, tip, strokeWidth = unit * 0.07f, cap = StrokeCap.Round)
    }
    drawCircle(armInk, radius = unit * 0.06f, center = frame.pivot)

    if (reading == null) return@Canvas

    // Level through the tool's hinge, as far either side as the bucket can reach.
    drawLine(
      levelInk,
      Offset(tip.x - TOOL_REACH * unit, tip.y),
      Offset(tip.x + TOOL_REACH * 0.3f * unit, tip.y),
      strokeWidth = 1.5f,
      pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)),
    )

    if (kind == LoaderToolKind.SHOVEL && load != null) {
      shovelFill(frame, tip, angle, load)?.let { drawPath(pathOf(it, close = true), fillInk) }
    }
    toolStrokes(frame, tip, angle, kind, rig.toolOpen ?: DEFAULT_OPEN, rig.clamp?.travel).forEach { line ->
      drawPath(
        pathOf(line),
        toolInk,
        style = Stroke(width = unit * 0.06f, cap = StrokeCap.Round, join = StrokeJoin.Round),
      )
    }
  }
}

private fun pathOf(points: List<Offset>, close: Boolean = false) = Path().apply {
  moveTo(points[0].x, points[0].y)
  points.drop(1).forEach { lineTo(it.x, it.y) }
  if (close) close()
}
