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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.components.Centered
import net.vertexdezign.vdt.app.components.FilterChip
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.components.SearchField
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.AdsState
import net.vertexdezign.vdt.model.FleetData
import net.vertexdezign.vdt.model.FleetVehicle
import net.vertexdezign.vdt.model.GameDate
import net.vertexdezign.vdt.model.PropertyState
import kotlin.math.roundToInt

/**
 * The Fleet app full page: a master/detail over the [FleetData] channel — every machine the farm
 * owns, what condition it is in, and (with Advanced Damage System installed) what maintenance it is
 * due. The game's own vehicle overview, on a screen that doesn't pause the game.
 *
 * A null [data] means the channel is absent (export off / no data yet), which is a different
 * statement from a farm that owns nothing, and gets a different empty state: a list of machines that
 * quietly stops listing one is the one failure mode this panel must not have.
 *
 * [onShowOnMap] is given a machine whose position is known; the app wires it to the map (see
 * `FleetApp`), which is why the panel itself knows nothing about navigation.
 */
@Composable
fun FleetPanel(data: FleetData?, modifier: Modifier = Modifier, onShowOnMap: (FleetVehicle) -> Unit = {}) {
  Panel(title = "Fleet", icon = Icons.Filled.Agriculture, modifier = modifier) {
    when {
      data == null -> Centered("Waiting for fleet data…")
      data.vehicles.isEmpty() -> Centered("This farm owns no machines")
      else -> FleetMasterDetail(data, onShowOnMap)
    }
  }
}

// ---- The list ------------------------------------------------------------------------------------

/** How the list is ordered. The same columns both in-game lists sort on. */
enum class FleetSort(val label: String) {
  NAME("Name"),
  CONDITION("Condition"),
  HOURS("Hours"),
  AGE("Age"),
  SERVICE("Service due"),
  VALUE("Value"),
}

/**
 * Which slice of the fleet is listed. Single-select rather than a set of independent toggles: these
 * answer one question each ("what needs me", "what am I paying for"), and ANDing them together would
 * produce combinations nobody asks for.
 */
enum class FleetView(val label: String) {
  ALL("All"),
  ATTENTION("Attention"),
  WORKSHOP("Workshop"),
  LEASED("Leased"),
  IMPLEMENTS("Implements"),
}

/** Below this, a machine is worth looking at before it goes out. */
internal const val CONDITION_ATTENTION = 40

/** And below this it will want a tank before the shift is over. */
internal const val FUEL_ATTENTION = 15

/**
 * The condition to print for a machine, as a percentage, or null when nobody has measured one.
 *
 * **Two sources, one right at a time.** Under Advanced Damage System the vanilla damage figure is
 * pinned to 0 — printing it would report a worn-out tractor as brand new — so a machine with an
 * [FleetVehicle.ads] block is read from its inspection record and everything else from its wear.
 * A machine ADS manages that has never been inspected has no condition at all, which is exactly what
 * the mod means by hiding it: go and have it looked at.
 */
internal fun fleetCondition(vehicle: FleetVehicle): Int? {
  val ads = vehicle.ads
  return if (ads != null) ads.inspected?.condition else vehicle.wearable?.let { 100 - it.damage }
}

/**
 * Whether that condition is ADS's approximation rather than a measurement — an ordinary inspection
 * instead of a full defectoscopy. The reading is still shown; it is labelled, because quoting a guess
 * as a measurement is how a dashboard lies.
 */
internal fun conditionIsApproximate(vehicle: FleetVehicle): Boolean =
  vehicle.ads?.inspected?.let { !it.complete } == true

/** Fuel level in percent, for the machines that burn any. */
internal fun fuelPercent(vehicle: FleetVehicle): Int? = vehicle.motorFillUnits?.fuel?.fillLevelPercentage

/**
 * Worth a look before this machine goes out: ADS says so, its condition is low, or it is nearly out
 * of fuel. The point of the whole list is to answer this without walking the yard.
 */
internal fun needsAttention(vehicle: FleetVehicle): Boolean {
  if (vehicle.ads?.needsAttention == true) return true
  val condition = fleetCondition(vehicle)
  if (condition != null && condition < CONDITION_ATTENTION) return true
  val fuel = fuelPercent(vehicle)
  return fuel != null && fuel < FUEL_ATTENTION
}

/**
 * Which views this fleet can actually offer. A chip that can only ever come back empty is a question
 * with no answer: without Advanced Damage System nothing is ever in a workshop, and a farm that
 * leases nothing has no leased view to look at.
 */
internal fun fleetViews(vehicles: List<FleetVehicle>): List<FleetView> = buildList {
  add(FleetView.ALL)
  add(FleetView.ATTENTION)
  if (vehicles.any { it.ads != null }) add(FleetView.WORKSHOP)
  if (vehicles.any { it.propertyState == PropertyState.LEASED }) add(FleetView.LEASED)
  if (vehicles.any { !it.isMotorized }) add(FleetView.IMPLEMENTS)
}

