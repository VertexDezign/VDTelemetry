package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.ControlTarget
import net.vertexdezign.vdt.app.components.FillUnitsDisplay
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.resources.Res
import net.vertexdezign.vdt.app.resources.isobus_mixer_wagon
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.ControlGroup
import net.vertexdezign.vdt.model.Cover
import net.vertexdezign.vdt.model.CoverType
import net.vertexdezign.vdt.model.Cutter
import net.vertexdezign.vdt.model.Discharge
import net.vertexdezign.vdt.model.DischargeReason
import net.vertexdezign.vdt.model.DischargeState
import net.vertexdezign.vdt.model.FillUnit
import net.vertexdezign.vdt.model.FoldableState
import net.vertexdezign.vdt.model.Harvest
import net.vertexdezign.vdt.model.Implement
import net.vertexdezign.vdt.model.Mass
import net.vertexdezign.vdt.model.MixState
import net.vertexdezign.vdt.model.Mixer
import net.vertexdezign.vdt.model.MixerIngredient
import net.vertexdezign.vdt.model.Pipe
import net.vertexdezign.vdt.model.PipeState
import net.vertexdezign.vdt.model.TipState
import net.vertexdezign.vdt.model.Tipping
import net.vertexdezign.vdt.model.Vehicle
import net.vertexdezign.vdt.model.WorkArea
import org.jetbrains.compose.resources.painterResource
import kotlin.math.roundToInt

/** Aspect ratio (w/h) of `isobus_mixer_wagon.png` — 1200×600, so exactly 2:1. */
private const val ART_ASPECT = 2f

/**
 * The text block on the machine's red body, as fractions of the art's box.
 *
 * Measured off the asset rather than guessed: the tub is a trapezoid, and this is the largest
 * rectangle that stays on red from its top edge down to where the augers' housing cuts in. Anything
 * wider spills onto the frame at the bottom of the range.
 */
private const val TUB_LEFT = 0.53f
private const val TUB_RIGHT = 0.80f
private const val TUB_TOP = 0.12f
private const val TUB_BOTTOM = 0.54f

/**
 * Smaller than this the art says nothing a picture is worth saying, so the panel drops it and gives
 * the room to the bars. The picture is orientation; the bars are the reason to look.
 *
 * Set by the *text*, not by the machine: the tub's block is about a quarter of the art's width, so
 * below this the figures written on the body stop being legible — and they read fine in the plain
 * row that replaces them.
 */
private val MIN_ART_HEIGHT = 96.dp

/** Wide and low enough that the art belongs beside the bars rather than above them. */
private val SIDE_BY_SIDE_FROM = 520.dp

/** Line box of the tub's figures, as a multiple of their type size. */
private const val TUB_LINE = 1.15f

/** The tub's sub-lines, relative to the headline they sit under. */
private const val SUB_SCALE = 0.62f

/** The gap between the two figures on the tub, as a multiple of the headline's size. */
private const val TUB_GAP = 0.35f

/** Roughly how many headline glyph-widths the tub's block has to hold: "225,000 l" and some slack. */
private const val HEADLINE_EMS = 5.2f

/** Below this the four-line block would be too small to read, so it drops to the two figures alone. */
private const val TUB_FULL_FROM = 12f

/** Past this the panel header can no longer hold the machine's name *and* the state; see [IsoBusPanel]. */
private val BARE_HEADER_BELOW = 260.dp

/** What one ingredient's label, bar and (occasional) shortfall line come to. Used to budget the art. */
private val BAR_ROW_HEIGHT = 36.dp

/** One line of the status strip plus the gap under it; it wraps to a second when the chips need one. */
private val STATUS_STRIP_HEIGHT = 23.dp

/**
 * Below this the body is only just enough for the machine itself, so the rig diagram is dropped
 * rather than shrunk — it is orientation, and the machine is the reason to look.
 */
private val MIN_BODY_FOR_SCHEMA = 96.dp

/**
 * The band the rig diagram gets. Small, and deliberately: the game's own is a thin strip of
 * silhouettes at the bottom of the screen, and ours carries no text either, so height buys it nothing
 * past the point where a box is a legible shape and a reachable tap target.
 */
private val MIN_SCHEMA_HEIGHT = 22.dp
private val MAX_SCHEMA_HEIGHT = 40.dp

/**
 * How long a tap on the diagram is drawn before the game has confirmed it.
 *
 * The round trip is a WebSocket hop, a file write, the mod's command poll (half the export interval)
 * and the next export — call it two intervals at the 100 ms default, and more on a slower profile.
 * Generous enough that the ordinary case never expires, short enough that a command the mod dropped
 * stops being shown as though it had worked. Expiring costs nothing when it is wrong: the diagram
 * simply falls back to whatever the game says, which is what it would have shown anyway.
 */
private const val SELECT_ECHO_MS = 1500L

/**
 * Width from which the type and the condition move onto the diagram's band, one at each end.
 *
 * Set by what the three have to fit side by side: the diagram's own drawn width is about 160dp for a
 * three-machine rig at the tallest band, and it is given the middle half, so the flanks get a quarter
 * each — enough for a twelve-character type name and a `96%` chip at 10sp. Below this the flanks
 * would start clipping the type, and the strip below has room to spare.
 */
private val IDENTITY_ON_BAND_FROM = 340.dp

/**
 * One machine on the rig, flattened out of [Vehicle] or [Implement] — the two speak the same shape,
 * and a mixer wagon can be either (a self-propelled one is the vehicle).
 */
internal data class IsoBusMachine(
  val name: String,
  /**
   * `FRONT` / `BACK` for an implement, empty for the vehicle. Part of how a node is *addressed*
   * rather than part of what it is — see [controlTargetOf].
   */
  val position: String,
  /** The game's vehicle type name. A **label hint only** — never the thing a section dispatches on. */
  val type: String,
  /**
   * Whether the *game* has this machine selected — the engine's own per-object flag, mirrored onto
   * every node in the rig, so exactly one is normally true.
   */
  val selected: Boolean,
  /**
   * Whether the game would let the player select it — [net.vertexdezign.vdt.model.Selection.selectable],
   * defaulted to `false` where the mod could not say, because unknown must not read as permission:
   * `setSelectedVehicle` answers an ineligible machine by selecting a *different* one.
   *
   * Almost everything hitched to a tractor passes — `Attachable` alone overrides the engine's test to
   * `true` — so what this usually marks is the **tractor**, and on a default save it always does: the
   * engine allows selecting it only while automatic motor start is off. The game's own cycling key
   * skips it for exactly the same reason, so a greyed tractor here is the diagram agreeing with the
   * game rather than withholding something.
   */
  val selectable: Boolean,
  /** The moving-tool group being cycled, on a machine that splits its controls into named groups. */
  val controlGroup: ControlGroup?,
  val foldable: FoldableState?,
  val lowered: Boolean?,
  val isTurnedOn: Boolean?,
  val damage: Int?,
  /**
   * This machine's **own** fill units, not its chain's.
   *
   * `RigSlotPanel` merges a chain because a slot *is* a chain — one position with everything behind
   * it. Here every machine is its own node on the diagram with its own tap target, so rolling a
   * child's tank into its parent's would double-count it the moment the child is selected too.
   */
  val fillUnits: List<FillUnit>,
  val tipping: Tipping?,
  val discharge: Discharge?,
  val pipe: Pipe?,
  val cover: Cover?,
  val mass: Mass?,
  val mixer: Mixer?,
  /** The combine, on the machine that threshes. Never on the same node as [cutter]. */
  val harvest: Harvest?,
  /** The header, on the machine at the front. Never on the same node as [harvest]. */
  val cutter: Cutter?,
  /**
   * This machine's own work areas. Carried for one thing only: a header's `CUTTER` area is where its
   * working width comes from, and no other aspect reports it.
   */
  val workAreas: List<WorkArea>,
) {
  /**
   * Whether this machine has anything the panel knows how to draw. The dispatch list, and the one
   * place it grows when a section is added.
   *
   * Both halves of a harvesting rig are on it. They draw the *same* section — [CombineSection] takes
   * the combine and its header together, because a header on its own says nothing a driver wants and
   * the game frequently has it selected rather than the machine pulling it.
   */
  val hasSection: Boolean get() = mixer != null || harvest != null || cutter != null
}

