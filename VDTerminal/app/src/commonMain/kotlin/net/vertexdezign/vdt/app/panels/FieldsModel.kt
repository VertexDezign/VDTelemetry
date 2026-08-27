package net.vertexdezign.vdt.app.panels

import net.vertexdezign.vdt.model.FieldInfoData
import net.vertexdezign.vdt.model.FieldInfoEntry
import net.vertexdezign.vdt.model.FieldStatus
import net.vertexdezign.vdt.model.FieldStatusData
import net.vertexdezign.vdt.model.LayerKind
import net.vertexdezign.vdt.model.MapData
import net.vertexdezign.vdt.model.MapField
import net.vertexdezign.vdt.model.Mission
import net.vertexdezign.vdt.model.MissionsData
import net.vertexdezign.vdt.model.TaskListData
import net.vertexdezign.vdt.model.UNKNOWN_FIELD_KIND

/**
 * The join behind the Fields app: one row per field, gathering everything four channels say about it.
 *
 * Kept out of the composable for the usual reason — it is the part with rules in it, and rules are
 * easier to read (and to change) where no layout is in the way. Every function here is pure.
 */
data class FieldRow(
  /**
   * Geometry, ownership, area, price — the near-static half.
   *
   * Named `mapField` rather than `field` because `field` is the backing-field identifier inside a
   * property accessor, and the derived properties below are accessors.
   */
  val mapField: MapField,
  /** Agronomy sampled at the field centre; null when the channel is off or the field isn't in it. */
  val info: FieldInfoEntry?,
  /** The raster breakdown; null when nothing has been swept yet (see [FieldStatusData]). */
  val status: FieldStatus?,
  /** A contract on this field, if the board carries one. */
  val mission: Mission?,
  /** Whether the local player's farm owns it. False for unowned *and* for another farm's. */
  val owned: Boolean,
  /** Tasks whose detail names this field — see [FieldTaskRef]. Empty when the mod isn't installed. */
  val tasks: List<FieldTaskRef> = emptyList(),
) {
  val id: Int get() = mapField.id

  /** The number the game prints on the map — the field's name when the map gave it one. */
  val label: String get() = mapField.name.ifBlank { mapField.id.toString() }
}

/**
 * How many cells a field must have before its percentages are quoted.
 *
 * At 512² on a 2 km map a cell is about 4 m square, so this is roughly a third of a hectare of
 * sampled ground. Below it the raster is answering with too few pixels to round honestly — "83 %" off
 * forty cells is a number with two significant figures and none of them earned — and the row falls
 * back to the point sample the game's own panel would show.
 *
 * **Provisional.** The plan flags picking this properly as an open question: it wants a real 512²
 * capture to look at, and none has been taken yet (`FUTURE.md` -> "Captures wanted as fixtures").
 */
const val MIN_STATUS_CELLS = 100

/**
 * A ground state in the words this app uses.
 *
 * Its own vocabulary rather than the legend's labels, for two reasons. The legend is **localized** —
 * it is whatever language the game runs in, so a German client's raster would print German into an
 * English list. And the breakdown is grouped by kind, where several legend entries collapse into one
 * slice (every step of the growing gradient is `growing`), so there is no single label to borrow.
 *
 * An unrecognised kind keeps its own token capitalised rather than being called "Other": the mod owns
 * this vocabulary and may extend it, and a word from a newer mod is more use to the reader than a
 * bucket that hides it.
 */
fun kindLabel(kind: String): String = when (LayerKind.of(kind)) {
  LayerKind.CULTIVATED -> "Cultivated"

  LayerKind.STUBBLE -> "Stubble"

  LayerKind.SEEDBED -> "Seedbed"

  LayerKind.PLOWED -> "Plowed"

  LayerKind.GROWING -> "Growing"

  LayerKind.TOPPING -> "Toppable"

  LayerKind.HARVEST -> "Ready"

  LayerKind.CUT -> "Cut"

  LayerKind.WITHERED -> "Withered"

  LayerKind.CROP -> "Planted"

  LayerKind.WEED, LayerKind.STONE, LayerKind.NEEDS_PLOWING, LayerKind.NEEDS_LIME, LayerKind.FERTILIZED,
  null,
  -> if (kind == UNKNOWN_FIELD_KIND) "Unknown" else kind.replaceFirstChar { it.uppercase() }
}

