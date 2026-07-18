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
import androidx.compose.material.icons.filled.Warehouse
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.OutputMode
import net.vertexdezign.vdt.app.components.Centered
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.components.ProgressBar
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.ProductionFill
import net.vertexdezign.vdt.model.ProductionIo
import net.vertexdezign.vdt.model.ProductionLine
import net.vertexdezign.vdt.model.ProductionPoint
import net.vertexdezign.vdt.model.ProductionsData
import net.vertexdezign.vdt.model.StandaloneStorage

/**
 * The Productions app full page: a master/detail over the own-farm [ProductionsData] channel. The
 * left column lists owned production points and standalone storages (two sections, mirroring the
 * game's own "Im Besitz" list); selecting one shows its detail on the right — a production point's
 * lines (status, output mode, per-line input/output storage bars, cycles/costs), or a standalone
 * storage's fill levels.
 *
 * Production lines can be switched on/off and buffered outputs' distribution mode changed, via
 * [onCommand] (absolute-state commands over the mod command channel). A null [data] means the
 * channel is absent (export off / no data yet) — distinct from an owned-nothing farm, which shows
 * the empty state.
 */
@Composable
fun ProductionsPanel(data: ProductionsData?, modifier: Modifier = Modifier, onCommand: (ClientMessage) -> Unit = {}) {
  Panel(title = "Productions", icon = Icons.Filled.Factory, modifier = modifier) {
    when {
      data == null -> Centered("Waiting for production data…")

      data.productionPoints.isEmpty() && data.storages.isEmpty() ->
        Centered("No owned productions or storages")

      else -> ProductionsMasterDetail(data, onCommand)
    }
  }
}

@Composable
private fun ProductionsMasterDetail(data: ProductionsData, onCommand: (ClientMessage) -> Unit) {
  // Selection is by id so it survives the ~2 s refreshes; falls back to the first entry when the
  // selected placeable disappears (sold / demolished) or on first render.
  var selectedId by remember { mutableStateOf<String?>(null) }
  val ids = remember(data) { data.productionPoints.map { it.id } + data.storages.map { it.id } }
  val currentId = selectedId.takeIf { it in ids } ?: ids.firstOrNull()

  Row(Modifier.fillMaxSize()) {
    OwnedList(
      data = data,
      selectedId = currentId,
      onSelect = { selectedId = it },
      modifier = Modifier.width(240.dp).fillMaxHeight(),
    )
    Box(Modifier.width(1.dp).fillMaxHeight().background(VdtColors.PanelBorder))
    Box(Modifier.weight(1f).fillMaxHeight().padding(start = 10.dp)) {
      val point = data.productionPoints.firstOrNull { it.id == currentId }
      val storage = data.storages.firstOrNull { it.id == currentId }
      when {
        point != null -> ProductionPointDetail(point, onCommand)
        storage != null -> StandaloneStorageDetail(storage)
        else -> Centered("Select an entry")
      }
    }
  }
}

// ---- Master list ---------------------------------------------------------------------------------

@Composable
private fun OwnedList(
  data: ProductionsData,
  selectedId: String?,
  onSelect: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier.verticalScroll(rememberScrollState()).padding(end = 10.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    if (data.productionPoints.isNotEmpty()) {
      SectionHeader("Productions")
      data.productionPoints.forEach { point ->
        OwnedRow(
          name = point.name,
          subtitle = lineCountLabel(point.lines.size),
          selected = point.id == selectedId,
          onClick = { onSelect(point.id) },
        )
      }
    }
    if (data.storages.isNotEmpty()) {
      SectionHeader("Storages")
      data.storages.forEach { storage ->
        OwnedRow(
          name = storage.name,
          subtitle = typeCountLabel(storage.fills.size),
          selected = storage.id == selectedId,
          onClick = { onSelect(storage.id) },
        )
      }
    }
  }
}

@Composable
private fun SectionHeader(text: String) {
  Text(
    text.uppercase(),
    color = VdtColors.Gray,
    fontSize = 10.sp,
    fontWeight = FontWeight.Bold,
    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
  )
}

@Composable
private fun OwnedRow(name: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
  val bg = if (selected) VdtColors.Green else VdtColors.TrackGray
  val fg = if (selected) VdtColors.White else VdtColors.TextDark
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(bg)
      .clickable(onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 8.dp),
  ) {
    Text(
      name,
      color = fg,
      fontSize = 13.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      subtitle,
      color = if (selected) VdtColors.White.copy(alpha = 0.85f) else VdtColors.Gray,
      fontSize = 10.sp,
    )
  }
}

// ---- Production point detail ---------------------------------------------------------------------

@Composable
private fun ProductionPointDetail(point: ProductionPoint, onCommand: (ClientMessage) -> Unit) {
  // The shared storage joined by fill type, so each line's inputs/outputs resolve their live level.
  val byType = remember(point) { point.storage.associateBy { it.type } }
  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(point.name, color = VdtColors.TextDark, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    if (point.lines.isEmpty()) {
      Text("This production has no production lines", color = VdtColors.Gray, fontSize = 11.sp)
    }
    point.lines.forEach { line -> LineCard(point.id, line, byType, onCommand) }
  }
}

