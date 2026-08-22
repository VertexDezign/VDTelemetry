package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

/**
 * Typed model of the **fleet** channel the mod writes to `fleet.json` (separate file, interval-driven
 * cadence — see the mod's `src/collect/FleetExporter.lua`): every machine the local farm owns, with
 * its condition, its hours and what it is worth, plus Advanced Damage System's maintenance record
 * where that mod is installed.
 *
 * It is the game's own vehicle overview (ESC → Statistics) on a second screen — the list you consult
 * *before* getting in, which in game costs a pause. One row per machine rather than per rig: an
 * implement is listed on its own, as the game lists it, with [FleetVehicle.attachedTo] naming the rig
 * it is currently part of.
 *
 * Its own [version], independent of [VdtData.version]. Omitted keys fall back to these defaults.
 */
@Serializable
data class FleetData(
  val version: String = "",
  /**
   * Today, as the game counts it. Carried because the ADS log dates below are only meaningful
   * against it — "serviced in period 5 of year 2" is not something a reader can render on its own.
   */
  val date: GameDate? = null,
  val vehicles: List<FleetVehicle> = emptyList(),
)

/**
 * A date the way the game keeps them: [month] is the **period** (1..12), not a calendar month, and
 * [year] counts from the start of the save.
 */
@Serializable
data class GameDate(
  val year: Int = 0,
  val month: Int = 0,
  val day: Int = 1,
) {
  /** Months since [other], for the "3 months ago" the game's own maintenance screens print. */
  fun monthsSince(other: GameDate): Int = (year * 12 + month) - (other.year * 12 + other.month)
}

/**
 * One machine of the farm's fleet.
 *
 * **Condition has two sources and only one is right at a time.** [wearable] carries the vanilla
 * damage figure, which Advanced Damage System pins to 0 on every machine it manages — so a reader
 * takes [Ads.inspected][FleetAds.inspected] where [ads] is present and [wearable] otherwise.
 * Printing the vanilla figure on an ADS machine would report every tractor as brand new.
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

  /** Equipment lent with a contract — the farm's to use, not to keep. */
  MISSION,

  /** Neither, which for a machine on this list means the game gave no answer. */
  NONE,

  /** A configuration being assembled in the shop; never reaches this channel. */
  SHOP_CONFIG,
}

/**
 * What **Advanced Damage System** says about a machine nobody is sitting in — its own fleet menu, as
 * a data block. Null when ADS isn't installed, when the machine is an implement (ADS attaches to
 * motorized vehicles only), or when it is one of the machines ADS excludes.
 *
 * This is a different block from [Ads], and deliberately so: that one is the *dashboard* of the
 * machine you are in — lamps, load, temperatures, all live readings that say nothing about a tractor
 * parked in a shed. This one is the maintenance record.
 *
 * **Nothing here is a number ADS hides.** [inspected] is what an inspection told the player and
 * [breakdowns] holds only the faults they have already found; the mod's exact condition, service
 * level and undiscovered faults are what its workshop diagnostic is *for*, and are not on the wire in
 * any form — not even as a count.
 */
@Serializable
data class FleetAds(
  val state: AdsState = AdsState.READY,
  /** What the last inspection found. Null until the machine has had one. */
  val inspected: AdsInspected? = null,
  /** Hours since the last maintenance against the interval this machine wants. */
  val service: AdsService? = null,
  val lastInspection: GameDate? = null,
  val lastMaintenance: GameDate? = null,
  /** Only the faults the player has discovered, ordered by id so the list doesn't reshuffle itself. */
  val breakdowns: List<FleetBreakdown> = emptyList(),
  /** Present only while the machine is in a workshop, i.e. while [state] is not [AdsState.READY]. */
  val workshop: FleetWorkshop? = null,
  /** What this machine has cost in maintenance so far. */
  val maintenanceCost: Int? = null,
) {
  /** In a workshop right now: the one state where the machine cannot be worked. */
  val isInWorkshop: Boolean get() = state != AdsState.READY && state != AdsState.BROKEN

  /** Past the interval its manufacturer recommends — the same test that lights ADS's service lamp. */
  val isServiceOverdue: Boolean get() = (service?.fraction ?: 0f) > 1f

  /** Worth a look before this machine goes out: broken down, in the shop, or overdue for service. */
  val needsAttention: Boolean
    get() = state == AdsState.BROKEN || isInWorkshop || isServiceOverdue || breakdowns.isNotEmpty()
}

/** Where ADS has the machine: ready to work, in the shop for one of four jobs, or broken down. */
@Serializable
enum class AdsState { READY, INSPECTION, MAINTENANCE, REPAIR, OVERHAUL, BROKEN }

/**
 * One fault the player knows about, rendered the way ADS's own workshop dialog renders it: the part
 * it is on, and how bad the stage it has reached is.
 *
 * A fault ADS has *suspended* — quick-fixed, or left with a poor part — says that instead of its
 * stage, because that is what the player is actually looking at.
 */
@Serializable
data class FleetBreakdown(
  /** ADS's registry id (`ENGINE_OIL_LEAK`, …); stable, and what the list is ordered by. */
  val id: String = "",
  /** Localized part name, ADS's own — its `part`, falling back to the system the fault is in. */
  val part: String? = null,
  val severity: String? = null,
  val description: String? = null,
  /** Which stage the fault has progressed to; 1 is the first, and they get worse. */
  val stage: Int = 1,
)

/**
 * What the workshop is doing to a machine right now. Times are **in-game hours**: [remaining] is the
 * work left, [finishHour] the hour of the day it comes back and [finishInDays] how many day
 * rollovers away that is (0 = today).
 */
@Serializable
data class FleetWorkshop(
  val remaining: Float? = null,
  val finishHour: Float? = null,
  val finishInDays: Int = 0,
  /** What the service under way will cost. */
  val price: Int? = null,
)
