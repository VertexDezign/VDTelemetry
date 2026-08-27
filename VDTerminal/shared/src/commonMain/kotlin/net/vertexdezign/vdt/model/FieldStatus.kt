package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

/**
 * The bucket cells land in when nothing names what they are — a raster value the plane's legend does
 * not list, or one whose [MapLayerLegendEntry.kind] is null (a plane the mod keeps no vocabulary for,
 * or a value it produced but could not name).
 *
 * Its own bucket on purpose, never folded into a known kind: a mod newer than this build classifying
 * ground it has a word for and we don't must read as "something is there that I can't name", not as a
 * confident share of a kind it isn't. It is also the bucket that makes an old mod (every legend entry
 * `kind`-less, since the key predates `mapLayers` version 3) degrade to "no breakdown" rather than to
 * a wrong one.
 */
const val UNKNOWN_FIELD_KIND = "unknown"

/**
 * The per-field breakdown of every plane the server counts — one [FieldStatusData] per raster.
 *
 * More than one plane answers a question about a field, and they answer different ones: `growth` says
 * what stage the ground is at, `soil` what condition it is in. Both are counted off the same index
 * grid in the same pass, so they arrive together rather than as two channels that could disagree
 * about which map they are describing.
 *
 * A plane nobody has swept yet is simply absent from [planes] — never present with zeroed counts,
 * which would read as "nothing there" instead of "not looked yet".
 */
@Serializable
data class FieldStatuses(val planes: List<FieldStatusData> = emptyList()) {
  /** Memoized, and delegated so it stays out of the serialized form and out of `equals`. */
  val byLayer: Map<String, FieldStatusData> by lazy { planes.associateBy { it.layerId } }

  /** What stage each field's ground is at; null until the growth plane has been swept. */
  val growth: FieldStatusData? get() = byLayer[GROWTH_LAYER_ID]

  /** What condition each field's ground is in; null until the soil plane has been swept. */
  val soil: FieldStatusData? get() = byLayer[SOIL_LAYER_ID]
}

/**
 * What is actually on each field, counted off one ground-layer raster rather than sampled at a point.
 *
 * Derived by the **server** from data it already holds — `map.json`'s field polygons and one
 * [MapLayerData] plane — so, like the coverage layer, it has no channel file behind it and a null
 * means "no raster yet", never "the mod isn't installed". See [FieldIndexGrid] for the mechanism.
 *
 * ### Why this exists next to `fieldInfo`
 *
 * `FieldInfoExporter` builds one `FieldState` at the field **centre** — one point. That is right for
 * the map popup, which mirrors the game's own FELDINFO panel under the cursor, and wrong as a
 * headline in a list: a field 70 % cut reports whatever its middle happens to be. The same raster the
 * mod already sweeps for the map overlay answers the question properly, on a multiplayer client too,
 * because the plane was swept on that client from synchronised density maps. The two server-side
 * alternatives (`field:getFieldState()`, `FieldGetInfoTask`) are both maintained only inside
 * `FieldManager:update`, which returns early when `g_server == nil` — correct in singleplayer, silently
 * empty on a joined client.
 *
 * ### What it is not
 *
 * A **snapshot of the last sweep**, not a live reading. Between full sweeps the mod patches only cells
 * near active vehicles, which is where the work is — so a field being worked does update — but a field
 * a helper harvests across the map moves on the next full sweep.
 */
@Serializable
data class FieldStatusData(
  /** The plane these counts came from — `growth` in round 1. */
  val layerId: String = "",
  /** Ground one cell stands for, in hectares; `(terrainSize / gridSize)^2 / 10000`. */
  val haPerCell: Float = 0f,
  /** One entry per field the grid resolved at least one cell for, ascending by [FieldStatus.id]. */
  val fields: List<FieldStatus> = emptyList(),
) {
  /**
   * The fields keyed by id, for the join every consumer makes — `MapField.id`, `FieldInfoEntry.id`
   * and `Mission.fieldId` are all the same farmland id.
   *
   * Memoized, and delegated rather than a constructor property so it stays out of the serialized form
   * and out of `equals`/`hashCode`.
   */
  val byId: Map<Int, FieldStatus> by lazy { fields.associateBy { it.id } }

  /** [cells] as hectares. Zero when the geometry never arrived, which reads as "unknown", not "none". */
  fun ha(cells: Int): Float = cells * haPerCell
}

