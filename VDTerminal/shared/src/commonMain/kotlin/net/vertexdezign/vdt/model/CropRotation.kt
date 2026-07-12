package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

/**
 * Typed model of the optional **FS25_CropRotation** channel the mod writes to `cropRotation.json`
 * (separate file, event-driven cadence — see the mod's `src/integrations/CropRotation.lua`). Mirrors
 * the mod's saved rotation *plans* (its planner), scoped to the local player's farm.
 *
 * Its own [version], independent of [VdtData.version]. Same tolerance rules as the rest of the model:
 * omitted keys fall back to these defaults, so the mod can add fields ahead of the client.
 */
@Serializable
data class CropRotationData(
  val version: String = "",
  val rotations: List<CropRotationPlan> = emptyList(),
  /**
   * The selectable main crops for the write-side dropdowns (fruit-type index + display name), in the
   * mod's order and already minus the planner-ignored ones. Empty when the mod doesn't ship a
   * catalog, which the app reads as "render read-only".
   */
  val crops: List<CropOption> = emptyList(),
  /** The selectable catch crops, including index 0 ("without catch crop"). */
  val catchCrops: List<CropOption> = emptyList(),
)

/** One selectable crop: [state] is the fruit-type index a write command sends back, [name] the label. */
@Serializable
data class CropOption(
  val state: Int = 0,
  val name: String = "",
)

/** One named rotation plan: an ordered [sequence] of crops the farmer cycles a field through. */
@Serializable
data class CropRotationPlan(
  /** The mod's stable plan id within the planner (used to target edits later). */
  val index: Int = 0,
  val name: String = "",
  val farmId: Int = 0,
  val sequence: List<CropRotationSlot> = emptyList(),
)

/**
 * One step in a rotation: the main [crop] plus an optional [catchCrop]. [state] / [catchCropState]
 * are the mod's raw fruit-type indices (**0 = fallow / no catch crop**); [crop] / [catchCrop] are the
 * display names resolved mod-side so the app needn't know the fruit-type table.
 */
@Serializable
data class CropRotationSlot(
  val state: Int = 0,
  val crop: String = "",
  val catchCropState: Int = 0,
  val catchCrop: String = "",
  /**
   * The yield-bonus percentage the game shows under this slot (e.g. 115 = +15%), recomputed mod-side
   * with FS25_CropRotation's own YieldCalculator. Null when the mod couldn't compute it (older
   * version / missing calculator), rendered as no percentage rather than a misleading 0.
   */
  val yieldPercent: Int? = null,
  /**
   * Per-option yield previews for this slot's crop dropdown: for each selectable crop, the % this
   * slot would yield if it were picked (catch crop held at its current value). Lets the app show the
   * outcome of each choice inline. Empty when the mod ships no catalog / calculator.
   */
  val cropYields: List<CropYield> = emptyList(),
  /** The same previews for the catch-crop dropdown (main crop held at its current value). */
  val catchYields: List<CropYield> = emptyList(),
)

/** One dropdown option's preview: [state] is the crop/catch index, [yieldPercent] its resulting %. */
@Serializable
data class CropYield(
  val state: Int = 0,
  val yieldPercent: Int? = null,
)
