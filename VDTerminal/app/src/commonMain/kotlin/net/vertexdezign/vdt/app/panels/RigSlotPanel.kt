package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PowerSettingsNew
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.ControlTarget
import net.vertexdezign.vdt.app.components.FillUnitsDisplay
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.components.SectionView
import net.vertexdezign.vdt.app.components.StatusColor
import net.vertexdezign.vdt.app.components.StatusIconButton
import net.vertexdezign.vdt.app.components.ownRates
import net.vertexdezign.vdt.app.components.sectionMember
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.app.widgets.WidgetSettings
import net.vertexdezign.vdt.model.FillUnit
import net.vertexdezign.vdt.model.FoldableState
import net.vertexdezign.vdt.model.Implement
import net.vertexdezign.vdt.model.PrecisionFarming
import net.vertexdezign.vdt.model.Vehicle
import net.vertexdezign.vdt.model.WorkArea
import net.vertexdezign.vdt.model.WorkWidth
import kotlin.math.roundToInt

private val Green600 = Color(0xFF16A34A)
private val Gray300 = Color(0xFFD1D5DB)
private val Gray400 = Color(0xFF9CA3AF)

/**
 * The merge toggle's persistence name, scoped per placed tile by [WidgetSettings] — like the map's
 * zoom and filters. Whether a chain reads as one load per fill type is a view preference about *this*
 * tile, and it used to live in a plain `remember`: leaving the vehicle swapped the panel for the
 * "no vehicle" tile and the toggle came back off.
 */
private const val KEY_MERGED = "merged"

/**
 * One position on the rig: the vehicle itself, or whatever hangs off its front or back.
 *
 * This used to be two things — a two-column Implements panel, and a row of buttons buried in the
 * engine readout for the vehicle. They were the same interface twice, because the model already says
 * so: [Vehicle] and [Implement] both carry `isTurnedOn` / `foldable` / `lowered` / `wearable` /
 * `fillUnits`, and [ControlTarget] already addresses all three positions. Collapsing them into one
 * configurable widget is what lets a page put front on the left, the map in the middle and rear on the
 * right — an arrangement the fixed two-column panel could not express.
 */
enum class RigSlot(val label: String, val target: ControlTarget) {
  VEHICLE("Vehicle", ControlTarget.VEHICLE),
  FRONT("Front", ControlTarget.FRONT),
  REAR("Rear", ControlTarget.BACK),
  ;

  /** The `position` the mod exports for this slot; null for the vehicle, which is not an implement. */
  val implementPosition: String?
    get() = when (this) {
      VEHICLE -> null
      FRONT -> "FRONT"
      REAR -> "BACK"
    }
}

/** What a slot shows, read off either the vehicle or an implement — the two speak the same shape. */
private data class RigSlotState(
  val name: String,
  val type: String,
  val damage: Int,
  val foldable: FoldableState?,
  val isTurnedOn: Boolean?,
  val lowered: Boolean?,
  val fillUnits: List<FillUnit>,
  val workWidth: WorkWidth?,
  val workAreas: List<WorkArea>,
  val precisionFarming: PrecisionFarming?,
)

private fun findImplement(list: List<Implement>, pos: String): Implement? {
  for (imp in list) {
    if (imp.position == pos) return imp
    findImplement(imp.implement, pos)?.let { return it }
  }
  return null
}

/** An implement's own fill units plus every child's — a trailer chain reads as one load. */
private fun collectFillUnits(imp: Implement?): List<FillUnit> {
  if (imp == null) return emptyList()
  val units = mutableListOf<FillUnit>()
  imp.fillUnits?.fillUnit?.let { units += it }
  for (child in imp.implement) units += collectFillUnits(child)
  return units
}

