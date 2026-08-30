package net.vertexdezign.vdt.app.panels

import net.vertexdezign.vdt.model.FieldInfoData
import net.vertexdezign.vdt.model.FieldInfoEntry
import net.vertexdezign.vdt.model.FieldStatus
import net.vertexdezign.vdt.model.FieldStatuses
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
  /** What stage the ground is at, off the growth raster; null until that plane has been swept. */
  val growth: FieldStatus?,
  /** What condition the ground is in, off the soil raster; null until that plane has been swept. */
  val soil: FieldStatus?,
  /** Which fruit covers it, off the crops raster; null until that plane has been swept. */
  val crops: FieldStatus?,
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
 * At 512² on a 2 km map a cell is 4 m square, so this is 0.16 ha of sampled ground. Below it the
 * raster is answering with too few pixels to round honestly — "83 %" off forty cells is a number with
 * two significant figures and none of them earned — and the row falls back to the point sample the
 * game's own panel would show.
 *
 * **Provisional**, and note it is a *cell* count on a grid that is 512² whatever the map measures, so
 * the ground it stands for doubles with the map: 0.16 ha on a 2 km map, 0.64 ha on a 4 km one. On
 * `map/vanilla.json` it silences nothing at all — the smallest of those 77 fields is 0.21 ha, about
 * 131 cells — so it is a guard against the odd sliver rather than a line anyone has seen bite.
 * The committed 512² capture says the same thing louder: on `map/mp_modded.json` every one of its 85
 * fields resolves, and the smallest — half a hectare — is 313 cells, three times this. So no committed
 * data has ever reached the line. Expressing it in hectares off `FieldStatusData.haPerCell` would at
 * least make it mean the same thing on every map; that change has its own issue.
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

  // The soil plane's kinds all read correctly as their own capitalised token — "Mulched", "Weed",
  // "Fertilized" — and this ladder is for the growth and crops breakdowns, so none of them earns a
  // word of its own here.
  LayerKind.WEED, LayerKind.STONE, LayerKind.NEEDS_PLOWING, LayerKind.MULCHED, LayerKind.NEEDS_LIME,
  LayerKind.FERTILIZED,
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
  val dominant = row.growth?.takeIf { it.cells >= MIN_STATUS_CELLS }?.dominant
  if (dominant != null) return FieldHeadline(kindLabel(dominant.kind), fromRaster = true)
  // The raster answered, and the answer is "nothing" — see [isBareByRaster].
  if (isBareByRaster(row)) return FieldHeadline("Bare", fromRaster = true)
  val info = row.info
  val word = growthWord(info?.growth.orEmpty())
  if (word.isNotEmpty()) return FieldHeadline(word, fromRaster = false)
  // No crop and nothing sampled. "Bare" is a statement about the field; "—" would be a statement
  // about the data, and the two are worth telling apart in a list that exists to be scanned.
  return FieldHeadline(if (info != null) "Bare" else "—", fromRaster = false)
}

/**
 * Whether the growth raster resolved this field and found no growth anywhere on it.
 *
 * A distinction the [cells] count alone cannot make. The growth plane's 0 means "no growth state
 * here", which on a field is an answer rather than a gap, so a field that is entirely 0 has `cells`
 * of zero and a *full* [FieldStatus.polygonCells] — indistinguishable, if you only look at `cells`,
 * from a field the raster could not resolve at all. Asking the polygon instead is the same question
 * [hasSoilBreakdown] asks of the soil plane, for the same reason.
 *
 * **Mulching is the ordinary way to get here**, and it is not a mod gap: the game paints mulch on its
 * *soil* overlay (`SOIL_STATE_INDEX.MULCHED`, off `FieldDensityMap.STUBBLE_SHRED_LEVEL`), never on the
 * growth one, whose ground-type paints are only cultivated / plowed / stubble-tillage / seedbed. A
 * mulched field is genuinely blank on the growth plane, in the game's own map as much as in ours. Any
 * other bare ground the four ground types do not name lands here too.
 *
 * Which of the two it is comes from the *soil* plane, where the mod reports mulch as of `mapLayers`
 * version 4 — [mulchShare], and [bareGroundNote] for the sentence that uses it.
 *
 * Without this the field fell through to `fieldInfo`'s centre sample and was labelled "at the field
 * centre" — claiming the whole-field reading was unavailable when it was the reading we had.
 */
