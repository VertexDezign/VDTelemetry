package net.vertexdezign.vdt.app.panels

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.BalePart
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.ControlTarget
import net.vertexdezign.vdt.app.components.FillUnitsDisplay
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.BaleDoor
import net.vertexdezign.vdt.model.BaleType
import net.vertexdezign.vdt.model.BaleUnloadAction
import net.vertexdezign.vdt.model.BaleWrapper
import net.vertexdezign.vdt.model.Baler
import net.vertexdezign.vdt.model.FillUnit
import net.vertexdezign.vdt.model.FillUnits
import net.vertexdezign.vdt.model.WrapperState
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Below this the picture is dropped rather than shrunk — the combine's rule, for the same reason. */
private val MIN_ART_HEIGHT = 56.dp

/** Three columns only from this width, and only while a flank still holds a figure on one line. */
private val THREE_COLUMN_FROM = 420.dp
private val FLANK_MIN = 110.dp
private val COLUMN_GAP = 12.dp

/** The share of the width the machine may take; past it the readouts are worth more. */
private const val ART_SHARE = 0.56f

/** What the chip strip along the bottom is reserved, as a sibling of the columns. */
private val BOTTOM_STRIP_HEIGHT = 30.dp

/** Below this the flanks drop a type size and their sub-lines. See the combine's COMPACT_BELOW. */
private val COMPACT_BELOW = 170.dp

/**
 * The baler screen — round, square, a baler-wrapper, or a standalone wrapper: the machine in the
 * middle, the bale on the left, the count and the rolls on the right, and the controls along the
 * bottom.
 *
 * One machine, unlike the combine's two: a baler-wrapper carries both aspects on the same node, so
 * [target] addresses both and nothing here has to find the other half.
 *
 * Measures itself and thins out rather than taking a "compact" flag, like every other panel here.
 */
@Composable
internal fun BalerSection(
  machine: IsoBusMachine,
  target: ControlTarget?,
  onCommand: (ClientMessage) -> Unit,
  modifier: Modifier = Modifier,
) {
  val picture = balerPicture(machine) ?: return
  BoxWithConstraints(modifier) {
    val bodyWidth = maxWidth
    val bodyHeight = maxHeight
    val columnHeight = bodyHeight - BOTTOM_STRIP_HEIGHT
    val compact = columnHeight < COMPACT_BELOW

    val artWidth = minOf((bodyWidth - COLUMN_GAP * 2) * ART_SHARE, columnHeight * BALER_ART_ASPECT)
    val showArt = artWidth / BALER_ART_ASPECT >= MIN_ART_HEIGHT
    // Without the picture the row is two columns and one gap, and the flanks share all of it.
    val flank = if (showArt) (bodyWidth - COLUMN_GAP * 2 - artWidth) / 2 else (bodyWidth - COLUMN_GAP) / 2
    val threeColumn = bodyWidth >= THREE_COLUMN_FROM && flank >= FLANK_MIN

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      if (threeColumn) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP)) {
          BaleColumn(machine, compact, Modifier.width(flank).fillMaxHeight())
          if (showArt) {
            Box(Modifier.width(artWidth).fillMaxHeight(), contentAlignment = Alignment.Center) {
              BalerArt(picture, Modifier.fillMaxWidth().height(artWidth / BALER_ART_ASPECT))
            }
          }
          CountColumn(machine, target, onCommand, compact, Modifier.width(flank).fillMaxHeight())
        }
      } else {
        // Narrow: stacked, the picture between the two readouts and the first thing to go.
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP)) {
            BaleColumn(machine, compact = true, Modifier.weight(1f))
            CountColumn(machine, target, onCommand, compact = true, Modifier.weight(1f))
          }
          if (bodyHeight >= MIN_ART_HEIGHT * 3) {
            BalerArt(picture, Modifier.weight(1f).fillMaxWidth())
          }
        }
      }
      BalerControls(machine, target, onCommand, Modifier.fillMaxWidth())
    }
  }
}

// ---------------------------------------------------------------------------
// Left: the bale
// ---------------------------------------------------------------------------

/**
 * The bale being formed, the size it is being formed at, and what is still on the machine. On a
 * standalone wrapper, which forms nothing, the wrap in its place.
 */
