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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.vertexdezign.vdt.ControlTarget
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.Implement
import net.vertexdezign.vdt.model.Schema
import net.vertexdezign.vdt.model.SchemaJoint
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
 * What the game multiplies the *vertical* lift offset by once it is normalized — and only the
 * vertical one. Not a calibration of ours like [BOX_W]: it is in
 * `InputHelpDisplay:collectVehicleSchemaDisplayOverlays` verbatim, so it moves only if the engine
 * changes.
 */
private const val LIFT_Y_FACTOR = 0.5f

/**
 * `InputHelpDisplay.MAX_SCHEMA_COLLECTION_DEPTH`. The walk starts at depth 1 on the root's own
 * implements and recurses while `depth <= 5`, so six levels below the root are drawn and the seventh
 * is dropped. Faithful to the game rather than to a round number: a rig this deep is already
 * pathological, and matching means our diagram and the game's stop in the same place.
 */
private const val MAX_DEPTH = 5

/** `VehicleSchemaOverlayData.new`'s own fallback for a silhouette that declares no invisible border. */
private const val DEFAULT_BORDER = 0.05f

/** `VehicleSchemaOverlayData:addAttacherJoint`'s own default lift, in pixels. */
private const val DEFAULT_LIFT_Y = 5f

/**
 * Where to hang a child whose parent named no attachment points — everything the export knows about
 * the join in that case is which end of the tractor it is on.
 *
 * `x = 1` puts it flush against the parent's edge, which is what every joint in every capture uses,
 * and `invertX` picks the end: mirrored is ahead, unmirrored behind. A nested machine reports no
 * position at all and goes behind, which is where a hitch chain runs.
 */
private fun fallbackJoint(position: String) = SchemaJoint(
  x = 1f,
  invertX = position == RigSlot.FRONT.implementPosition,
  liftedOffsetY = DEFAULT_LIFT_Y,
)

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
  /** 0 for the root machine, 1 for what is hitched to it, and so on. See [controlTargetOf]. */
  val depth: Int,
  /**
   * The share of this machine's slot that is padding, left and right.
   *
   * The game's silhouettes carry this space *inside the artwork*, which is why its diagram can butt
   * neighbours up against each other and still read as separate machines. We draw a plain box, so we
   * have to inset it by the same amount or a three-machine rig renders as one long bar. Defaulted to
   * the engine's own 0.05 when a machine names none.
   */
  val borderLeft: Float,
  val borderRight: Float,
  val x: Float,
  /** Where the box is actually drawn: [restY] plus whatever lift is applied along its path. */
  val y: Float,
  /**
   * Where this node sits with nothing on the rig raised, and the total lift it *could* take if
   * everything on its path were.
   *
   * These are what the diagram measures itself against, and they do not move when an implement is
   * raised — which is the whole point. Fitting the band to the drawn positions instead made the
   * tractor slide down and every box shrink the moment a plough came up, because the bounding box
   * grew and the whole diagram was re-centred and re-scaled inside it. In the game the root is nailed
   * to a fixed line and raised implements poke up above it; this is how that is reproduced.
   */
  val restY: Float,
  val liftHeadroom: Float,
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
 * Lays the rig out for drawing: the root machine and every implement on it, depth-first in hitch
 * order.
 *
 * A mirror of the game's `InputHelpDisplay:collectVehicleSchemaDisplayOverlays`, which is the
 * reference algorithm — the mod exports the raw `schema` and `jointDescIndex` and does no layout
 * arithmetic precisely so this can live here and change without a mod release.
 *
 * **Every machine on the rig gets a box; the schema only refines where it goes.** This is one place
 * the game is deliberately not copied. `schemaOverlay` is only assigned when a machine's XML declares
 * `vehicle.base.schemaOverlay`, and where it is missing the game gives up outright — no diagram at
 * all for the rig (`drawVehicleSchema` returns early), and a child with none is skipped along with
 * its subtree. It can afford that: without a silhouette it has nothing to draw. We draw one generic
 * box for every machine regardless, so there is nothing to give up, and a modded vehicle whose XML
 * omits the element would otherwise cost the whole diagram — including the machines around it that
 * did declare one.
 *
 * What a missing schema does cost is placement. A machine with no attacher joints of its own cannot
 * say where its children hang, so those fall back to [fallbackJoint] and are placed by their
 * `position` — ahead for `FRONT`, behind for anything else. That is the same fact `RigSlotPanel`
 * works from, and it is honest about being coarser than the engine's geometry.
 */