/**
 * The same ladder for `fieldInfo`'s growth token, which is the fallback when the raster is thin.
 *
 * Shorter than the map popup's version of it ("Ready" against "Ready to harvest"): that popup mirrors
 * the game's own panel and has a line to itself, while this word shares a row with a crop, an area and
 * a price. Same vocabulary as [kindLabel] on purpose, so a field does not change its wording when the
 * raster takes over from the point sample.
 */
fun growthWord(growth: String): String = when (growth) {
  "growing" -> "Growing"
  "readyToPrepare" -> "Toppable"
  "readyToHarvest" -> "Ready"
  "cut" -> "Cut"
  "withered" -> "Withered"
  else -> ""
}

/**
 * The one-word answer for a row, and whether it came from the raster.
 *
 * The raster leads when it has enough cells, because it is the more truthful of the two about a field
 * half-worked — the whole reason the histogram exists. The point sample is the fallback, and it is
 * also the honest answer for a field too small for the raster to resolve.
 */
data class FieldHeadline(val text: String, val fromRaster: Boolean)

fun fieldHeadline(row: FieldRow): FieldHeadline {
  val dominant = row.status?.takeIf { it.cells >= MIN_STATUS_CELLS }?.dominant
  if (dominant != null) return FieldHeadline(kindLabel(dominant.kind), fromRaster = true)
  val info = row.info
  val word = growthWord(info?.growth.orEmpty())
  if (word.isNotEmpty()) return FieldHeadline(word, fromRaster = false)
  // No crop and nothing sampled. "Bare" is a statement about the field; "—" would be a statement
  // about the data, and the two are worth telling apart in a list that exists to be scanned.
  return FieldHeadline(if (info != null) "Bare" else "—", fromRaster = false)
}

/** Whether the breakdown is worth drawing at all, rather than a bar made of four cells. */
fun hasBreakdown(row: FieldRow): Boolean = (row.status?.cells ?: 0) >= MIN_STATUS_CELLS

/** The crop on the field, from the point sample — the raster's growth plane doesn't name one. */
fun fieldCrop(row: FieldRow): String = row.info?.crop.orEmpty()

/**
 * Work this field is asking for, in the words the suggestion chips will use.
 *
 * Only what the data actually supports on a Precision Farming save: PF replaces the base soil model,
 * and the mod already withholds `sprayLevelPercent` and `needsLime` while it is active (mirroring the
 * game's own panel), so fertiliser, lime and weeds are absent rather than false. They are planned
 * ahead by hand instead of triggered off a reading.
 */
fun fieldWork(row: FieldRow): List<FieldTaskType> = buildList {
  val info = row.info
  if (info?.needsPlowing == true) add(FieldTaskType.PLOW)
  if (info?.needsRolling == true) add(FieldTaskType.ROLL)
  val status = row.status
  val ready = status != null && status.cells >= MIN_STATUS_CELLS && status.fractionOf("harvest") >= HARVEST_SHARE
  if (ready || info?.growth == "readyToHarvest") add(FieldTaskType.HARVEST)
  val withered = status != null && status.cells >= MIN_STATUS_CELLS && status.fractionOf("withered") >= WITHERED_SHARE
  // The crop is lost either way; clearing it is the only honest advice left.
  if (withered || info?.growth == "withered") add(FieldTaskType.CULTIVATE)
  // A bare owned field is work too — it is the one that earns nothing while it waits.
  if (isEmpty() && row.owned && fieldCrop(row).isEmpty() && info != null) add(FieldTaskType.SOW)
}