/**
 * Folds a chain's fill units into one bar per fill type — what the panel's merge toggle turns on.
 *
 * Grouped by [FillUnit.type], falling back to the title for units that report none, and keyed in
 * encounter order so the merged list keeps the order the rig is hitched in. Internal rather than
 * private so the arithmetic can be tested directly: getting it wrong looks like a plausible bar
 * rather than an error.
 */
internal fun mergeFillUnits(units: List<FillUnit>): List<FillUnit> {
  val groups = LinkedHashMap<String, MutableList<FillUnit>>()
  for (u in units) {
    groups
      .getOrPut(
        u.type?.ifBlank { null } ?: u.title.ifBlank { "Unknown" },
      ) { mutableListOf() }
      .add(u)
  }
  return groups.values.map { g ->
    // `sumOf` has no Float overload, hence map/sum.
    val value = g.map { it.value }.sum()
    val capacity = g.sumOf { it.capacity }
    g.first().copy(
      value = value,
      // Capacity has to grow with the level, or the bar (which is driven by value/capacity) reads a
      // two-trailer 37000/18500 as permanently full.
      capacity = capacity,
      // Derive the percentage from the totals so it matches the combined ratio. Pass-through units
      // report no capacity at all; there the reported percentages are all there is, so average them.
      fillLevelPercentage =
      if (capacity > 0) {
        (value / capacity * 100f).roundToInt()
      } else {
        (g.sumOf { it.fillLevelPercentage }.toDouble() / g.size).roundToInt()
      },
    )
  }
}

private fun Vehicle.slotState() = RigSlotState(
  name = name,
  type = type,
  damage = wearable?.damage ?: 0,
  foldable = foldable,
  isTurnedOn = isTurnedOn,
  lowered = lowered,
  // Cargo only. The engine's own fuel/def/air stay with the engine readout, next to the rates.
  fillUnits = fillUnits?.fillUnit ?: emptyList(),
  // A self-propelled sprayer carries its own boom, so the vehicle slot shows a section view exactly
  // as an implement slot does. A prime mover does not: the mod hands it the rates of the tool it is
  // driving, and that tool has a slot of its own to show them in. See [ownRates].
  workWidth = workWidth,
  workAreas = workAreas,
  precisionFarming = ownRates(this),
)

private fun Implement.slotState(): RigSlotState {
  // The section view reads the chain, not just the head — the same way the fill units above always
  // have. On a slurry tanker the machine working the ground is the tool hitched behind it, and it has
  // no slot of its own to be shown in. See [sectionMember].
  val working = sectionMember(this)
  return RigSlotState(
    name = name,
    type = type,
    // The mod's old `combined.implement.front/back` was just the first front/back implement's own
    // aspect state — which is exactly this implement — so read status/damage straight off it.
    damage = wearable?.damage ?: 0,
    foldable = foldable,
    isTurnedOn = isTurnedOn,
    lowered = lowered,
    fillUnits = collectFillUnits(this),
    workWidth = working.workWidth,
    workAreas = working.workAreas,
    precisionFarming = working.precisionFarming,
  )
}

/**
 * Below this the three controls can no longer sit in a row and still be worth aiming at: they need
 * roughly 40dp each plus the 8dp gaps between them. A one-cell tile gives the body about 75dp in
 * landscape, so it stacks; two cells gives about 174dp, so it doesn't.
 */
private val STACK_CONTROLS_BELOW = 140.dp

/**
 * Past this even the panel header can't hold everything: the title, the leading icon and the merge
 * toggle together overrun a single cell. At that width the header keeps the title alone — it is the
 * one part that says which slot you're looking at.
 */
private val BARE_HEADER_BELOW = 100.dp

/**
 * Stacked, the three controls are the tallest thing in the panel, and at the default 48dp they take
 * the whole of a three-row tile before the name and condition get any. 40dp is still a comfortable
 * target on a tablet and leaves the rest of the panel room to exist.
 */
private val STACKED_BUTTON_HEIGHT = 40.dp