@Composable
private fun LineCard(
  pointId: String,
  line: ProductionLine,
  storageByType: Map<String, ProductionFill>,
  onCommand: (ClientMessage) -> Unit,
) {
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.White.copy(alpha = 0.6f))
      .padding(10.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      // Tapping the toggle sends the *opposite* of the current state (absolute, idempotent command).
      EnableToggle(line.enabled, onToggle = { onCommand(ClientMessage.SetProductionEnabled(pointId, line.id, it)) })
      Text(
        line.name,
        color = VdtColors.TextDark,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false),
      )
      Box(Modifier.weight(1f))
      StatusBadge(line.status)
    }

    if (line.inputs.isNotEmpty()) {
      IoGroup("Inputs", line.inputs, storageByType, onSetMode = null)
    }
    if (line.outputs.isNotEmpty()) {
      IoGroup(
        "Outputs",
        line.outputs,
        storageByType,
        onSetMode = { fillType, mode -> onCommand(ClientMessage.SetProductionOutputMode(pointId, fillType, mode)) },
      )
    }

    Text(
      "${formatInt(line.cyclesPerMonth)} cycles/mo · ${formatInt(line.costsPerMonth)}/mo",
      color = VdtColors.Gray,
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
  onSetMode: ((String, OutputMode) -> Unit)?,
) {
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(label.uppercase(), color = VdtColors.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    io.forEach { entry ->
      val fill = storageByType[entry.type]
      IoRow(entry, fill, onSetMode)
    }
  }
}

@Composable
private fun IoRow(entry: ProductionIo, fill: ProductionFill?, onSetMode: ((String, OutputMode) -> Unit)?) {
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
        rightLabel = "${formatInt(level)} / ${formatInt(capacity)} L",
      )
      if (mode != null && onSetMode != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          ModeDropdown(current = mode, onSelect = { onSetMode(entry.type, it) })
        }
      } else {
        entry.mode?.let { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { ModeTag(it) } }
      }
    }
  }
}

// ---- Standalone storage detail -------------------------------------------------------------------

@Composable
private fun StandaloneStorageDetail(storage: StandaloneStorage) {
  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Icon(
        Icons.Filled.Warehouse,
        contentDescription = null,
        tint = VdtColors.DarkGray,
        modifier = Modifier.size(16.dp),
      )
      Text(storage.name, color = VdtColors.TextDark, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
    if (storage.fills.isEmpty()) {
      Text("This storage is empty", color = VdtColors.Gray, fontSize = 11.sp)
    }
    storage.fills.forEach { fill -> FillRow(fill) }
  }
}

@Composable
private fun FillRow(fill: ProductionFill) {
  val fraction = if (fill.capacity > 0) fill.level.toFloat() / fill.capacity.toFloat() else 0f
  ProgressBar(
    fraction = fraction,
    leftLabel = fill.title,
    rightLabel = "${formatInt(fill.level)} / ${formatInt(fill.capacity)} L",
  )
}

// ---- Small shared pieces -------------------------------------------------------------------------

/** Tappable on/off chip for a production line — green ON / gray OFF; a tap requests the opposite. */
@Composable
private fun EnableToggle(enabled: Boolean, onToggle: (Boolean) -> Unit) {
  val bg = if (enabled) VdtColors.Green else VdtColors.TrackGray
  val fg = if (enabled) VdtColors.White else VdtColors.DarkGray
  Text(
    if (enabled) "ON" else "OFF",
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
private fun ModeDropdown(current: OutputMode, onSelect: (OutputMode) -> Unit) {
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
        modeLabel(current.token).uppercase(),
        color = VdtColors.DarkGray,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
      )
      Icon(Icons.Filled.ArrowDropDown, "change mode", tint = VdtColors.DarkGray, modifier = Modifier.size(14.dp))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      OutputMode.entries.forEach { mode ->
        DropdownMenuItem(
          text = { Text(modeLabel(mode.token)) },
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
    color = VdtColors.White,
    fontSize = 9.sp,
    fontWeight = FontWeight.Bold,
    modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(color).padding(horizontal = 6.dp, vertical = 2.dp),
  )
}

@Composable
private fun ModeTag(mode: String) {
  Text(
    modeLabel(mode).uppercase(),
    color = VdtColors.DarkGray,
    fontSize = 9.sp,
    fontWeight = FontWeight.Bold,
    modifier = Modifier.clip(
      RoundedCornerShape(3.dp),
    ).background(VdtColors.TrackGray).padding(horizontal = 6.dp, vertical = 2.dp),
  )
}

private fun statusStyle(status: String): Pair<Color, String> = when (status) {
  "running" -> VdtColors.Green to "Running"
  "missingInputs" -> VdtColors.Amber to "Missing input"
  "noOutputSpace" -> VdtColors.Red to "Out of space"
  else -> VdtColors.Gray to "Inactive"
}

private fun modeLabel(mode: String): String = when (mode) {
  "directSell" -> "Direct sell"
  "autoDeliver" -> "Distribute"
  else -> "Keep"
}

private fun lineCountLabel(n: Int): String = if (n == 1) "1 line" else "$n lines"

private fun typeCountLabel(n: Int): String = if (n == 1) "1 fill type" else "$n fill types"

/** Group a non-negative liter count with thousands separators (e.g. 145000 -> "145,000"). */
private fun formatInt(value: Int): String {
  val digits = value.toString()
  if (digits.length <= 3) return digits
  val sb = StringBuilder()
  val firstGroup = digits.length % 3
  if (firstGroup > 0) {
    sb.append(digits, 0, firstGroup)
  }
  var i = firstGroup
  while (i < digits.length) {
    if (sb.isNotEmpty()) sb.append(',')
    sb.append(digits, i, i + 3)
    i += 3
  }
  return sb.toString()
}
