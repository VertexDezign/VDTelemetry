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
  val pipe: Pipe? = null,
  val cover: Cover? = null,
  val wearable: Wearable? = null,
  val schema: Schema? = null,
  val selection: Selection? = null,
  val discharge: Discharge? = null,
  val tipping: Tipping? = null,
  val harvest: Harvest? = null,
  val workMode: WorkMode? = null,
  val workWidth: WorkWidth? = null,
  val baleCounter: BaleCounter? = null,
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
  // The three drivetrain telltales below come from the optional Enhanced Vehicle integration
  // (FS25_EnhancedVehicle), which only decorates the *controlled* vehicle's motor. `null` means "we
  // don't know" — the mod isn't installed, or it doesn't manage this vehicle — and must never be
  // rendered as "off": an unlit diff-lock lamp is a claim, and we don't have the state to make it.
  val diffLock: DiffLock? = null,
  val awd: Boolean? = null,
  val parkingBrake: Boolean? = null,
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

/**
 * Front / rear differential locks, from Enhanced Vehicle.
 *
 * The two sides are reported independently and each is null on its own: the integration only sets a
 * side once Enhanced Vehicle hands it a boolean, so a tractor with a rear lock only yields
 * `front == null`. Same rule as [Motor.diffLock] itself — null is "unknown", not "unlocked".
 */