/**
 * Renders [slot] of [vehicle]: its name, condition, the fold/power/raise controls, and its load.
 *
 * An empty implement position still draws the panel, greyed — the tile is a fixed place on the page,
 * so it says "nothing attached" rather than vanishing and reflowing everything around it.
 *
 * The panel measures itself and thins out as it narrows, rather than taking a "compact" flag: the
 * grid lets this tile be placed anywhere from one cell to a dozen, and what fits is a fact about the
 * width it ended up with, not something the page should have to declare.
 *
 * [settings] scopes the merge toggle to this placed tile, so a page can show the same slot merged in
 * one place and per-unit in another, and either survives leaving the vehicle.
 */
@Composable
fun RigSlotPanel(
  slot: RigSlot,
  vehicle: Vehicle,
  settings: WidgetSettings,
  modifier: Modifier = Modifier,
  onCommand: (ClientMessage) -> Unit = {},
) {
  var merged by remember { mutableStateOf(settings.getBoolean(KEY_MERGED, false)) }
  LaunchedEffect(merged) { settings.putBoolean(KEY_MERGED, merged) }

  val state = when (slot) {
    RigSlot.VEHICLE -> vehicle.slotState()

    // Safe-called rather than asserted: a slot that names no position simply has nothing to look up,
    // which is the panel's ordinary "nothing attached" state, not a crash.
    else -> slot.implementPosition?.let { findImplement(vehicle.implement, it) }?.slotState()
  }
  val fillUnits = state?.fillUnits.orEmpty().let { if (merged) mergeFillUnits(it) else it }

  BoxWithConstraints(modifier) {
    val stackControls = maxWidth < STACK_CONTROLS_BELOW
    val bareHeader = maxWidth < BARE_HEADER_BELOW

    Panel(
      title = slot.label,
      icon = if (bareHeader) null else slot.icon,
      modifier = Modifier.fillMaxSize(),
      headerActions = {
        // The vehicle keeps the toggle too. Merging groups by fill type, so on a machine with one
        // unit of each it simply changes nothing — not worth a second, conditional header layout.
        if (!bareHeader) {
          Icon(
            if (merged) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.Layers,
            contentDescription = "toggle merge",
            tint = VdtColors.DarkGray,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { merged = !merged }.padding(2.dp),
          )
        }
      },
    ) {
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The vehicle is always "attached" — it's the thing the implements hang off. Narrow, the
        // header already carries the label, so only the attached lamp is worth the row.
        if (slot != RigSlot.VEHICLE) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!bareHeader) {
              Text(
                slot.label.uppercase(),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = VdtColors.DarkGray,
              )
            }
            Icon(
              Icons.Filled.Link,
              null,
              tint = if (state != null) Green600 else Gray400,
              modifier = Modifier.height(16.dp),
            )
          }
        }

        NameBox(state, empty = if (slot == RigSlot.VEHICLE) "No Vehicle" else "No Implement")

        if (state != null) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Build, null, tint = VdtColors.DarkGray, modifier = Modifier.height(14.dp))
            Text(
              "${100 - state.damage}%",
              fontSize = 14.sp,
              fontWeight = FontWeight.Bold,
              color = VdtColors.DarkGray,
            )
          }
        }

        // What this tool is doing across its width — absent on anything that works no ground, which
        // is most of a rig. Above the controls: it is what the fold/raise buttons under it change.
        //
        // Given the same onCommand as those buttons, so the Precision Farming rate can be driven from
        // here too. Addressed at the rig rather than at this slot: PF drives whichever machine on the
        // rig is its valid sprayer, so a front tank and a rear boom are one rate, and the tile that
        // shows it is whichever one has the readout.
        if (state != null) {
          SectionView(state.workWidth, state.workAreas, state.precisionFarming, onCommand = onCommand)
        }

        // Each control is clickable only when this slot has that aspect; the tap sends the ABSOLUTE
        // target for the slot's position, computed from the rendered state (idempotent over the lossy
        // command channel — see ClientMessage). Front/back are routed mod-side through
        // FS25_additionalInputs.
        RigSlotControls(state, slot.target, stacked = stackControls, onCommand = onCommand)

        if (state != null) FillUnitsDisplay(fillUnits, Modifier.fillMaxWidth(), spacing = 4)
      }
    }
  }
}

