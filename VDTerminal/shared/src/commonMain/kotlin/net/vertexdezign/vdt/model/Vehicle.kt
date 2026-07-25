package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

@Serializable
data class Vehicle(
  val name: String = "",
  val type: String = "",
  val speed: Speed? = null,
  val brand: Brand? = null,
  val operatingTime: OperatingTime? = null,
  val motor: Motor? = null,
  val lights: Lights? = null,
  val gps: Gps? = null,
  val ai: Ai? = null,
  val cruiseControl: CruiseControl? = null,
  val isTurnedOn: Boolean? = null,
  val foldable: FoldableState? = null,
  val lowered: Boolean? = null,
  val fillUnits: FillUnits? = null,
  val pipe: PipeState? = null,
  val cover: CoverType? = null,
  val wearable: Wearable? = null,
  val implement: List<Implement> = emptyList(),
  val combined: Combined? = null,
)

@Serializable
data class Speed(
  val value: Float = 0f,
  val unit: String = "",
  val direction: DriveDirection = DriveDirection.STOPPED,
)

@Serializable
data class Brand(
  val name: String? = null,
  val title: String? = null,
)

@Serializable
data class OperatingTime(
  val value: String = "",
  val unit: String = "",
)

// ---------------------------------------------------------------------------
// Motor
// ---------------------------------------------------------------------------

@Serializable
data class Motor(
  val state: MotorState = MotorState.OFF,
  val temperatur: Temperatur? = null,
  val rpm: Rpm? = null,
  val load: Load? = null,
  val gear: Gear? = null,
  val maxSpeed: MaxSpeed? = null,
  val fillUnits: MotorFillUnits? = null,
)

@Serializable
data class Temperatur(
  val value: Int = 0,
  val min: Int = 0,
  val max: Int = 0,
  val unit: String = "",
)

@Serializable
data class Rpm(
  val value: Int = 0,
  val min: Int = 0,
  val max: Int = 0,
)

@Serializable
data class Load(
  val value: Double = 0.0,
  val min: Int = 0,
  val max: Int = 0,
  val unit: String = "",
)

@Serializable
data class Gear(
  val value: String = "",
  val isNeutral: Boolean = false,
  val group: String = "",
)

@Serializable
data class MaxSpeed(
  val forward: Int? = null,
  val backward: Int? = null,
)

/** Motor fill units use fixed, named children (`fuel`/`def`/`air`) — distinct from [FillUnits]. */
@Serializable
data class MotorFillUnits(
  val fuel: FillUnit? = null,
  val def: FillUnit? = null,
  val air: FillUnit? = null,
)

// ---------------------------------------------------------------------------
// Fill units (repeated `fillUnit` form, used by vehicle / implement / combined)
// ---------------------------------------------------------------------------

@Serializable
data class FillUnits(
  val fillUnit: List<FillUnit> = emptyList(),
)

/**
 * Shared fill-unit shape: a fill level plus descriptive attributes.
 *
 * [value] is fractional, not a count of litres. A `Consumable` unit (bale net / twine / wrap) is
 * measured in **slots**, and the mod reports the game's display level — spare rolls in storage *plus*
 * the partially-used one on the machine — so one spare and a half-used roll reads `1.5`. Rendering it
 * as a whole number is a per-unit decision: see [precision] and [display].
 */
@Serializable
data class FillUnit(
  val value: Float = 0f,
  val type: String? = null,
  val title: String = "",
  val unit: String = "",
  val capacity: Int = 0,
  val fillLevelPercentage: Int = 0,
  val usage: Float? = null,
  /** Decimals the game prints for this unit. Absent means 0 — *not* a rounding hint for [value]. */
  val precision: Int = 0,
  val display: FillDisplayType = FillDisplayType.BAR,
)

/**
 * How the game draws this fill unit's level.
 *
 * [STEP] means one segment per unit of capacity, with the current segment filled fractionally — how
 * consumables are shown, where capacity is a slot count. [BAR] is the default continuous bar.
 */
@Serializable
enum class FillDisplayType { BAR, STEP }

// ---------------------------------------------------------------------------
// Lights
// ---------------------------------------------------------------------------

@Serializable
data class Lights(
  val indicator: Indicator? = null,
  val beaconLight: Boolean? = null,
  val light: Light? = null,
  val workLight: WorkLight? = null,
)

@Serializable
data class Indicator(
  val left: Boolean = false,
  val right: Boolean = false,
  val hazard: Boolean = false,
)

@Serializable
data class Light(
  val lowBeam: Boolean = false,
  val highBeam: Boolean = false,
)

@Serializable
data class WorkLight(
  val front: Boolean = false,
  val back: Boolean = false,
)

// ---------------------------------------------------------------------------
// GPS / AI / cruise control
// ---------------------------------------------------------------------------

/**
 * [linesVisible] is the odd one out: the steering-assist guide lines are drawn from a *global* client
 * setting, not from vehicle state. It rides here because it only means anything for a vehicle that
 * has the steering spec — which is exactly when the mod emits a `gps` subtree at all.
 */
@Serializable
data class Gps(
  val enabled: Boolean = false,
  val active: Boolean = false,
  val heading: Int = 0,
  val headingUnit: String = "",
  val linesVisible: Boolean = false,
)

@Serializable
data class Ai(
  val active: Boolean = false,
)

@Serializable
data class CruiseControl(
  val targetSpeed: Float? = null,
  val active: Boolean? = null,
)

// ---------------------------------------------------------------------------
// Wear
// ---------------------------------------------------------------------------

@Serializable
data class Wearable(
  val damage: Int = 0,
  val wear: Int = 0,
  val dirt: Int = 0,
  val unit: String = "",
)

// ---------------------------------------------------------------------------
// Implements (recursive) + combined
// ---------------------------------------------------------------------------

@Serializable
data class Implement(
  val position: String = "",
  val name: String = "",
  val type: String = "",
  val isTurnedOn: Boolean? = null,
  val foldable: FoldableState? = null,
  val lowered: Boolean? = null,
  val fillUnits: FillUnits? = null,
  val pipe: PipeState? = null,
  val cover: CoverType? = null,
  val wearable: Wearable? = null,
  val implement: List<Implement> = emptyList(),
)

@Serializable
data class Combined(
  val fillUnits: FillUnits? = null,
  val wearable: Wearable? = null,
  val implement: CombinedImplement? = null,
)

@Serializable
data class CombinedImplement(
  val front: CombinedImplementState? = null,
  val back: CombinedImplementState? = null,
)

@Serializable
data class CombinedImplementState(
  val isTurnedOn: Boolean? = null,
  val lowered: Boolean? = null,
  val foldable: FoldableState? = null,
)
