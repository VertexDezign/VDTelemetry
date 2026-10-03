package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

/**
 * Typed model of the **fleet** channel the mod writes to `fleet.json` (separate file, interval-driven
 * cadence — see the mod's `src/collect/FleetExporter.lua`): every machine the local farm owns, with
 * its condition, its hours and what it is worth, plus whether Advanced Damage System has it in the
 * workshop or due for service where that mod is installed.
 *
 * It is the game's own vehicle overview (ESC → Statistics) on a second screen — the list you consult
 * *before* getting in, which in game costs a pause. One row per machine rather than per rig: an
 * implement is listed on its own, as the game lists it, with [FleetVehicle.attachedTo] naming the rig
 * it is currently part of.
 *
 * It departs from that overview in exactly one place: **equipment a contract lent the farm is
 * listed** ([PropertyState.MISSION]), where the game drops it for not being the farm's to keep. It is
 * a machine you are driving today, which is the question this list answers — and it carries neither a
 * sell value nor a leasing rate, so nothing about it reads as owned.
 *
 * Its own [version], independent of [VdtData.version]. Omitted keys fall back to these defaults.
 */
@Serializable
data class FleetData(val version: String = "", val vehicles: List<FleetVehicle> = emptyList())

/**
 * One machine of the farm's fleet.
 *
 * **Condition is only known without ADS.** [wearable] carries the vanilla damage figure, which
 * Advanced Damage System pins to 0 on every machine it manages — so a reader takes condition from
 * [wearable] only where [ads] is absent, and reports none where it is present. Printing the vanilla
 * figure on an ADS machine would report every tractor as brand new.
 *
 * [id] is the network object id, not the game's `uniqueId`: the latter is nil on a multiplayer
 * client, so it cannot key a row. It is stable for a session, not across saves.
 */
@Serializable
data class FleetVehicle(
  val id: Int = 0,
  val name: String = "",
  /** `VehicleHotspot.TYPE` key, camelCased — the same tokens [MapVehicle.type] uses. */
  val type: String = "other",
  /** Localized store category ("Tractors", "Ploughs"), as both the game's and ADS's lists print it. */
  val category: String? = null,
  /** Age in months, the unit the game counts vehicle age in. */
  val age: Int = 0,
  /** Operating hours, to a tenth. A number rather than the game's `"1234.5 h"` so the list can sort. */
  val hours: Float = 0f,
  val propertyState: PropertyState = PropertyState.OWNED,
  /** What the game would pay for it right now. Owned machines only — a leased one has no sell value. */
  val sellPrice: Int? = null,
  /** Running plus per-day leasing cost, the game's own formula. Leased machines only. */
  val leasePerDay: Int? = null,
  /** Vanilla damage / wear / dirt. See the class note: under ADS the damage half is not a reading. */
  val wearable: Wearable? = null,
  /** What it is carrying, as the vehicle app shows it. */
  val fillUnits: FillUnits? = null,
  /** Fuel, DEF and air. Its presence is also what marks the machine as motorized. */
  val motorFillUnits: MotorFillUnits? = null,
  /** The rig's root vehicle, when this machine is attached to one. */
  val attachedTo: Int? = null,
  /** It drowned and is waiting for a reset (fleet version 2). See [Implement.broken]. */
  val broken: Boolean = false,
  val isAI: Boolean = false,
  /** A human is driving it — any player, in multiplayer. */
  val isControlled: Boolean = false,
  /** The local player is inside it. */
  val isEntered: Boolean = false,
  /**
   * Whether the machine is in the game's tab rotation — `Enterable:getIsTabbable()`. **Null when it
   * has no seat at all**, which is a different answer from being out of the rotation, and the reason
   * this is nullable rather than defaulted.
   *
   * See [isParked] for what a `false` is taken to mean.
   */
  val isTabbable: Boolean? = null,
  /** Normalized `[0,1]` map position, the same frame as the map channels — what "show on map" uses. */
  val posX: Float? = null,
  val posZ: Float? = null,
  val ads: FleetAds? = null,
) {
  /** Whether it has an engine, which is also what separates a machine from an implement in the list. */
  val isMotorized: Boolean get() = motorFillUnits != null

  /**
   * **Put away**: a machine with a seat that has been taken out of the tab rotation. That flag is how
   * the parking mods mark a machine as parked (`setIsTabbable(false)`), and it is the player's own
   * deliberate act, so it is worth saying on the list — unlike [isIdle], which is merely the absence
   * of a driver right now.
   *
   * The one thing it cannot tell apart: a machine whose *own* XML ships `isTabbable="false"` reads as
   * parked too. Those are machines the game already treats as not part of the fleet (it leaves them
   * out of its own vehicle statistics for the same reason), so the word is not far wrong on them.
   */
  val isParked: Boolean get() = isTabbable == false

  /** Nobody has it right now: no helper, no driver. Says nothing about whether it is [isParked]. */
  val isIdle: Boolean get() = !isAI && !isControlled
}

