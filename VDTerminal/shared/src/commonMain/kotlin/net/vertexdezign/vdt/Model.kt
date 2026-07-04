package net.vertexdezign.vdt

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName
import nl.adaptivity.xmlutil.serialization.XmlValue

/**
 * Typed model of the VDT telemetry schema (`vdTelemetrySchema.xsd`) as emitted by the mod
 * into `vdTelemetry.xml`.
 *
 * The classes carry xmlutil annotations so the server can decode them from XML, and are plain
 * `@Serializable` so the same instances travel to the web client as JSON over the WebSocket.
 *
 * Everything the schema/mod treats as situational is nullable with a default: the fixtures lag
 * the schema (no `weather`/`brand`/`pda`), and the mod evolves faster than the client. Parsing is
 * configured to ignore unknown children (see [VdtParser]).
 */

// ---------------------------------------------------------------------------
// Enums (values match the XML text / attribute values verbatim)
// ---------------------------------------------------------------------------

@Serializable
enum class DriveDirection { FORWARD, BACKWARD, STOPPED }

@Serializable
enum class MotorState { OFF, STARTING, ON }

@Serializable
enum class FoldableState { FOLDED, EXTENDED }

@Serializable
enum class PipeState { RETRACTED, EXTENDED }

@Serializable
enum class CoverType { CLOSED, OPEN, UNKNOWN }

// ---------------------------------------------------------------------------
// Root
// ---------------------------------------------------------------------------

@Serializable
@XmlSerialName("VDT")
data class VdtData(
    @XmlElement(false) val version: String = "",
    val environment: Environment? = null,
    val vehicle: Vehicle? = null,
)

// ---------------------------------------------------------------------------
// Environment
// ---------------------------------------------------------------------------

@Serializable
@XmlSerialName("environment")
data class Environment(
    @XmlElement(true) val date: String? = null,
    @XmlElement(true) val time: String? = null,
    val weather: Weather? = null,
    val pda: Pda? = null,
)

@Serializable
@XmlSerialName("weather")
data class Weather(
    val temperature: Temperature? = null,
)

@Serializable
@XmlSerialName("temperature")
data class Temperature(
    @XmlElement(false) val min: Int = 0,
    @XmlElement(false) val max: Int = 0,
    @XmlElement(false) val current: Int = 0,
    @XmlElement(false) val unit: String = "",
)

/**
 * PDA / map data. Emitted by the mod (`VDTelemetry.lua`) but not yet in the xsd/fixtures.
 * `filename`/`width`/`height` are absent when the mod has no PDA reference, so they are optional.
 */
@Serializable
@XmlSerialName("pda")
data class Pda(
    @XmlElement(false) val filename: String? = null,
    @XmlElement(false) val width: Int? = null,
    @XmlElement(false) val height: Int? = null,
    val player: Player? = null,
)

@Serializable
@XmlSerialName("player")
data class Player(
    @XmlElement(false) val posX: Float = 0f,
    @XmlElement(false) val posZ: Float = 0f,
)

// ---------------------------------------------------------------------------
// Vehicle
// ---------------------------------------------------------------------------

@Serializable
@XmlSerialName("vehicle")
data class Vehicle(
    @XmlElement(false) val name: String = "",
    @XmlElement(false) val type: String = "",
    val speed: Speed? = null,
    val brand: Brand? = null,
    val operatingTime: OperatingTime? = null,
    val motor: Motor? = null,
    val lights: Lights? = null,
    val gps: Gps? = null,
    val ai: Ai? = null,
    val cruiseControl: CruiseControl? = null,
    // Field order follows the mod's emission order (VDTelemetry.lua).
    @XmlElement(true) val isTurnedOn: Boolean? = null,
    @XmlElement(true) val foldable: String? = null,
    @XmlElement(true) val lowered: Boolean? = null,
    @XmlSerialName("fillUnits") val fillUnits: FillUnits? = null,
    @XmlElement(true) val pipe: String? = null,
    @XmlElement(true) val cover: String? = null,
    val wearable: Wearable? = null,
    @XmlSerialName("implement") val implement: List<Implement> = emptyList(),
    val combined: Combined? = null,
)