@Composable
private fun BaleColumn(machine: IsoBusMachine, compact: Boolean, modifier: Modifier = Modifier) {
  val gap = if (compact) 5.dp else 8.dp
  Column(modifier, verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically)) {
    val units = FillUnits(machine.fillUnits)
    val baler = machine.baler
    if (baler != null) {
      val chamber = baler.chamberIn(units)
      Figure(
        "Bale",
        chamber?.let { "${it.fillLevelPercentage}%" } ?: "-",
        chamber?.takeIf {
          !compact
        }?.let { "${formatInt(it.value.roundToInt())} of ${formatInt(it.capacity)} ${it.unit}".trim() },
        compact,
      )
      baler.currentBaleType?.let { size ->
        val next = baler.nextBaleType?.let { baler.baleTypes.getOrNull(it - 1) }
        Figure(
          "Size",
          baleSizeLabel(size),
          if (next != null) {
            "${baleSizeLabel(next)} after this bale"
          } else if (compact) {
            null
          } else {
            baleShapeLabel(size)
          },
          compact,
        )
      }
      balesOnMachine(baler, units)?.let { (label, value) -> Figure(label, value, null, compact) }
      // A non-stop baler's pre-chamber, which keeps taking crop while the chamber ties and drops.
      baler.buffer?.let { buffer ->
        units.at(buffer.fillUnit)?.let { unit ->
          Figure(
            "Buffer",
            "${unit.fillLevelPercentage}%",
            if (buffer.overloading) "emptying into the chamber" else null,
            compact,
          )
        }
      }
    } else {
      machine.baleWrapper?.let { wrapper ->
        Figure(
          "Wrap",
          wrapper.progress?.let {
            "${(it * 100).roundToInt()}%"
          } ?: "-",
          wrapperStateLabel(wrapper),
          compact,
        )
      }
    }
  }
}

/** What is on the machine besides the bale forming: the collector's rack, or the channel's bales. */
internal fun balesOnMachine(baler: Baler, units: FillUnits): Pair<String, String>? {
  val rack = baler.collector?.let { units.at(it.fillUnit) }
  if (rack != null) return "Collector" to "${rack.value.roundToInt()} / ${rack.capacity}"
  if (!baler.round && baler.bales.isNotEmpty()) return "In channel" to "${baler.bales.size}"
  return null
}

/**
 * A size as the game's own "change bale size" prompt gives it: a round bale by its diameter, a
 * square one by its length — the one dimension each machine actually lets you change.
 */
internal fun baleSizeLabel(type: BaleType): String {
  val metres = type.diameter ?: type.length ?: type.width
  return "${formatCm(metres)} cm"
}

private fun baleShapeLabel(type: BaleType): String =
  if (type.diameter != null) "diameter" else "long, ${formatCm(type.width)} x ${formatCm(type.height ?: 0.0)} cm"

private fun formatCm(metres: Double): String = (metres * 100).roundToInt().toString()

// ---------------------------------------------------------------------------
// Right: the count and the rolls
// ---------------------------------------------------------------------------

/**
 * The bale count, big, with its reset under it — and the net, twine and film, in rolls, because a
 * roll is what the driver goes to the trailer for.
 */
@Composable
private fun CountColumn(
  machine: IsoBusMachine,
  target: ControlTarget?,
  onCommand: (ClientMessage) -> Unit,
  compact: Boolean,
  modifier: Modifier = Modifier,
) {
  val gap = if (compact) 5.dp else 8.dp
  val units = FillUnits(machine.fillUnits)
  Column(modifier, verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically)) {
    machine.baleCounter?.let { counter ->
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Figure(
          "Bales",
          formatInt(counter.session),
          if (compact) null else "${formatInt(counter.lifetime)} total",
          compact,
        )
        // Gated on power, as the game's key is; the lifetime count has no reset anywhere.
        Chip(
          Icons.Filled.Refresh,
          "Reset",
          VdtColors.TextDark,
          onClick = target?.takeIf { machine.baler?.powered == true && counter.session > 0 }?.let {
            { onCommand(ClientMessage.ResetBaleCounter(it)) }
          },
          control = true,
        )
      }
    }
    val baler = machine.baler
    baler?.consumableIn(units)?.let { RollFigure(if (baler.round) "Net" else "Twine", it, compact) }
    machine.baleWrapper?.consumableIn(units)?.let { RollFigure("Film", it, compact) }
    // Whatever else is on the machine (an additive tank), where there is height for it.
    if (!compact) {
      val others = otherUnits(machine)
      if (others.isNotEmpty()) FillUnitsDisplay(others, Modifier.fillMaxWidth(), spacing = 4)
    }
  }
}

@Composable
private fun RollFigure(label: String, unit: FillUnit, compact: Boolean) {
  Figure(label, rollsLabel(unit), if (compact) null else "rolls", compact)
}

