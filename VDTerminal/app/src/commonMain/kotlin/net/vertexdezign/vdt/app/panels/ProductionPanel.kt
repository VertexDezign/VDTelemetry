package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Factory
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.OutputMode
import net.vertexdezign.vdt.app.components.Centered
import net.vertexdezign.vdt.app.components.ListDetail
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.components.ProgressBar
import net.vertexdezign.vdt.app.i18n.LocalStrings
import net.vertexdezign.vdt.app.i18n.Strings
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.Construction
import net.vertexdezign.vdt.model.ProductionData
import net.vertexdezign.vdt.model.ProductionFill
import net.vertexdezign.vdt.model.ProductionIo
import net.vertexdezign.vdt.model.ProductionLine
import net.vertexdezign.vdt.model.ProductionPoint

/**
 * The Production app full page: a master/detail over the own-farm [ProductionData] channel. The left
 * column lists owned production points (incl. factories, mirroring the game's own "Im Besitz" list);
 * selecting one shows its detail on the right — its lines (status, output mode, per-line input/output
 * storage bars, cycles/costs).
 *
 * Points that are part of one CONSTRUCTION — a Pumps & Hoses biogas plant — are listed under its name
 * rather than loose: the plant's fermenters arrive as one entry and its cogeneration units as another,
 * already merged by the game, and the rest of the plant (bunkers, digestate tank, torch) is drawn from
 * [ProductionData.constructions] into the detail. See ConstructionParts.kt.
 *
 * Production lines can be switched on/off and buffered outputs' distribution mode changed, via
 * [onCommand] (absolute-state commands over the mod command channel). A null [data] means the channel
 * is absent (export off / no data yet) — distinct from an owned-nothing farm, which shows the empty
 * state. Standalone storages live on the sibling [StoragePanel].
 */
@Composable
fun ProductionPanel(data: ProductionData?, modifier: Modifier = Modifier, onCommand: (ClientMessage) -> Unit = {}) {
  val strings = LocalStrings.current
  Panel(title = strings.apps.production, icon = Icons.Filled.Factory, modifier = modifier) {
    when {
      data == null -> Centered(strings.production.waiting)
      data.productionPoints.isEmpty() -> Centered(strings.production.empty)
      else -> ProductionMasterDetail(data, onCommand)
    }
  }
}

@Composable
private fun ProductionMasterDetail(data: ProductionData, onCommand: (ClientMessage) -> Unit) {
  val strings = LocalStrings.current
  // Selection is by id so it survives the ~2 s refreshes; falls back to the first entry when the
  // selected placeable disappears (sold / demolished) or on first render.
  var selectedId by remember { mutableStateOf<String?>(null) }
  val ids = remember(data) { data.productionPoints.map { it.id } }
  val currentId = selectedId.takeIf { it in ids } ?: ids.firstOrNull()

  // Standalone points first, then one titled section per construction. Sections come last because a
  // plant's two entries are only half of what its section says — the header names a building, and a
  // reader scanning for their own greenhouse should not have to cross it.
  val byId = remember(data) { data.constructions.associateBy { it.id } }
  val grouped = remember(data) { data.productionPoints.groupBy { it.construction?.id } }
  val loose = remember(grouped) { grouped[null].orEmpty() }

  var detailOpen by remember { mutableStateOf(false) }
  ListDetail(
    listWidth = 240.dp,
    detailOpen = detailOpen,
    backLabel = strings.production.listTitle,
    onBack = { detailOpen = false },
    list = { listModifier ->
      Column(
        listModifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        loose.forEach { point ->
          OwnedRow(
            name = point.name,
            subtitle = strings.production.lines(point.lines.size),
            selected = point.id == currentId,
            onClick = {
              selectedId = point.id
              detailOpen = true
            },
          )
        }
        data.constructions.forEach { construction ->
          val members = grouped[construction.id].orEmpty()
          if (members.isNotEmpty()) {
            GroupHeader(construction.name)
            members.forEach { point ->
              OwnedRow(
                // The point's own name is the machine's ("BGA (1): Fermenter 30m"), which under the
                // header would repeat the plant and then name one of the three machines the entry
                // actually speaks for. Its role is the honest label.
                name = point.construction?.role?.let { roleLabel(strings, it) } ?: point.name,
                subtitle = strings.production.lines(point.lines.size),
                selected = point.id == currentId,
                onClick = {
                  selectedId = point.id
                  detailOpen = true
                },
              )
            }
          }
        }
      }
    },
  ) {
    val point = data.productionPoints.firstOrNull { it.id == currentId }
    if (point != null) {
      ProductionPointDetail(point, byId[point.construction?.id], onCommand)
    } else {
      Centered(strings.common.selectEntry)
    }
  }
}

// ---- Production point detail ---------------------------------------------------------------------