@Serializable
@XmlSerialName("speed")
data class Speed(
    @XmlValue val value: Float = 0f,
    @XmlElement(false) val unit: String = "",
    @XmlElement(false) val direction: DriveDirection = DriveDirection.STOPPED,
)

@Serializable
@XmlSerialName("brand")
data class Brand(
    @XmlElement(false) val name: String? = null,
    @XmlElement(false) val title: String? = null,
)

@Serializable
@XmlSerialName("operatingTime")
data class OperatingTime(
    @XmlValue val value: String = "",
    @XmlElement(false) val unit: String = "",
)

// ---------------------------------------------------------------------------
// Motor
// ---------------------------------------------------------------------------

@Serializable
@XmlSerialName("motor")
data class Motor(
    @XmlElement(false) val state: MotorState = MotorState.OFF,
    val temperatur: Temperatur? = null,
    val rpm: Rpm? = null,
    val load: Load? = null,
    val gear: Gear? = null,
    val maxSpeed: MaxSpeed? = null,
    @XmlSerialName("fillUnits") val fillUnits: MotorFillUnits? = null,
)

@Serializable
@XmlSerialName("temperatur")
data class Temperatur(
    @XmlValue val value: Int = 0,
    @XmlElement(false) val min: Int = 0,
    @XmlElement(false) val max: Int = 0,
    @XmlElement(false) val unit: String = "",
)

@Serializable
@XmlSerialName("rpm")
data class Rpm(
    @XmlValue val value: Int = 0,
    @XmlElement(false) val min: Int = 0,
    @XmlElement(false) val max: Int = 0,
)

@Serializable
@XmlSerialName("load")
data class Load(
    @XmlValue val value: Double = 0.0,
    @XmlElement(false) val min: Int = 0,
    @XmlElement(false) val max: Int = 0,
    @XmlElement(false) val unit: String = "",
)

@Serializable
@XmlSerialName("gear")
data class Gear(
    @XmlValue val value: String = "",
    @XmlElement(false) val isNeutral: Boolean = false,
    @XmlElement(false) val group: String = "",
)

@Serializable
@XmlSerialName("maxSpeed")
data class MaxSpeed(
    @XmlElement(false) val forward: Int? = null,
    @XmlElement(false) val backward: Int? = null,
)

/** Motor fill units use fixed, named children (`fuel`/`def`/`air`) — distinct from [FillUnits]. */
@Serializable
@XmlSerialName("fillUnits")
data class MotorFillUnits(
    @XmlSerialName("fuel") val fuel: FillUnit? = null,
    @XmlSerialName("def") val def: FillUnit? = null,
    @XmlSerialName("air") val air: FillUnit? = null,
)

// ---------------------------------------------------------------------------
// Fill units (repeated <fillUnit> form, used by vehicle / implement / combined)
// ---------------------------------------------------------------------------

@Serializable
@XmlSerialName("fillUnits")
data class FillUnits(
    @XmlSerialName("fillUnit") val fillUnit: List<FillUnit> = emptyList(),
)

/** Shared fill-unit shape: an integer fill level plus descriptive attributes. */
@Serializable
data class FillUnit(
    @XmlValue val value: Int = 0,
    @XmlElement(false) val type: String? = null,
    @XmlElement(false) val title: String = "",
    @XmlElement(false) val unit: String = "",
    @XmlElement(false) val capacity: Int = 0,
    @XmlElement(false) val fillLevelPercentage: Int = 0,
    @XmlElement(false) val usage: Float? = null,
)

// ---------------------------------------------------------------------------
// Lights
// ---------------------------------------------------------------------------

@Serializable
@XmlSerialName("lights")
data class Lights(
    val indicator: Indicator? = null,
    @XmlElement(true) val beaconLight: Boolean? = null,
    val light: Light? = null,
    val workLight: WorkLight? = null,
)

@Serializable
@XmlSerialName("indicator")
data class Indicator(
    @XmlElement(false) val left: Boolean = false,
    @XmlElement(false) val right: Boolean = false,
    @XmlElement(false) val hazard: Boolean = false,
)

