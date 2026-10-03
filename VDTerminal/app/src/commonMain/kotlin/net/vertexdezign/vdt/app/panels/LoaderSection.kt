package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
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
import net.vertexdezign.vdt.model.LoaderCylinder
import net.vertexdezign.vdt.model.LoaderCylinderRole
import net.vertexdezign.vdt.model.LoaderTool
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
}

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

/** A cylinder's name on the screen: what it does, and for a tool's own, which one it is. */
internal fun cylinderLabel(cylinder: LoaderCylinder): String = when (cylinder.role) {
  LoaderCylinderRole.LIFT -> "Lift"
  LoaderCylinderRole.TELESCOPE -> "Telescope"
  LoaderCylinderRole.TILT -> "Tilt"
  LoaderCylinderRole.AUX -> "Tool " + cylinder.axis.removePrefix("AXIS_FRONTLOADER_TOOL").ifEmpty { "1" }
}

/** Which way off level, as a glyph: hue never carries it alone, and a word goes beside it. */
private fun inclinationMark(degrees: Float): ImageVector = when {
  abs(degrees) < LEVEL_TOLERANCE_DEG -> Icons.Filled.Check
  degrees > 0 -> Icons.Filled.ArrowUpward
  else -> Icons.Filled.ArrowDownward
}

/**
 * The header's one line: how far off level the tool is, the glance a driver makes between buckets.
 * Nothing until the player has set a level — the body says why, and the header has no room to.
 */
@Composable
internal fun RowScope.LoaderStateChip(rig: LoaderRig) {
  val inclination = rig.reading?.inclination ?: return
  Chip(inclinationMark(inclination), inclinationLabel(inclination), VdtColors.TextDark)
}

/**
 * The loader screen: a side view of the arm and the tool, how far off level the tool is and how high,
 * each cylinder's travel, and "set level".
 *
 * **No reference, no level.** The angle the mod reports is the tool's root node, and nothing makes
 * that node parallel to a shovel floor — so until the player has said where level is for this tool,
 * the angle is shown in disabled ink and labelled as unlevelled. A raw 0° passing for level is the one
 * thing this screen must never show.
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

@Composable
private fun LoaderReadouts(rig: LoaderRig, modifier: Modifier = Modifier) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
    rig.reading?.let { reading ->
      AngleReadout(reading)
      val metres = reading.height ?: reading.distance
      Figure(
        "Height",
        metres?.let(::heightLabel) ?: "-",
        when {
          metres == null -> "nothing below in reach"
          reading.height != null -> "above level"
          else -> "above what is below"
        },
      )
    }
    if (rig.cylinders.isNotEmpty()) {
      @OptIn(ExperimentalLayoutApi::class)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rig.cylinders.forEach { Figure(cylinderLabel(it), "${(it.travel * 100).roundToInt()}%", null, compact = true) }
      }
    }
  }
}

/**
 * The angle, with its glyph and its word. Unlevelled, it is the raw root-node angle in disabled ink
 * under a label that says so — the colour is the lesser half; the words are what carry it.
 */
@Composable
private fun AngleReadout(reading: LoaderTool) {
  val inclination = reading.inclination
  Column {
    Text("ANGLE", color = VdtColors.DarkGray, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    if (inclination != null) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
          inclinationMark(inclination),
          contentDescription = null,
          tint = VdtColors.TextDark,
          modifier = Modifier.size(18.dp),
        )
        Text(
          inclinationLabel(inclination),
          color = VdtColors.TextDark,
          fontSize = 20.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
        )
      }
      Text(
        when {
          abs(inclination) < LEVEL_TOLERANCE_DEG -> "on your level"
          inclination > 0 -> "nose up"
          else -> "nose down"
        },
        color = VdtColors.DarkGray,
        fontSize = 10.sp,
        maxLines = 1,
      )
    } else {
      Text(
        inclinationLabel(reading.pitch).let { if (it == "LEVEL") "0.0°" else it },
        color = VdtColors.TextDisabled,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
      )
      Text("no level set for this tool", color = VdtColors.DarkGray, fontSize = 10.sp, maxLines = 1)
    }
  }
}