internal fun Vehicle.isoBus() = IsoBusMachine(
  name = name,
  // The vehicle is not attached to anything; it is what the attaching is done to.
  position = "",
  type = type,
  selected = selection?.selected == true,
  selectable = selection?.selectable == true,
  controlGroup = selection?.controlGroup,
  foldable = foldable,
  lowered = lowered,
  isTurnedOn = isTurnedOn,
  damage = wearable?.damage,
  fillUnits = fillUnits?.fillUnit ?: emptyList(),
  tipping = tipping,
  discharge = discharge,
  pipe = pipe,
  cover = cover,
  mass = mass,
  mixer = mixer,
  harvest = harvest,
  cutter = cutter,
  workAreas = workAreas,
)

internal fun Implement.isoBus() = IsoBusMachine(
  name = name,
  position = position,
  type = type,
  selected = selection?.selected == true,
  selectable = selection?.selectable == true,
  controlGroup = selection?.controlGroup,
  foldable = foldable,
  lowered = lowered,
  isTurnedOn = isTurnedOn,
  damage = wearable?.damage,
  fillUnits = fillUnits?.fillUnit ?: emptyList(),
  tipping = tipping,
  discharge = discharge,
  pipe = pipe,
  cover = cover,
  mass = mass,
  mixer = mixer,
  harvest = harvest,
  cutter = cutter,
  workAreas = workAreas,
)

/** Every machine on the rig, the vehicle first, then its implements depth-first in hitch order. */
internal fun rigMachines(vehicle: Vehicle): List<IsoBusMachine> {
  val out = mutableListOf(vehicle.isoBus())
  fun walk(list: List<Implement>) {
    for (imp in list) {
      out += imp.isoBus()
      walk(imp.implement)
    }
  }
  walk(vehicle.implement)
  return out
}

/**
 * The machine this panel is showing.
 *
 * [slot] null means *auto*: the first machine on the rig with a section to draw, which is how a real
 * terminal behaves — it shows whatever announced itself on the bus, not a position you chose. A named
 * slot pins the tile to that position instead, which is what lets two tiles show two machines of a
 * combination rig at once.
 */
internal fun isoBusMachine(vehicle: Vehicle?, slot: RigSlot?): IsoBusMachine? {
  if (vehicle == null) return null
  if (slot == null) return rigMachines(vehicle).firstOrNull { it.hasSection }
  val position = slot.implementPosition ?: return vehicle.isoBus()
  return findIsoBusImplement(vehicle.implement, position)?.isoBus()
}

private fun findIsoBusImplement(list: List<Implement>, position: String): Implement? {
  for (imp in list) {
    if (imp.position == position) return imp
    findIsoBusImplement(imp.implement, position)?.let { return it }
  }
  return null
}

/**
 * The ISOBUS panel: the terminal the tractor lends to whatever is hitched behind it. Renders [slot] of
 * [vehicle] — or, with a null [slot], whatever on the rig has something to say.
 *
 * Unlike [RigSlotPanel], which is deliberately type-agnostic and shows the same six things about any
 * machine, this is the type-*aware* complement — and it dispatches on **which aspect subtree the
 * machine carries**, never on its `type` string. `type` is modder-defined and open-ended, and more
 * importantly a machine is not one class: a fertilizing seeder has `sowing` *and* `spraying`. Aspect
 * presence composes; a switch on `type` would have to pick one and drop the other. The dispatch list
 * is [IsoBusMachine.hasSection], and the mixer wagon (issue #113) is the first entry on it.
 *
 * Measures itself and thins out rather than taking a "compact" flag, like every other panel here:
 * what fits is a fact about the size the tile ended up at.
 */
