package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.Place
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.components.ActionIcon
import net.vertexdezign.vdt.app.components.Centered
import net.vertexdezign.vdt.app.components.FilterChip
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.components.SearchField
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.FieldInfoData
import net.vertexdezign.vdt.model.FieldStatusData
import net.vertexdezign.vdt.model.LayerKind
import net.vertexdezign.vdt.model.MapData
import net.vertexdezign.vdt.model.MissionsData
import kotlin.math.roundToInt

/**
 * The Fields app full page: every field on the map as a row you can filter and sort, with what each
 * one is in and what it is asking for.
 *
 * Deliberately **not** a mode of the Map app. The map already answers "what is this field" through
 * its popup; what an overview adds is sorting and filtering *across* fields, which a popup cannot do.
 * The two cross-link instead: a row can put its field on the map ([onShowOnMap]).
 *
 * It also diverges from that popup on purpose. The popup mirrors the game's own FELDINFO panel and
 * should keep doing so; this is a working view for a Precision Farming save, where the vanilla
 * fertiliser and lime readings are withheld by the mod (it mirrors the game, which hides them under
 * PF) and the weed reading is not worth a row. So there are no fertiliser/lime/weed lines here, and
 * nothing is suggested off them.
 *
 * A null [status] is "no raster yet", not "no mod": the app holds the growth subscription while this
 * screen is open, and the mod's first full sweep after that takes seconds. The rows say so rather
 * than drawing a zeroed breakdown.
 */
@Composable
fun FieldsPanel(
  map: MapData?,
  info: FieldInfoData?,
  status: FieldStatusData?,
  missions: MissionsData?,
  playerFarmId: Int?,
  modifier: Modifier = Modifier,
  onShowOnMap: (Float, Float) -> Unit = { _, _ -> },
) {
  Panel(title = "Fields", icon = Icons.Filled.Grass, modifier = modifier) {
    val rows = remember(map, info, status, missions, playerFarmId) {
      fieldRows(map, info, status, missions, playerFarmId)
    }
    when {
      map == null -> Centered("Waiting for map data…")
      rows.isEmpty() -> Centered("This map has no fields")
      else -> FieldsMasterDetail(rows, status, onShowOnMap)
    }
  }
}

@Composable
private fun FieldsMasterDetail(rows: List<FieldRow>, status: FieldStatusData?, onShowOnMap: (Float, Float) -> Unit) {
  var query by remember { mutableStateOf("") }
  var view by remember { mutableStateOf(FieldView.ALL) }
  var sort by remember { mutableStateOf(FieldSort.NUMBER) }
  var ascending by remember { mutableStateOf(true) }
  var selectedId by remember { mutableStateOf<Int?>(null) }

  // The offered views follow the data, so buying the last unowned field drops the reader back to ALL
  // rather than leaving them on an empty list they cannot explain.
  val views = remember(rows) { fieldViews(rows) }
  val activeView = if (view in views) view else FieldView.ALL
  val shown = remember(rows, query, activeView, sort, ascending) {
    fieldSorted(fieldSearch(fieldView(rows, activeView), query), sort, ascending)
  }
  val currentId = selectedId.takeIf { id -> shown.any { it.id == id } } ?: shown.firstOrNull()?.id
  val selected = shown.firstOrNull { it.id == currentId }
  val summary = remember(rows, status) { fieldsSummary(rows, status) }

  Column(Modifier.fillMaxSize()) {
    FieldsSummaryRow(summary)
    Spacer(Modifier.height(8.dp))
    FieldsControls(
      query = query,
      views = views,
      view = activeView,
      sort = sort,
      ascending = ascending,
      onQuery = { query = it },
      onView = { view = it },
      onSort = { sort = it },
      onDirection = { ascending = !ascending },
    )
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxSize()) {
      Box(Modifier.width(300.dp).fillMaxHeight().padding(end = 10.dp)) {
        if (shown.isEmpty()) {
          Centered("Nothing matches")
        } else {
          // A played-in map is 80-odd fields, so this is lazy for the same reason the fleet list is.
          LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(shown, key = { it.id }) { row ->
              FieldRowTile(row, status, row.id == currentId) { selectedId = row.id }
            }
          }
        }
      }
      Box(Modifier.width(1.dp).fillMaxHeight().background(VdtColors.PanelBorder))
      Box(Modifier.weight(1f).fillMaxHeight().padding(start = 10.dp)) {
        if (selected != null) {
          FieldDetail(selected, status, onShowOnMap)
        } else {
          Centered("Select a field")
        }
      }
    }
  }
}