fun isBareByRaster(row: FieldRow): Boolean {
  val growth = row.growth ?: return false
  return growth.cells == 0 && growth.polygonCells >= MIN_STATUS_CELLS
}

/**
 * What to say about a field the growth raster resolved as entirely blank — see [isBareByRaster].
 *
 * The growth plane cannot tell mulched ground from bare, but the soil plane can, so the sentence
 * names the mulch when the soil raster carries enough of it and stays with the plain statement
 * otherwise. "Otherwise" covers three different situations on purpose — an unmulched field, a soil
 * plane nobody is subscribed to (the share is null, not zero), and a mod too old to report the state
 * — because none of them is a claim this app can make about the ground, and the honest sentence for
 * all three is the one that says only what the growth plane saw.
 */
fun bareGroundNote(row: FieldRow): String = if ((mulchShare(row) ?: 0f) >= WORK_SHARE) {
  "Nothing growing on any of it — the ground is mulched."
} else {
  "Nothing growing on any of it."
}

/** Whether the breakdown is worth drawing at all, rather than a bar made of four cells. */
fun hasBreakdown(row: FieldRow): Boolean = (row.growth?.cells ?: 0) >= MIN_STATUS_CELLS

/**
 * The crop on the field: the fruit covering most of it, off the crops raster.
 *
 * The one place the whole app asks "what is on this field" — the row, the search, the crop sort, the
 * hectares-by-crop summary and the gate on suggesting a sow all read this — so the raster reaches all
 * of them by being answered here.
 *
 * `fieldInfo.crop` is the fallback, and it is a **single cell at the field's centre**
 * (`MathUtil.getPolygonLabel`, so inside the polygon but not otherwise meaningful). That one cell is
 * empty for every ordinary reason a spot can be: a track or a headland through the middle, ground a
 * machine drove over, the part of a half-sown field that is not drilled yet. The field then read as
 * growing nothing while most of it stood in wheat, which is the bug this exists to fix.
 *
 * Empty means empty here too — a bare field, with the raster and the sample agreeing or the raster
 * absent. [fieldCropMix] is the same question answered in full for a field carrying more than one.
 */
fun fieldCrop(row: FieldRow): String {
  val dominant = row.crops?.takeIf { it.cells >= MIN_STATUS_CELLS }?.dominant?.label
  if (dominant != null) return dominant
  return row.info?.crop.orEmpty()
}

/**
 * Every fruit on the field with its share of the planted ground, biggest first.
 *
 * Empty when the raster is absent or too thin, and a single entry for the ordinary field — so the
 * detail can say "Wheat 71 %, Barley 29 %" exactly when that is the truth and stay quiet otherwise.
 * The share is of the *planted* part, not of the title deed: a field two thirds sown and one third
 * bare reads 100 % of what is on it, and the bare third is the growth plane's business.
 */
fun fieldCropMix(row: FieldRow): List<Pair<String, Float>> {
  val crops = row.crops?.takeIf { it.cells >= MIN_STATUS_CELLS } ?: return emptyList()
  return crops.slices.mapNotNull { slice ->
    slice.label?.let { it to crops.fraction(slice.cells) }
  }
}

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
  if (needsPlowing(row)) add(FieldTaskType.PLOW)
  if (info?.needsRolling == true) add(FieldTaskType.ROLL)
  val status = row.growth
  val ready = status != null && status.cells >= MIN_STATUS_CELLS && status.fractionOf("harvest") >= WORK_SHARE
  if (ready || info?.growth == "readyToHarvest") add(FieldTaskType.HARVEST)
  val withered = status != null && status.cells >= MIN_STATUS_CELLS && status.fractionOf("withered") >= WORK_SHARE
  // The crop is lost either way; clearing it is the only honest advice left.
  if (withered || info?.growth == "withered") add(FieldTaskType.CULTIVATE)
  // A bare owned field is work too — it is the one that earns nothing while it waits.
  if (isEmpty() && row.owned && fieldCrop(row).isEmpty() && info != null) add(FieldTaskType.SOW)
}

