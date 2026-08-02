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
  val workAreas: List<WorkArea> = emptyList(),
  val baleCounter: BaleCounter? = null,
  val sowing: Sowing? = null,
  val spraying: Spraying? = null,
  val plow: Plow? = null,
  val tillage: Tillage? = null,
  val precisionFarming: PrecisionFarming? = null,
  val implement: List<Implement> = emptyList(),
  val combined: Combined? = null,
)

/**
 * [direction] is the way the machine is *travelling*, and the game reports it as stopped below
 * walking pace — so it goes blank whenever the tractor is standing still. What the transmission is
 * *set to* is [Motor.direction], which does not.
 */
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
  /**
   * The direction the transmission is **set to** — the motor's own answer, and the one the game
   * prints on a vehicle's dashboard as F / R / N. Unlike [Speed.direction] it holds at a standstill,
   * which is what makes it worth showing on a cluster.
   *
   * [DriveDirection.STOPPED] here means **neutral**, not "not moving": with automatic direction
   * change the motor reports neutral below about 1 km/h. Null means the mod predates the field
   * (before version 6) or the vehicle has no motor to ask.
   */
  val direction: DriveDirection? = null,
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

/**
 * What the transmission is in.
 *
 * [value] is a gear *name*, not a number: a geared tractor reports `"12"`, but a machine with no
 * discrete gears — a combine, a CVT, anything fully automatic — reports `"D"` / `"R"`, and either
 * kind reports `"N"` in neutral.
 *
 * [group] is the range the gear sits in (`"E"`, `"M"`), and **only a transmission that has ranges
 * reports one** — the mod drops the placeholder the engine hands back for the rest, so an empty
 * group means "this machine has no ranges" rather than "we didn't look". A ranged transmission with
 * its range lever out reports `"N"` here, which is a range like any other.
 */
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
  /** Null when the vehicle has no steering course — off a field, or not in steering-assist mode. */
  val course: GpsCourseState? = null,
)

/**
 * The live half of the steering course: which line, how far off it, how far to its end, which lines
 * are done. It rides on the 10 Hz telemetry because all of it changes as you drive; the geometry it
 * indexes into is its own channel ([GpsCourseData], `gpsCourse.json`), rewritten only when the course
 * itself changes.
 *
 * [courseId] joins the two. A consumer must **ignore [segmentIndex] and [worked] unless the course
 * geometry it holds carries the same id**: the mod publishes a new id the moment the game replaces
 * the course, and the geometry file follows a beat later, so for that beat these indices refer to
 * lines the app has not received yet.
 */
@Serializable
data class GpsCourseState(
  val courseId: String = "",
  /** The line being followed; -1 when the game has not picked one (nothing to engage on). */
  val segmentIndex: Int = -1,
  /** Which side of the line the game has the vehicle assigned to. */
  val isLeft: Boolean = false,
  val segmentCount: Int = 0,
  val workedCount: Int = 0,
  /**
   * Hex bitmask over segment indices, four per character: character *k* covers indices `4k-3..4k`,
   * bit 0 being the lowest of those, and the all-zero tail is trimmed. Read it through [isWorked]
   * rather than by hand.
   */
  val worked: String? = null,
  /**
   * Signed cross-track error in meters — **positive means right of the line**, in the sense the
   * game itself uses (its `sideOffset` shifts a line left by `(dirZ, -dirX)`), measured relative to
   * the direction of travel so driving a line the other way does not mirror it. Null until the game
   * has a line picked.
   */
  val deviationM: Float? = null,
  /** Meters of the current line left ahead of the vehicle; null while no line is picked. */
  val distanceToEndM: Float? = null,
) {
  /** Whether the line with the mod's 1-based [index] has been worked. */
  fun isWorked(index: Int): Boolean {
    val mask = worked ?: return false
    if (index < 1) return false
    val char = mask.getOrNull((index - 1) / 4) ?: return false
    val nibble = char.digitToIntOrNull(16) ?: return false
    return (nibble shr ((index - 1) % 4)) and 1 == 1
  }
}

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
 *
 * [sections] is the same data one level deeper: the individual shutoff sections, which are the base
 * game's whole answer to "section control". They arrive in the order the game's own HUD draws them,
 * left to right across the boom, so a bar can render them straight through.
 */