// ---- The summary line ----------------------------------------------------------------------------

/**
 * What a player reads first: their own ground, not the map's.
 *
 * The ready figure is withheld rather than shown as zero while nothing has been sampled — "0 ha ready"
 * and "I haven't looked yet" are different statements, and only one of them should send anyone home.
 */
@Composable
private fun FieldsSummaryRow(summary: FieldsSummary) {
  FlowRow(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    SummaryStat("${summary.ownedFields}", "own fields")
    SummaryStat(formatHa(summary.ownedHa), "hectares")
    SummaryStat(summary.readyHa?.let { formatHa(it) } ?: "—", "ready to harvest")
    SummaryStat("${summary.needsWork}", "need work")
    SummaryStat("${summary.forSale}", "for sale")
    if (summary.byCrop.isNotEmpty()) {
      // The crops, biggest first, as one quiet line — it answers "what am I growing" without a chart.
      Text(
        summary.byCrop.joinToString(" · ") { (crop, ha) -> "$crop ${formatHa(ha)}" },
        color = VdtColors.DarkGray,
        fontSize = 10.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.align(Alignment.CenterVertically),
      )
    }
  }
}

@Composable
private fun SummaryStat(value: String, label: String) {
  Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(value, color = VdtColors.TextDark, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    Text(label, color = VdtColors.DarkGray, fontSize = 10.sp, modifier = Modifier.padding(bottom = 2.dp))
  }
}

// ---- Controls ------------------------------------------------------------------------------------

@Composable
private fun FieldsControls(
  query: String,
  views: List<FieldView>,
  view: FieldView,
  sort: FieldSort,
  ascending: Boolean,
  onQuery: (String) -> Unit,
  onView: (FieldView) -> Unit,
  onSort: (FieldSort) -> Unit,
  onDirection: () -> Unit,
) {
  Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    SearchField(query, "Search fields", onQuery, Modifier.width(150.dp))
    FlowRow(
      Modifier.weight(1f),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      views.forEach { entry -> FilterChip(entry.label, view == entry, { onView(entry) }) }
    }
    FieldSortControl(sort, ascending, onSort, onDirection)
  }
}

@Composable
private fun FieldSortControl(
  sort: FieldSort,
  ascending: Boolean,
  onSort: (FieldSort) -> Unit,
  onDirection: () -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    Box {
      Row(
        Modifier
          .clip(RoundedCornerShape(4.dp))
          .background(VdtColors.TrackGray)
          .clickable { open = true }
          .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(sort.label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = VdtColors.DarkGray)
        Icon(
          Icons.Filled.ArrowDropDown,
          contentDescription = null,
          tint = VdtColors.DarkGray,
          modifier = Modifier.size(14.dp),
        )
      }
      DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        FieldSort.entries.forEach { entry ->
          DropdownMenuItem(
            text = { Text(entry.label, fontSize = 12.sp) },
            onClick = {
              onSort(entry)
              open = false
            },
          )
        }
      }
    }
    // An Icon, never an arrow character: the wasm build has no font fallback and would draw one as
    // tofu (see VDTerminal/README.md -> "Design rules").
    Icon(
      if (ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
      contentDescription = if (ascending) "Ascending" else "Descending",
      tint = VdtColors.DarkGray,
      modifier = Modifier
        .size(24.dp)
        .clip(RoundedCornerShape(4.dp))
        .background(VdtColors.TrackGray)
        .clickable(onClick = onDirection)
        .padding(4.dp),
    )
  }
}

// ---- The row -------------------------------------------------------------------------------------

@Composable
private fun FieldRowTile(row: FieldRow, status: FieldStatusData?, selected: Boolean, onClick: () -> Unit) {
  val fg = if (selected) VdtColors.White else VdtColors.TextDark
  val muted = if (selected) VdtColors.White.copy(alpha = 0.85f) else VdtColors.DarkGray
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(if (selected) VdtColors.Green else VdtColors.TrackGray)
      .clickable(onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 7.dp),
    verticalArrangement = Arrangement.spacedBy(3.dp),
  ) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(
        row.label,
        color = fg,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Text(formatHa(row.mapField.areaHa), color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
    Text(rowLine(row), color = muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (hasBreakdown(row)) StatusBar(row, Modifier.fillMaxWidth())
    val badges = fieldBadges(row)
    if (badges.isNotEmpty()) {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        badges.forEach { badge -> FieldBadge(badge, selected) }
      }
    }
    // Suppressed on the selected row: the detail beside it says the same thing with the numbers.
    if (!selected && status == null) {
      Text("sampling…", color = muted, fontSize = 9.sp)
    }
  }
}

