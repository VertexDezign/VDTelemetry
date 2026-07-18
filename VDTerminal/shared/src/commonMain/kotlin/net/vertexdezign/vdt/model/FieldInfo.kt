package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

/**
 * Typed model of the **fieldInfo** channel the mod writes to `fieldInfo.json` (separate file,
 * interval-driven cadence — see the mod's `src/collect/FieldInfoExporter.lua`): the per-field
 * *agronomy* state that the game shows in its FELDINFO panel (crop, growth, plow/spray/weed), one
 * entry per field keyed by [FieldInfoEntry.id].
 *
 * Deliberately separate from [MapData]: that channel carries the near-static geometry (labels,
 * polygons, owner, area) and rewrites only on ownership/placeable events, whereas the data here
 * grows over in-game time and changes on till/harvest/sow, so it is resampled on its own timer. The
 * app **joins the two by field id** to render the field-info popup.
 *
 * Its own [version], independent of [VdtData.version]. Same tolerance rules as the rest of the
 * model: omitted keys fall back to these defaults, so the mod can add fields ahead of the client.
 */
@Serializable
data class FieldInfoData(
  val version: String = "",
  val fields: List<FieldInfoEntry> = emptyList(),
)

/**
 * One field's agronomy state, sampled mod-side at the field's centre from the same density maps the
 * game's `PlayerHUDUpdater:showFieldInfo` reads. Fields with no farmable ground are omitted entirely.
 */
@Serializable
data class FieldInfoEntry(
  /** Farmland id — the same value as [MapField.id], the key the app joins on. */
  val id: Int = 0,
  /** Fruit-type title (localized mod-side); empty when the field is bare / crop unknown. */
  val crop: String = "",
  /** Current growth state, as the game numbers it; 0 when there is no crop. */
  val growthState: Int = 0,
  /** The crop's max growth state, so the app can render e.g. "6 / 7"; 0 when unknown. */
  val maxGrowthState: Int = 0,
  /**
   * Growth phase token, mirroring the game's growth-map text ladder: `growing`, `readyToPrepare`,
   * `readyToHarvest`, `cut`, `withered`. Empty when there is no crop.
   */
  val growth: String = "",
  /**
   * Yield bonus percentage the game shows while the crop is growing (e.g. 12 = "+ 12 %"), from
   * `FieldState:getHarvestScaleMultiplier`. Null when not growing / not computable.
   */
  val yieldBonusPercent: Int? = null,
  /** Fertilized percentage (spray level as a % of max). Null when unfertilizable / unreadable. */
  val sprayLevelPercent: Int? = null,
  /** Resolved weed-state title (localized mod-side); empty when the field is weed-free. */
  val weed: String = "",
  /** Plowing required (plow level 0 with plowing enabled) — a warning row in the popup. */
  val needsPlowing: Boolean = false,
  /** Liming required — a warning row in the popup. */
  val needsLime: Boolean = false,
  /** Rolling required — a warning row in the popup. */
  val needsRolling: Boolean = false,
  /**
   * The FS25_CropRotation rows for this field, or null when that mod isn't installed (the mod-side
   * collector only attaches this when the integration is available — same guarded, fail-soft
   * contract as the planner channel). Lets the popup show the mod's extra lines without a second
   * channel to join.
   */
  val cropRotation: FieldCropRotation? = null,
)

/**
 * The per-field FS25_CropRotation lines, mirroring the mod's `PlayerHUDUpdaterExtension` HUD
 * additions: the crop history plus the rotation yield and catch crop at this field's position.
 */
@Serializable
data class FieldCropRotation(
  /** Last harvested crop title on this field ("Letzte Frucht"); empty when none recorded. */
  val lastCrop: String = "",
  /** Crop before that ("Vorletzte Frucht"); empty when none recorded. */
  val prevCrop: String = "",
  /** Rotation yield percentage the mod shows ("Fruchtfolgen Ertrag", e.g. 115). Null when unknown. */
  val yieldPercent: Int? = null,
  /** Catch/cover crop title ("Zwischenfrucht"); null means "no catch crop". */
  val catchCrop: String? = null,
)