@Composable
fun IsoBusPanel(
  vehicle: Vehicle?,
  slot: RigSlot?,
  modifier: Modifier = Modifier,
  onCommand: (ClientMessage) -> Unit = {},
) {
  // The diagram is the picker, and only where there is a choice to make: a tile pinned to a position
  // has already been told which machine it follows, so it keeps the direct lookup. Empty on any rig
  // whose objects carry no `schema` — every capture from before mod version 4 — and the panel then
  // falls back to the auto-pick it used before the diagram existed.
  val nodes = if (slot == null && vehicle != null) layoutRig(vehicle) else emptyList()

  // The diagram shows the *game's* selection, and a tap MOVES it (issue #119) rather than pointing
  // this tile somewhere else. That is what makes this behave like the terminal in the cab rather than
  // like a menu: there is one selection, the keyboard and the screen agree about it, and what you
  // last touched is what both are acting on.
  //
  // What is left of the tile's old local pin is an optimistic echo. The tap is drawn immediately so
  // the screen answers the finger, and the game's own next tick confirms it a beat later. If it never
  // does — the mod dropped the command, the machine was unhitched in between — the echo expires and
  // the diagram goes back to the truth instead of showing a tap that did not happen.
  val gameSelectedId = selectedRigNode(nodes)?.id
  var echoId by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(gameSelectedId) { echoId = null }
  LaunchedEffect(echoId) {
    if (echoId != null) {
      delay(SELECT_ECHO_MS)
      echoId = null
    }
  }
  // Resolved against the live node list, so an echo left on a machine that has since been unhitched
  // falls back to the game's selection rather than blanking the panel.
  val selected = nodes.firstOrNull { it.id == echoId } ?: nodes.firstOrNull { it.id == gameSelectedId }
  // True exactly while the tile is showing a tap the game has not answered yet. The controls stay
  // inert through it — they are addressed at the game's confirmed selection, never at the echo — but
  // the driver must not be told to do the thing they have just done: see [MachineDetail].
  val awaitingSelection = echoId != null && selected?.id == echoId
  // RigSchema only offers a tap on a machine the game will actually select, so this needs no gate of
  // its own; the mod applies the same test again against state a tick fresher than ours.
  val onSelectNode: (String) -> Unit = { id ->
    echoId = id
    onCommand(ClientMessage.SetSelected(id))
  }

  val machine = if (nodes.isEmpty()) isoBusMachine(vehicle, slot) else selected?.machine
  val mixer = machine?.mixer

  // The harvesting rig is resolved from the WHOLE rig rather than from the selected node, because its
  // two halves are two machines: the combine threshes and the header cuts, and the game very often has
  // the header selected — the forage-harvester capture does. Either node opens the same section, so
  // tapping between them on the diagram changes which machine the generic controls address without the
  // screen under them changing.
  val combine = combineRigOf(if (nodes.isEmpty()) listOfNotNull(machine) else nodes.map { it.machine })
  // The straw command is aimed at the machine that threshes, never at whatever the diagram has
  // selected: on a rig whose header is selected, the target below names the header, which has no
  // swath to set.
  val combineTarget = when {
    combine == null -> null
    nodes.isEmpty() -> slot?.target ?: ControlTarget.VEHICLE
    else -> nodes.firstOrNull { it.machine === combine.combine }?.let(::controlTargetOf)
  }

  BoxWithConstraints(modifier) {
    // Narrow, the machine's name and the mix state cannot both sit in the header without one running
    // over the other. The state is the thing you glance at, so it moves into the body's status strip
    // rather than being dropped — see [MachineStatus].
    val bareHeader = maxWidth < BARE_HEADER_BELOW
    // Hoisted out of the scope: inside the Panel's content lambda these read as its BoxScope's, not
    // this box's — the same trap [MixerSection] documents.
    val bodyHeight = maxHeight
    val bodyWidth = maxWidth

    Panel(
      title = machine?.name ?: "ISOBUS",
      icon = if (bareHeader) null else Icons.Filled.Memory,
      modifier = Modifier.fillMaxSize(),
      headerActions = {
        if (!bareHeader) {
          when {
            mixer != null -> MixStateChip(mixer)
            combine != null -> HarvestChip(combine)
            else -> Unit
          }
        }
      },
    ) {
      if (machine == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text(
            when {
              vehicle == null -> "No vehicle connected"
              slot == null -> "Nothing on the rig"
              else -> "Nothing attached to ${slot.label.lowercase()}"
            },
            color = VdtColors.DarkGray,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
          )
        }
      } else {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          // A tile too short for the band would spend all of it on the picture — a fact about the size
          // this tile ended up at, which is how every other decision in this panel is taken.
          val band = schemaHeight(bodyHeight, nodes.size)
          // Wide enough, the band carries the machine's type and condition in the room either side of
          // the diagram, which is otherwise empty: the diagram is height-limited, so on a wide tile it
          // sits in the middle of a mostly blank strip. They come out of the chip strip when they do,
          // never appearing twice. Narrow, there is no such room and they stay where they were.
          val identityOnBand = band != null && bodyWidth >= IDENTITY_ON_BAND_FROM
          if (band != null) {
            if (identityOnBand) {
              Row(
                Modifier.height(band).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                // Equal weights on the flanks are what keeps the diagram *centred* rather than merely
                // between them — a long type name would otherwise shove it off to the right.
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { TypeLabel(machine) }
                RigSchema(
                  nodes,
                  selected?.id,
                  onSelect = onSelectNode,
                  modifier = Modifier.weight(2f).fillMaxHeight(),
                )
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { DamageChip(machine) }
              }
            } else {
              RigSchema(nodes, selected?.id, onSelect = onSelectNode, modifier = Modifier.height(band).fillMaxWidth())
            }
          }

          // The diagram addresses a node by where it sits in the tree; a pinned tile is already a
          // position, so it addresses itself. Either way a machine the channel cannot name gets null.
          val target = if (nodes.isEmpty()) slot?.target ?: ControlTarget.VEHICLE else selected?.let(::controlTargetOf)
          MachineDetail(
            machine = machine,
            target = target,
            awaitingSelection = awaitingSelection,
            // The control-group control addresses the machine by its node path rather than by a
            // target token, so it reaches machines `target` cannot name — and, on a tile pinned to a
            // slot, no path exists and it falls back to naming the group without offering the step.
            node = selected?.id,
            onCommand = onCommand,
            showIdentity = !identityOnBand,
            modifier = Modifier.fillMaxWidth(),
          )

          if (mixer != null) {
            MixerSection(machine, mixer, showStateInBody = bareHeader, modifier = Modifier.weight(1f).fillMaxWidth())
          } else if (combine != null) {
            CombineSection(
              rig = combine,
              target = combineTarget,
              onCommand = onCommand,
              modifier = Modifier.weight(1f).fillMaxWidth(),
            )
          }
        }
      }
    }
  }
}

/**
 * How much height the rig diagram gets, or null when the tile is too short to spend any on it.
 *
 * A share of the body rather than a fixed band, bounded so it can neither vanish on a tall tile nor
 * crowd out the machine below it on a short one — and a *small* share, because the diagram is
 * orientation and the machine under it is the reason to look.
 *
 * **One machine is still a diagram.** A bare tractor draws its own box and nothing else, exactly as
 * the game's HUD does — the band is where the rig lives, and a band that appears the moment something
 * is hitched and vanishes when it is unhitched makes the whole panel jump for a change the driver
 * already knows about. It is also where the type and condition sit once there is width for them
 * ([IDENTITY_ON_BAND_FROM]), so an empty rig would drop those back into the chip strip on a hitch.
 */
private fun schemaHeight(body: Dp, nodes: Int): Dp? {
  if (nodes == 0) return null
  if (body < MIN_BODY_FOR_SCHEMA) return null
  return (body * 0.15f).coerceIn(MIN_SCHEMA_HEIGHT, MAX_SCHEMA_HEIGHT)
}

// ---------------------------------------------------------------------------
// The generic machine — what every machine gets, with or without a section
// ---------------------------------------------------------------------------

/**
 * The part of the screen that does not depend on knowing what the machine *is*: what it is called
 * (in the header), what the game calls its type, how worn it is, and what is in it.
 *
 * This is the half of the terminal that makes the app useful on the overwhelming majority of rigs,
 * where no aspect section exists yet — and it is deliberately the *frame* rather than a fallback. A
 * machine that does have a section gets this too, with the section below it.
 *
 * The load is the one thing that moves: a machine whose section already draws its contents (a mixer
 * wagon's tub *is* its fill unit — the capture reads 12400/24000 in both places) must not have them
 * drawn twice, so the section owns them where there is one.
 */
@Composable
private fun MachineDetail(
  machine: IsoBusMachine,
  target: ControlTarget?,
  node: String?,
  onCommand: (ClientMessage) -> Unit,
  modifier: Modifier = Modifier,
  showIdentity: Boolean = true,
  awaitingSelection: Boolean = false,
) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    MachineStatus(machine, target, node, onCommand, showIdentity, Modifier.fillMaxWidth())

    // Why the machine's own controls are inert, rather than leaving the driver to tap and wonder. The
    // limit is the command channel's, not the diagram's: see [controlTargetOf]. Named rather than
    // written as "the controls", because the control group beside them is addressed by node path and
    // is not subject to it.
    //
    // Two different situations since #120, and only one of them is a dead end. A machine deep in the
    // chain is commandable as soon as it is the game's selection, and a tap on the diagram is what
    // moves that — so where the game would take it, the line is directions rather than an apology.
    //
    // Silent while that tap is in flight. Selecting a trailer hitched behind another trailer leaves
    // this null for the beat between the tap and the game's next export, and the directions would
    // then be telling the driver to do what they are already waiting on. Nothing is being hidden: the
    // chips are visibly inert for that beat, and if the selection is refused the echo expires and the
    // line comes back with the panel's state unchanged.
    if (target == null && !awaitingSelection) {
      Text(
        if (machine.selectable) {
          "Select it on the diagram to fold, power or unload it"
        } else {
          "Fold, power and unload reach the tractor, its front and rear, and the selected machine"
        },
        color = VdtColors.TextDisabled,
        fontSize = 9.sp,
        lineHeight = 11.sp,
      )
    }

    if (!machine.hasSection && machine.fillUnits.isNotEmpty()) {
      FillUnitsDisplay(machine.fillUnits, Modifier.fillMaxWidth(), spacing = 4)
    }
  }
}

