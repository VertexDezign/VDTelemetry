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
 * what stage the ground is at, `soil` what condition it is in, `crops` which fruit is planted. All are
 * counted off the same index grid in the same pass, so they arrive together rather than as three
 * channels that could disagree about which map they are describing.
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

  /** Which fruit covers each field, by area; null until the crops plane has been swept. */
  val crops: FieldStatusData? get() = byLayer[CROPS_LAYER_ID]
}

/**
 * How one plane's cells are bucketed into [FieldStatusSlice]s.
 *
 * [KIND] is right for a plane whose legend entries mean *different things* — the growth plane's ready
 * against cut against withered — where the token is the answer and being coarser than the wire value
 * is the point (all eight steps of the growing gradient are one `growing` slice).
 *
 * [VALUE] is right for a plane whose entries are all the same *kind* of thing and differ only in which
 * one — the crops plane, where every entry is `crop` and grouping by kind would collapse a field into
 * a single slice saying "planted". There the wire value is the identity and [MapLayerLegendEntry.label]
 * is the name, so the slice carries the label for display and still carries the kind for branching.
 */
enum class SliceGrouping {
  KIND,
  VALUE,
}

/**
 * The planes the per-field breakdown is counted off, and how each one's cells are bucketed.
 *
 * One definition rather than two, because the server counts these and the app subscribes to them, and
 * a plane in one list but not the other is either a histogram of a raster nobody refreshes or a sweep
 * nobody reads. Order is the order they reach [FieldStatuses.planes].
 *
 * Counting a plane is cheap where it matters, which is the mod: `classifyCell` gates its reads per
 * plane, growth and soil share the `GROUND_TYPE` read, and crops shares growth's fruit read — it is
 * `cropsV = fruit.index` off a lookup growth has already done. So all three are one cell walk with a
 * few more density reads, not three sweeps.
 */
val FIELD_STATUS_PLANES: Map<String, SliceGrouping> =
  linkedMapOf(
    GROWTH_LAYER_ID to SliceGrouping.KIND,
    SOIL_LAYER_ID to SliceGrouping.KIND,
    CROPS_LAYER_ID to SliceGrouping.VALUE,
  )

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
 * [kind] is [MapLayerLegendEntry.kind] — the token, not the mod's wire value and not the localized
 * label. The value is a private enumeration the mod may renumber and the label is whatever language
 * the game runs in, so **branching** on either would be a cross-subsystem contract made by accident.
 * Resolve it with [LayerKind.of] to branch, keep the string to count.
 *
 * On a [SliceGrouping.KIND] plane that is also the whole slice: deliberately coarser than the value,
 * so every step of the growth plane's growing gradient is one `growing` slice, and [label] is null
 * because the kind already names the thing.
 *
 * On a [SliceGrouping.VALUE] plane the slice is one legend value and [label] carries that value's
 * name — the crops plane's fruit title. It is localized, exactly like `fieldInfo.crop` which it stands
 * in for, so it is a string to **show**, never one to branch on; the kind beside it is what code reads.
 */
@Serializable
data class FieldStatusSlice(
  val kind: String = "",
  val cells: Int = 0,
  /** Display name when the slice is finer than its kind; null when the kind is the whole answer. */
  val label: String? = null,
)