@Serializable
@XmlSerialName("light")
data class Light(
    @XmlElement(false) val lowBeam: Boolean = false,
    @XmlElement(false) val highBeam: Boolean = false,
)

@Serializable
@XmlSerialName("workLight")
data class WorkLight(
    @XmlElement(false) val front: Boolean = false,
    @XmlElement(false) val back: Boolean = false,
)

// ---------------------------------------------------------------------------
// GPS / AI / cruise control
// ---------------------------------------------------------------------------

@Serializable
@XmlSerialName("gps")
data class Gps(
    @XmlElement(false) val enabled: Boolean = false,
    @XmlElement(false) val active: Boolean = false,
    @XmlElement(false) val heading: Int = 0,
    @XmlElement(false) val headingUnit: String = "",
)

@Serializable
@XmlSerialName("ai")
data class Ai(
    @XmlElement(false) val active: Boolean = false,
)

@Serializable
@XmlSerialName("cruiseControl")
data class CruiseControl(
    @XmlElement(false) val targetSpeed: Int? = null,
    @XmlElement(false) val active: Boolean? = null,
)

// ---------------------------------------------------------------------------
// Wear
// ---------------------------------------------------------------------------

@Serializable
@XmlSerialName("wearable")
data class Wearable(
    @XmlElement(false) val damage: Int = 0,
    @XmlElement(false) val wear: Int = 0,
    @XmlElement(false) val dirt: Int = 0,
    @XmlElement(false) val unit: String = "",
)

// ---------------------------------------------------------------------------
// Implements (recursive) + combined
// ---------------------------------------------------------------------------

@Serializable
@XmlSerialName("implement")
data class Implement(
    @XmlElement(false) val position: String = "",
    @XmlElement(false) val name: String = "",
    @XmlElement(false) val type: String = "",
    // Order matches the mod's emission (isTurnedOn, foldable, lowered).
    @XmlElement(true) val isTurnedOn: Boolean? = null,
    @XmlElement(true) val foldable: String? = null,
    @XmlElement(true) val lowered: Boolean? = null,
    @XmlSerialName("fillUnits") val fillUnits: FillUnits? = null,
    @XmlElement(true) val pipe: String? = null,
    // xsd types implement `cover` as boolean, but the mod emits CoverType strings ("CLOSED").
    // Keep it a lenient String so neither form breaks parsing.
    @XmlElement(true) val cover: String? = null,
    val wearable: Wearable? = null,
    @XmlSerialName("implement") val implement: List<Implement> = emptyList(),
)

@Serializable
@XmlSerialName("combined")
data class Combined(
    @XmlSerialName("fillUnits") val fillUnits: FillUnits? = null,
    val wearable: Wearable? = null,
    @XmlSerialName("implement") val implement: CombinedImplement? = null,
)

@Serializable
@XmlSerialName("implement")
data class CombinedImplement(
    @XmlSerialName("front") val front: CombinedImplementState? = null,
    @XmlSerialName("back") val back: CombinedImplementState? = null,
)

@Serializable
data class CombinedImplementState(
    // Combined states emit in a different order than implements: isTurnedOn, lowered, foldable.
    @XmlElement(true) val isTurnedOn: Boolean? = null,
    @XmlElement(true) val lowered: Boolean? = null,
    @XmlElement(true) val foldable: String? = null,
)

// ---------------------------------------------------------------------------
// Typed accessors for enum-valued *elements*.
//
// xmlutil decodes enum-typed *attributes* (e.g. speed.direction, motor.state) fine, but not
// enum-typed *element text*. So those elements are stored as raw strings and parsed leniently
// here — an unknown/future value yields null instead of crashing the whole document.
// ---------------------------------------------------------------------------

fun String?.toFoldableState(): FoldableState? =
    this?.let { runCatching { FoldableState.valueOf(it) }.getOrNull() }

fun String?.toPipeState(): PipeState? =
    this?.let { runCatching { PipeState.valueOf(it) }.getOrNull() }

fun String?.toCoverType(): CoverType? =
    this?.let { runCatching { CoverType.valueOf(it) }.getOrNull() }
