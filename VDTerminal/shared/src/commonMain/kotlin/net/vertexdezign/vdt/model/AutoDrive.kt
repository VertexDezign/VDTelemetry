package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

/**
 * **AutoDrive** on the machine you are in (FS25_AutoDrive, issue #97, mod version 34): what the mod's
 * HUD shows. Null when AutoDrive isn't installed or doesn't drive this machine. See the mod's
 * `src/integrations/AutoDrive.lua`.
 *
 * Every field is AutoDrive's own state, streamed from the server to every client, so a multiplayer
 * client reads what the host's AutoDrive is doing. The markers are named by their index in
 * AutoDrive's list ([AutoDriveTarget.id]) — the same ids [AutoDriveData.destinations] carries.
 *
 * While it drives, the machine's [Ai.active] is raised as well: AutoDrive is a helper.
 */
@Serializable
data class AutoDrive(
  val active: Boolean = false,
  val mode: AutoDriveMode = AutoDriveMode.OTHER,
  /** The modes this machine can be switched to, in AutoDrive's own cycle order (a train has no UNLOAD). */
  val modes: List<AutoDriveMode> = emptyList(),
  /** The destination — where [AutoDriveMode.DRIVE_TO] goes, and where the other modes pick up. */
  val target: AutoDriveTarget? = null,
  /** Where it unloads. Only in the modes that have one — see [AutoDriveMode.hasUnloadTarget]. */
  val unloadTarget: AutoDriveTarget? = null,
  /** The fill type it loads, by engine name. Only in the modes that load — see [AutoDriveMode.hasFillType]. */
  val fillType: String? = null,
  /** What the attached trailers can carry, as AutoDrive offers it. Null outside the loading modes. */
  val fillTypes: List<AutoDriveFillType>? = null,
  /** Rounds [AutoDriveMode.PICKUP_AND_DELIVER] runs; **0 is "until stopped"**. At most 99. */
  val loops: Int = 0,
  val loopsDone: Int = 0,
  /** What it is doing right now, in AutoDrive's words (localized). Only while [active]. */
  val task: String? = null,
  /**
   * Seconds to the destination. Only while [active], and only where AutoDrive estimates one: off the
   * field, with someone in the machine. Absent is "no estimate", not "arrived".
   */
  val remainingSeconds: Int? = null,
  /** The marker it is on its way to right now, which can differ from [target] mid-round. Only while [active]. */
  val heading: AutoDriveTarget? = null,
)

/** A map marker, by its index in AutoDrive's list and its name. */
@Serializable
data class AutoDriveTarget(val id: Int = 0, val name: String = "")

@Serializable
data class AutoDriveFillType(
  /** The engine's fill type name ("WHEAT") — what [net.vertexdezign.vdt.ClientMessage.SetAutoDriveFillType] sends. */
  val name: String = "",
  /** Localized. */
  val title: String = "",
)

/** AutoDrive's modes, as its mode button cycles them. */
@Serializable
enum class AutoDriveMode {
  DRIVE_TO,
  PICKUP_AND_DELIVER,
  DELIVER_TO,
  LOAD,
  UNLOAD,

  /** A mode this app doesn't know (a later AutoDrive). Never offered, only reported. */
  OTHER,
  ;

  /** Whether [AutoDrive.unloadTarget] means anything in this mode — the modes AutoDrive shows its second list in. */
  val hasUnloadTarget: Boolean get() = this == PICKUP_AND_DELIVER || this == LOAD || this == UNLOAD

  /** Whether the fill type means anything in this mode (AutoDrive's third list). */
  val hasFillType: Boolean get() = this == PICKUP_AND_DELIVER || this == LOAD

  /** Whether the loop counter means anything in this mode. */
  val hasLoops: Boolean get() = this == PICKUP_AND_DELIVER
}

/**
 * Typed model of the **autoDrive** channel the mod writes to `autoDrive.json` (separate file,
 * event-driven off AutoDrive's own destination listener — see the mod's
 * `src/integrations/AutoDrive.lua`): every destination of the route network.
 *
 * **Absent file = AutoDrive not installed**, like the other optional channels; an installed AutoDrive
 * on a map with no network yet writes an empty list.
 *
 * Its own [version], independent of [VdtData.version].
 */
@Serializable
data class AutoDriveData(val version: String = "", val destinations: List<AutoDriveDestination> = emptyList())

@Serializable
data class AutoDriveDestination(
  /** Its index in AutoDrive's list: what [AutoDriveTarget.id] refers to. Shifts when a marker before it is deleted. */
  val id: Int = 0,
  val name: String = "",
  /** The folder it is filed in; null for AutoDrive's catch-all "All". */
  val folder: String? = null,
  /** Normalized `[0,1]` map position, the frame of the map channels. */
  val posX: Float = 0f,
  val posZ: Float = 0f,
)