/**
 * Everything the machine *is* and *is doing*, as one wrapping strip: its type, its condition, and a
 * chip per aspect it carries.
 *
 * One row rather than three. The type and the condition used to sit above the status chips, and the
 * status chips above a row of icon buttons — three lines saying what fits on one, two of which said
 * the same thing twice: a chip reading "Unfolded" over a button whose only job was to fold it.
 *
 * **A chip that names a state the app can change IS the control for it.** No icon buttons on this
 * screen at all, so there is one way to change anything rather than two. What it costs is a smaller
 * target than a 48dp button; what it buys is a control that says *which* of three covers is open, or
 * which side the trough will tip to, which an icon button never could — and a panel that fits a
 * machine and its whole state in the space the buttons alone used to take.
 *
 * An actionable chip is told apart from a read-only one by **weight and outline, never by hue**:
 * light with a border against flat grey, and taller.
 *
 * (`RigSlotPanel` keeps its buttons. A rig slot shows three fixed aspects with no labels to fold a
 * control into, and it is the tile a driver puts on a page precisely to have big targets.)
 *
 * Every tap sends the ABSOLUTE next state, computed from what is rendered, so the lossy channel can
 * drop or double it without desyncing. Cycles wrap the way the game's own actions do — a multi-state
 * pipe steps to the next position, a multi-cover machine steps through its covers and back to shut.
 *
 * A null [target] means the command channel cannot name this machine, and every chip goes read-only.
 */
@Composable
private fun MachineStatus(
  machine: IsoBusMachine,
  target: ControlTarget?,
  node: String?,
  onCommand: (ClientMessage) -> Unit,
  showIdentity: Boolean,
  modifier: Modifier = Modifier,
) {
  FlowRow(
    modifier,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    // Only when the diagram's band did not take them; see [IDENTITY_ON_BAND_FROM].
    if (showIdentity) {
      Box(Modifier.align(Alignment.CenterVertically)) { TypeLabel(machine) }
      DamageChip(machine)
    }

    // The three any machine on the rig might have, first: they are its operating state, where the
    // rest of the strip is situational.
    machine.isTurnedOn?.let { on ->
      Chip(
        if (on) Icons.Filled.PowerSettingsNew else Icons.Filled.PowerOff,
        if (on) "On" else "Off",
        if (on) VdtColors.AccentText else VdtColors.DarkGray,
        onClick = target?.let { { onCommand(ClientMessage.SetActivated(it, on = !on)) } },
      )
    }

    machine.lowered?.let { down ->
      Chip(
        if (down) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
        if (down) "Lowered" else "Raised",
        if (down) VdtColors.AccentText else VdtColors.DarkGray,
        onClick = target?.let { { onCommand(ClientMessage.SetLowered(it, on = !down)) } },
      )
    }

    when (machine.foldable) {
      FoldableState.FOLDED -> Chip(
        Icons.Filled.UnfoldLess,
        "Folded",
        VdtColors.DarkGray,
        onClick = target?.let { { onCommand(ClientMessage.SetFolded(it, on = false)) } },
      )

      FoldableState.EXTENDED -> Chip(
        Icons.Filled.UnfoldMore,
        "Unfolded",
        VdtColors.AccentText,
        onClick = target?.let { { onCommand(ClientMessage.SetFolded(it, on = true)) } },
      )

      null -> Unit
    }

    machine.tipping?.let { TipChip(it, target, onCommand) }
    machine.discharge?.let { DischargeChip(it, target, onCommand) }

    // The engine's own "why is nothing coming out". A terminal earns its place at the trough here:
    // the tip side is open, the drum is turning, and the reason it is not unloading is a fact only
    // the game has. Absent whenever nothing is wrong, which is most of the time — and read-only,
    // because it is an explanation rather than a state anything here can set.
    machine.discharge?.reason?.let { Chip(Icons.Filled.PriorityHigh, refusalOf(it), VdtColors.Amber) }

    machine.pipe?.let { PipeChip(it, target, onCommand) }
    machine.cover?.let { CoverChip(it, target, onCommand) }
    ControlGroupChip(machine, node, onCommand)
  }
}

/**
 * Which set of moving tools the machine's own controls are driving — a crane's boom against its
 * grab, a front loader's arms against the tool on the end.
 *
 * **It says the name, where the game says the number.** The game's HUD prints
 * `"Control group: 2"` because the number is all it carries to the screen; the names are declared in
 * the vehicle's XML and the mod exports them, so there is a word to print instead. That is the whole
 * reason this chip is worth its room on the strip.
 *
 * Shown only while a group is named, which on the game's side normally means the machine is the
 * selected one — the chip is a readout of live state, not a menu of what the machine could do. A
 * tap steps to the next group the machine can *currently* reach ([ControlGroup.available]), which is
 * not the same as the next one it declares: a group whose tools are inactive has no sub-selection and
 * cannot be reached at all. Named and reachable are separate answers, so the chip can be a label with
 * no tap on it — see [nextControlGroup].
 *
 * Stepping is one [ClientMessage.SetSelected] with the group named — selecting a machine and choosing
 * its group is a single engine call, so it is a single command here too.
 */
@Composable
private fun ControlGroupChip(machine: IsoBusMachine, node: String?, onCommand: (ClientMessage) -> Unit) {
  val group = machine.controlGroup ?: return
  val label = group.names.getOrNull(group.current - 1)?.takeIf { it.isNotBlank() } ?: return
  val next = nextControlGroup(group)
  Chip(
    Icons.Filled.Tune,
    label,
    VdtColors.AccentText,
    onClick = if (node != null && next != null) {
      { onCommand(ClientMessage.SetSelected(node, next)) }
    } else {
      null
    },
  )
}

/**
 * The group a tap steps to, or null when there is nowhere to step.
 *
 * Cycles over what the machine can reach right now, wrapping the way the game's own selection key
 * does. [ControlGroup.available] is the only source, and an empty one is the end of it: the chip
 * keeps its label and loses its tap.
 *
 * Deliberately no fallback to [ControlGroup.names]. Absent availability is two different things at
 * once on the wire — an export from before mod version 20, which reported none, and a version-20
 * machine none of whose groups is reachable, its moving tools inactive so no sub-selection exists to
 * name them. Cycling the declared groups would draw a live control the mod can only drop for the
 * second of those, and the first never had a tap to lose: the label, which is all a pre-20 export
 * ever put on screen here, is drawn either way.
 *
 * A [ControlGroup.current] outside the cycle (nothing active yet) steps to the first entry, since
 * `indexOf` returns -1 and the wrap turns that into 0.
 */
internal fun nextControlGroup(group: ControlGroup): Int? {
  val options = group.available
  if (options.size < 2) return null
  return options[(options.indexOf(group.current) + 1) % options.size]
}