internal fun layoutRig(vehicle: Vehicle): List<RigNode> {
  val schema = vehicle.schema ?: Schema()
  val out = mutableListOf(
    RigNode(
      id = RIG_ROOT_ID,
      machine = vehicle.isoBus(),
      isRoot = true,
      depth = 0,
      borderLeft = schema.borderLeft ?: DEFAULT_BORDER,
      borderRight = schema.borderRight ?: DEFAULT_BORDER,
      x = 0f,
      y = 0f,
      restY = 0f,
      liftHeadroom = 0f,
      rotation = 0f,
      invertX = false,
    ),
  )
  layoutChildren(1, schema, vehicle.implement, 0f, 0f, 0f, 0f, 0f, false, RIG_ROOT_ID, out)
  return out
}

private fun layoutChildren(
  depth: Int,
  parentSchema: Schema,
  children: List<Implement>,
  x: Float,
  y: Float,
  restY: Float,
  liftHeadroom: Float,
  rotation: Float,
  invertingX: Boolean,
  parentId: String,
  out: MutableList<RigNode>,
) {
  for ((index, implement) in children.withIndex()) {
    val schema = implement.schema ?: Schema()
    val joint =
      if (parentSchema.attacherJoint.isEmpty()) {
        // The parent named no attachment points at all — it has no schema, or one whose attacher
        // joints declared no `schema` element. Place by position rather than dropping the machine.
        fallbackJoint(implement.position)
      } else {
        // Lua is 1-based, so the exported index is too. An implement whose joint the parent *does*
        // have a list for but is not in is dropped rather than guessed at: that is inconsistent data
        // rather than absent data, and the game drops it too (`jointDesc == nil` -> skip).
        implement.jointDescIndex?.let { parentSchema.attacherJoint.getOrNull(it - 1) } ?: continue
      }

    val invertX = invertingX != joint.invertX
    var baseY = y + joint.y * BOX_H
    // The same placement with no lift anywhere, tracked in parallel so the band can measure itself
    // against something that does not move when an implement comes up.
    var baseRestY = restY + joint.y * BOX_H
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
    val rotatedOffsetY = offsetX * sin(rot) + offsetY * cos(rot)
    baseY -= rotatedOffsetY
    baseRestY -= rotatedOffsetY

    // A raised implement is nudged clear of the machine towing it, which is how the diagram shows
    // raised from lowered at all.
    //
    // Only an explicit `false` earns the nudge. A machine with no lowered state at all — a trailer on
    // a ball hitch — sits level with whatever is towing it, and that is not a special case of ours:
    // the game asks `object:getIsLowered(TRUE)`, so an object with no opinion is handed `true` back
    // and reads as lowered. Our export says the same thing by leaving the key out, which is exactly
    // what mod version 18 changed. Treating null as raised put every trailer on the rig up in the air.
    //
    // The room is reserved whether or not the nudge is taken, so the band's extent is a fact about
    // the rig rather than about what the driver has raised. See [RigNode.restY].
    // The halved height is the engine's own: `collectVehicleSchemaDisplayOverlays` normalizes the
    // pixel offset and then adds `heightOffset * 0.5`, where it adds the width offset whole.
    val lift = joint.liftedOffsetY / LIFT_REF_H * LIFT_Y_FACTOR
    val baseHeadroom = liftHeadroom + lift
    if (implement.lowered == false) {
      baseX += joint.liftedOffsetX / LIFT_REF_W
      baseY += lift
    }

    val id = "$parentId/$index"
    out += RigNode(
      id = id,
      machine = implement.isoBus(),
      isRoot = false,
      depth = depth,
      borderLeft = schema.borderLeft ?: DEFAULT_BORDER,
      borderRight = schema.borderRight ?: DEFAULT_BORDER,
      x = baseX,
      y = baseY,
      restY = baseRestY,
      liftHeadroom = baseHeadroom,
      rotation = rot,
      invertX = invertX,
    )

    if (depth <= MAX_DEPTH) {
      layoutChildren(
        depth + 1,
        schema,
        implement.implement,
        baseX,
        baseY,
        baseRestY,
        baseHeadroom,
        rot,
        invertX,
        id,
        out,
      )
    }
  }
}

