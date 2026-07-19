package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

/**
 * Typed model of the **production** channel the mod writes to `production.json` (separate file,
 * interval-driven cadence — see the mod's `src/collect/ProductionExporter.lua`): the local farm's
 * owned production points (with their production lines + shared internal storage) and factories.
 *
 * Standalone storages (owned silos + object storages) live on the sibling **storage** channel
 * ([StorageData], `storage.json`) — the two were split so each app/channel can evolve on its own.
 *
 * Scope is own-farm only. Fill levels/capacities are liters; the app derives the fill percentage
 * from [ProductionFill.level] / [ProductionFill.capacity].
 *
 * Its own [version], independent of [VdtData.version]. Same tolerance rules as the rest of the
 * model: omitted keys fall back to these defaults, so the mod can add fields ahead of the client.
 */
@Serializable
data class ProductionData(
  val version: String = "",
  val productionPoints: List<ProductionPoint> = emptyList(),
)

/** One owned production point (greenhouse, biogas plant, ...) or a factory. */
@Serializable
data class ProductionPoint(
  /** Stable id for selection — the placeable's uniqueId, or a synthesized fallback. */
  val id: String = "",
  val name: String = "",
  /**
   * True for a `PlaceableFactory` — a passive "deliver input → produce/sell a product" building
   * (a separate chain-manager list from real production points). Read-only: it has no on/off state
   * and no output distribution mode, so the app hides those controls.
   */
  val isFactory: Boolean = false,
  val lines: List<ProductionLine> = emptyList(),
  /**
   * The point's shared internal storage, one row per fill type. A line's [ProductionIo.type] joins
   * to a row here to show its buffered level (the game groups these into inputs vs outputs per the
   * selected line).
   */
  val storage: List<ProductionFill> = emptyList(),
)

/** One production ("recipe") of a production point. */
@Serializable
data class ProductionLine(
  /** Production id, stable within the point — the future setProductionState / outputMode key. */
  val id: String = "",
  val name: String = "",
  /**
   * Live status: `inactive`, `running`, `missingInputs`, `noOutputSpace`. A string token (the game's
   * enum key camelCased) rather than an enum so an unknown value renders neutrally instead of
   * breaking the parse.
   */
  val status: String = "inactive",
  /** Whether the line is switched on (independent of [status]; the on/off toggle state). */
  val enabled: Boolean = false,
  val cyclesPerMonth: Int = 0,
  /** Operating cost per in-game month while active (currency units). */
  val costsPerMonth: Int = 0,
  val inputs: List<ProductionIo> = emptyList(),
  val outputs: List<ProductionIo> = emptyList(),
)

/** One input or output of a production line (per-cycle recipe amount). */
@Serializable
data class ProductionIo(
  /** Fill type internal name; joins to [ProductionFill.type] in the owning point's storage. */
  val type: String = "",
  val title: String = "",
  /** Liters consumed (input) or produced (output) per cycle. */
  val amount: Int = 0,
  /**
   * Output distribution mode — `keep`, `directSell`, `autoDeliver`. Null on inputs (and if the mode
   * couldn't be read). The step-2 controls will target this per output fill type.
   */
  val mode: String? = null,
  /** True for a direct-sell output that is never buffered in storage. Null/false on inputs. */
  val sellDirectly: Boolean = false,
)

/**
 * A fill-type row in a shared storage. Used by a production point's internal storage here, and also
 * by a standalone silo on the sibling storage channel ([StandaloneStorage.fills]) — the shape is
 * identical, so the type is shared rather than duplicated.
 */
@Serializable
data class ProductionFill(
  val type: String = "",
  val title: String = "",
  /** Current fill level in liters. */
  val level: Int = 0,
  /** Storage capacity in liters. */
  val capacity: Int = 0,
)