/** Crop and status in one line — what the field is growing and what state that is in. */
private fun rowLine(row: FieldRow): String = buildList {
  val crop = fieldCrop(row)
  if (crop.isNotEmpty()) add(crop)
  val headline = fieldHeadline(row)
  if (headline.text.isNotEmpty()) add(headline.text)
  row.mapField.price?.takeIf { row.mapField.ownerFarmId == null }?.let { add(formatMoney(it.toLong())) }
}.joinToString(" · ")

/**
 * The words a row wears. Words rather than colours, and short: what a badge says has to survive being
 * read by someone who cannot tell the amber one from the green one.
 */
internal fun fieldBadges(row: FieldRow): List<String> = buildList {
  if (row.mapField.ownerFarmId == null) {
    add("FOR SALE")
  } else if (!row.owned) {
    add("OTHER FARM")
  }
  row.mission?.let { add(if (it.isActive) "CONTRACT" else "CONTRACT OFFER") }
  fieldWork(row).forEach { add(it.uppercase()) }
}

@Composable
private fun FieldBadge(label: String, selected: Boolean) {
  Text(
    label,
    fontSize = 8.sp,
    fontWeight = FontWeight.Bold,
    color = if (selected) VdtColors.Green else VdtColors.White,
    modifier = Modifier
      .clip(RoundedCornerShape(3.dp))
      .background(if (selected) VdtColors.White else VdtColors.DarkGray)
      .padding(horizontal = 4.dp, vertical = 2.dp),
  )
}

// ---- The breakdown -------------------------------------------------------------------------------

/**
 * A fill per ground state.
 *
 * **Colour reinforces here, it never decides.** Every segment is named in the legend beneath the bar
 * and the row's headline is a word, so the bar can be read with no colour vision at all — which is the
 * standing rule for this app (see VDTerminal/README.md -> "Design rules"). The tones are chosen to
 * differ in brightness as well as hue for the same reason.
 */
private fun kindColor(kind: String): Color = when (LayerKind.of(kind)) {
  LayerKind.HARVEST -> VdtColors.Amber
  LayerKind.GROWING -> VdtColors.Green
  LayerKind.SEEDBED -> VdtColors.Accent
  LayerKind.TOPPING -> VdtColors.AccentText
  LayerKind.CUT -> VdtColors.DarkGray
  LayerKind.WITHERED -> VdtColors.Red
  LayerKind.PLOWED -> VdtColors.TextDisabled
  LayerKind.CULTIVATED -> VdtColors.ProgressBlue
  LayerKind.STUBBLE -> VdtColors.Gray
  else -> VdtColors.PanelBorder
}

/** The stacked bar itself: one segment per slice, widths in proportion to their share. */
@Composable
private fun StatusBar(row: FieldRow, modifier: Modifier = Modifier) {
  val status = row.status ?: return
  Row(modifier.height(6.dp).clip(RoundedCornerShape(3.dp)).background(VdtColors.PanelBorder)) {
    status.slices.forEach { slice ->
      // weight() on the share rather than a computed width: the segments then fill the row exactly,
      // with no rounding gap at the end that would read as a state nobody named.
      Box(Modifier.weight(slice.cells.toFloat()).fillMaxHeight().background(kindColor(slice.kind)))
    }
  }
}

/** The bar's legend — the half that carries the meaning. */
@Composable
private fun StatusLegend(row: FieldRow, status: FieldStatusData?) {
  val breakdown = row.status ?: return
  FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
    breakdown.slices.forEach { slice ->
      val share = (breakdown.fraction(slice.cells) * 100).roundToInt()
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(kindColor(slice.kind)))
        Text(kindLabel(slice.kind), color = VdtColors.TextDark, fontSize = 10.sp)
        Text("$share%", color = VdtColors.DarkGray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        status?.let { Text(formatHa(it.ha(slice.cells)), color = VdtColors.DarkGray, fontSize = 10.sp) }
      }
    }
  }
}

