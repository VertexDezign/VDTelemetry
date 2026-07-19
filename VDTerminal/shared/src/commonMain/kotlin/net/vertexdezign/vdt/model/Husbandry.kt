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
  /** Condition bars — food, water, straw, milk/manure/wool outputs, cleanliness (already localized). */
  val conditions: List<HusbandryCondition> = emptyList(),
  /** Per-group animal breakdown (breed + age groups). */
  val animals: List<HusbandryAnimalGroup> = emptyList(),
)

/** One condition bar of a husbandry. */
@Serializable
data class HusbandryCondition(
  val title: String = "",
  /** Fill/level ratio in `[0,1]`. */
  val ratio: Float = 0f,
  /** True when the bar reads inversely (a high value is bad, e.g. dirtiness). */
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
