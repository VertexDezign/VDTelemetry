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
import androidx.compose.material.icons.filled.Add
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
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.TaskInput
import net.vertexdezign.vdt.app.components.ActionIcon
import net.vertexdezign.vdt.app.components.Centered
import net.vertexdezign.vdt.app.components.FilterChip
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.components.SearchField
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.CropCalendarData
import net.vertexdezign.vdt.model.CropRotationData
import net.vertexdezign.vdt.model.FieldInfoData
import net.vertexdezign.vdt.model.FieldStatusData
import net.vertexdezign.vdt.model.FieldStatuses
import net.vertexdezign.vdt.model.LayerKind
import net.vertexdezign.vdt.model.MapData
import net.vertexdezign.vdt.model.MissionsData
import net.vertexdezign.vdt.model.TaskListData
import kotlin.math.roundToInt

/**
 * The Fields app full page: every field on the map as a row you can filter and sort, with what each
 * one is in and what it is asking for.
 *
 * Deliberately **not** a mode of the Map app. The map already answers "what is this field" through
 * its popup; what an overview adds is sorting and filtering *across* fields, which a popup cannot do.
 * The two cross-link instead: a row can put its field on the map ([onShowOnMap]).
 *
 * It also diverges from that popup on purpose, in two ways.
 *
 * **What it leaves out:** fertiliser and lime. Precision Farming replaces both with its own soil model
 * and the mod already withholds the vanilla numbers while PF is active (mirroring the game, which
 * hides them too), so a row that appeared on some saves and not others is a row nobody can learn to
 * read. Nothing is suggested off them either.
 *
 * **What it reads differently:** plough and weeds come from the *soil raster* here, as a share of the
 * field, where the popup keeps `fieldInfo`'s single density read at the field-number anchor. That one
 * read answers for a ~4 m cell and calls the whole field after it — and on a multiplayer client it can
 * be a stale cell, since a client's density maps arrive in bandwidth-limited batches, so a field
 * nobody is standing near keeps answering with what it looked like some time ago. The popup keeps the
 * point sample anyway, deliberately: reading the raster means holding a ground-layer subscription, and
 * the map is often on screen all session — that is a sweep the game would run for a line in a popup.
 * This screen is opened to answer the question, so it can afford the sweep.
 *
 * A null [status] is "no raster yet", not "no mod": the app holds the growth subscription while this
 * screen is open, and the mod's first full sweep after that takes seconds. The rows say so rather
 * than drawing a zeroed breakdown.
 */
@Composable
fun FieldsPanel(
  map: MapData?,
  info: FieldInfoData?,
  status: FieldStatuses?,
  missions: MissionsData?,
  tasks: TaskListData?,
  rotation: CropRotationData?,
  calendar: CropCalendarData?,
  playerFarmId: Int?,
  modifier: Modifier = Modifier,
  onShowOnMap: (Float, Float) -> Unit = { _, _ -> },
  onCommand: (ClientMessage) -> Unit = {},
) {
  // A create needs a group, and nothing in the format names one. The first Standard group is the one
  // the game's own list opens on; a task landing in the wrong group of several is a drag-and-drop
  // away in-game, where an app-side group picker in a suggestion chip would be a question asked
  // every single time to save that.
  val groupId =
    tasks?.groups?.firstOrNull { it.type == TASK_GROUP_STANDARD }?.id ?: tasks?.groups?.firstOrNull()?.id
  var form by remember { mutableStateOf<TaskInput?>(null) }

  Panel(title = "Fields", icon = Icons.Filled.Grass, modifier = modifier) {
    val rows = remember(map, info, status, missions, tasks, playerFarmId) {
      fieldRows(map, info, status, missions, tasks, playerFarmId)
    }
    when {
      map == null -> Centered("Waiting for map data…")

      rows.isEmpty() -> Centered("This map has no fields")

      else ->
        FieldsMasterDetail(
          rows = rows,
          status = status,
          rotation = rotation,
          calendar = calendar,
          canCreate = groupId != null,
          onShowOnMap = onShowOnMap,
          onCreate = { form = it },
        )
    }
  }

  val target = groupId
  form?.let { initial ->
    if (target == null) {
      form = null
    } else {
      TaskFormDialog(
        title = "New task",
        initial = initial,
        onSave = {
          onCommand(ClientMessage.CreateTask(target, it))
          form = null
        },
        onDismiss = { form = null },
        todayPeriod = calendar?.today?.period,
      )
    }
  }
}

/** `TaskGroup.GROUP_TYPE.Standard` — the ordinary group, as against a template or its instance. */
private const val TASK_GROUP_STANDARD = 1