/**
 * A consumable in rolls, as the game's own stepped display counts them: the part-used roll on the
 * machine is a roll, so 1.9 of 2 reads "2 / 2" — `value` is fractional precisely because it carries
 * that part-used roll.
 */
internal fun rollsLabel(unit: FillUnit): String = "${ceil(unit.value - 0.0005f).toInt()} / ${unit.capacity}"

// ---------------------------------------------------------------------------
// Bottom: the controls
// ---------------------------------------------------------------------------

/**
 * Every control this screen offers, as chips — a chip that names a state the app can change IS the
 * control for it (the #116 rule), so nothing here says the same thing twice.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BalerControls(
  machine: IsoBusMachine,
  target: ControlTarget?,
  onCommand: (ClientMessage) -> Unit,
  modifier: Modifier = Modifier,
) {
  FlowRow(
    modifier,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    machine.baler?.let { baler ->
      BaleSizeChip(baler, target, onCommand)
      baler.autoDrop?.let {
        AutoDropChip("Auto drop", it.on, it.canToggle && baler.powered, target, BalePart.BALER, onCommand)
      }
      baler.unload?.let { action ->
        Chip(
          Icons.Filled.Download,
          unloadLabel(action),
          VdtColors.TextDark,
          onClick = target?.let { { onCommand(ClientMessage.UnloadBale(it, action)) } },
          control = true,
        )
      }
    }
    machine.baleWrapper?.let { wrapper ->
      AutoDropChip(
        if (machine.baler != null) "Auto drop wrapped" else "Auto drop",
        wrapper.autoDrop.on,
        wrapper.autoDrop.canToggle,
        target,
        BalePart.WRAPPER,
        onCommand,
      )
      if (wrapper.state == WrapperState.WRAPPED && !wrapper.autoDrop.on) {
        Chip(
          Icons.Filled.VerticalAlignBottom,
          "Drop wrapped bale",
          VdtColors.TextDark,
          onClick = target?.takeIf { wrapper.canDrop }?.let { { onCommand(ClientMessage.DropWrappedBale(it)) } },
          control = true,
        )
      }
      if (wrapper.unsupportedBale) Chip(Icons.Filled.WarningAmber, "Bale cannot be wrapped", VdtColors.Amber)
    }
  }
}

/** The game's words for its drop key, one per action it offers. */
internal fun unloadLabel(action: BaleUnloadAction): String = when (action) {
  BaleUnloadAction.UNLOAD -> "Drop bale"
  BaleUnloadAction.UNLOAD_UNFINISHED -> "Drop unfinished bale"
  BaleUnloadAction.CLOSE -> "Close tailgate"
  BaleUnloadAction.DROP_PLATFORM -> "Tip platform"
}

/**
 * Auto-drop, on or off. The state is the box's **shape** — ticked or empty — and the word stays the
 * same, so the two states differ in form and never in hue alone.
 */
@Composable
private fun AutoDropChip(
  label: String,
  on: Boolean,
  canToggle: Boolean,
  target: ControlTarget?,
  part: BalePart,
  onCommand: (ClientMessage) -> Unit,
) {
  Chip(
    if (on) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
    label,
    if (on) VdtColors.AccentText else VdtColors.DarkGray,
    onClick = target?.takeIf { canToggle }?.let { { onCommand(ClientMessage.SetBaleAutoDrop(it, part, on = !on)) } },
    control = canToggle,
  )
}

/**
 * The bale size, as a dropdown of the sizes the machine offers — absolute, where the game's key steps
 * to the next one. Read-only on a machine with one size, which the game offers no key for either.
 */
@Composable
private fun BaleSizeChip(baler: Baler, target: ControlTarget?, onCommand: (ClientMessage) -> Unit) {
  val current = baler.currentBaleType ?: return
  val shown = baler.nextBaleType?.let { baler.baleTypes.getOrNull(it - 1) } ?: current
  val choosable = baler.baleTypes.size > 1
  var open by remember { mutableStateOf(false) }
  Box {
    Chip(
      if (choosable) Icons.Filled.ArrowDropDown else Icons.Filled.Straighten,
      baleSizeLabel(shown),
      VdtColors.TextDark,
      onClick = target?.takeIf { choosable && baler.powered }?.let { { open = true } },
      control = choosable,
    )
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      baler.baleTypes.forEachIndexed { i, type ->
        val index = i + 1
        val chosen = index == (baler.nextBaleType ?: baler.baleType)
        DropdownMenuItem(
          text = {
            Text(
              baleSizeLabel(type),
              fontSize = 12.sp,
              fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
              color = VdtColors.TextDark,
            )
          },
          leadingIcon = if (chosen) {
            { Icon(Icons.Filled.Check, contentDescription = null, tint = VdtColors.TextDark) }
          } else {
            null
          },
          onClick = {
            open = false
            // Asked again here, not only when the menu opened: the motor can stop while it is open, and
            // the mod would drop the command without a word.
            if (!chosen && baler.powered) target?.let { onCommand(ClientMessage.SetBaleType(it, index)) }
          },
        )
      }
    }
  }
}