@Composable
private fun ProductionPointDetail(
  point: ProductionPoint,
  construction: Construction?,
  onCommand: (ClientMessage) -> Unit,
) {
  val strings = LocalStrings.current
  // The shared storage joined by fill type, so each line's inputs/outputs resolve their live level.
  val byType = remember(point) { point.storage.associateBy { it.type } }
  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(point.name, color = VdtColors.TextDark, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    if (point.lines.isEmpty()) {
      Text(strings.production.noLines, color = VdtColors.DarkGray, fontSize = 11.sp)
    }
    // A plant's production carries no name in the game — the DLC keys it by sandbox type and leaves
    // the name blank, so the mod's generic "Line 1" is all there is to print, under a title that
    // already says which machine this is. The role names it honestly instead. Only when there is a
    // single line: a point with several needs each card to say which one it is.
    val single = point.lines.size == 1
    val roleName = point.construction?.role?.takeIf { single }?.let { roleLabel(strings, it) }
    // The DLC's fourth mode exists only inside a plant, so it is offered only there — a base-game
    // production would send the mod a mode the game has no value for.
    val modes = remember(point.construction) {
      OutputMode.entries.filter { it != OutputMode.AUTO_DISTRIBUTION || point.construction != null }
    }
    point.lines.forEach { line -> LineCard(point.id, line, byType, point.isFactory, roleName, modes, onCommand) }
    // The rest of the plant this point is one part of — its bunkers, its tank, its torch. Below the
    // lines, because the point's own recipe is what the reader came for; the plant is the context.
    if (construction != null) ConstructionCard(construction)
  }
}

@Composable
private fun LineCard(
  pointId: String,
  line: ProductionLine,
  storageByType: Map<String, ProductionFill>,
  isFactory: Boolean,
  nameOverride: String? = null,
  modes: List<OutputMode> = OutputMode.entries,
  onCommand: (ClientMessage) -> Unit,
) {
  val strings = LocalStrings.current
  val numbers = strings.numbers
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.Surface.copy(alpha = 0.6f))
      .padding(10.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      // A factory has no on/off state and no live status — it just runs on delivered input. Show a
      // neutral "Factory" tag in place of the toggle/badge so its read-only nature is obvious.
      if (!isFactory) {
        // Tapping the toggle sends the *opposite* of the current state (absolute, idempotent command).
        EnableToggle(line.enabled, onToggle = { onCommand(ClientMessage.SetProductionEnabled(pointId, line.id, it)) })
      }
      Text(
        nameOverride ?: line.name,
        color = VdtColors.TextDark,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = if (isFactory) 0.dp else 8.dp).weight(1f, fill = false),
      )
      Box(Modifier.weight(1f))
      if (isFactory) NeutralTag(strings.production.factory) else StatusBadge(line.status)
    }

    if (line.inputs.isNotEmpty()) {
      IoGroup(strings.production.inputs, line.inputs, storageByType, modes, onSetMode = null)
    }
    if (line.outputs.isNotEmpty()) {
      // A factory's output is sold, not stored/routed — no mode control (onSetMode stays null).
      val onSetMode: ((String, OutputMode) -> Unit)? =
        if (isFactory) {
          null
        } else {
          { fillType, mode -> onCommand(ClientMessage.SetProductionOutputMode(pointId, fillType, mode)) }
        }
      IoGroup(strings.production.outputs, line.outputs, storageByType, modes, onSetMode = onSetMode)
    }

    Text(
      strings.production.perMonth(numbers.integer(line.cyclesPerMonth), numbers.integer(line.costsPerMonth)),
      color = VdtColors.DarkGray,
      fontSize = 10.sp,
      fontWeight = FontWeight.Bold,
    )
  }
}

@Composable
private fun IoGroup(
  label: String,
  io: List<ProductionIo>,
  storageByType: Map<String, ProductionFill>,
  modes: List<OutputMode>,
  onSetMode: ((String, OutputMode) -> Unit)?,
) {
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(label.uppercase(), color = VdtColors.DarkGray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    io.forEach { entry ->
      val fill = storageByType[entry.type]
      IoRow(entry, fill, modes, onSetMode)
    }
  }
}

@Composable
private fun IoRow(
  entry: ProductionIo,
  fill: ProductionFill?,
  modes: List<OutputMode>,
  onSetMode: ((String, OutputMode) -> Unit)?,
) {
  val strings = LocalStrings.current
  val numbers = strings.numbers
  val level = fill?.level ?: 0
  val capacity = fill?.capacity ?: 0
  val fraction = if (capacity > 0) level.toFloat() / capacity.toFloat() else 0f
  // A buffered output (has a mode + a callback) gets an interactive mode dropdown; everything else
  // (inputs, direct-sell outputs) is read-only.
  val mode = entry.mode?.let { OutputMode.fromToken(it) }
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
    // Direct-sell outputs are never buffered in storage — there is no fill bar to show, so the
    // recipe amount + mode carries the row instead.
    if (entry.sellDirectly || fill == null) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
          entry.title,
          color = VdtColors.TextDark,
          fontSize = 11.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )
        entry.mode?.let { ModeTag(it) }
      }
    } else {
      ProgressBar(
        fraction = fraction,
        leftLabel = entry.title,
        rightLabel = strings.common.litres(numbers.integer(level), numbers.integer(capacity)),
      )
      if (mode != null && onSetMode != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          ModeDropdown(current = mode, modes = modes, onSelect = { onSetMode(entry.type, it) })
        }
      } else {
        entry.mode?.let { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { ModeTag(it) } }
      }
    }
  }
}