/** The slice [view] asks for. */
internal fun fleetView(vehicles: List<FleetVehicle>, view: FleetView): List<FleetVehicle> = when (view) {
  FleetView.ALL -> vehicles
  FleetView.ATTENTION -> vehicles.filter { needsAttention(it) }
  FleetView.WORKSHOP -> vehicles.filter { it.ads?.isInWorkshop == true }
  FleetView.LEASED -> vehicles.filter { it.propertyState == PropertyState.LEASED }
  FleetView.IMPLEMENTS -> vehicles.filter { !it.isMotorized }
}

/** Name or category match, case-insensitively; a blank query matches everything. */
internal fun fleetSearch(vehicles: List<FleetVehicle>, query: String): List<FleetVehicle> {
  val needle = query.trim()
  if (needle.isEmpty()) return vehicles
  return vehicles.filter {
    it.name.contains(needle, ignoreCase = true) || it.category?.contains(needle, ignoreCase = true) == true
  }
}

/**
 * Order the list. Ties break on the name so the order is **total** — otherwise a refresh could
 * reshuffle the twelve machines that all read 0 hours, under the reader's finger.
 *
 * A machine with nothing to sort on — no condition measured, no service record, no sell value because
 * it is leased — sorts to the end whichever way the sort runs. "Unknown" is not a low value, and
 * floating it to the top of an ascending condition sort would put the machines nobody has looked at
 * where the broken ones belong.
 */
internal fun fleetSorted(vehicles: List<FleetVehicle>, sort: FleetSort, ascending: Boolean): List<FleetVehicle> =
  vehicles.sortedWith { a, b ->
    val primary = when (sort) {
      FleetSort.NAME -> 0
      FleetSort.CONDITION -> compareOptional(fleetCondition(a)?.toFloat(), fleetCondition(b)?.toFloat(), ascending)
      FleetSort.HOURS -> compareOptional(a.hours, b.hours, ascending)
      FleetSort.AGE -> compareOptional(a.age.toFloat(), b.age.toFloat(), ascending)
      FleetSort.SERVICE -> compareOptional(a.ads?.service?.fraction, b.ads?.service?.fraction, ascending)
      FleetSort.VALUE -> compareOptional(a.sellPrice?.toFloat(), b.sellPrice?.toFloat(), ascending)
    }
    if (primary != 0) {
      primary
    } else {
      val byName = a.name.lowercase().compareTo(b.name.lowercase())
      // Only the name sort itself reverses its tie-break; everywhere else the name is the stable
      // spelling of "equal", and reversing it would make the list jump when nothing changed.
      if (sort == FleetSort.NAME && !ascending) -byName else byName
    }
  }

/** Compare two optional numbers, with "no value" sorting to the end whichever way the sort runs. */
private fun compareOptional(a: Float?, b: Float?, ascending: Boolean): Int = when {
  a == null && b == null -> 0
  a == null -> 1
  b == null -> -1
  ascending -> a.compareTo(b)
  else -> b.compareTo(a)
}

@Composable
private fun FleetMasterDetail(data: FleetData, onShowOnMap: (FleetVehicle) -> Unit) {
  var query by remember { mutableStateOf("") }
  var view by remember { mutableStateOf(FleetView.ALL) }
  var sort by remember { mutableStateOf(FleetSort.NAME) }
  var ascending by remember { mutableStateOf(true) }
  // Selection is by id so it survives the refreshes; the id is the network object id, which is stable
  // for the session. It falls back to the first row when the machine is sold or filtered away.
  var selectedId by remember { mutableStateOf<Int?>(null) }

  // The offered views follow the fleet, so selling the last leased machine drops the reader back to
  // ALL rather than leaving them staring at an empty list they cannot explain.
  val views = remember(data) { fleetViews(data.vehicles) }
  val activeView = if (view in views) view else FleetView.ALL
  val shown = remember(data, query, activeView, sort, ascending) {
    fleetSorted(fleetSearch(fleetView(data.vehicles, activeView), query), sort, ascending)
  }
  val currentId = selectedId.takeIf { id -> shown.any { it.id == id } } ?: shown.firstOrNull()?.id
  val selected = shown.firstOrNull { it.id == currentId }
  // Resolved off the whole fleet rather than the filtered list: the rig a plough hangs off may well
  // be filtered out of view while the plough is not.
  val byId = remember(data) { data.vehicles.associateBy { it.id } }

  Column(Modifier.fillMaxSize()) {
    FleetControls(
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
          // The one list in the app that can run to a hundred rows on a played-in farm, which is why
          // it is lazy where the others are a plain scrolling column.
          LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(shown, key = { it.id }) { vehicle ->
              FleetRow(
                vehicle = vehicle,
                rig = vehicle.attachedTo?.let { byId[it] },
                selected = vehicle.id == currentId,
                onClick = { selectedId = vehicle.id },
              )
            }
          }
        }
      }
      Box(Modifier.width(1.dp).fillMaxHeight().background(VdtColors.PanelBorder))
      Box(Modifier.weight(1f).fillMaxHeight().padding(start = 10.dp)) {
        if (selected != null) {
          FleetVehicleDetail(
            vehicle = selected,
            today = data.date,
            rig = selected.attachedTo?.let { byId[it] },
            onShowOnMap = onShowOnMap,
          )
        } else {
          Centered("Select a machine")
        }
      }
    }
  }
}

