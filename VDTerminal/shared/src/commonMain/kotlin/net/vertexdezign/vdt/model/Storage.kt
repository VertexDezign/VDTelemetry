package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

/**
 * Typed model of the **storage** channel the mod writes to `storage.json` (separate file,
 * interval-driven cadence — see the mod's `src/collect/StorageExporter.lua`): everything the local
 * farm holds outside a production point, on three keys.
 *
 * [storages] is the app's Storage view — the storage *placeables* you can walk up to and unload:
 * silos, manure heaps and object storages. One kind is deliberately missing: a placeable a husbandry
 * has taken over as its own store — the manure heap behind the barn, an `isExtension` slurry tank —
 * whose liters the pen already reports as a condition bar, with a fill type on it
 * ([HusbandryCondition.type]). Counting it here as well would have the stock overview and the price
 * list price the same manure twice.
 * [bunkerSilos], [looseBales] and [loosePallets] deliberately are **not** part of it: they are the
 * rest of the farm's holding, and they exist for the price list and stock overview rather than for
 * that view. A panel rendering the Storage app reads [storages] and nothing else.
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
  val bunkerSilos: List<BunkerSilo> = emptyList(),
  val looseBales: List<LooseStock> = emptyList(),
  val loosePallets: List<LooseStock> = emptyList(),
)

/**
 * An owned storage placeable with no production. Two kinds, distinguished by [kind]:
 * - `fill` — a liter store: a silo, or a manure heap (which is not a `Storage` in the engine at all,
 *   but answers the same questions and is the solid twin of the slurry tank beside it). Contents in
 *   [fills] (per fill type, level/capacity). A heap or tank wired into a **husbandry** does not
 *   appear at all — see [StorageData] — so one here is a store the farm fills itself.
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
  /**
   * Fill type name of what these objects **hold** — a separate question from [kind], and one that can
   * fail on its own: a crate or a vegetable pallet holds no fill type at all, and the game's own
   * dialog text drops the liter figure for exactly those. Such a row still says `PALLET`: it is stock
   * the farm owns, just stock nothing can price.
   */
  val type: String = "",
  /**
   * Liters in **one** of those objects — a group is objects the game considers identical, fill level
   * included, so the group holds `level * count`. Note the difference from [LooseStock.level], which
   * is already a group total. Zero when [type] is empty.
   */
  val level: Int = 0,
  /**
   * What these objects **are**: `BALE`, `PALLET` or `BIGBAG` — the three things an object storage can
   * hold. Empty only for a group the mod could not recognise at all; note it survives a [type] it
   * could not resolve.
   *
   * Same vocabulary as [LooseStock.kind] on purpose: a stored bale and a bale on the ground are the
   * same resource, so this is what lets the two lists be read as one. [title] cannot do that job — it
   * is the game's dialog text, in the player's language.
   */
  val kind: String = "",
  /** `ROUND` or `SQUARE`, on `kind == "BALE"` only. Empty otherwise. */
  val shape: String = "",
)

/**
 * A silage bunker — one `PlaceableBunkerSilo`, or one bay of a multi-bay placeable (whose bays get
 * the bay number appended to [id] and [name], since the placeable itself has one name for all of
 * them).
 *
 * There is no capacity: a bunker's ceiling is the shape of its walls, and the game only ever prints
 * a fill level for one. [type] follows the game's own readout — the input type (chaff, grass) while
 * the heap is open, the output type (silage) once it has been covered.
 */
@Serializable
data class BunkerSilo(
  val id: String = "",
  val name: String = "",
  /**
   * `FILL` (open, being driven in and compacted), `CLOSED` (covered, fermenting), `FERMENTED` (done,
   * still covered) or `DRAIN` (opened, being taken out).
   */
  val state: String = "FILL",
  val type: String = "",
  val title: String = "",
  val level: Int = 0,
  /** Compaction percent 0..100. Only meaningful — and only sent — in state `FILL`. */
  val compacted: Int = 0,
  /** Fermentation percent 0..100. Only meaningful — and only sent — in `CLOSED` / `FERMENTED`. */
  val fermenting: Int = 0,
)

/**
 * Bales or pallets lying around the farm, aggregated: one row per fill type and form — [kind] says
 * which of the three it is, [shape] refines a bale.
 *
 * These are the objects in the world, so bales riding a trailer are in here (they are stock, just in
 * transit) while anything put away in an object storage is not — that is counted once, under
 * [StandaloneStorage.objects].
 */
@Serializable
data class LooseStock(
  val type: String = "",
  val title: String = "",
  /** What these are: `BALE`, `PALLET` or `BIGBAG`. Always set. */
  val kind: String = "",
  /** `ROUND` or `SQUARE`, on `kind == "BALE"` only. Empty otherwise. */
  val shape: String = "",
  val count: Int = 0,
  /** Liters the whole group holds — a **total**, unlike [StoredObject.level]. */
  val level: Int = 0,
  /**
   * Bales only: how many of the group are still fermenting. A fermenting bale reports the fill type
   * it went in as (grass, not silage) until the game swaps it at the end, so a stock overview that
   * ignores this prices a wrapped grass bale as grass.
   */
  val fermenting: Int = 0,
)
