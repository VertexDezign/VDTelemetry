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
)