/**
 * The game's own type name, unlocalized and modder-defined ("manureBarrel", "sprayer"). A plain label
 * because that is all it is good for — no section anywhere dispatches on it, and nothing can be done
 * to it. Ellipsized, since the flank it usually sits in is a quarter of the panel.
 *
 * A **modded** type carries its mod as a prefix, the way the game namespaces everything a mod
 * registers: `FS25_JohnDeere_6MSeries.tractor_foldable`. Which mod it came from is not what the label
 * is for, and at a quarter of the panel the prefix is all that survives the ellipsis — so it goes,
 * leaving the part that says what the machine *is*. A base-game type has no dot and is untouched.
 */
@Composable
private fun TypeLabel(machine: IsoBusMachine) {
  if (machine.type.isBlank()) return
  Text(
    machine.type.substringAfterLast('.'),
    color = VdtColors.DarkGray,
    fontSize = 10.sp,
    fontWeight = FontWeight.Bold,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

/**
 * Condition, as a percentage rather than the damage the mod reports — a driver reads "how much is
 * left", not "how much is gone". The number carries it; the tint only reinforces, and a machine in
 * good order gets the same neutral chip as anything else that cannot be acted on.
 */
@Composable
private fun DamageChip(machine: IsoBusMachine) {
  val damage = machine.damage ?: return
  Chip(
    Icons.Filled.Build,
    "${100 - damage}%",
    if (damage >= WORN_FROM) VdtColors.Amber else VdtColors.DarkGray,
  )
}

/**
 * Where the spout is. A pipe often has more than the two positions [PipeState] can name — an auger
 * wagon's is a multi-state animation — so a machine that reports more says which one it is at rather
 * than in-or-out, and `current != target` is the engine still travelling.
 */
@Composable
private fun PipeChip(pipe: Pipe, target: ControlTarget?, onCommand: (ClientMessage) -> Unit) {
  val moving = pipe.state == PipeState.MOVING || (pipe.current != 0 && pipe.current != pipe.target)
  val label = when {
    moving -> "Pipe moving"

    pipe.state == PipeState.RETRACTED -> "Pipe in"

    // 1 is fully retracted, so anything above it is a real position worth naming on a multi-state pipe.
    pipe.numStates > 2 && pipe.current > 0 -> "Pipe ${pipe.current}/${pipe.numStates}"

    else -> "Pipe out"
  }
  Chip(
    if (pipe.state == PipeState.RETRACTED) Icons.Filled.UnfoldLess else Icons.Filled.UnfoldMore,
    label,
    if (pipe.state == PipeState.RETRACTED) VdtColors.DarkGray else VdtColors.AccentText,
    // 1 is fully retracted and numStates the far end, so stepping with a wrap covers both the
    // ordinary two-position pipe and an auger wagon's multi-state one. `current` is 0 while the pipe
    // is travelling; stepping from the target instead keeps a second tap moving it onward.
    onClick = target?.let {
      {
        val from = if (pipe.current > 0) pipe.current else pipe.target
        onCommand(ClientMessage.SetPipeState(it, if (from >= pipe.numStates) 1 else maxOf(from, 1) + 1))
      }
    },
  )
}

/**
 * Which cover is open, on a machine that has more than one. `index` is 0 when everything is shut, so
 * it doubles as the closed state; the count is what makes naming the open one worth the room.
 */
@Composable
private fun CoverChip(cover: Cover, target: ControlTarget?, onCommand: (ClientMessage) -> Unit) {
  val open = cover.state == CoverType.OPEN || cover.index > 0
  val label = when {
    !open -> "Cover closed"
    cover.count > 1 -> "Cover ${cover.index} of ${cover.count}"
    else -> "Cover open"
  }
  Chip(
    if (open) Icons.Filled.LockOpen else Icons.Filled.Lock,
    label,
    if (open) VdtColors.AccentText else VdtColors.DarkGray,
    // 0 is everything shut; the game's own action reads "Next cover" while one of several is open, so
    // the cycle runs 0 -> 1 -> ... -> count -> 0 rather than toggling the first.
    onClick = target?.takeIf { cover.count > 0 }?.let {
      { onCommand(ClientMessage.SetCoverState(it, if (cover.index >= cover.count) 0 else cover.index + 1)) }
    },
  )
}

/**
 * Whether material is leaving the machine, and the one chip on the strip that names an **action**
 * while idle rather than a state: "Unload" is what a tap does, "Unloading" is what is happening. A
 * machine sitting still has nothing to report here, and a chip reading "Not unloading" would be noise
 * on every trailer on the rig.
 *
 * **Absent entirely on a machine the player cannot start unloading** ([Discharge.canToggle]). A
 * sprayer and a seeder are `Dischargeable` exactly as a trailer is — that is how the material leaves
 * them — but it leaves while they work rather than on a command, so the game offers no tip action and
 * neither did we, once the mod started saying so. Before that the chip was there and did nothing.
 *
 * Not gated on `discharge.allowed`: that is a master latch other specializations hold, not a verdict
 * on the spot the machine is standing on — a captured wagon reads `true` there while the engine is
 * refusing the trough in front of it. The mod asks the engine at the moment it acts, and the refusal
 * chip beside this one carries the answer.
 */
@Composable
private fun DischargeChip(discharge: Discharge, target: ControlTarget?, onCommand: (ClientMessage) -> Unit) {
  val unloading = discharge.state != DischargeState.OFF
  // Null is an older mod that never reported the flag; keep the pre-19 behaviour rather than hiding a
  // control that used to work. Something already unloading always gets a chip, so it can be stopped.
  if (discharge.canToggle == false && !unloading) return
  Chip(
    Icons.Filled.Download,
    if (unloading) "Unloading" else "Unload",
    if (unloading) VdtColors.AccentText else VdtColors.DarkGray,
    onClick = target?.let { { onCommand(ClientMessage.SetDischarging(it, on = !unloading)) } },
  )
}

/**
 * Damage past which the condition chip stops being neutral. The game starts warning the player around
 * here, and below it a couple of per cent of wear is not news.
 */
private const val WORN_FROM = 20

// ---------------------------------------------------------------------------
// Mixer wagon
// ---------------------------------------------------------------------------

@Composable
private fun MixerSection(
  machine: IsoBusMachine,
  mixer: Mixer,
  showStateInBody: Boolean,
  modifier: Modifier = Modifier,
) {
  BoxWithConstraints(modifier) {
    // Hoisted out of the scope: further in, these read as the enclosing Column's, not the box's.
    val bodyWidth = maxWidth
    val bodyHeight = maxHeight

    if (bodyWidth >= SIDE_BY_SIDE_FROM && bodyWidth > bodyHeight * 1.4f) {
      // Wide and low: the art is 2:1 and would eat the height a stacked layout needs for the bars.
      Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(0.55f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          MachineArt(mixer, machine.mass, Modifier.weight(1f).fillMaxWidth())
          MixerStatus(mixer, showStateInBody)
        }
        RatioBars(mixer, bodyWidth * 0.45f, Modifier.weight(0.45f).fillMaxHeight())
      }
    } else {
      val art = artHeight(bodyWidth, bodyHeight, mixer.ingredients.size)
      Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (art >= MIN_ART_HEIGHT) {
          MachineArt(mixer, machine.mass, Modifier.height(art).fillMaxWidth())
        } else {
          TubReadout(mixer, machine.mass, Modifier.fillMaxWidth())
        }
        MixerStatus(mixer, showStateInBody)
        RatioBars(mixer, bodyWidth, Modifier.weight(1f).fillMaxWidth())
      }
    }
  }
}