// ---- Small pieces --------------------------------------------------------------------------------

/** Tappable on/off chip for a production line — green ON / gray OFF; a tap requests the opposite. */
@Composable
private fun EnableToggle(enabled: Boolean, onToggle: (Boolean) -> Unit) {
  val strings = LocalStrings.current.production
  val bg = if (enabled) VdtColors.Green else VdtColors.TrackGray
  val fg = if (enabled) VdtColors.OnFill else VdtColors.DarkGray
  Text(
    if (enabled) strings.on else strings.off,
    color = fg,
    fontSize = 9.sp,
    fontWeight = FontWeight.Bold,
    modifier = Modifier
      .clip(RoundedCornerShape(3.dp))
      .background(bg)
      .clickable { onToggle(!enabled) }
      .padding(horizontal = 8.dp, vertical = 3.dp),
  )
}

/** The current output mode as a tappable chip; the dropdown picks another (absolute-state command). */
@Composable
private fun ModeDropdown(current: OutputMode, modes: List<OutputMode>, onSelect: (OutputMode) -> Unit) {
  val strings = LocalStrings.current
  var expanded by remember { mutableStateOf(false) }
  Box {
    Row(
      Modifier
        .clip(RoundedCornerShape(3.dp))
        .background(VdtColors.TrackGray)
        .clickable { expanded = true }
        .padding(start = 6.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        modeLabel(strings, current.token).uppercase(),
        color = VdtColors.DarkGray,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
      )
      Icon(
        Icons.Filled.ArrowDropDown,
        strings.production.changeMode,
        tint = VdtColors.DarkGray,
        modifier = Modifier.size(14.dp),
      )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      // `current` is always offered even when it is not in `modes`: a mode the game somehow put the
      // production in must stay selectable, or the dropdown becomes a one-way door out of it.
      (if (current in modes) modes else modes + current).forEach { mode ->
        DropdownMenuItem(
          text = { Text(modeLabel(strings, mode.token)) },
          onClick = {
            if (mode != current) onSelect(mode)
            expanded = false
          },
        )
      }
    }
  }
}

@Composable
private fun StatusBadge(status: String) {
  val (color, text) = statusStyle(status)
  Text(
    text.uppercase(),
    color = VdtColors.OnFill,
    fontSize = 9.sp,
    fontWeight = FontWeight.Bold,
    modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(color).padding(horizontal = 6.dp, vertical = 2.dp),
  )
}

/** A muted pill for a non-status label (e.g. "Factory") — same shape as the status badge, greyed. */
@Composable
private fun NeutralTag(text: String) {
  Text(
    text.uppercase(),
    color = VdtColors.DarkGray,
    fontSize = 9.sp,
    fontWeight = FontWeight.Bold,
    modifier = Modifier
      .clip(RoundedCornerShape(3.dp))
      .background(VdtColors.TrackGray)
      .padding(horizontal = 6.dp, vertical = 2.dp),
  )
}

@Composable
private fun ModeTag(mode: String) {
  Text(
    modeLabel(LocalStrings.current, mode).uppercase(),
    color = VdtColors.DarkGray,
    fontSize = 9.sp,
    fontWeight = FontWeight.Bold,
    modifier = Modifier.clip(
      RoundedCornerShape(3.dp),
    ).background(VdtColors.TrackGray).padding(horizontal = 6.dp, vertical = 2.dp),
  )
}

@Composable
@ReadOnlyComposable
private fun statusStyle(status: String): Pair<Color, String> {
  val strings = LocalStrings.current.production
  return when (status) {
    "running" -> VdtColors.Green to strings.statusRunning
    "missingInputs" -> VdtColors.Amber to strings.statusMissingInput
    "noOutputSpace" -> VdtColors.Red to strings.statusNoSpace
    else -> VdtColors.DarkGray to strings.statusInactive
  }
}

/**
 * Chip text for an output distribution mode. `autoDistribution` is the Pumps & Hoses one, which the
 * DLC calls "Distribute across biogas plant" — the same verb as `autoDeliver` above it, and so a
 * longer way of saying what looks like the same thing. "Within plant" is the difference that matters:
 * that one ships to the farm's other productions, this one stays inside the building.
 */
private fun modeLabel(strings: Strings, mode: String): String = when (mode) {
  "directSell" -> strings.production.modeDirectSell
  "autoDeliver" -> strings.production.modeDistribute
  "autoDistribution" -> strings.production.modeWithinPlant
  else -> strings.production.modeKeep
}