/**
 * Which [ControlTarget] addresses [node], or **null when nothing does**.
 *
 * `ControlTarget` reaches three places — the controlled vehicle, and the implements on its front and
 * rear attachers — because that is what FS25_additionalInputs' `vdAI*Front/Back` functions reach, and
 * the rule here is to use what vdAI already has rather than to extend it. So the diagram can show a
 * machine the command channel has no way to name: in `liquidManure_dribbleBar.json` the Bomech is
 * hitched behind the Kaweco, and only the Kaweco is the tractor's rear implement.
 *
 * Strictly structural, and deliberately stricter than `RigSlotPanel`'s recursive position search: a
 * `position` of `BACK` two levels down is the *dolly's* rear, not the tractor's, and commanding it as
 * though it were the tractor's would move the wrong machine.
 */
internal fun controlTargetOf(node: RigNode): ControlTarget? = when {
  node.isRoot -> ControlTarget.VEHICLE

  node.depth != 1 -> null

  // Reuses RigSlot's own position -> target table rather than keeping a second copy of the tokens.
  else -> RigSlot.entries.firstOrNull { it.implementPosition == node.machine.position }?.target
}

/**
 * The node the diagram should start on.
 *
 * The game's own selection first — in every capture we hold that is the machine actually being
 * worked, never the tractor, which is what makes the screen right before anyone touches it.
 *
 * Where the game says nothing (an older mod build, or a rig nobody has selected into yet), the first
 * machine with a type-aware section is a better guess than the tractor: it is the one with something
 * to show. That is the auto-pick this panel used before the diagram existed, kept rather than lost.
 */
internal fun selectedRigNode(nodes: List<RigNode>): RigNode? = nodes.firstOrNull { it.machine.selected }
  ?: nodes.firstOrNull { it.machine.hasSection }
  ?: nodes.firstOrNull()

// ---------------------------------------------------------------------------
// Drawing
// ---------------------------------------------------------------------------

/**
 * Below this the root's icon has no room to read and the box is drawn bare.
 *
 * Low, because a bare box is a perfectly good node: nothing on this diagram is *named*, so a box that
 * cannot hold its icon has lost a hint rather than its meaning.
 */
private val MIN_ICON_WIDTH = 24.dp

/** Room around the diagram, so a box on the edge is not flush against the panel. */
private val SCHEMA_PADDING = 4.dp

/**
 * A ring of the panel's own ground drawn just outside each box, so a machine that sits **on** another
 * still reads as two machines.
 *
 * The same idea as the invisible borders, applied where they cannot reach. Those inset a box inside
 * its own slot, which separates neighbours that merely touch; a mounted machine overlaps its parent's
 * slot outright. A front loader's attacher joint reports `x = 0.8` — the only non-1.0 joint in any
 * capture — so its slot starts at 80% of the tractor's and the two boxes cross by a tenth of a box
 * width even after both insets. The game gets away with drawing that as-is because its silhouettes
 * are distinct shapes with transparent ground; ours are identical rectangles, and two of those
 * crossing read as one dented box.
 *
 * Deliberately **not** a fix to the placement: the overlap is what the game reports and what a front
 * loader physically does. Only the drawing changes.
 */
private val HALO = 1.5.dp