// ---------------------------------------------------------------------------
// The header chip
// ---------------------------------------------------------------------------

/**
 * What the machine is doing, in the panel header — the combine's harvest chip and the mixer's mix
 * state in the same slot. Glyph and word both change with the state, so nothing rests on the tint.
 */
@Composable
internal fun RowScope.BalerStateChip(machine: IsoBusMachine) {
  // Held like the art's rotor, so "Baling" does not flicker to "Idle" on every thin spot in the swath.
  val working = heldWorking(machine.baler?.working == true)
  val held = machine.baler?.let { machine.copy(baler = it.copy(working = working)) } ?: machine
  val (icon, label, active) = balerState(held) ?: return
  Chip(icon, label, if (active) VdtColors.AccentText else VdtColors.DarkGray)
}

internal data class BalerStateLabel(val icon: ImageVector, val label: String, val active: Boolean)

/** The one line the header says. The wrapper wins while it is busy: that is what is moving. */
internal fun balerState(machine: IsoBusMachine): BalerStateLabel? {
  val baler = machine.baler
  val wrapper = machine.baleWrapper
  if (wrapper != null && (baler == null || wrapper.state != WrapperState.EMPTY)) {
    val label = wrapperStateLabel(wrapper)
    return when (wrapper.state) {
      WrapperState.WRAPPING -> BalerStateLabel(Icons.Filled.Sync, label, true)
      WrapperState.WRAPPED -> BalerStateLabel(Icons.Filled.Check, label, true)
      WrapperState.LOADING, WrapperState.LOADED -> BalerStateLabel(Icons.Filled.ArrowUpward, label, true)
      WrapperState.DROPPING, WrapperState.RESETTING -> BalerStateLabel(Icons.Filled.VerticalAlignBottom, label, true)
      WrapperState.EMPTY -> BalerStateLabel(Icons.Filled.Pause, label, false)
    }
  }
  if (baler == null) return null
  return when {
    baler.door == BaleDoor.OPENING || baler.door == BaleDoor.OPEN -> BalerStateLabel(
      Icons.Filled.ArrowUpward,
      "Tailgate open",
      true,
    )

    baler.door == BaleDoor.CLOSING -> BalerStateLabel(Icons.Filled.ArrowDownward, "Tailgate closing", true)

    baler.round && baler.bales.isNotEmpty() -> BalerStateLabel(Icons.Filled.Check, "Bale ready", true)

    baler.working -> BalerStateLabel(Icons.Filled.Grass, "Baling", true)

    else -> BalerStateLabel(Icons.Filled.Pause, "Idle", false)
  }
}

internal fun wrapperStateLabel(wrapper: BaleWrapper): String = when (wrapper.state) {
  WrapperState.EMPTY -> "Waiting for a bale"
  WrapperState.LOADING -> "Loading"
  WrapperState.LOADED -> "Bale on table"
  WrapperState.WRAPPING -> "Wrapping"
  WrapperState.WRAPPED -> "Wrapped"
  WrapperState.DROPPING -> "Dropping"
  WrapperState.RESETTING -> "Table returning"
}

/**
 * The units this screen does not draw itself — a silage-additive tank, anything else a modder bolted
 * on. The generic fill-unit block stands down on a machine with a section, so without this they would
 * leave the screen entirely (the combine's `otherUnits` hole).
 */
internal fun otherUnits(machine: IsoBusMachine): List<FillUnit> {
  val units = FillUnits(machine.fillUnits)
  val drawn = listOfNotNull(
    machine.baler?.fillUnit,
    machine.baler?.consumable,
    machine.baler?.buffer?.fillUnit,
    machine.baler?.collector?.fillUnit,
    machine.baleWrapper?.consumable,
  ).toSet()
  return units.fillUnit.filterIndexed { i, _ -> (i + 1) !in drawn }
}
