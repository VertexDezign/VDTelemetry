package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

/**
 * Typed model of the **missions** channel the mod writes to `missions.json` (separate file,
 * event-driven plus a slow interval — see the mod's `src/collect/MissionExporter.lua`): the farm's
 * contracts, the same set the in-game contracts screen lists. What is still on offer, what this farm
 * is running, and what it has finished and not yet collected.
 *
 * Its own [version], independent of [VdtData.version]. Omitted keys fall back to these defaults.
 */
@Serializable
data class MissionsData(
  val version: String = "",
  /** How many contracts this farm may run at once. Absent (null) when no farm is resolved. */
  val limit: MissionLimit? = null,
  /**
   * Whether this player holds the game's `manageContracts` right. Drives whether the app offers the
   * accept/cancel/collect buttons at all — the server re-checks it, so this is the UI gate, not the
   * boundary.
   */
  val canManage: Boolean = false,
  val missions: List<Mission> = emptyList(),
)

/** The engine's per-farm contract cap and how much of it is used. */
@Serializable
data class MissionLimit(
  /** Contracts this farm has started (the count the engine's own limit check walks). */
  val active: Int = 0,
  /** `MissionManager.MAX_MISSIONS_PER_FARM` — 3 in the base game. */
  val max: Int = 0,
) {
  val isReached: Boolean get() = max > 0 && active >= max
}

/** One contract. */
@Serializable
data class Mission(
  /**
   * The mission's network object id — the handle a command addresses, and the only identifier that
   * agrees between server and client (the mod's `uniqueId` is savegame-only, see the exporter). Not
   * stable across sessions: it identifies a contract in the live game, nothing more.
   */
  val id: Int = 0,
  /**
   * Mission type name — `harvestMission`, `sowMission`, `deadwoodMission`, … A **label and icon
   * hint**, never a dispatcher: the set is open (mods register their own), so anything keyed on it
   * must fall back rather than assume.
   */
  val type: String = "",
  val title: String = "",
  val description: String = "",
  val status: MissionStatus = MissionStatus.CREATED,
  /** How it ended. Null while it hasn't — the mod omits the engine's `NONE`. */
  val finishState: MissionFinishState? = null,
  /** Localized location line ("Field 12"), as the contract list prints it. */
  val location: String = "",
  val npc: MissionNpc? = null,
  /** The offered reward, whole currency units. */
  val reward: Int = 0,
  /**
   * Finished contracts only: reward − vehicle costs − stealing + reimbursement, i.e. what collecting
   * it actually pays. Null while the contract is still on offer or running.
   */
  val totalReward: Int? = null,
  /** What taking this contract with leased equipment costs. Null when it offers none. */
  val vehicleCosts: Int? = null,
  /** True when the contract comes with equipment to lease. */
  val leasable: Boolean = false,
  /** Work done in `[0,1]`. Null before the contract is started. */
  val completion: Float? = null,
  /** In-game minutes until it times out. Null when the contract has no end date. */
  val minutesLeft: Int? = null,
  /** Localized progress line ("3 trees remaining"); running contracts only. */
  val extraProgress: String = "",
  /**
   * The farmland the contract sits on — the same id the map channel keys its field polygons by
   * ([MapField.id]), which is how the map tints a contract's field. Present on the point-located
   * types too (forestry, rock): they resolve the farmland under their spot.
   */
  val fieldId: Int? = null,
  /**
   * Field size in hectares. Only field missions have a field object, so **this** is what tells a
   * field contract from a point-located one — not [fieldId], which both carry.
   */
  val areaHa: Float? = null,
  /** Normalized `[0,1]` map x, the same frame as [Player.posX] and the map channel. */
  val posX: Float? = null,
  /** Normalized `[0,1]` map z. */
  val posZ: Float? = null,
  /** True when this farm is the one running it; false while it is on offer to everyone. */
  val own: Boolean = false,
  /**
   * What the contract is about beyond its type, already localized: the crop for a harvest or sowing
   * job, the bale form for a baling one, both when it names both. Empty when the contract names
   * neither (ploughing, mowing) — the mod assembles it, because the parts are the game's own strings.
   */
  val subtitle: String = "",
  /** Engine fruit token (`WHEAT`, `OAT`, …) when the contract names a crop; joins to fill types. */
  val fruitType: String? = null,
  /** The bale form a baling or wrapping contract asks for. Null when it asks for none. */
  val baleType: BaleForm? = null,
  /** Where the load has to be delivered — harvest and tree-transport contracts. */
  val sellingStation: MissionStation? = null,
  /**
   * The game's own contract detail rows, already localized and formatted — the crop, the selling
   * station, the field size, the per-tree reward, whatever this mission type prints. Once the
   * contract is finished this is the reward breakdown instead. The app renders them as given: the
   * whole point is that no client code knows what a harvest mission is.
   */
  val details: List<MissionDetail> = emptyList(),
) {
  /** On offer — nobody has taken it yet. */
  val isOffered: Boolean get() = status == MissionStatus.CREATED

  /** Being worked (or spinning up): the contract occupies one of the farm's slots. */
  val isActive: Boolean get() = status == MissionStatus.PREPARING || status == MissionStatus.RUNNING

  /** Over, and waiting to be collected. */
  val isFinished: Boolean get() = status == MissionStatus.FINISHED || status == MissionStatus.DISMISSED
}

/**
 * Where a contract's load is delivered. The position comes from the station placeable's **own map
 * hotspot** — the spot the game marks it at — so the app never has to match a station by name.
 */
@Serializable
data class MissionStation(
  val name: String = "",
  /** Normalized `[0,1]` map coordinates, same frame as [Mission.posX]. Null when unresolvable. */
  val posX: Float? = null,
  val posZ: Float? = null,
) {
  /** Whether this station can be drawn; a station may be nameable but not placeable. */
  val hasPosition: Boolean get() = posX != null && posZ != null
}

/** The bale form a contract asks for. */
@Serializable
enum class BaleForm {
  ROUND,
  SQUARE,
}

/** The farmer offering a contract. */
@Serializable
data class MissionNpc(
  val name: String = "",
  /** Engine-relative path to the portrait; empty when the NPC has none. */
  val image: String = "",
)

/** One row of the game's contract detail list — both sides already localized by the game. */
@Serializable
data class MissionDetail(val title: String = "", val value: String = "")

/** The engine's `MissionStatus`. */
@Serializable
enum class MissionStatus {
  /** Generated and on offer. */
  CREATED,

  /** Accepted, and the game is preparing it (field reset, leased vehicles spawning). */
  PREPARING,

  /** Being worked. */
  RUNNING,

  /** Over — see [Mission.finishState] for how. */
  FINISHED,

  /** Finished and collected; the payout has been made. */
  DISMISSED,
}

/** The engine's `MissionFinishState` — its `NONE` is modelled as a null [Mission.finishState]. */
@Serializable
enum class MissionFinishState {
  SUCCESS,
  FAILED,
  TIMED_OUT,
  CANCELED,
}