@Composable
private fun FleetControls(
  query: String,
  views: List<FleetView>,
  view: FleetView,
  sort: FleetSort,
  ascending: Boolean,
  onQuery: (String) -> Unit,
  onView: (FleetView) -> Unit,
  onSort: (FleetSort) -> Unit,
  onDirection: () -> Unit,
) {
  Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    SearchField(query, "Search machines", onQuery, Modifier.width(170.dp))
    FlowRow(
      Modifier.weight(1f),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      views.forEach { entry ->
        FilterChip(entry.label, view == entry, { onView(entry) })
      }
    }
    SortControl(sort, ascending, onSort, onDirection)
  }
}

@Composable
private fun SortControl(sort: FleetSort, ascending: Boolean, onSort: (FleetSort) -> Unit, onDirection: () -> Unit) {
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
        FleetSort.entries.forEach { entry ->
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
    // The direction is an Icon, never an arrow character: the wasm build has no font fallback and
    // would render one as tofu (see VDTerminal/README.md -> "Design rules").
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

@Composable
private fun FleetRow(vehicle: FleetVehicle, rig: FleetVehicle?, selected: Boolean, onClick: () -> Unit) {
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
        vehicle.name,
        color = fg,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      val condition = fleetCondition(vehicle)
      Text(
        if (condition == null) "—" else "$condition%",
        color = fg,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
      )
    }
    Text(
      rowSubtitle(vehicle),
      color = muted,
      fontSize = 10.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    val badges = rowBadges(vehicle, rig)
    if (badges.isNotEmpty()) {
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        badges.forEach { badge -> RowBadge(badge, selected) }
      }
    }
  }
}

/** Category, hours and — for a machine that burns fuel — how much is left in it. */
internal fun rowSubtitle(vehicle: FleetVehicle): String = buildList {
  vehicle.category?.takeIf { it.isNotBlank() }?.let { add(it) }
  add("${formatHours(vehicle.hours)} h")
  fuelPercent(vehicle)?.let { add("fuel $it%") }
}.joinToString(" · ")

/**
 * The words a row wears. Words rather than colours, and at most three: what a badge says has to
 * survive being read by someone who cannot tell the amber one from the green one.
 */
internal fun rowBadges(vehicle: FleetVehicle, rig: FleetVehicle? = null): List<String> = buildList {
  val ads = vehicle.ads
  when {
    ads == null -> Unit

    ads.state == AdsState.BROKEN -> add("BROKEN")

    ads.isInWorkshop -> add("WORKSHOP")

    // A state token this build cannot name. It earns a badge because it also earns a place in the
    // attention view, and a row listed there with nothing to show for it is the worse of the two.
    ads.state == AdsState.UNKNOWN -> add("UNKNOWN STATE")

    ads.isServiceOverdue -> add("SERVICE DUE")

    ads.breakdowns.isNotEmpty() -> add("FAULT")

    else -> Unit
  }
  when (vehicle.propertyState) {
    PropertyState.LEASED -> add("LEASED")

    // The one machine on the list the game's own overview leaves out, so it says what it is.
    PropertyState.MISSION -> add("CONTRACT")

    else -> Unit
  }
  // Who has it, or that it has been put away: one rung of statusLabel's ladder, short enough for a row
  // — including the rig's answer for an implement, or a plough would work all day saying nothing.
  when {
    vehicle.isAI -> add("AI")
    vehicle.isControlled || rig?.isControlled == true || rig?.isEntered == true -> add("IN USE")
    vehicle.isParked -> add("PARKED")
  }
}

@Composable
private fun RowBadge(label: String, selected: Boolean) {
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

// ---- Formatting ----------------------------------------------------------------------------------

/** Operating hours to one decimal, the way the game prints them ("1234.5"). */
internal fun formatHours(hours: Float): String {
  val tenths = (hours * 10).roundToInt()
  return "${tenths / 10}.${tenths % 10}"
}

/** Age, which the game counts in months, read back as years and months once there are enough. */
internal fun formatAge(months: Int): String {
  if (months < 12) return "$months mo"
  val years = months / 12
  val rest = months % 12
  return if (rest == 0) "$years y" else "$years y $rest mo"
}

/** Whole months between two game dates, where a "month" is one of the game's twelve periods. */
internal fun monthsBetween(then: GameDate, today: GameDate): Int = today.monthsSince(then).coerceAtLeast(0)

/** How long ago something happened, in the game's own months — the phrasing ADS's own screens use. */
internal fun formatMonthsAgo(months: Int): String = when (months) {
  0 -> "this month"
  1 -> "1 month ago"
  else -> "$months months ago"
}
