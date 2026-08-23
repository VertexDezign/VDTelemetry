package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.Implement
import net.vertexdezign.vdt.model.Schema
import net.vertexdezign.vdt.model.Vehicle
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ---------------------------------------------------------------------------
// Units
// ---------------------------------------------------------------------------

/**
 * One machine's box, in the same **normalized-screen** units the game lays its rig diagram out in
 * (`x / screenWidth`, `y / screenHeight`). Keeping the game's unit system is what lets
 * [layoutRig] be a line-for-line mirror of `InputHelpDisplay:collectVehicleSchemaDisplayOverlays`
 * with nothing but the overlay size substituted.
 *
 * The game reads each silhouette's size from `dataS/vehicleSchemaOverlays.xml`, which we do not have
 * and do not export. **We draw one generic box instead** (issue #116): silhouette identity is
 * decoration here — the machine's name and type are in the detail below the diagram — and the
 * diagram's job is to show the chain, the selection and a tap target.
 *
 * These two numbers are therefore a *calibration*, not a measurement, and they matter in exactly one
 * place. The joint offsets are fractions of the parent's box, so they are scale-invariant and do not
 * care what these are; the **lifted** offsets ([LIFT_REF_W] / [LIFT_REF_H]) are absolute, so the box
 * size is what decides how large the raised-implement nudge reads. Chosen to resemble the game's own
 * overlays, which are roughly this size on screen.
 */
private const val BOX_W = 0.05f
private const val BOX_H = 0.03f

/**
 * `liftedOffsetX` / `liftedOffsetY` are **pixels**, which the game turns into normalized screen
 * values before use — hence these two reference dimensions. It is why a capture reads
 * `liftedOffsetY: 5` where every other number in the schema is a fraction: 5 is the engine's default,
 * in pixels.
 */
private const val LIFT_REF_W = 1920f
private const val LIFT_REF_H = 1080f

/**
 * `InputHelpDisplay.MAX_SCHEMA_COLLECTION_DEPTH`. The walk starts at depth 1 on the root's own
 * implements and recurses while `depth <= 5`, so six levels below the root are drawn and the seventh
 * is dropped. Faithful to the game rather than to a round number: a rig this deep is already
 * pathological, and matching means our diagram and the game's stop in the same place.
 */
private const val MAX_DEPTH = 5

/** The root machine's id. Children extend their parent's, so an id encodes the path to the node. */
internal const val RIG_ROOT_ID = "0"

// ---------------------------------------------------------------------------
// Layout
// ---------------------------------------------------------------------------

/**
 * One machine, placed. [x] / [y] are the box's lower-left corner in the units described above, with
 * **y pointing up** — the game's axis, not Compose's. [RigSchema] flips it when it draws.
 *
 * [id] is a path (`0`, `0/1`, `0/1/0`) rather than an index into the flattened list: it survives a
 * machine further up the rig gaining or losing a child, which an index does not, so a selection
 * pinned to a node stays pinned to *that* node.
 */
internal data class RigNode(
  val id: String,
  val machine: IsoBusMachine,
  val isRoot: Boolean,
  val x: Float,
  val y: Float,
  /** Accumulated down the tree, in radians. */
  val rotation: Float,
  /**
   * Whether this node's frame is mirrored, composed as an **XOR** down the tree
   * (`invertX = invertingX != joint.invertX`) — a child hitched to a mirrored parent by a mirrored
   * joint comes back the right way round. It decides which edge of the parent the child butts
   * against, and the sign of the child's own `offsetX`.
   */
  val invertX: Boolean,
)

/**
 * Lays the rig out for drawing: the root machine plus every implement that carries a [Schema],
 * depth-first in hitch order.
 *
 * A mirror of the game's `InputHelpDisplay:collectVehicleSchemaDisplayOverlays`, which is the
 * reference algorithm — the mod exports the raw `schema` and `jointDescIndex` and does no layout
 * arithmetic precisely so this can live here and change without a mod release.
 *
 * Nodes without a schema are **skipped along with their whole subtree**, as the game does: an object
 * with no silhouette has no box for its children to hang off, so there is nowhere to put them.
 * Returns empty when the root itself has none, which is every capture taken before mod version 4.
 */