// ---- The detail ----------------------------------------------------------------------------------

@Composable
private fun FieldDetail(row: FieldRow, status: FieldStatusData?, onShowOnMap: (Float, Float) -> Unit) {
  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("Field ${row.label}", color = VdtColors.TextDark, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(ownershipLine(row), color = VdtColors.DarkGray, fontSize = 11.sp)
      }
      ActionIcon(
        Icons.Filled.Place,
        "Show on map",
        VdtColors.DarkGray,
        { onShowOnMap(row.mapField.labelX, row.mapField.labelZ) },
      )
    }

    DetailSection("Ground") {
      val headline = fieldHeadline(row)
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(headline.text, color = VdtColors.TextDark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        // Say which of the two answered. The raster is the whole field; the point sample is its
        // middle, and a reader deciding whether to drive out there deserves to know which they have.
        Text(
          if (headline.fromRaster) "across the field" else "at the field centre",
          color = VdtColors.DarkGray,
          fontSize = 10.sp,
        )
      }
      when {
        hasBreakdown(row) -> {
          StatusBar(row, Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp))
          StatusLegend(row, status)
        }

        status == null ->
          Text(
            "Sampling the ground — the first sweep takes a few seconds.",
            color = VdtColors.DarkGray,
            fontSize = 10.sp,
          )

        else ->
          Text(
            "Too small to break down; showing the reading at the field centre.",
            color = VdtColors.DarkGray,
            fontSize = 10.sp,
          )
      }
    }

    val info = row.info
    if (info != null) {
      DetailSection("Crop") {
        DetailLine("Crop", info.crop.ifBlank { "none" })
        if (info.maxGrowthState > 0) DetailLine("Growth", "${info.growthState} / ${info.maxGrowthState}")
        info.yieldBonusPercent?.let { DetailLine("Yield bonus", "+ $it %") }
      }
      val work = fieldWork(row)
      if (work.isNotEmpty()) {
        DetailSection("Asking for") {
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            work.forEach { FieldBadge(it.uppercase(), selected = false) }
          }
        }
      }
      val rotation = info.cropRotation
      if (rotation != null) {
        DetailSection("Rotation") {
          if (rotation.lastCrop.isNotBlank()) DetailLine("Last crop", rotation.lastCrop)
          if (rotation.prevCrop.isNotBlank()) DetailLine("Before that", rotation.prevCrop)
          rotation.yieldPercent?.let { DetailLine("Rotation yield", "$it %") }
          rotation.catchCrop?.let { DetailLine("Catch crop", it) }
        }
      }
    }

    val mission = row.mission
    if (mission != null) {
      DetailSection("Contract") {
        DetailLine(
          mission.title.ifBlank {
            mission.type
          },
          formatMoney((mission.totalReward ?: mission.reward).toLong()),
        )
        mission.completion?.let { DetailLine("Progress", "${(it * 100).roundToInt()} %") }
        if (mission.npc?.name?.isNotBlank() == true) DetailLine("For", mission.npc!!.name)
      }
    }
  }
}

/** Owner, size and — where it is for sale — what it costs, per hectare as well as outright. */
private fun ownershipLine(row: FieldRow): String = buildList {
  add(formatHa(row.mapField.areaHa))
  when {
    row.owned -> add("yours")
    row.mapField.ownerFarmId == null -> add("for sale")
    else -> add("farm ${row.mapField.ownerFarmId}")
  }
  val price = row.mapField.price
  if (price != null && row.mapField.ownerFarmId == null) {
    add(formatMoney(price.toLong()))
    if (row.mapField.areaHa > 0f) add("${formatMoney((price / row.mapField.areaHa).toLong())}/ha")
  }
}.joinToString(" · ")

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
    Text(title.uppercase(), color = VdtColors.DarkGray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    content()
  }
}

@Composable
private fun DetailLine(label: String, value: String) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, color = VdtColors.DarkGray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(value, color = VdtColors.TextDark, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
  }
}

// ---- Formatting ----------------------------------------------------------------------------------

/** Hectares, one decimal below ten and none above — a 43 ha field does not need a tenth. */
internal fun formatHa(ha: Float): String = if (ha <
  10f
) {
  "${(ha * 10).roundToInt() / 10f} ha"
} else {
  "${ha.roundToInt()} ha"
}
