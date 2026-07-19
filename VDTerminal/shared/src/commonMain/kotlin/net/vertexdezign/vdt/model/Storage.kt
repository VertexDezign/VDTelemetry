package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

/**
 * Typed model of the **storage** channel the mod writes to `storage.json` (separate file,
 * interval-driven cadence — see the mod's `src/collect/StorageExporter.lua`): the local farm's
 * owned standalone storages with no production — liter silos and object storages (bales/pallets).
 *
 * Split out from the sibling **production** channel ([ProductionData], `production.json`) so each
 * app/channel can evolve independently. The per-row [ProductionFill] shape is shared with that
 * channel (a silo's fills look exactly like a production point's internal storage rows).
 *
 * Scope is own-farm only. Its own [version], independent of [VdtData.version]. Same tolerance rules
 * as the rest of the model: omitted keys fall back to these defaults, so the mod can add fields
 * ahead of the client.
 */
@Serializable
data class StorageData(
  val version: String = "",
  val storages: List<StandaloneStorage> = emptyList(),
)

/**
 * An owned storage placeable with no production. Two kinds, distinguished by [kind]:
 * - `fill` — a liter silo: contents in [fills] (per fill type, level/capacity).
 * - `object` — an object storage (bales/pallets, count-based): total [count] / [capacity] objects,
 *   with a per-type breakdown in [objects] (which may be partial on a multiplayer client, where only
 *   counts are synced — the total is always accurate).
 */
@Serializable
data class StandaloneStorage(
  val id: String = "",
  val name: String = "",
  val kind: String = "fill",
  val fills: List<ProductionFill> = emptyList(),
  val objects: List<StoredObject> = emptyList(),
  val count: Int = 0,
  val capacity: Int = 0,
  /**
   * `object` kind: the per-action unload cap (the game's per-building `maxUnloadAmount`, usually 25).
   * The effective max for a given type is `min(maxUnloadAmount, that group's count)`.
   */
  val maxUnloadAmount: Int = 0,
)

/** A group of identical stored objects in an object storage. */
@Serializable
data class StoredObject(
  /** The group's `objectInfoIndex` (1-based) — the addressing key for the unload command. */
  val index: Int = 0,
  val title: String = "",
  val count: Int = 0,
)