internal fun layoutRig(vehicle: Vehicle): List<RigNode> {
  val schema = vehicle.schema ?: return emptyList()
  val out = mutableListOf(
    RigNode(
      id = RIG_ROOT_ID,
      machine = vehicle.isoBus(),
      isRoot = true,
      x = 0f,
      y = 0f,
      rotation = 0f,
      invertX = false,
    ),
  )
  layoutChildren(1, schema, vehicle.implement, 0f, 0f, 0f, false, RIG_ROOT_ID, out)
  return out
}

private fun layoutChildren(
  depth: Int,
  parentSchema: Schema,
  children: List<Implement>,
  x: Float,
  y: Float,
  rotation: Float,
  invertingX: Boolean,
  parentId: String,
  out: MutableList<RigNode>,
) {
  for ((index, implement) in children.withIndex()) {
    val schema = implement.schema ?: continue
    // Lua is 1-based, so the exported index is too. An implement whose joint the parent does not
    // have is dropped rather than guessed at — the game does the same (`jointDesc == nil` -> skip).
    val joint = implement.jointDescIndex?.let { parentSchema.attacherJoint.getOrNull(it - 1) } ?: continue

    val invertX = invertingX != joint.invertX
    var baseY = y + joint.y * BOX_H
    // Mirrored, the child hangs off the parent's *near* edge and grows away from it; unmirrored it
    // hangs off the far edge, so its own width has to come back out of the sum. With every box the
    // same width this collapses to ±(joint.x * BOX_W), but it is written the game's way so that
    // giving silhouettes their own sizes later is a change to BOX_W alone.
    var baseX =
      if (invertX) {
        x + joint.x * BOX_W
      } else {
        x - BOX_W + (1 - joint.x) * BOX_W
      }

    val rot = rotation + joint.rotation
    // The machine's own offset is expressed in its local frame, so it is rotated by the accumulated
    // angle before it can be taken off a position in the parent's. Every capture so far has both
    // offsets at 0 and every rotation at 0, so this arm is faithful-but-unverified: it is here
    // because leaving it out would silently misplace the first machine that does use it.
    val offsetX = if (invertX) -schema.offsetX * BOX_W else schema.offsetX * BOX_W
    val offsetY = schema.offsetY * BOX_H
    baseX -= offsetX * cos(rot) - offsetY * sin(rot)
    baseY -= offsetX * sin(rot) + offsetY * cos(rot)

    // A raised implement is nudged clear of the machine towing it, which is how the diagram shows
    // raised vs lowered at all. `getIsLowered` is false for anything that cannot be lowered, so a
    // trailer takes the nudge too — matching the game, which likewise does not special-case it.
    if (implement.lowered != true) {
      baseX += joint.liftedOffsetX / LIFT_REF_W
      baseY += joint.liftedOffsetY / LIFT_REF_H * 0.5f
    }

    val id = "$parentId/$index"
    out += RigNode(id, implement.isoBus(), isRoot = false, x = baseX, y = baseY, rotation = rot, invertX = invertX)

    if (depth <= MAX_DEPTH) {
      layoutChildren(depth + 1, schema, implement.implement, baseX, baseY, rot, invertX, id, out)
    }
  }
}

/** The node the diagram should start on: whatever the *game* has selected, else the root. */
internal fun selectedRigNode(nodes: List<RigNode>): RigNode? =
  nodes.firstOrNull { it.machine.selected } ?: nodes.firstOrNull()

// ---------------------------------------------------------------------------
// Drawing
// ---------------------------------------------------------------------------

/** Below this a box is too small to hold a legible name, so it draws as a bare box. */
private val MIN_LABEL_WIDTH = 44.dp

/** Below this the root's icon crowds the name off its box, so the name wins. */
private val MIN_ICON_WIDTH = 60.dp