/**
 * How much height the art gets in the stacked layout.
 *
 * Budgeted from what the rest of the panel needs rather than as a flat fraction: the bars are the
 * reason the panel exists, so they are subtracted first and the picture takes what is left, bounded
 * by its own natural height (the art is 2:1, so a full-width band is already half the tile's width
 * tall) and by a share of the body, so a big tile is not mostly poster.
 *
 * Four rows' worth of bars are reserved at most: every base-game recipe is shorter than that, so the
 * usual case gives the whole list room, while a modded recipe of six scrolls instead of costing the
 * picture entirely.
 */
private fun artHeight(width: Dp, height: Dp, ingredients: Int): Dp {
  val natural = width / ART_ASPECT
  val cap = height * 0.55f
  val reserved = STATUS_STRIP_HEIGHT + BAR_ROW_HEIGHT * minOf(ingredients, 4)
  return minOf(natural, cap, height - reserved)
}

/**
 * The machine, with its load and weight written on the body — the mixer wagon's own weighing terminal
 * is on the front ladder of the real thing, and this is the same number in the same place.
 *
 * The art is one specific twin-auger vertical mixer standing in for the class, as the rest of the
 * ISOBUS machine set does. The level is **not** drawn as a rising fill: a mixer's contents are visible
 * from the top, not the side, so a filling side view would be a picture of something that does not
 * happen.
 */
@Composable
private fun MachineArt(mixer: Mixer, mass: Mass?, modifier: Modifier = Modifier) {
  BoxWithConstraints(modifier) {
    // The largest 2:1 box that fits, letterboxed on the long axis — same treatment as [Lighting].
    val boxW: Dp
    val boxH: Dp
    if (maxWidth / maxHeight >= ART_ASPECT) {
      boxH = maxHeight
      boxW = maxHeight * ART_ASPECT
    } else {
      boxW = maxWidth
      boxH = maxWidth / ART_ASPECT
    }

    Box(Modifier.size(boxW, boxH).align(Alignment.Center)) {
      Image(
        painterResource(Res.drawable.isobus_mixer_wagon),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Fit,
      )

      val tubW = boxW * (TUB_RIGHT - TUB_LEFT)
      val tubH = boxH * (TUB_BOTTOM - TUB_TOP)
      val type = tubType(tubW, tubH)
      Column(
        Modifier
          .offset(x = boxW * TUB_LEFT, y = boxH * TUB_TOP)
          .width(tubW)
          .height(tubH),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        // White on the body's #DA1218 reads at 5.2:1; black would be 4.1:1.
        Text("${formatInt(mixer.value.roundToInt())} l", style = type.headline, maxLines = 1)
        if (type.full) {
          Text("of ${formatInt(mixer.capacity)}", style = type.sub, maxLines = 1)
        }
        // The load's own weight, never the machine's mass minus its empty one — see [Mixer.mass].
        val load = mixer.mass
        if (load != null) {
          Spacer(Modifier.height(type.gap))
          Text(formatTonnes(load), style = type.headline, maxLines = 1)
          if (type.full && mass != null) {
            Text("${formatTonnes(mass.value)} total", style = type.sub, maxLines = 1)
          }
        } else if (mass != null) {
          Spacer(Modifier.height(type.gap))
          Text(formatTonnes(mass.value), style = type.headline, maxLines = 1)
        }
      }
    }
  }
}

/** The type the tub's readout is set in, and whether the two sub-lines fit alongside the figures. */
private data class TubType(val headline: TextStyle, val sub: TextStyle, val gap: Dp, val full: Boolean)

/**
 * Sizes the block on the tub to the space it actually has, in both axes.
 *
 * Two things this has to do that the obvious version does not. It sets an explicit [lineHeight]:
 * Material 3's default text style carries an **absolute** 24sp line height, so a `Text` given only a
 * `fontSize` keeps 24dp-tall line boxes however small the type is — four of them overran the tub and
 * the last two were clipped to slivers. And it picks the **line count from what fits** rather than
 * from a width threshold: the capacity and the gross weight are what give way, because what is in
 * there and what it weighs are the numbers this block exists for.
 */
@Composable
private fun tubType(width: Dp, height: Dp): TubType {
  // A headline glyph is about 0.6em wide and the longest is around nine ("225,000 l"), so the block's
  // width supports a headline of roughly a fifth of it.
  val byWidth = width.value / HEADLINE_EMS
  // Four lines: two headlines, two sub-lines at SUB_SCALE, and the gap between the figures.
  val four = minOf(height.value / (TUB_LINE * 2 * (1 + SUB_SCALE) + TUB_GAP), byWidth)
  val two = minOf(height.value / (TUB_LINE * 2 + TUB_GAP), byWidth)
  val full = four >= TUB_FULL_FROM
  val size = (if (full) four else two).coerceIn(9f, 34f).sp
  return TubType(
    headline = tubStyle(size, VdtColors.White, FontWeight.Bold),
    sub = tubStyle(size * SUB_SCALE, VdtColors.White.copy(alpha = 0.85f), FontWeight.Normal),
    gap = (size.value * TUB_GAP).dp,
    full = full,
  )
}

/**
 * A line box exactly as tall as its type, centred and trimmed — the same treatment `ProgressBar`'s
 * labels get, and for the same reason: the inherited line height is fixed in absolute sp and has
 * nothing to do with the size actually being drawn.
 */
private fun tubStyle(size: TextUnit, color: Color, weight: FontWeight) = TextStyle(
  color = color,
  fontSize = size,
  fontWeight = weight,
  lineHeight = size * TUB_LINE,
  lineHeightStyle =
  LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.Both,
  ),
)

/** The same two numbers as a plain row, for a tile too short to carry the picture. */
@Composable
private fun TubReadout(mixer: Mixer, mass: Mass?, modifier: Modifier = Modifier) {
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
    Figure("Load", "${formatInt(mixer.value.roundToInt())} l", "of ${formatInt(mixer.capacity)}")
    val load = mixer.mass
    if (load != null) {
      Figure("Weight", formatTonnes(load), mass?.let { "${formatTonnes(it.value)} total" })
    } else if (mass != null) {
      Figure("Weight", formatTonnes(mass.value), null)
    }
  }
}