/** Enough of the field is standing to be worth taking the combine out for. */
private const val HARVEST_SHARE = 0.25f

/** …and enough is lost that clearing it is the honest advice. */
private const val WITHERED_SHARE = 0.25f

/** Which slice of the map is listed. Single-select: each answers one question, and ANDing them is noise. */
enum class FieldView(val label: String) {
  ALL("All"),
  MINE("Mine"),
  UNOWNED("For sale"),
  WORK("Needs work"),
  HARVEST("Ready"),
  CONTRACT("Contract"),
}

/** How the list is ordered. */
enum class FieldSort(val label: String) {
  NUMBER("Number"),
  AREA("Area"),
  CROP("Crop"),
  STATUS("Status"),
  PRICE("Price"),
}

/** Build the rows. Every field on the map appears; the other three channels fill in what they have. */
fun fieldRows(
  map: MapData?,
  info: FieldInfoData?,
  status: FieldStatusData?,
  missions: MissionsData?,
  tasks: TaskListData?,
  playerFarmId: Int?,
): List<FieldRow> {
  if (map == null) return emptyList()
  val byId = info?.fields?.associateBy { it.id }.orEmpty()
  val tasksByField = fieldTasks(tasks)
  // A field can carry more than one contract over a session; the active one is what the row is about,
  // and an offer is only worth showing while nothing is running on that ground.
  val missionByField =
    missions?.missions
      ?.filter { it.fieldId != null }
      ?.groupBy { it.fieldId!! }
      ?.mapValues { (_, list) -> list.firstOrNull { it.isActive } ?: list.firstOrNull { it.isOffered } ?: list.first() }
      .orEmpty()
  return map.fields.map { field ->
    FieldRow(
      mapField = field,
      info = byId[field.id],
      status = status?.byId?.get(field.id),
      mission = missionByField[field.id],
      // Null farm id (spectating, or no telemetry yet) means nothing is "mine" — which is right:
      // claiming ownership on missing data is the one direction this must not guess in.
      owned = playerFarmId != null && field.ownerFarmId == playerFarmId,
      tasks = tasksByField[field.id].orEmpty(),
    )
  }
}

/**
 * Which views these rows can actually offer. A chip that can only come back empty is a question with
 * no answer — there is no contract view on a board with no field contracts.
 */
fun fieldViews(rows: List<FieldRow>): List<FieldView> = buildList {
  add(FieldView.ALL)
  if (rows.any { it.owned }) add(FieldView.MINE)
  if (rows.any { it.mapField.ownerFarmId == null }) add(FieldView.UNOWNED)
  if (rows.any { fieldWork(it).isNotEmpty() }) add(FieldView.WORK)
  if (rows.any { FieldTaskType.HARVEST in fieldWork(it) }) add(FieldView.HARVEST)
  if (rows.any { it.mission != null }) add(FieldView.CONTRACT)
}

/** The slice [view] asks for. */
fun fieldView(rows: List<FieldRow>, view: FieldView): List<FieldRow> = when (view) {
  FieldView.ALL -> rows
  FieldView.MINE -> rows.filter { it.owned }
  FieldView.UNOWNED -> rows.filter { it.mapField.ownerFarmId == null }
  FieldView.WORK -> rows.filter { fieldWork(it).isNotEmpty() }
  FieldView.HARVEST -> rows.filter { FieldTaskType.HARVEST in fieldWork(it) }
  FieldView.CONTRACT -> rows.filter { it.mission != null }
}

/** Number, name or crop, case-insensitively; a blank query matches everything. */
fun fieldSearch(rows: List<FieldRow>, query: String): List<FieldRow> {
  val needle = query.trim()
  if (needle.isEmpty()) return rows
  return rows.filter {
    it.label.contains(needle, ignoreCase = true) || fieldCrop(it).contains(needle, ignoreCase = true)
  }
}