/** Room around the diagram, so a box on the edge is not flush against the panel. */
private val SCHEMA_PADDING = 4.dp

/**
 * The rig, drawn: one box per machine, hitched the way the game's own HUD diagram hitches them, with
 * the selected one standing out and every box a tap target.
 *
 * Selection is carried by **brightness and border weight**, never by hue alone — a selected box is
 * dark with light text where the others are light with dark text, which survives being read by
 * someone who cannot separate the two colours. The root machine is marked with an [Icons.Filled.Agriculture]
 * icon rather than a colour or a second shape, matching what `RigSlotPanel` already uses for the
 * vehicle slot.
 */
@Composable
internal fun RigSchema(
  nodes: List<RigNode>,
  selectedId: String?,
  modifier: Modifier = Modifier,
  onSelect: (String) -> Unit = {},
) {
  if (nodes.isEmpty()) return

  BoxWithConstraints(modifier) {
    // The extent of the laid-out rig, in schema units. Every box is the same size, so the bounds are
    // the outermost origins plus one box.
    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (node in nodes) {
      minX = min(minX, node.x)
      maxX = max(maxX, node.x + BOX_W)
      minY = min(minY, node.y)
      maxY = max(maxY, node.y + BOX_H)
    }

    val availW = maxWidth - SCHEMA_PADDING * 2
    val availH = maxHeight - SCHEMA_PADDING * 2
    // Uniform, so the rig keeps its proportions however the tile is shaped, and letterboxed into
    // whichever axis has room to spare.
    val scale = (availW / (maxX - minX)).coerceAtMost(availH / (maxY - minY))
    val drawnW = scale * (maxX - minX)
    val drawnH = scale * (maxY - minY)
    val originX = SCHEMA_PADDING + (availW - drawnW) / 2
    val originY = SCHEMA_PADDING + (availH - drawnH) / 2

    val boxW = scale * BOX_W
    val boxH = scale * BOX_H

    Box(Modifier.fillMaxSize()) {
      for (node in nodes) {
        RigBox(
          node = node,
          selected = node.id == selectedId,
          width = boxW,
          height = boxH,
          onSelect = onSelect,
          // Schema y points up and Compose's points down, so the box's *lower* edge measured from
          // the bottom becomes its *upper* edge measured from the top.
          modifier = Modifier.offset(
            x = originX + scale * (node.x - minX),
            y = originY + scale * (maxY - node.y - BOX_H),
          ),
        )
      }
    }
  }
}

@Composable
private fun RigBox(
  node: RigNode,
  selected: Boolean,
  width: Dp,
  height: Dp,
  onSelect: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val shape = RoundedCornerShape(3.dp)
  Box(
    modifier
      .width(width)
      .height(height)
      // About the box's own centre. No capture has produced a non-zero rotation yet — every joint in
      // every fixture reads 0 — so this is the same faithful-but-unverified arm as the offsets above.
      .rotate(node.rotation * 180f / kotlin.math.PI.toFloat())
      .clip(shape)
      .background(if (selected) VdtColors.TextDark else VdtColors.White)
      .border(
        width = if (selected) 2.dp else 1.dp,
        color = if (selected) VdtColors.TextDark else VdtColors.PanelBorder,
        shape = shape,
      )
      .clickable { onSelect(node.id) }
      .padding(horizontal = 2.dp),
    contentAlignment = Alignment.Center,
  ) {
    if (node.isRoot && width >= MIN_ICON_WIDTH) {
      Icon(
        Icons.Filled.Agriculture,
        contentDescription = null,
        tint = if (selected) VdtColors.White else VdtColors.DarkGray,
        modifier = Modifier.align(Alignment.CenterStart).size((height * 0.5f).coerceAtMost(14.dp)),
      )
    }
    if (width >= MIN_LABEL_WIDTH) {
      Text(
        node.machine.name,
        fontSize = 8.sp,
        lineHeight = 10.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) VdtColors.White else VdtColors.TextDark,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
      )
    }
  }
}