/**
 * Whether the soil raster can be trusted for this field, which is the same "too few cells" question
 * the growth plane answers — asked of the **whole polygon** rather than of the sampled part.
 *
 * The soil plane's 0 means "nothing to report here": a cell that is ploughed, limed and weed-free
 * carries no value at all. So its sampled-cell count is a count of *problems*, not of field, and
 * dividing by it would turn one weedy corner on an otherwise perfect field into 100 % weeds.
 */
fun hasSoilBreakdown(row: FieldRow): Boolean = (row.soil?.polygonCells ?: 0) >= MIN_STATUS_CELLS

/**
 * How much of the field needs plowing, `0..1`, or null when the raster can't say.
 *
 * **Understated where another condition wins.** The mod classifies a soil cell by priority — weeds
 * beat stones beat needs-plowing beat mulched beat needs-lime beat fertilizer — so a cell that is
 * both weedy and unploughed is counted as weeds. Read this as "at least this much", and read it
 * beside [weedShare] and [mulchShare]: between them they account for the field.
 */
fun plowShare(row: FieldRow): Float? = if (!hasSoilBreakdown(row)) null else row.soil?.polygonFractionOf("needsPlowing")

/** How much of the field is carrying weeds, `0..1`, or null when the raster can't say. */
fun weedShare(row: FieldRow): Float? = if (!hasSoilBreakdown(row)) null else row.soil?.polygonFractionOf("weed")

/**
 * How much of the field has been mulched, `0..1`, or null when the raster can't say.
 *
 * The one soil reading that is not a complaint — shredded stubble is work already done — and the one
 * that explains a field the growth plane calls blank (see [isBareByRaster]). Unlike lime and
 * fertiliser it survives a Precision Farming save, because PF replaces neither the stubble map nor
 * the game's own mulch overlay.
 *
 * Understated for the same reason as [plowShare]: a mulched cell that also wants the plough is
 * counted as needing the plough, which is the order the game's overlay paints them in.
 */
fun mulchShare(row: FieldRow): Float? = if (!hasSoilBreakdown(row)) null else row.soil?.polygonFractionOf("mulched")

/**
 * Whether this field wants the plough.
 *
 * The raster leads when it has the cells for it, and the point sample is the fallback — the same rule
 * the headline follows, and for the same reason, only more sharply here. `fieldInfo.needsPlowing` is
 * one density read at `field.posX/posZ` (the field-number label anchor), so it answers for a single
 * ~4 m cell and calls the whole field after it. On a multiplayer client that one cell can also be
 * *stale*: a client's density maps arrive in bandwidth-limited batches, so a field nobody is standing
 * near can keep answering with what it looked like some time ago. The raster is the same data, but
 * hundreds of cells of it, so one stale or unrepresentative cell no longer decides.
 */
fun needsPlowing(row: FieldRow): Boolean {
  val share = plowShare(row)
  return if (share != null) share >= WORK_SHARE else row.info?.needsPlowing == true
}

/**
 * How much of a field has to be in some state before it is worth a trip out there.
 *
 * One bar for every kind of work rather than one per kind: enough of the crop standing to take the
 * combine out, enough of it lost to be worth clearing, enough of the ground unploughed to hitch the
 * plough — they are the same judgement, and three tunable numbers would only invite three different
 * answers to it. [bareGroundNote] borrows it for a sentence rather than a chip, which is the same
 * question once more: enough of the field in one state to speak for the whole of it.
 */
private const val WORK_SHARE = 0.25f

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
  status: FieldStatuses?,
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
      growth = status?.growth?.byId?.get(field.id),
      soil = status?.soil?.byId?.get(field.id),
      crops = status?.crops?.byId?.get(field.id),
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
  /**
   * Crop -> own hectares, biggest first.
   *
   * A field counts whole under its dominant crop (see [fieldCrop]) rather than split across the two it
   * carries, because the number this feeds is "how much of my land is in wheat" at a glance and a
   * mixed field is rare enough that splitting it would cost more clarity than it buys.
   */
  val byCrop: List<Pair<String, Float>>,
)

fun fieldTotals(rows: List<FieldRow>, status: FieldStatuses?): FieldTotals {
  val mine = rows.filter { it.owned }
  val growth = status?.growth
  val ready =
    if (growth == null) {
      null
    } else {
      mine.sumOf { row ->
        val cells = row.growth?.takeIf { it.cells >= MIN_STATUS_CELLS }?.cellsOf("harvest") ?: 0
        growth.ha(cells).toDouble()
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