/**
 * The rig, drawn: one box per machine, hitched the way the game's own HUD diagram hitches them, with
 * the selected one standing out and every box the game will let you select a tap target.
 *
 * **No names on it.** The game's own diagram carries none either — it is a shape and a position, read
 * at a glance while driving — and the panel header already names whatever is selected, with the type
 * and condition directly under it. Putting the names back would cost the band roughly three times the
 * height for something already on screen twice.
 *
 * Three states, and all three are carried by **brightness and border weight**, never by hue: the
 * selected box is a dark solid, a selectable one a light box with an outline, and one the game will
 * not select a flat fill with no outline at all. That is the same idiom the status chips below use to
 * separate a control from a readout, and it survives being read by someone who cannot separate two
 * colours. The root machine is marked with an [Icons.Filled.Agriculture] icon rather than a colour or
 * a second shape, matching what `RigSlotPanel` already uses for the vehicle slot.
 *
 * [onSelect] fires only for a machine the game can actually select. The unselectable ones are drawn
 * because the rig has them — the diagram is the shape of what you are towing — but they are not
 * offered as targets, because `setSelectedVehicle` answers a machine it cannot select by selecting a
 * different one, and a tap that moves the selection somewhere else is worse than a tap that does
 * nothing. See [net.vertexdezign.vdt.model.Selection.selectable].
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
      // Vertically the band is measured against the rig at rest plus the room a raise would need, so
      // neither the scale nor the origin moves when one is raised — the root stays put and only the
      // implement travels, which is what the game's own diagram does. Horizontally it still fits the
      // drawn extent, as the game does too (`getSchemaDelimiters` takes minX/maxX only).
      minY = min(minY, node.restY)
      maxY = max(maxY, node.restY + node.liftHeadroom + BOX_H)
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
        // The slot is what the layout placed; the box drawn in it is inset by the machine's own
        // invisible borders, so neighbours butted up against each other still read as two machines.
        val insetLeft = scale * BOX_W * node.borderLeft
        val insetRight = scale * BOX_W * node.borderRight
        RigBox(
          node = node,
          selected = node.id == selectedId,
          width = boxW - insetLeft - insetRight,
          height = boxH,
          onSelect = onSelect,
          // Schema y points up and Compose's points down, so the box's *lower* edge measured from
          // the bottom becomes its *upper* edge measured from the top.
          modifier = Modifier.offset(
            x = originX + scale * (node.x - minX) + insetLeft,
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
  val isSelected = selected
  // A box the game will not select is still drawn — it is part of the rig — but it is not a target.
  // The already-selected box stays one: re-selecting it changes nothing (the mod sends the engine no
  // control group in that case, so it does not even disturb which tools are live), and a box that
  // stopped taking taps the moment it was selected would read as broken rather than as done.
  val canTap = node.machine.selectable
  Box(
    modifier
      // The halo grows outward and the offset takes it back, so the box still lands exactly where the
      // layout put it — the separation is drawn, never placed.
      .offset(x = -HALO, y = -HALO)
      .width(width + HALO * 2)
      .height(height + HALO * 2)
      .background(VdtColors.Panel, RoundedCornerShape(4.5.dp))
      .padding(HALO)
      .width(width)
      .height(height)
      // About the box's own centre. No capture has produced a non-zero rotation yet — every joint in
      // every fixture reads 0 — so this is the same faithful-but-unverified arm as the offsets above.
      .rotate(node.rotation * 180f / kotlin.math.PI.toFloat())
      .clip(shape)
      .background(
        when {
          selected -> VdtColors.TextDark

          canTap -> VdtColors.White

          // Flat and outline-less, the way a read-only chip is flat where an actionable one is raised
          // and outlined. Nothing here is said by hue.
          else -> VdtColors.TrackGray
        },
      )
      .border(
        width = if (selected) {
          2.dp
        } else if (canTap) {
          1.dp
        } else {
          0.dp
        },
        color = if (selected) VdtColors.TextDark else VdtColors.PanelBorder,
        shape = shape,
      )
      .then(if (canTap) Modifier.clickable(role = Role.Button) { onSelect(node.id) } else Modifier)
      // The box has no text of its own -- the machine's name lives under the diagram -- so the only
      // way a screen reader can tell one tap target from the next is here. `selected` is read into a
      // local first: inside the semantics scope the bare name would resolve to the property being
      // written.
      .semantics {
        contentDescription = node.machine.name
        this.selected = isSelected
        if (!canTap) disabled()
      }
      .padding(horizontal = 2.dp),
    contentAlignment = Alignment.Center,
  ) {
    if (node.isRoot && width >= MIN_ICON_WIDTH) {
      Icon(
        Icons.Filled.Agriculture,
        contentDescription = null,
        tint = when {
          selected -> VdtColors.White
          canTap -> VdtColors.DarkGray
          else -> VdtColors.TextDisabled
        },
        modifier = Modifier.size((height * 0.6f).coerceAtMost(14.dp)),
      )
    }
  }
}
