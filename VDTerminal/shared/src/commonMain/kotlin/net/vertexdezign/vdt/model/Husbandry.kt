package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

/**
 * Typed model of the **husbandry** channel the mod writes to `husbandry.json` (separate file,
 * interval-driven cadence — see the mod's `src/collect/HusbandryExporter.lua`): the local farm's
 * owned animal husbandries (pens), each with the game's own aggregated condition bars, its overall
 * productivity, animal counts, and the per-group animal breakdown.
 *
 * Its own [version], independent of [VdtData.version]. Omitted keys fall back to these defaults.
 */
@Serializable
data class HusbandriesData(
  val version: String = "",
  val husbandries: List<Husbandry> = emptyList(),
)

/** One owned animal husbandry (pen/barn). */
@Serializable
data class Husbandry(
  /** Stable id for selection — the placeable's uniqueId, or a synthesized fallback. */
  val id: String = "",
  val name: String = "",
  val numAnimals: Int = 0,
  val maxNumAnimals: Int = 0,
  /** Overall production factor in `[0,1]` (the game's global production factor). */
  val productivity: Float = 0f,
  /** Food-group bars (the game's `getFoodInfos`, separate from [conditions]), already localized. */
  val food: List<HusbandryCondition> = emptyList(),
  /** Condition bars — water, straw, milk/manure/wool outputs, cleanliness (already localized). */
  val conditions: List<HusbandryCondition> = emptyList(),
  /** Per-group animal breakdown (breed + age groups). */
  val animals: List<HusbandryAnimalGroup> = emptyList(),
)

/** One condition/food bar of a husbandry. */
@Serializable
data class HusbandryCondition(
  val title: String = "",
  /** Fill/level ratio in `[0,1]` (drives the bar). */
  val ratio: Float = 0f,
  /** Current fill level in liters. */
  val value: Int = 0,
  /** Storage capacity in liters, or 0 when the info carries none (the condition bars don't). */
  val capacity: Int = 0,
  /** True when the bar reads inversely (a high value is bad, e.g. an output awaiting collection). */
  val inverted: Boolean = false,
)

/** One cluster of identical animals (same breed + age). */
@Serializable
data class HusbandryAnimalGroup(
  /** Breed/age label — the animal store name for that subtype at that age. */
  val name: String = "",
  val count: Int = 0,
  /** Age in months. */
  val age: Int = 0,
  /** Health, 0..100. */
  val health: Int = 0,
  /** Reproduction progress, 0..100. */
  val reproduction: Int = 0,
  /** False for animals that don't breed (e.g. horses) — the app hides the reproduction figure then. */
  val supportsReproduction: Boolean = false,
)