/** How the farm holds a machine. */
@Serializable
enum class PropertyState {
  OWNED,
  LEASED,

  /**
   * Equipment lent with a contract — the farm's to use, not to keep, and gone when the contract ends.
   * The game's own overview omits these; this channel keeps them (see [FleetData]).
   */
  MISSION,

  /** Neither, which for a machine on this list means the game gave no answer. */
  NONE,

  /** A configuration being assembled in the shop; never reaches this channel. */
  SHOP_CONFIG,
}

/**
 * What **Advanced Damage System** says about a machine nobody is sitting in: whether it is in the
 * workshop, and where it is in its service interval. Null when ADS isn't installed, when the machine
 * is an implement (ADS attaches to motorized vehicles only), or when it is one of the machines ADS
 * excludes.
 *
 * This is a different block from [Ads], and deliberately so: that one is the *dashboard* of the
 * machine you are in — lamps, load, temperatures, all live readings that say nothing about a tractor
 * parked in a shed.
 *
 * Deliberately this small since fleet version 3: ADS 0.9.9.3 rewrote the inspection record, the
 * breakdowns and the workshop jobs the rest of its fleet menu was read from, so those are not on the
 * wire until that mod settles. Nothing here is a number ADS hides.
 */
@Serializable
data class FleetAds(
  /**
   * Defaulted to [AdsState.UNKNOWN] rather than to [AdsState.READY], and that is the point of the
   * value existing: the parser coerces a token it doesn't know onto this default, and a state a later
   * ADS invents must not read as "ready to work" on a machine that is standing in a workshop.
   */
  val state: AdsState = AdsState.UNKNOWN,
  /** Hours since the last maintenance against the interval this machine wants. */
  val service: AdsService? = null,
) {
  /**
   * In a workshop right now: the one state where the machine cannot be worked. Named states rather
   * than "everything that isn't READY or BROKEN", so [AdsState.UNKNOWN] is not asserted to be in a
   * workshop it was never read as being in.
   */
  val isInWorkshop: Boolean
    get() = state == AdsState.INSPECTION || state == AdsState.MAINTENANCE || state == AdsState.REPAIR

  /** Past the interval its manufacturer recommends — the same test that lights ADS's service lamp. */
  val isServiceOverdue: Boolean get() = (service?.fraction ?: 0f) > 1f

  /**
   * Worth a look before this machine goes out: broken down, in the shop, overdue for service — or in
   * a state this build cannot name, which is not READY whatever else it is.
   */
  val needsAttention: Boolean
    get() = state == AdsState.BROKEN || state == AdsState.UNKNOWN || isInWorkshop || isServiceOverdue
}

/**
 * Where ADS has the machine: ready to work, in the shop for one of three jobs, or broken down.
 *
 * [UNKNOWN] is the fifth answer and never comes from ADS: it is what a state token this build does
 * not know decodes to — a state a later ADS added, or one the mod could not resolve. It is reported
 * as unknown rather than guessed at, because the only guess available would be the reassuring one.
 */
@Serializable
enum class AdsState { READY, INSPECTION, MAINTENANCE, REPAIR, BROKEN, UNKNOWN }