@Composable
private fun FieldsMasterDetail(
  rows: List<FieldRow>,
  status: FieldStatuses?,
  rotation: CropRotationData?,
  calendar: CropCalendarData?,
  canCreate: Boolean,
  onShowOnMap: (Float, Float) -> Unit,
  onCreate: (TaskInput) -> Unit,
) {
  var query by remember { mutableStateOf("") }
  // Opens on your own fields, not on the map's. The list exists to answer "what does my land need",
  // and on a vanilla map ALL is 77 rows of which a handful are yours — the reader would filter every
  // time. Buying land is the deliberate act, so the buy planner is one chip away rather than the
  // greeting.
  var view by remember { mutableStateOf(FieldView.MINE) }
  var sort by remember { mutableStateOf(FieldSort.NUMBER) }
  var ascending by remember { mutableStateOf(true) }
  var selectedId by remember { mutableStateOf<Int?>(null) }

  // The offered views follow the data, so buying the last unowned field drops the reader back to ALL
  // rather than leaving them on an empty list they cannot explain. It is also what makes MINE a safe
  // default: a farm that owns nothing yet is not offered the chip, and opens on ALL.
  val views = remember(rows) { fieldViews(rows) }
  val activeView = if (view in views) view else FieldView.ALL
  val shown = remember(rows, query, activeView, sort, ascending) {
    fieldSorted(fieldSearch(fieldView(rows, activeView), query), sort, ascending)
  }
  val currentId = selectedId.takeIf { id -> shown.any { it.id == id } } ?: shown.firstOrNull()?.id
  val selected = shown.firstOrNull { it.id == currentId }
  val summary = remember(rows, status) { fieldTotals(rows, status) }

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
          FieldDetail(selected, status, rotation, calendar, canCreate, onShowOnMap, onCreate)
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
private fun FieldsSummaryRow(summary: FieldTotals) {
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
private fun FieldRowTile(row: FieldRow, status: FieldStatuses?, selected: Boolean, onClick: () -> Unit) {
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
  fieldWork(row).forEach { add(it.label.uppercase()) }
  // The count, not the tasks: a row is scanned, and three task names would push everything else off
  // it. What it has to answer is "is this one already on the list".
  if (row.tasks.isNotEmpty()) add(if (row.tasks.size == 1) "1 TASK" else "${row.tasks.size} TASKS")
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
  val status = row.growth ?: return
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
  val breakdown = row.growth ?: return
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
private fun FieldDetail(
  row: FieldRow,
  status: FieldStatuses?,
  rotation: CropRotationData?,
  calendar: CropCalendarData?,
  canCreate: Boolean,
  onShowOnMap: (Float, Float) -> Unit,
  onCreate: (TaskInput) -> Unit,
) {
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
          StatusLegend(row, status?.growth)
        }

        // Ahead of the two below, because this field has a breakdown -- it is all one thing, and that
        // thing is nothing. Calling it too small would be the same mistake in a second place.
        isBareByRaster(row) ->
          Text(
            "Nothing growing on any of it — mulch and bare ground read the same here.",
            color = VdtColors.DarkGray,
            fontSize = 10.sp,
          )

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

    ConditionSection(row)

    val info = row.info
    if (info != null) {
      val mix = fieldCropMix(row)
      DetailSection("Crop") {
        DetailLine("Crop", fieldCrop(row).ifBlank { "none" })
        // Only when the field really carries more than one; a single-crop field would just be told its
        // own crop is 100 % of itself. The share is of the planted ground -- see fieldCropMix.
        if (mix.size > 1) {
          DetailLine("Mix", mix.joinToString(", ") { (crop, share) -> "$crop ${(share * 100).roundToInt()} %" })
        }
        // Both of these are the centre reading and only make sense there: a growth state is a number
        // for one point, and averaging it across a half-cut field would invent a stage nothing is at.
        if (info.maxGrowthState > 0) DetailLine("Growth", "${info.growthState} / ${info.maxGrowthState}")
        info.yieldBonusPercent?.let { DetailLine("Yield bonus", "+ $it %") }
      }
    }

    // Outside the fieldInfo guard, unlike everything above it: plough, harvest and cultivate are all
    // read off the rasters, so a field the channel never named can still be asking for work -- and its
    // row in the list already says so in its badges, which is the worst place to disagree with. Only
    // the sow chip needs the point sample, and fieldWork withholds it by itself.
    val suggestions = fieldSuggestions(row, rotation, calendar)
    val work = fieldWork(row)
    if (work.isNotEmpty() || canCreate) {
      DetailSection("Asking for") {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          work.forEach { type ->
            val suggestion = suggestions.firstOrNull { it.type == type }
            when {
              // Already written down: the chip degrades to a plain label rather than offering the
              // same work twice, which is the fastest way to make a suggester worth ignoring.
              suggestion == null -> FieldBadge("${type.label.uppercase()} · ON THE LIST", selected = false)

              canCreate ->
                SuggestionChip(suggestion) { onCreate(taskInputFor(suggestion, calendar?.today?.period)) }

              else -> FieldBadge(type.label.uppercase(), selected = false)
            }
          }
          if (canCreate) AddTaskChip(row.id, calendar?.today?.period, onCreate)
        }
        // Said where the sow chip is, rather than left for the reader to work out: outside seasonal
        // growth the calendar answers "yes" to every period, so there is no best month to date it.
        if (calendar?.isSeasonal == false && suggestions.any { it.type == FieldTaskType.SOW }) {
          Text("No sowing window on this map — growth isn't seasonal.", color = VdtColors.DarkGray, fontSize = 10.sp)
        }
      }
    }

    val cropRotation = info?.cropRotation
    if (cropRotation != null) {
      DetailSection("Rotation") {
        if (cropRotation.lastCrop.isNotBlank()) DetailLine("Last crop", cropRotation.lastCrop)
        if (cropRotation.prevCrop.isNotBlank()) DetailLine("Before that", cropRotation.prevCrop)
        cropRotation.yieldPercent?.let { DetailLine("Rotation yield", "$it %") }
        cropRotation.catchCrop?.let { DetailLine("Catch crop", it) }
      }
    }

    if (row.tasks.isNotEmpty()) {
      DetailSection("Tasks") {
        row.tasks.forEach { ref ->
          DetailLine(fieldTaskLabel(ref), if (ref.task.active) "due now" else MONTH_LABELS[taskMonth(ref.task) - 1])
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

// ---- Creating a task -----------------------------------------------------------------------------

/**
 * The form's starting values for a suggestion.
 *
 * The month falls back to *now* rather than to January when the calendar has no window to offer: a
 * task written while standing on the field is about this month far more often than it is about the
 * turn of the year.
 */
internal fun taskInputFor(suggestion: FieldSuggestion, todayPeriod: Int?): TaskInput {
  val month = suggestion.month ?: todayPeriod?.let { monthFromNow(it, 0) }
  return if (month ==
    null
  ) {
    TaskInput(detail = suggestion.detail)
  } else {
    TaskInput(detail = suggestion.detail, month = month)
  }
}

/**
 * A suggestion, as the control that acts on it.
 *
 * A chip that opens a prefilled dialog, not a button that writes silently: the same call the machine
 * screen makes. What it says is the work and — where the calendar could date it — the month, because
 * a suggestion that quietly picks a month the reader never saw is a surprise on the task list later.
 */
@Composable
private fun SuggestionChip(suggestion: FieldSuggestion, onClick: () -> Unit) {
  val month = suggestion.month?.let { " · ${MONTH_LABELS[it - 1]}" }.orEmpty()
  Row(
    Modifier
      .clip(RoundedCornerShape(3.dp))
      .background(VdtColors.Green)
      .clickable(onClick = onClick)
      .padding(horizontal = 5.dp, vertical = 3.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(3.dp),
  ) {
    Icon(Icons.Filled.Add, contentDescription = null, tint = VdtColors.White, modifier = Modifier.size(10.dp))
    Text(
      // The field number is the heading above these chips, so the chip drops it and keeps the work.
      suggestion.detail.substringAfter(" - ") + month,
      fontSize = 8.sp,
      fontWeight = FontWeight.Bold,
      color = VdtColors.White,
    )
  }
}

/**
 * The manual side: any task type on this field, prefilled and left for the form to finish.
 *
 * This is where fertilize, lime, weed and spray live. Nothing suggests them — under Precision Farming
 * the readings that would are withheld or meaningless — so they are planned forward by hand, which is
 * exactly what the form's "from now" month chips are for.
 */
@Composable
private fun AddTaskChip(fieldId: Int, todayPeriod: Int?, onCreate: (TaskInput) -> Unit) {
  var open by remember { mutableStateOf(false) }
  Box {
    Row(
      Modifier
        .clip(RoundedCornerShape(3.dp))
        .background(VdtColors.TrackGray)
        .clickable { open = true }
        .padding(horizontal = 5.dp, vertical = 3.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
      Icon(Icons.Filled.Add, contentDescription = null, tint = VdtColors.DarkGray, modifier = Modifier.size(10.dp))
      Text("ADD TASK", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = VdtColors.DarkGray)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      FieldTaskType.entries.filter { it != FieldTaskType.OTHER }.forEach { type ->
        DropdownMenuItem(
          text = { Text(type.label, fontSize = 12.sp) },
          onClick = {
            open = false
            val month = todayPeriod?.let { monthFromNow(it, 0) }
            val detail = composeTaskDetail(fieldId, type)
            onCreate(if (month == null) TaskInput(detail = detail) else TaskInput(detail = detail, month = month))
          },
        )
      }
    }
  }
}

// ---- The tile ------------------------------------------------------------------------------------

/** How many fields the tile names before it stops listing and just counts. */
private const val SUMMARY_FIELDS = 3

/**
 * The Fields tile: the fields asking for something, and what.
 *
 * A count plus the first few, because that is what a tile can hold and what the question needs — the
 * full list, the filters and the chips are a screen's worth of work. Own fields only: a tile that
 * counted the whole map would lead with a number nobody can act on.
 */
@Composable
fun FieldsSummary(
  map: MapData?,
  info: FieldInfoData?,
  status: FieldStatuses?,
  tasks: TaskListData?,
  playerFarmId: Int?,
  modifier: Modifier = Modifier,
) {
  Panel(title = "Fields", icon = Icons.Filled.Grass, modifier = modifier) {
    val rows = remember(map, info, status, tasks, playerFarmId) {
      fieldRows(map, info, status, null, tasks, playerFarmId).filter { it.owned }
    }
    val needing = remember(rows) { rows.filter { fieldWork(it).isNotEmpty() } }
    when {
      map == null -> Centered("Waiting for map data…")

      rows.isEmpty() -> Centered("No fields owned")

      needing.isEmpty() -> Centered("${rows.size} fields, nothing outstanding")

      else ->
        Column(
          Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            if (needing.size == 1) "1 FIELD NEEDS WORK" else "${needing.size} FIELDS NEED WORK",
            color = VdtColors.Green,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
          )
          needing.take(SUMMARY_FIELDS).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text(
                row.label,
                color = VdtColors.TextDark,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
              )
              Text(
                fieldWork(row).joinToString(" · ") { it.label },
                color = VdtColors.DarkGray,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
          if (needing.size > SUMMARY_FIELDS) {
            Text("+ ${needing.size - SUMMARY_FIELDS} more", color = VdtColors.DarkGray, fontSize = 10.sp)
          }
        }
    }
  }
}

/**
 * What condition the ground is in — the soil plane's answer, as shares of the field.
 *
 * Two readings only: plough and weeds. Fertilizer and lime are on the same plane and deliberately
 * left off, because Precision Farming replaces both with its own soil model and the mod already
 * withholds the vanilla numbers while PF is active — a row that appeared on some saves and not others
 * is a row nobody can learn to read.
 *
 * The share is of the **whole field**, and it is a floor rather than a measurement: the mod classifies
 * each soil cell by priority (weeds beat stones beat needs-plowing), so ground that is both weedy and
 * unploughed is counted once, as weeds. Saying "at least" is the honest way to print that.
 */
@Composable
private fun ConditionSection(row: FieldRow) {
  val plow = plowShare(row)
  val weeds = weedShare(row)
  val info = row.info
  val sampledWeed = info?.weed.orEmpty()
  // Nothing measured and nothing sampled: the field has no condition worth a heading.
  if (plow == null && weeds == null && info?.needsPlowing != true && sampledWeed.isEmpty()) return

  DetailSection("Condition") {
    when {
      plow != null -> DetailLine("Needs plowing", shareLine(plow))

      // The point sample is the fallback, and it is labelled as one: it is a single ~4 m cell at the
      // field-number anchor, and on a multiplayer client it can be a stale one.
      info?.needsPlowing == true -> DetailLine("Needs plowing", "at the field centre")

      else -> Unit
    }
    when {
      weeds != null -> DetailLine("Weeds", shareLine(weeds))
      sampledWeed.isNotEmpty() -> DetailLine("Weeds", "$sampledWeed at the field centre")
      else -> Unit
    }
    if (plow == null && weeds == null) {
      Text(
        "Sampling the soil — the first sweep takes a few seconds.",
        color = VdtColors.DarkGray,
        fontSize = 10.sp,
      )
    }
  }
}

/**
 * A soil share as a percentage, floored rather than quoted — see [ConditionSection].
 *
 * Truncated, not rounded, because the sentence it builds says *at least*: rounding 42.6 % up would
 * print a lower bound the measurement does not support. For the same reason "the whole field" waits
 * for a real 100 % — every cell classified — rather than settling for a rounded one.
 */
private fun shareLine(share: Float): String {
  val percent = (share * 100).toInt()
  return when {
    percent <= 0 -> "none"
    percent >= 100 -> "the whole field"
    else -> "at least $percent %"
  }
}