/**
 * One field's breakdown.
 *
 * [cells] counts only the cells the plane recorded a value for; [blank] counts the rest of the
 * polygon. The split matters: a field polygon is the farmland border, while the raster carries the
 * *ground state*, so meadow, a track, a pond or simply ground the plane has no word for sits inside
 * the polygon carrying nothing. Percentages are of [cells], because "62 % ready to harvest" means 62 %
 * of the ground that is in a state, not 62 % of the title deed.
 *
 * `cells == 0 && blank > 0` is therefore "the field is there, the plane says nothing about it";
 * `cells == 0 && blank == 0` never appears here — a field the grid resolved no cell for is left out
 * entirely, and the caller falls back to `fieldInfo`'s point sample.
 */
@Serializable
data class FieldStatus(
  /** The farmland id — the same integer as `MapField.id` and the field number the game displays. */
  val id: Int = 0,
  /** Cells inside the polygon carrying a raster value; the denominator of every fraction here. */
  val cells: Int = 0,
  /** Cells inside the polygon the plane recorded nothing for (raster value 0). */
  val blank: Int = 0,
  /** Descending by [FieldStatusSlice.cells], ties broken by kind so the order is deterministic. */
  val slices: List<FieldStatusSlice> = emptyList(),
) {
  /** Cells inside the polygon at all — [cells] plus [blank]. A measure of the field, not of its state. */
  val polygonCells: Int get() = cells + blank

  /** The largest slice, or null when nothing was sampled. What a one-word headline should read. */
  val dominant: FieldStatusSlice? get() = slices.firstOrNull()

  /** Cells of one kind; 0 for a kind this field has none of, which is also what an absent kind reads as. */
  fun cellsOf(kind: String): Int = slices.firstOrNull { it.kind == kind }?.cells ?: 0

  /** Share of the sampled ground in one kind, `0..1`. */
  fun fractionOf(kind: String): Float = fraction(cellsOf(kind))

  /** [count] as a share of [cells]; 0 when nothing was sampled, so a bar never divides by zero. */
  fun fraction(count: Int): Float = if (cells <= 0) 0f else count.toFloat() / cells

  /**
   * [count] as a share of the whole field ([polygonCells]) rather than of the sampled part.
   *
   * The right denominator for a plane whose 0 means "nothing to report" rather than "not field
   * ground" — the soil plane, where a cell that is ploughed, limed and weed-free carries no value at
   * all. Dividing those counts by [cells] would compare one condition against the other conditions
   * instead of against the field, and a single weedy corner on an otherwise perfect field would read
   * as 100 % weeds.
   */
  fun polygonFraction(count: Int): Float = if (polygonCells <= 0) 0f else count.toFloat() / polygonCells

  /** Share of the whole field in one kind, `0..1` — see [polygonFraction]. */
  fun polygonFractionOf(kind: String): Float = polygonFraction(cellsOf(kind))
}

/**
 * How much of a field is in one state.
 *
 * Keyed by [MapLayerLegendEntry.kind] — the token, not the mod's wire value and not the localized
 * label. The value is a private enumeration the mod may renumber and the label is whatever language
 * the game runs in, so grouping by either would be a cross-subsystem contract made by accident.
 * Deliberately coarser than the value: every step of the growth plane's growing gradient is `growing`.
 * Resolve it with [LayerKind.of] to branch, keep the string to display and to count.
 */
@Serializable
data class FieldStatusSlice(val kind: String = "", val cells: Int = 0)
