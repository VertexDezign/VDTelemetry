package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import net.vertexdezign.vdt.app.components.StatusColor
import net.vertexdezign.vdt.app.components.StatusIconButton
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.FillUnit
import net.vertexdezign.vdt.model.FoldableState
import net.vertexdezign.vdt.model.Implement
import net.vertexdezign.vdt.model.Vehicle
import kotlin.math.roundToInt

private val Green600 = Color(0xFF16A34A)
private val Gray300 = Color(0xFFD1D5DB)
private val Gray400 = Color(0xFF9CA3AF)

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
private data class SlotState(
  val name: String,
  val type: String,
  val damage: Int,
  val foldable: FoldableState?,
  val isTurnedOn: Boolean?,
  val lowered: Boolean?,
  val fillUnits: List<FillUnit>,
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

private fun mergeFillUnits(units: List<FillUnit>): List<FillUnit> {
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

private fun Vehicle.slotState() = SlotState(
  name = name,
  type = type,
  damage = wearable?.damage ?: 0,
  foldable = foldable,
  isTurnedOn = isTurnedOn,
  lowered = lowered,
  // Cargo only. The engine's own fuel/def/air stay with the engine readout, next to the rates.
  fillUnits = fillUnits?.fillUnit ?: emptyList(),
)

private fun Implement.slotState() = SlotState(
  name = name,
  type = type,
  // The mod's old `combined.implement.front/back` was just the first front/back implement's own
  // aspect state — which is exactly this implement — so read status/damage straight off it.
  damage = wearable?.damage ?: 0,
  foldable = foldable,
  isTurnedOn = isTurnedOn,
  lowered = lowered,
  fillUnits = collectFillUnits(this),
)

/**
 * Renders [slot] of [vehicle]: its name, condition, the fold/power/raise controls, and its load.
 *
 * An empty implement position still draws the panel, greyed — the tile is a fixed place on the page,
 * so it says "nothing attached" rather than vanishing and reflowing everything around it.
 */
@Composable
fun SlotPanel(
  slot: RigSlot,
  vehicle: Vehicle,
  modifier: Modifier = Modifier,
  onCommand: (ClientMessage) -> Unit = {},
) {
  var merged by remember { mutableStateOf(false) }

  val state = when (slot) {
    RigSlot.VEHICLE -> vehicle.slotState()
    else -> findImplement(vehicle.implement, slot.implementPosition!!)?.slotState()
  }
  val fillUnits = state?.fillUnits.orEmpty().let { if (merged) mergeFillUnits(it) else it }

  Panel(
    title = slot.label,
    icon = slot.icon,
    modifier = modifier,
    headerActions = {
      // The vehicle keeps the toggle too. Merging groups by fill type, so on a machine with one unit
      // of each it simply changes nothing — not worth a second, conditional header layout.
      Icon(
        if (merged) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.Layers,
        contentDescription = "toggle merge",
        tint = VdtColors.DarkGray,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { merged = !merged }.padding(2.dp),
      )
    },
  ) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      // The vehicle is always "attached" — it's the thing the implements hang off.
      if (slot != RigSlot.VEHICLE) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(slot.label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = VdtColors.DarkGray)
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

      // Each control is clickable only when this slot has that aspect; the tap sends the ABSOLUTE
      // target for the slot's position, computed from the rendered state (idempotent over the lossy
      // command channel — see ClientMessage). Front/back are routed mod-side through
      // FS25_additionalInputs.
      SlotControls(state, slot.target, onCommand)

      if (state != null) FillUnitsDisplay(fillUnits, Modifier.fillMaxWidth(), spacing = 4)
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
private fun NameBox(state: SlotState?, empty: String) {
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

@Composable
private fun SlotControls(state: SlotState?, target: ControlTarget, onCommand: (ClientMessage) -> Unit) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    val foldable = state?.foldable
    StatusIconButton(
      Icons.Filled.UnfoldMore,
      Modifier.weight(1f),
      active = foldable != null,
      color = if (foldable == FoldableState.EXTENDED) StatusColor.Green else StatusColor.White,
      onClick =
      foldable?.let {
        { onCommand(ClientMessage.SetFolded(target, on = it == FoldableState.EXTENDED)) }
      },
    )
    StatusIconButton(
      Icons.Filled.PowerSettingsNew,
      Modifier.weight(1f),
      active = state?.isTurnedOn != null,
      color = if (state?.isTurnedOn == true) StatusColor.Green else StatusColor.White,
      onClick =
      state?.isTurnedOn?.let {
        { onCommand(ClientMessage.SetActivated(target, on = !it)) }
      },
    )
    StatusIconButton(
      if (state?.lowered == true) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
      Modifier.weight(1f),
      active = state?.lowered != null,
      color = if (state?.lowered == true) StatusColor.Green else StatusColor.White,
      onClick =
      state?.lowered?.let {
        { onCommand(ClientMessage.SetLowered(target, on = !it)) }
      },
    )
  }
}