@Composable
internal fun Figure(label: String, value: String, sub: String?) {
  Column {
    Text(label.uppercase(), color = VdtColors.DarkGray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    Text(value, color = VdtColors.TextDark, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    if (sub != null) Text(sub, color = VdtColors.DarkGray, fontSize = 10.sp, maxLines = 1)
  }
}

/**
 * The drum, the discharge and the fold, as a row of chips.
 *
 * It **wraps** rather than running one line: five chips can be up at once (the mix state on a narrow
 * tile, the drum, the tip side by its localized name, a refusal and the fold), and side by side the
 * strip only has the art column's width. A plain `Row` would have laid the overflow past the panel's
 * edge — and the chip most likely to fall off it is the last one added, the refusal, which is the one
 * the driver is standing at the trough for. [STATUS_STRIP_HEIGHT] budgets one line; a second is taken
 * from the bars, which are weighted, rather than from the panel.
 *
 * "Running" is the **drum**, and it is deliberately not the machine's turn-on state: the drum also
 * turns while discharging and for the mix cycle after the last thing went in, so reading turn-on as
 * "is it running" calls a mixing machine idle.
 *
 * There is no second chip for turn-on. On a mixer wagon turn-on engages the loading mechanism — which
 * on a machine that has none is nothing at all — and it feeds the same condition as the drum, so
 * whenever it is on the drum is turning too. Shown next to "Running" it was one fact stated twice.
 * What would earn a chip back is the **bale pickup** specifically (armed, and the game's own "that
 * bale is not in the recipe"), and that needs `spec.baleTriggers`, which the engine builds server-side
 * only. See FUTURE.md.
 */
@Composable
private fun MixerStatus(mixer: Mixer, showState: Boolean) {
  FlowRow(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    // Narrow, the header gave up the mix state; it leads the strip instead of being lost.
    if (showState) {
      val (icon, tint) = mixStateMark(mixer)
      Chip(icon, mixStateLabel(mixer), tint)
    }

    when {
      !mixer.powered -> Chip(Icons.Filled.PowerOff, "No power", VdtColors.TextDisabled)

      mixer.running && mixer.remaining > 0 ->
        Chip(Icons.Filled.Sync, "Mixing ${formatSeconds(mixer.remaining)}", VdtColors.AccentText)

      mixer.running -> Chip(Icons.Filled.Sync, "Running", VdtColors.AccentText)

      else -> Chip(Icons.Filled.Pause, "Idle", VdtColors.DarkGray)
    }
  }
}

/**
 * Which way the wagon is unloading, and whether the door is moving.
 *
 * The side is named rather than drawn on the machine: the art is a side view, and left/right on a
 * mixer wagon is toward and away from the viewer — a position on that picture could not say it.
 * While a tip is under way it names the side actually in use; otherwise the one the next tip will
 * take.
 */
@Composable
private fun TipChip(tipping: Tipping, target: ControlTarget?, onCommand: (ClientMessage) -> Unit) {
  val open = tipping.state != TipState.CLOSED
  val index = if (open) tipping.side ?: tipping.preferredSide else tipping.preferredSide
  val name = index?.let { tipping.sides.getOrNull(it - 1) }?.ifBlank { null }
  val label = when (tipping.state) {
    TipState.CLOSED -> name?.let { "Tip: $it" } ?: "Closed"

    TipState.OPENING -> name?.let { "Opening $it" } ?: "Opening"

    // "Open", not "Unloading": this aspect is the trough moving, and [DischargeChip] beside it is the
    // material actually leaving. A trough can sit fully open with nothing coming out, which is the
    // window this chip exists to show — and with both on one strip the old wording read as a
    // contradiction whenever the engine was refusing the spot.
    TipState.OPEN -> name?.let { "Open: $it" } ?: "Open"

    TipState.CLOSING -> name?.let { "Closing $it" } ?: "Closing"
  }
  // The trough has to be shut to change sides — the engine refuses otherwise — so the chip goes
  // read-only mid-tip rather than sending a command the mod would drop. A machine with one side has
  // nothing to cycle.
  val sides = tipping.count ?: 0
  val canSwitch = sides > 1 && tipping.state == TipState.CLOSED
  Chip(
    if (open) Icons.Filled.Sync else Icons.Filled.Pause,
    label,
    if (open) VdtColors.AccentText else VdtColors.DarkGray,
    onClick = target?.takeIf { canSwitch }?.let {
      {
        val from = tipping.preferredSide ?: 1
        onCommand(ClientMessage.SetTipSide(it, if (from >= sides) 1 else from + 1))
      }
    },
  )
}

/**
 * The engine's discharge refusals, in the words that fit a machine unloading into a feeding trough —
 * which is what every one of these means on a mixer wagon.
 */
internal fun refusalOf(reason: DischargeReason): String = when (reason) {
  DischargeReason.NOT_ALLOWED_HERE -> "Can't unload here"
  DischargeReason.NO_FREE_CAPACITY -> "Trough full"
  DischargeReason.FILLTYPE_NOT_SUPPORTED -> "Won't take this feed"
  DischargeReason.TOOLTYPE_NOT_SUPPORTED -> "Wrong tool for it"
  DischargeReason.NO_ACCESS -> "Not your trough"
  DischargeReason.NO_ACCESS_LAND -> "Not your land"
}

@Composable
internal fun Chip(icon: ImageVector, label: String, tint: Color, onClick: (() -> Unit)? = null) {
  val shape = RoundedCornerShape(3.dp)
  // Actionable chips are told apart by weight and outline, never by hue: a raised, outlined, taller
  // chip against a flat grey one. The extra height is not decoration — it is what makes the tap
  // target reachable in a moving cab, where a 19dp chip is not.
  var box = Modifier.clip(shape)
  box = if (onClick != null) {
    box.background(VdtColors.White)
      .border(1.dp, VdtColors.PanelBorder, shape)
      .clickable(onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 6.dp)
  } else {
    box.background(VdtColors.TrackGray).padding(horizontal = 6.dp, vertical = 3.dp)
  }
  Row(box, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(11.dp))
    Text(label, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
  }
}

/**
 * What the tub holds, in the engine's own words plus a glyph.
 *
 * The word is the mod's `title` — the loaded fill type's localized name, which is already exactly
 * right in all three loaded states ("Silage", "Mixing", "Total Mixed Ration") — and the glyph carries
 * the verdict, so neither the state nor its severity is ever told by hue alone.
 */
@Composable
private fun RowScope.MixStateChip(mixer: Mixer) {
  val (icon, tint) = mixStateMark(mixer)
  Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
  Text(mixStateLabel(mixer).uppercase(), color = tint, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
}

/**
 * What is in the tub, in a word.
 *
 * The engine's own title is right for two of the three loaded states — the material while one thing
 * is in ("Heu"), the recipe's own name once the mix is finished ("Totalmischration"). It is *usable*
 * for the third and not much good: FORAGE_MIXING's title is "Futter", which says the load is feed
 * without saying the ratio is wrong, and the word a driver needs there is the fault. So off-ratio
 * gets a word of our own — deliberately not "mixing", which the status strip already uses for the
 * drum turning.
 */
private fun mixStateLabel(mixer: Mixer): String = when (mixer.state) {
  MixState.EMPTY -> "Empty"
  MixState.OUT_OF_RATIO -> "Off ratio"
  else -> mixer.title ?: mixer.fillType ?: "Loaded"
}

/** The glyph that carries the verdict, so hue is never the only thing saying it. */
private fun mixStateMark(mixer: Mixer): Pair<ImageVector, Color> = when (mixer.state) {
  MixState.READY -> Icons.Filled.Check to VdtColors.AccentText
  MixState.OUT_OF_RATIO -> Icons.Filled.Sync to VdtColors.Amber
  MixState.SINGLE -> Icons.Filled.HourglassBottom to VdtColors.DarkGray
  MixState.EMPTY -> Icons.Filled.Pause to VdtColors.TextDisabled
}

// ---------------------------------------------------------------------------
// The mixing-ratio bars
// ---------------------------------------------------------------------------

/** Bar geometry. The track is short — this is a strip of them, not a stack of progress bars. */
private val BAR_HEIGHT = 12.dp

/** Narrower than this, a bar row drops its weight column and shortens its hint to fit one line. */
private val COMPACT_BARS_BELOW = 230.dp
private val MARKER_WIDTH = 3.dp

@Composable
private fun RatioBars(mixer: Mixer, width: Dp, modifier: Modifier = Modifier) {
  if (mixer.ingredients.isEmpty()) {
    // A mixer whose XML names no recipe: a trailer with a drum. Nothing to be short of.
    Box(modifier.fillMaxWidth().padding(top = 4.dp)) {
      Text("No recipe — takes one material at a time", color = VdtColors.DarkGray, fontSize = 10.sp)
    }
    return
  }
  // Scrolls rather than clips: recipes are map data and a modded one may run to six ingredients, so
  // "how many fit" is never something this panel gets to decide by dropping the ones that don't.
  // Centred rather than top-aligned: beside the art on a wide tile, and under it on a full page, a
  // short list stranded at the top reads as a panel that failed to finish loading. When the list
  // outgrows the space, Center lays out from the top anyway and it scrolls.
  Column(
    modifier.verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
  ) {
    for (ingredient in mixer.ingredients) RatioBar(mixer, ingredient, compact = width < COMPACT_BARS_BELOW)
  }
}

/**
 * One ingredient's bar: the recipe's allowed window drawn as a band on the track, and a marker where
 * this ingredient actually sits.
 *
 * **The share is of the load, not of the tub.** `value / Mixer.loaded`, which is what the game's own
 * HUD divides by — dividing by `capacity` would report a perfectly good mix as short of everything on
 * any wagon that is not brim full.
 *
 * In-window and out-of-window are told apart by **where the marker sits relative to the band**, by the
 * trailing glyph, and by the words; colour only agrees with them. That is the standing rule here — two
 * states must never differ by hue alone.
 *
 * A verdict is only passed when the engine has passed one. While the wagon is empty or holds a single
 * material there is nothing wrong with an ingredient reading zero, so those rows carry the shortfall
 * hint but no warning.
 */
@Composable
private fun RatioBar(mixer: Mixer, ingredient: MixerIngredient, compact: Boolean) {
  val share = mixer.shareOf(ingredient)
  val inWindow = ingredient.holds(share)
  val judged = mixer.state == MixState.OUT_OF_RATIO
  val flagged = judged && !inWindow

  val min = ingredient.minPercentage / 100f
  val max = ingredient.maxPercentage / 100f

  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(
        ingredient.title ?: ingredient.fillTypes.firstOrNull() ?: ingredient.name,
        color = VdtColors.TextDark,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      val mass = ingredient.mass
      if (!compact && mass != null && mass > 0.0) {
        Text(formatTonnes(mass), color = VdtColors.DarkGray, fontSize = 9.sp, maxLines = 1)
        Spacer(Modifier.width(6.dp))
      }
      Text(
        "${(share * 100).roundToInt()}%",
        color = if (flagged) VdtColors.Amber else VdtColors.TextDark,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
      )
      if (flagged) {
        Spacer(Modifier.width(3.dp))
        Icon(
          Icons.Filled.PriorityHigh,
          contentDescription = "outside its window",
          tint = VdtColors.Amber,
          modifier = Modifier.size(11.dp),
        )
      } else if (inWindow && share > 0f) {
        Spacer(Modifier.width(3.dp))
        Icon(
          Icons.Filled.Check,
          contentDescription = "in its window",
          tint = VdtColors.AccentText,
          modifier = Modifier.size(11.dp),
        )
      }
    }

    BoxWithConstraints(
      Modifier
        .fillMaxWidth()
        .height(BAR_HEIGHT)
        .clip(RoundedCornerShape(2.dp))
        .background(VdtColors.TrackGray)
        .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(2.dp)),
    ) {
      val track = maxWidth
      // The recipe's window. Drawn filled rather than outlined so the marker reads as inside or
      // outside a solid block at a glance, at any tile size.
      Box(
        Modifier
          .offset(x = track * min)
          .width(track * (max - min))
          .fillMaxHeight()
          .background(if (inWindow) VdtColors.AccentText.copy(alpha = 0.35f) else VdtColors.Gray),
      )
      // Where this ingredient actually sits. Clamped so a wildly over-filled ingredient still shows
      // at the end of the track instead of being laid out off it.
      val at = (track * share.toFloat().coerceIn(0f, 1f)) - MARKER_WIDTH / 2
      Box(
        Modifier
          .offset(
            x = if (at < 0.dp) {
              0.dp
            } else if (at > track - MARKER_WIDTH) {
              track - MARKER_WIDTH
            } else {
              at
            },
          )
          .width(MARKER_WIDTH)
          .fillMaxHeight()
          .background(if (flagged) VdtColors.Amber else VdtColors.TextDark),
      )
    }

    // What to do about it, when there is something to do.
    val short = shortfall(mixer, ingredient)
    if (short != null) {
      Text(
        if (compact) "+${formatInt(short)} l" else "+${formatInt(short)} l to reach ${ingredient.minPercentage}%",
        color = VdtColors.DarkGray,
        fontSize = 9.sp,
        maxLines = 1,
      )
    }
  }
}

/**
 * Litres of [ingredient] to add to bring it up to its minimum share — the number the game never
 * shows and the whole reason to look at a mixer terminal.
 *
 * **Adding moves the denominator too**, which is what makes this arithmetic rather than a
 * subtraction: the share is of the load, so `(v + x) / (L + x) = min` solves to
 * `x = (min·L − v) / (1 − min)`. Taking the naive `min·L − v` under-reports by a third at a 25%
 * target and by three times at 75%, and would have the driver top up twice.
 *
 * Null when there is nothing to say: the ingredient is already inside its window, the tub is empty
 * (nothing is wrong with an empty wagon), or the recipe demands the whole load be this one thing,
 * which no amount of adding it can fix.
 *
 * Internal so it can be tested directly — like [mergeFillUnits], a mistake here reads as a plausible
 * number rather than an error.
 */
internal fun shortfall(mixer: Mixer, ingredient: MixerIngredient): Int? {
  if (mixer.value <= 0.0 || mixer.loaded <= 0.0) return null
  val min = ingredient.minPercentage / 100.0
  if (min <= 0.0 || min >= 1.0) return null
  if (mixer.shareOf(ingredient) >= min) return null
  val litres = ((min * mixer.loaded - ingredient.value) / (1 - min)).roundToInt()
  return if (litres > 0) litres else null
}

// ---------------------------------------------------------------------------
// Formatting
// ---------------------------------------------------------------------------

/** Tonnes, dropping to kilograms below one — the same switch the game's own `formatMass` makes. */
internal fun formatTonnes(tonnes: Double): String =
  if (tonnes < 1.0) "${formatInt((tonnes * 1000).roundToInt())} kg" else "${format1(tonnes.toFloat())} t"

/** A mix countdown, in seconds with one decimal while it is short enough to watch. */
internal fun formatSeconds(ms: Int): String =
  if (ms >= 10_000) "${(ms / 1000.0).roundToInt()}s" else "${format1(ms / 1000f)}s"