@Serializable
data class DiffLock(
  val front: Boolean? = null,
  val back: Boolean? = null,
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
// Pipe / cover
// ---------------------------------------------------------------------------

/**
 * Unloading pipe (combines, auger wagons).
 *
 * The game reads positions from the vehicle's XML, so [numStates] is often greater than 2 and
 * [state] alone can't distinguish "half out" from "fully out". [current] is `0` *while the pipe is
 * moving* and `1..numStates` once it settles, where `1` is fully retracted; [target] is where it is
 * heading, so `current != target` means it is still travelling.
 */
@Serializable
data class Pipe(
  val state: PipeState = PipeState.RETRACTED,
  val current: Int = 0,
  val target: Int = 0,
  val numStates: Int = 0,
)

/**
 * Tarp / cover. A vehicle can have several (a trailer with separate sections): [index] is `0` when
 * everything is closed, otherwise the 1-based index of the open one, out of [count].
 */
@Serializable
data class Cover(
  val state: CoverType = CoverType.CLOSED,
  val index: Int = 0,
  val count: Int = 0,
)

// ---------------------------------------------------------------------------
// Schema (rig diagram) + selection
// ---------------------------------------------------------------------------

/**
 * The object's silhouette in the game's rig diagram, and where children hang off it.
 *
 * These are the engine's raw values — the mod does no layout arithmetic, so composing offsets,
 * rotations and [SchemaJoint.invertX] down the implement tree is this side's job. The game's own
 * implementation (`InputHelpDisplay.collectVehicleSchemaDisplayOverlays`) is the reference: each
 * child indexes its parent's [attacherJoint] list via [Implement.jointDescIndex], and the walk is
 * depth-capped at 5.
 */
@Serializable
data class Schema(
  /** `VEHICLE` / `HARVESTER` / `TRAILER` / … — mod-prefixed for modded silhouettes, `""` if unnamed. */
  val name: String = "",
  val offsetX: Float = 0f,
  val offsetY: Float = 0f,
  /** Share of the silhouette's width that is padding, so neighbours can be butted up against it. */
  val borderLeft: Float? = null,
  val borderRight: Float? = null,
  val attacherJoint: List<SchemaJoint> = emptyList(),
)

/** One attachment point in a [Schema]: where a child sits relative to this object. */
@Serializable
data class SchemaJoint(
  val x: Float = 0f,
  val y: Float = 0f,
  val rotation: Float = 0f,
  val invertX: Boolean = false,
  /** Extra offset applied while the child is raised rather than lowered. */
  val liftedOffsetX: Float = 0f,
  val liftedOffsetY: Float = 0f,
)

/**
 * What the player's controls are currently acting on. [selected] is the engine's own flag, mirrored
 * onto every object in the rig, so exactly one node in the tree is normally true.
 */
@Serializable
data class Selection(
  val selected: Boolean = false,
  val controlGroup: ControlGroup? = null,
)

/**
 * The moving-tool group being cycled on a `Cylindered` object (a crane or front loader splits its
 * controls into named groups). [current] is `0` when none is active, otherwise a 1-based index into
 * [names]; [name] is the resolved entry. The game's HUD only ever shows the number.
 */
@Serializable
data class ControlGroup(
  val current: Int = 0,
  val name: String? = null,
  val names: List<String> = emptyList(),
)

// ---------------------------------------------------------------------------
// Unloading / tipping / work configuration
// ---------------------------------------------------------------------------

/**
 * Unloading state — trailers, combines, auger wagons.
 *
 * Distinct from [Pipe], which only says where the spout is: this says whether material is actually
 * moving and, via [reason], why it isn't. That reason is the engine's own verdict, the same one
 * behind its on-screen warning, and is not something a dashboard could derive for itself.
 */
@Serializable
data class Discharge(
  val state: DischargeState = DischargeState.OFF,
  /** Whether unloading is permitted at all; some specs latch this off (e.g. while folding). */
  val allowed: Boolean = true,
  val nodeIndex: Int? = null,
  val fillUnitIndex: Int? = null,
  /** Something fillable is under the discharge node right now. */
  val hasObject: Boolean? = null,
  /** The node is pointed at terrain, i.e. tipping on the ground is physically possible here. */
  val hitTerrain: Boolean? = null,
  val reason: DischargeReason? = null,
)

@Serializable
enum class DischargeState { OFF, OBJECT, GROUND }

/** Why the game is refusing to unload. `null` means nothing is wrong. */
@Serializable
enum class DischargeReason {
  NOT_ALLOWED_HERE,
  NO_FREE_CAPACITY,
  FILLTYPE_NOT_SUPPORTED,
  TOOLTYPE_NOT_SUPPORTED,
  NO_ACCESS,
  NO_ACCESS_LAND,
}

/**
 * The trough itself moving, as opposed to material leaving it ([Discharge]). A tipper can sit in
 * [TipState.OPENING] with nothing coming out yet.
 *
 * [side] is which tip side is in use on a multi-way tipper and is null until one is chosen;
 * [preferredSide] is the one the next tip will use, so it is always set.
 */
@Serializable
data class Tipping(
  val state: TipState = TipState.CLOSED,
  val side: Int? = null,
  val preferredSide: Int? = null,
  val count: Int? = null,
)

@Serializable
enum class TipState { CLOSED, OPENING, OPEN, CLOSING }

/**
 * Combine straw handling: drop a swath to bale later, or chop it back onto the field. The
 * `*Available` flags say whether this machine offers the choice at all, which is what tells a
 * consumer whether to show a toggle or nothing.
 */
@Serializable
data class Harvest(
  val swathActive: Boolean = false,
  val swathAvailable: Boolean? = null,
  val chopperAvailable: Boolean? = null,
)

/** The discrete mode a tool is switched to. [name] comes from the vehicle XML and may be absent. */
@Serializable
data class WorkMode(
  val current: Int = 0,
  val count: Int = 0,
  val name: String? = null,
)

/**
 * Live working width of a tool with retractable sections — it changes as sections are switched off,
 * so it is not a static spec value. The two sides are independent (half-width on one side is a normal
 * headland technique).
 */
@Serializable
data class WorkWidth(
  val left: Float = 0f,
  val leftMax: Float = 0f,
  val right: Float = 0f,
  val rightMax: Float = 0f,
  val total: Float = 0f,
  val unit: String = "",
)

/** [session] is resettable from the vehicle's own action; [lifetime] is not. */
@Serializable
data class BaleCounter(
  val session: Int = 0,
  val lifetime: Int = 0,
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
  val pipe: Pipe? = null,
  val cover: Cover? = null,
  val wearable: Wearable? = null,
  val schema: Schema? = null,
  val selection: Selection? = null,
  val discharge: Discharge? = null,
  val tipping: Tipping? = null,
  val harvest: Harvest? = null,
  val workMode: WorkMode? = null,
  val workWidth: WorkWidth? = null,
  val baleCounter: BaleCounter? = null,
  /** Index into the *parent's* [Schema.attacherJoint] list — where this implement hangs off it. */
  val jointDescIndex: Int? = null,
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
