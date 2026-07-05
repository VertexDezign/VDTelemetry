package net.vertexdezign.vdt

import kotlinx.serialization.Serializable

/**
 * Typed model of the VDT telemetry emitted by the mod into `vdTelemetry.json`.
 *
 * A single `@Serializable` type: the mod writes it as JSON, the server decodes it (see
 * [VdtParser]), and the same instances travel to the web client over the WebSocket.
 *
 * Everything the mod treats as situational is nullable with a default: the mod writes only what's
 * present and evolves faster than the client, so parsing ignores unknown keys and lets omitted
 * fields fall back to these defaults.
 */

// ---------------------------------------------------------------------------
// Enums (values match the JSON text verbatim)
// ---------------------------------------------------------------------------

@Serializable
enum class DriveDirection { FORWARD, BACKWARD, STOPPED }

@Serializable
enum class MotorState { OFF, STARTING, ON }

@Serializable
enum class FoldableState { FOLDED, EXTENDED }

@Serializable
enum class PipeState { RETRACTED, EXTENDED, MOVING }

@Serializable
enum class CoverType { CLOSED, OPEN, UNKNOWN }

// ---------------------------------------------------------------------------
// Root
// ---------------------------------------------------------------------------

@Serializable
data class VdtData(
    val version: String = "",
    val environment: Environment? = null,
    val vehicle: Vehicle? = null,
)

// ---------------------------------------------------------------------------
// Environment
// ---------------------------------------------------------------------------

@Serializable
data class Environment(
    val date: String? = null,
    val time: String? = null,
    val weather: Weather? = null,
    val pda: Pda? = null,
)

@Serializable
data class Weather(
    val temperature: Temperature? = null,
)

@Serializable
data class Temperature(
    val min: Int = 0,
    val max: Int = 0,
    val current: Int = 0,
    val unit: String = "",
)

/**
 * PDA / map data. `filename`/`width`/`height` are absent when the mod has no PDA reference, so they
 * are optional.
 */
@Serializable
data class Pda(
    val filename: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val player: Player? = null,
)

@Serializable
data class Player(
    val posX: Float = 0f,
    val posZ: Float = 0f,
)

// ---------------------------------------------------------------------------
// Vehicle
// ---------------------------------------------------------------------------

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

/** Shared fill-unit shape: an integer fill level plus descriptive attributes. */
@Serializable
data class FillUnit(
    val value: Int = 0,
    val type: String? = null,
    val title: String = "",
    val unit: String = "",
    val capacity: Int = 0,
    val fillLevelPercentage: Int = 0,
    val usage: Float? = null,
)

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

@Serializable
data class Gps(
    val enabled: Boolean = false,
    val active: Boolean = false,
    val heading: Int = 0,
    val headingUnit: String = "",
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