@Serializable
data class WorkWidth(
  val left: Float = 0f,
  val leftMax: Float = 0f,
  val right: Float = 0f,
  val rightMax: Float = 0f,
  val total: Float = 0f,
  val unit: String = "",
  val sections: List<WorkSection> = emptyList(),
  /** How many of [sections] are on; absent on a tool with no sections at all. */
  val activeCount: Int? = null,
)

/**
 * Which side of the boom a section sits on. A [CENTER] section belongs to neither of the engine's two
 * side lists, so it is never switched off — the game brackets it with separators instead of counting
 * it in either side's fold-in state.
 */
@Serializable
enum class SectionSide { LEFT, CENTER, RIGHT }

/** One shutoff section of a boom. */
@Serializable
data class WorkSection(
  val active: Boolean = false,
  val side: SectionSide = SectionSide.CENTER,
)

/**
 * One work area of a tool: a rectangle of ground it processes.
 *
 * The two flags are the engine's own and say different things. [active] is capability — the area is
 * lowered, in contact, driving the right way, and its section is switched on. [processing] is
 * evidence: it actually worked ground within the last 200 ms. A raised sprayer has neither; a lowered
 * one running dry over a finished field has the first but not the second.
 *
 * [shape] is three corners of the footprint parallelogram — start, width, height — in the same
 * normalized `[0,1]` map frame as [MapData] and [GpsCourseData], so it draws with the map's own
 * projection. The fourth corner is `width + height - start`. Absent when the world size is unknown.
 */
@Serializable
data class WorkArea(
  val index: Int = 0,
  /** `"SPRAYER"`, `"CULTIVATOR"`, … — a token, not an enum: mods register their own types. */
  val type: String? = null,
  val active: Boolean = false,
  val processing: Boolean = false,
  val width: Float? = null,
  val unit: String? = null,
  val shape: List<Float> = emptyList(),
)

/** [session] is resettable from the vehicle's own action; [lifetime] is not. */
@Serializable
data class BaleCounter(
  val session: Int = 0,
  val lifetime: Int = 0,
)

/**
 * A sowing machine's hopper — which crop is selected, out of the list the machine itself declares.
 *
 * The fill unit only ever says SEEDS, so this is the only place the crop appears. [fruitType] is the
 * crop token (`WHEAT`), [fillType] the fill type it is carried as — which is what joins this to the
 * matching [FillUnit] — and [title] the localized name to print. **All three are null together**,
 * when the machine declares no seeds or the crop can't be resolved; the rest of the aspect still
 * describes the hopper, so an unresolvable crop is a missing name rather than a missing subtree.
 *
 * [seedIndex] is 1-based into the machine's own list, so it pairs with [seedCount] as "2 of 3" and
 * says whether there is a choice at all. Deliberately absent: whether the machine is currently
 * working — `workAreas` answers that from the engine's own predicate, and the sowing spec's own flag
 * does not survive on a multiplayer client.
 */
@Serializable
data class Sowing(
  val seedIndex: Int = 0,
  val seedCount: Int = 0,
  /** False while something else holds the hopper (a mission locking the crop). */
  val changeAllowed: Boolean = true,
  /** Sows straight into stubble — no seedbed needed. */
  val directPlanting: Boolean = false,
  /** Seed consumption multiplier; absent at the engine default of 1. */
  val usageScale: Float? = null,
  val fruitType: String? = null,
  val fillType: String? = null,
  val title: String? = null,
)

/**
 * A sprayer, fertilizer spreader, slurry tanker or manure spreader — the game's `Sprayer` spec covers
 * all four and [kind] is what separates them.
 *
 * [fillType] is the join key to the matching [FillUnit]: the fill unit list carries no indices, and a
 * combination machine has more than one tank, so this is the only way to know which one the sprayer
 * draws from. It and [title] are null when the tank is empty. [sprayType] and [category] are null for
 * a material the game registers no spray type for (water, an unregistered modded fill type) — the
 * tank still reports.
 */
