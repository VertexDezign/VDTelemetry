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
  /**
   * The farm's multi-placeable constructions — today only Pumps & Hoses biogas plants, of which there
   * are none in a base game (the list is then absent). Each entry names a group that the
   * [productionPoints] above join by [Construction.id] via their own [ProductionPoint.construction],
   * and describes the parts of the plant that are **not** production points.
   */
  val constructions: List<Construction> = emptyList(),
)

/**
 * One construction built out of several placeables — a Pumps & Hoses **biogas plant**, the only kind
 * there is. Its parts are separate buildings in the world (fermenters, a cogeneration unit, bunkers, a
 * digestate tank, a gas torch) that the DLC wires into one plant around a root placeable, and the game
 * presents as one thing under one name.
 *
 * Two of its parts are production points and appear in [ProductionData.productionPoints] — the
 * fermenters as one merged entry and the cogeneration units as another, each already carrying every
 * machine's recipe summed together, which is how the game's own production menu lists them. Everything
 * else about the plant is in [parts].
 */
@Serializable
data class Construction(
  /** The root placeable's stable id — what every part of this plant joins on, on both channels. */
  val id: String = "",
  /** What sort of construction it is; `bga` is the only one the DLC builds. */
  val kind: String = "bga",
  /** Display name, index and all: `BGA (1)`, or whatever the player renamed it to. */
  val name: String = "",
  /**
   * One row per part **type** the plant has, however many machines that is — ordered the way the plant
   * works, input first: bunkers, fermenters, cogeneration, digestate tank, torch.
   */
  val parts: List<ConstructionPart> = emptyList(),
)

/** One part type of a [Construction], standing for every machine of that type in the plant. */
@Serializable
data class ConstructionPart(
  /** `BUNKER`, `FERMENTER`, `POWERPLANT`, `SILO` or `TORCH`. A token, so an unknown one renders neutrally. */
  val role: String = "",
  /** How many machines of that role the plant has. */
  val count: Int = 0,
  /**
   * The DLC's own utilization reading, in percent — and deliberately **not** clamped to 100: a plant
   * running past the rate its inputs can sustain reads above it, and so does a torch, which exists to
   * burn exactly that surplus.
   *
   * Null where the part cannot answer. It is also meaningless — a flat `0` — for `SILO`, the one role
   * Pumps & Hoses 1.0.0.0 never implemented (its own panel prints "Silos: 0%" for the same reason).
   * Draw that row from [fills] instead.
   */
  val utilization: Int? = null,
  /**
   * What the part is holding, summed over every machine of that role. Only `BUNKER` and `SILO` hold
   * anything: a fermenter's liters are its production point's storage, reported there.
   *
   * The digestate tank's rows are deliberately on **both** channels — here, because the plant view
   * needs them, and in [StorageData.storages], because the tank really is a store the farm pumps out
   * of. The bunkers are only here: material driven into one is pumped on into the fermenters and
   * never comes back out, so it is stock in the same sense a production point's inputs are, which is
   * to say not at all.
   */
  val fills: List<ProductionFill> = emptyList(),
)

/**
 * Which [Construction] something is one part of, carried by a production point and by a storage alike
 * so the two channels can be read as one plant. Null/absent for anything standing on its own, which is
 * everything in a base game.
 */
@Serializable
data class ConstructionRef(
  /** The plant's [Construction.id] — the join key. */
  val id: String = "",
  /** The plant's [Construction.name], repeated here so the storage channel can name it alone. */
  val name: String = "",
  /** This part's role: `BUNKER`, `FERMENTER`, `POWERPLANT`, `SILO` or `TORCH`. */
  val role: String = "",
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
  /**
   * The construction this point is one part of — set only when a mod builds production points out of
   * several placeables, which today means a Pumps & Hoses biogas plant, whose fermenter entry and
   * cogeneration entry are two points of one plant. Null for a point that stands on its own.
   */
  val construction: ConstructionRef? = null,
  val lines: List<ProductionLine> = emptyList(),
  /**
   * The point's shared internal storage, one row per fill type. A line's [ProductionIo.type] joins
   * to a row here to show its buffered level (the game groups these into inputs vs outputs per the
   * selected line).
   *
   * Read through the point rather than off its tank, so a point whose stations reach further than its
   * own storage — a merged biogas plant, a storage extension standing in range — reports what the
   * game's own production menu prints for it.
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