private val RigSlot.icon: ImageVector
  get() = when (this) {
    RigSlot.VEHICLE -> Icons.Filled.Agriculture
    RigSlot.FRONT -> Icons.Filled.ArrowUpward
    RigSlot.REAR -> Icons.Filled.ArrowDownward
  }

@Composable
private fun NameBox(state: RigSlotState?, empty: String) {
  // heightIn(min) — not a fixed height — so two lines can never be clipped by the box on devices
  // whose font metrics make the name+type stack taller than the minimum. Line heights are tightened
  // (the old React panel used `leading-tight`) so it normally fits at 34dp.
  Box(
    Modifier
      .fillMaxWidth()
      .heightIn(min = 34.dp)
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.White)
      .border(1.dp, Gray300, RoundedCornerShape(4.dp))
      .padding(vertical = 2.dp),
    contentAlignment = Alignment.Center,
  ) {
    if (state == null) {
      Text(empty, fontSize = 10.sp, color = Gray300)
    } else {
      // Name plus (optional) type. The type is only rendered when present, so something without a
      // type shows a single, vertically-centred name instead of a name with an empty line below it.
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
          state.name,
          fontSize = 10.sp,
          lineHeight = 12.sp,
          fontWeight = FontWeight.Bold,
          color = VdtColors.TextDark,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (state.type.isNotBlank()) {
          Text(
            state.type,
            fontSize = 8.sp,
            lineHeight = 10.sp,
            color = Gray400,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

/**
 * The fold / power / raise trio, in a row when there is width for it and stacked when there isn't.
 *
 * The three buttons are identical either way — only the container changes — so the arrangement can
 * flip on a resize without the controls themselves knowing. Stacked, they take their natural
 * full-width height instead of splitting the row three ways.
 */
@Composable
private fun RigSlotControls(
  state: RigSlotState?,
  target: ControlTarget,
  stacked: Boolean,
  onCommand: (ClientMessage) -> Unit,
) {
  val foldable = state?.foldable

  // Declared once and placed by whichever container wins, so the two arrangements can't drift apart.
  val height = if (stacked) STACKED_BUTTON_HEIGHT else 48.dp
  val buttons = listOf<@Composable (Modifier) -> Unit>(
    { mod ->
      StatusIconButton(
        Icons.Filled.UnfoldMore,
        mod,
        height = height,
        active = foldable != null,
        color = if (foldable == FoldableState.EXTENDED) StatusColor.Green else StatusColor.White,
        onClick =
        foldable?.let {
          { onCommand(ClientMessage.SetFolded(target, on = it == FoldableState.EXTENDED)) }
        },
      )
    },
    { mod ->
      StatusIconButton(
        Icons.Filled.PowerSettingsNew,
        mod,
        height = height,
        active = state?.isTurnedOn != null,
        color = if (state?.isTurnedOn == true) StatusColor.Green else StatusColor.White,
        onClick =
        state?.isTurnedOn?.let {
          { onCommand(ClientMessage.SetActivated(target, on = !it)) }
        },
      )
    },
    { mod ->
      StatusIconButton(
        if (state?.lowered == true) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
        mod,
        height = height,
        active = state?.lowered != null,
        color = if (state?.lowered == true) StatusColor.Green else StatusColor.White,
        onClick =
        state?.lowered?.let {
          { onCommand(ClientMessage.SetLowered(target, on = !it)) }
        },
      )
    },
  )

  if (stacked) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      for (button in buttons) button(Modifier)
    }
  } else {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      for (button in buttons) button(Modifier.weight(1f))
    }
  }
}