@Serializable
data class Spraying(
  val kind: SprayerKind = SprayerKind.SPRAYER,
  /** Material actually leaving the machine — not merely switched on, which is `isTurnedOn`. */
  val active: Boolean = false,
  val doubledAmount: Boolean = false,
  /** False on a slurry tanker or manure spreader: doubling is a fertilizer-only control. */
  val doubledAmountAvailable: Boolean = false,
  val allowsSpraying: Boolean = true,
  val fillType: String? = null,
  val title: String? = null,
  val sprayType: String? = null,
  val category: SprayCategory? = null,
  /**
   * Litres per minute **at the machine's speed limit**, not the current draw — the game scales usage
   * by the speed limit rather than actual speed to hold consumption per hectare constant, so this
   * figure does not move as you slow down. Label it as a rating, never as live consumption.
   * Precision Farming publishes true application rates when it is installed.
   */
  val nominalUsagePerMin: Float? = null,
)

@Serializable
enum class SprayerKind { SPRAYER, SLURRY_TANKER, MANURE_SPREADER }

@Serializable
enum class SprayCategory { FERTILIZER, LIME, HERBICIDE }

/**
 * A plough.
 *
 * [side] is which way the bodies are turned, and is **null on a plough that does not reverse** — the
 * engine stores a bool meaning "at the max end of the turn animation", whose left/right sense is a
 * per-machine XML value, so a non-reversible plough has no side rather than a default one.
 *
 * [rotationAllowed] is the mechanical half (not mid-fold); [canToggleRotation] adds lowered and
 * powered. They are separate so a terminal can eventually say *why* the plough will not turn.
 *
 * [limitToField] is not carried in the multiplayer join stream — only broadcast on change — so on a
 * client that joined mid-session it reads the load default until somebody toggles it.
 */
@Serializable
data class Plow(
  val rotationAllowed: Boolean = false,
  val canToggleRotation: Boolean = false,
  val limitToField: Boolean = true,
  /** The player does not get to choose — the machine or the platform forces it. */
  val forceLimitToField: Boolean = false,
  val side: PlowSide? = null,
)

@Serializable
enum class PlowSide { LEFT, RIGHT }

/**
 * A cultivator, power harrow or subsoiler. Thin by design: width, sections and depth modes are
 * already answered by [WorkWidth], [WorkArea] and [WorkMode].
 *
 * None of this is synchronized in multiplayer. [kind] is read from the vehicle XML so it is identical
 * everywhere, but [limitToField] is engine state a client only ever sees at its load default.
 */
@Serializable
data class Tillage(
  val kind: TillageKind = TillageKind.CULTIVATOR,
  val deepMode: Boolean = true,
  val limitToField: Boolean = true,
)

@Serializable
enum class TillageKind { CULTIVATOR, POWER_HARROW, SUBSOILER }

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
  val workAreas: List<WorkArea> = emptyList(),
  val baleCounter: BaleCounter? = null,
  val sowing: Sowing? = null,
  val spraying: Spraying? = null,
  val plow: Plow? = null,
  val tillage: Tillage? = null,
  val precisionFarming: PrecisionFarming? = null,
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

/**
 * Every work area on the rig that is currently able to work ground — the machine's own and every
 * implement's, however deep it is hitched.
 *
 * [WorkArea.active] rather than [WorkArea.processing] on purpose. `active` is the engine's capability
 * predicate (lowered, in contact, moving forward, and its shutoff section on), and it is steady while
 * you drive; `processing` only says ground *changed* in the last 200 ms, which a tedder over already
 * spread grass — or a spot sprayer over clean crop — never does. Anything asking "where has this tool
 * been" wants the former.
 *
 * Flattened in hitch order (the machine, then each implement depth-first) — see [allWorkAreas], which
 * this filters.
 */
fun Vehicle.activeWorkAreas(): List<WorkArea> = allWorkAreas().filter { it.active }

/**
 * Every work area on the rig in hitch order, working or not — the machine's own first, then each
 * implement's depth-first, however deep it is hitched.
 *
 * A work area's position in THIS list is the only stable identity it has, which is what anything
 * pairing areas between samples has to key on. [WorkArea.index] is not: it is the engine's index
 * within the area's own object (`workArea.index = #spec.workAreas`), so a tractor's first area and its
 * seeder's first area are both 1. Neither is a position in [activeWorkAreas] — a section switching off
 * shifts every area behind it up a place.
 */
fun Vehicle.allWorkAreas(): List<WorkArea> {
  val out = mutableListOf<WorkArea>()

  fun walk(implements: List<Implement>) {
    for (implement in implements) {
      out += implement.workAreas
      walk(implement.implement)
    }
  }

  out += workAreas
  walk(implement)
  return out
}