/**
 * Order the list, ties breaking on the field number so the order is **total** — otherwise the twenty
 * bare fields would reshuffle under the reader's finger on every sweep.
 *
 * A field with nothing to sort on — no price read, no crop, nothing sampled — sorts to the end
 * whichever way the sort runs. Unknown is not a low value, and floating an unpriced field to the top
 * of a "cheapest first" list is exactly the lie the buy planner must not tell.
 */
fun fieldSorted(rows: List<FieldRow>, sort: FieldSort, ascending: Boolean): List<FieldRow> = rows.sortedWith { a, b ->
  val primary = when (sort) {
    FieldSort.NUMBER -> 0
    FieldSort.AREA -> compareOptionalField(a.mapField.areaHa, b.mapField.areaHa, ascending)
    FieldSort.CROP -> compareText(fieldCrop(a), fieldCrop(b), ascending)
    FieldSort.STATUS -> compareText(fieldHeadline(a).text, fieldHeadline(b).text, ascending)
    FieldSort.PRICE -> compareOptionalField(a.mapField.price?.toFloat(), b.mapField.price?.toFloat(), ascending)
  }
  if (primary != 0) {
    primary
  } else {
    val byNumber = a.id.compareTo(b.id)
    // Only the number sort reverses its own tie-break; everywhere else the number is the stable
    // spelling of "equal", and reversing it would make the list jump when nothing changed.
    if (sort == FieldSort.NUMBER && !ascending) -byNumber else byNumber
  }
}

/** Compare two optional numbers, with "no value" sorting to the end whichever way the sort runs. */
private fun compareOptionalField(a: Float?, b: Float?, ascending: Boolean): Int = when {
  a == null && b == null -> 0
  a == null -> 1
  b == null -> -1
  ascending -> a.compareTo(b)
  else -> b.compareTo(a)
}

/** Same, for text: blank is "nothing to say" and sorts to the end rather than to the top. */
private fun compareText(a: String, b: String, ascending: Boolean): Int = when {
  a.isBlank() && b.isBlank() -> 0
  a.isBlank() -> 1
  b.isBlank() -> -1
  ascending -> a.lowercase().compareTo(b.lowercase())
  else -> b.lowercase().compareTo(a.lowercase())
}

/**
 * The line above the list — the thing a player actually reads first.
 *
 * Everything here is about the **farm**, not about the map: the hectares are the ones you own, and
 * the counts are the ones you can act on. A summary that counted the whole map would lead with a
 * number that never changes.
 */
data class FieldTotals(
  val ownedFields: Int,
  val ownedHa: Float,
  /** Hectares of own ground the raster says are ready, or null when nothing has been sampled. */
  val readyHa: Float?,
  val needsWork: Int,
  val forSale: Int,
  /** Crop -> own hectares, biggest first. Point-sampled, so it is the dominant crop per field. */
  val byCrop: List<Pair<String, Float>>,
)

fun fieldTotals(rows: List<FieldRow>, status: FieldStatusData?): FieldTotals {
  val mine = rows.filter { it.owned }
  val ready =
    if (status == null) {
      null
    } else {
      mine.sumOf { row ->
        val cells = row.status?.takeIf { it.cells >= MIN_STATUS_CELLS }?.cellsOf("harvest") ?: 0
        status.ha(cells).toDouble()
      }.toFloat()
    }
  return FieldTotals(
    ownedFields = mine.size,
    ownedHa = mine.sumOf { it.mapField.areaHa.toDouble() }.toFloat(),
    readyHa = ready,
    needsWork = mine.count { fieldWork(it).isNotEmpty() },
    forSale = rows.count { it.mapField.ownerFarmId == null },
    byCrop =
    mine
      .filter { fieldCrop(it).isNotEmpty() }
      .groupBy { fieldCrop(it) }
      .map { (crop, fields) -> crop to fields.sumOf { it.mapField.areaHa.toDouble() }.toFloat() }
      .sortedByDescending { it.second },
  )
}