/**
 * "Set level" — the tool's pose right now becomes this tool model's zero, for every copy of it, on
 * every save and every screen — and, once there is one, "Clear level".
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
 * The loader from the side, **facing left** (design rule): the arm from its pivot on the right, raised
 * by its lift travel and lengthened by its telescope, and the tool at its tip turned to the angle the
 * screen prints — off the player's level once there is one, else the raw angle, drawn in disabled ink
 * like the figure. A dashed line through the tool is level, so the picture says what the number says.
 *
 * Schematic on purpose. The arm's real geometry is not exported and differs on every machine; what a
 * driver reads off it is "up or down, tipped or not", and the figures beside it are the measurement.
 */
@Composable
private fun LoaderGlyph(rig: LoaderRig, modifier: Modifier = Modifier) {
  val lift = rig.cylinders.firstOrNull { it.role == LoaderCylinderRole.LIFT }?.travel ?: 0.3f
  val telescope = rig.cylinders.firstOrNull { it.role == LoaderCylinderRole.TELESCOPE }?.travel ?: 0f
  val reading = rig.reading
  val angle = reading?.inclination ?: reading?.pitch ?: 0f
  val levelled = reading?.inclination != null
  val armInk = VdtColors.DarkGray
  val toolInk = if (levelled || reading == null) VdtColors.TextDark else VdtColors.TextDisabled
  val groundInk = VdtColors.PanelBorder
  val levelInk = VdtColors.Gray

  Canvas(modifier) {
    val w = size.width
    val h = size.height
    val unit = minOf(w, h * 1.6f)
    val ground = h * 0.92f
    val pivot = Offset(w * 0.82f, h * 0.55f)

    // Ground.
    drawLine(groundInk, Offset(0f, ground), Offset(w, ground), strokeWidth = 2f)

    // The arm, from the pivot to the left.
    val armDeg = ARM_LOW_DEG + (ARM_HIGH_DEG - ARM_LOW_DEG) * lift.coerceIn(0f, 1f)
    val armRad = armDeg * PI.toFloat() / 180f
    val armLength = unit * 0.5f * (1f + 0.45f * telescope.coerceIn(0f, 1f))
    val tip = Offset(pivot.x - armLength * cos(armRad), pivot.y - armLength * sin(armRad))
    drawLine(armInk, pivot, tip, strokeWidth = unit * 0.035f, cap = StrokeCap.Round)
    drawCircle(armInk, radius = unit * 0.03f, center = pivot)

    if (reading == null) return@Canvas

    // Level through the tool's hinge.
    drawLine(
      levelInk,
      Offset(tip.x - unit * 0.32f, tip.y),
      Offset(tip.x + unit * 0.08f, tip.y),
      strokeWidth = 1.5f,
      pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)),
    )

    // The tool: a bucket in profile, its cutting edge to the left, turned nose-up for a positive angle.
    val s = unit * 0.22f
    val rad = angle.coerceIn(-90f, 90f) * PI.toFloat() / 180f

    // Screen y grows downward, so with the edge at negative x this turns it UP for a positive angle.
    fun at(x: Float, y: Float) = Offset(tip.x + x * cos(rad) - y * sin(rad), tip.y + x * sin(rad) + y * cos(rad))
    val bucket = Path().apply {
      val edge = at(-s, 0f)
      moveTo(edge.x, edge.y)
      at(0f, 0f).let { lineTo(it.x, it.y) }
      at(0f, -s * 0.75f).let { lineTo(it.x, it.y) }
      at(-s * 0.35f, -s * 0.85f).let { lineTo(it.x, it.y) }
    }
    drawPath(bucket, toolInk, style = Stroke(width = unit * 0.03f, cap = StrokeCap.Round, join = StrokeJoin.Round))
  }
}
