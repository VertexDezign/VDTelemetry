package net.vertexdezign.vdt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.vertexdezign.vdt.model.CropRotationData
import net.vertexdezign.vdt.model.MapData
import net.vertexdezign.vdt.model.MapVehiclesData
import net.vertexdezign.vdt.model.TaskListData
import net.vertexdezign.vdt.model.VdtData

/**
 * Messages pushed server -> client over the WebSocket, JSON-encoded.
 *
 * See [ClientMessage] for the app -> mod direction.
 */
@Serializable
sealed interface ServerMessage {
  @Serializable
  @SerialName("telemetry")
  data class Telemetry(
    val data: VdtData,
  ) : ServerMessage

  /**
   * The optional FS25_TaskList channel. Broadcast on its own cadence (event-driven, not the ~100 ms
   * telemetry tick) and only while the mod is installed — its file's absence is why this arrives as a
   * distinct message rather than a field on [Telemetry].
   *
   * [data] is **null when the mod is not installed**, i.e. when `taskList.json` is absent (the mod
   * deletes it at startup when the integration isn't there). That null has to cross the wire: the app
   * holds the last value it was sent, so without an explicit "it's gone" the panel would keep
   * rendering the previous session's tasks forever.
   */
  @Serializable
  @SerialName("taskList")
  data class TaskList(
    val data: TaskListData? = null,
  ) : ServerMessage

  /**
   * The optional FS25_CropRotation channel. Like [TaskList], a distinct event-driven message (not a
   * field on [Telemetry]) so it broadcasts on its own cadence, and [data] is null when the mod isn't
   * installed — same "the absence must be broadcast, not swallowed" rule.
   */
  @Serializable
  @SerialName("cropRotation")
  data class CropRotation(
    val data: CropRotationData? = null,
  ) : ServerMessage

  /**
   * The map overlay channel (POIs + fields, `map.json`). Event-driven like [TaskList], hence a
   * distinct message on its own cadence rather than a field on [Telemetry] — the field polygons are
   * far too heavy to rebroadcast at the telemetry tick.
   *
   * [data] is **null when `map.json` is absent** (export disabled / cleaned up): the app must clear
   * its overlays then, not freeze them at the last state.
   */
  @Serializable
  @SerialName("map")
  data class MapUpdate(
    val data: MapData? = null,
  ) : ServerMessage

  /**
   * The vehicle-marker channel (`mapVehicles.json`). Broadcast on the mod's own ~1 s vehicle
   * interval — a third cadence besides the telemetry tick and the event-driven [MapUpdate], which
   * is why it is its own message. [data] is **null when the file is absent** (export disabled):
   * the app clears its vehicle markers then.
   */
  @Serializable
  @SerialName("mapVehicles")
  data class MapVehicles(
    val data: MapVehiclesData? = null,
  ) : ServerMessage

  @Serializable
  @SerialName("error")
  data class Error(
    val message: String,
  ) : ServerMessage
}

/**
 * Messages sent client -> server over the WebSocket (app -> mod back-channel), JSON-encoded. The
 * server turns these into `<command>` entries in `commands.xml`, which the mod polls and executes.
 *
 * Most commands carry an **absolute** target state, never a toggle: the file channel is lossy/async,
 * so an idempotent set-to-state is self-correcting where a dropped or doubled toggle would desync.
 * The app already knows the current state (it renders it), so a button tap computes the target itself.
 *
 * The **action** commands are the exception — `createTask` / `completeTask` / `deleteTask`, and the
 * CropRotation `addRotationSlot` / `removeRotationSlot` / `createRotation` / `deleteRotation`, each do
 * a thing rather than assert a state, so they can't be restated idempotently (a doubled `createTask`
 * makes two tasks). Redelivery there is *not* safe and must not be assumed. Safety instead comes from
 * delivery being **at-most-once**: each command's monotonic `id` plus the mod's `lastCommandId`
 * watermark runs an id at most once, and [net.vertexdezign.vdt.server.CommandWriter]'s session-reset
 * (file gone → ids restart at 1, ring dropped) preserves that across restarts. So these carry no
 * target-state and are never resent on their own. (The `setRotationCrop` / `setRotationCatchCrop`
 * slot edits, by contrast, *are* absolute-state and follow the idempotent rule like the rest.)
 */
@Serializable
sealed interface ClientMessage {
  /** Set one light on/off. The four beam/work lights are mask bits mod-side; `beacon` is a bool. */
  @Serializable
  @SerialName("setLight")
  data class SetLight(
    val light: LightTarget,
    val on: Boolean,
  ) : ClientMessage

  /** Set the (single) turn-light state — indicators are one enum, not three independent booleans. */
  @Serializable
  @SerialName("setTurnLight")
  data class SetTurnLight(
    val state: TurnLightState,
  ) : ClientMessage

  /**
   * Lower (`on = true`) or raise (`false`) the [target].
   *
   * Kept as three sibling command types (lower/fold/activate) rather than one action enum: they
   * share the `target`+`on` shape today, but each is likely to grow its own parameters, and a
   * separate type lets one evolve without disturbing the others.
   */
  @Serializable
  @SerialName("setLowered")
  data class SetLowered(
    val target: ControlTarget,
    val on: Boolean,
  ) : ClientMessage

  /** Fold (`on = true`, transport) or unfold (`false`, work) the [target]. */
  @Serializable
  @SerialName("setFolded")
  data class SetFolded(
    val target: ControlTarget,
    val on: Boolean,
  ) : ClientMessage

  /** Turn the [target] on (`on = true`) or off — PTO / powered tools. */
  @Serializable
  @SerialName("setActivated")
  data class SetActivated(
    val target: ControlTarget,
    val on: Boolean,
  ) : ClientMessage

  /** Start (`on = true`) or stop the vehicle's engine. */
  @Serializable
  @SerialName("setMotorState")
  data class SetMotorState(
    val on: Boolean,
  ) : ClientMessage

  /**
   * Cruise control. One command with an [action] (`enable`/`disable`/`setSpeed`) rather than
   * separate types: cruise is a single subsystem whose knobs move together. [speed] (km/h, a float
   * since mods allow sub-1 steps) is only meaningful for `setSpeed`.
   */
  @Serializable
  @SerialName("setCruiseControl")
  data class SetCruiseControl(
    val action: CruiseAction,
    val speed: Float? = null,
  ) : ClientMessage {
    init {
      // A non-finite speed would serialize to `speed="Infinity"`/`"NaN"`, which the mod's Lua
      // `tonumber` turns into `inf`/`nan` rather than nil — so its `speed == nil` guard misses it.
      // The constructor also runs during kotlinx decode, so rejecting it here makes the bad state
      // unrepresentable end to end: no client path can build one and no wire value (`1e400`) can
      // decode into one, which is why the command writer doesn't have to screen for it.
      require(speed == null || speed.isFinite()) { "cruise speed must be finite, was $speed" }
    }
  }

  /**
   * Show (`on = true`) or hide the steering-assist guide lines. Alone among the commands this one
   * targets a global client setting rather than the current vehicle, so the mod ignores which
   * vehicle is being driven when it runs it.
   */
  @Serializable
  @SerialName("setGpsLinesVisible")
  data class SetGpsLinesVisible(
    val on: Boolean,
  ) : ClientMessage

  // ---- FS25_TaskList write-back (farm page). All target the mod's own task state via its MP event
  // wrappers, so they run with no current vehicle (requiresVehicle = false mod-side). ----

  /** Mark the due (active) task `taskId` in `groupId` complete. */
  @Serializable
  @SerialName("completeTask")
  data class CompleteTask(
    val groupId: String,
    val taskId: String,
  ) : ClientMessage

  /** Remove task `taskId` from `groupId` entirely. */
  @Serializable
  @SerialName("deleteTask")
  data class DeleteTask(
    val groupId: String,
    val taskId: String,
  ) : ClientMessage

  /** Add a new Standard task to `groupId`. The mod generates the task id. */
  @Serializable
  @SerialName("createTask")
  data class CreateTask(
    val groupId: String,
    val task: TaskInput,
  ) : ClientMessage

  /** Replace the existing task `taskId` in `groupId` with [task]'s values. */
  @Serializable
  @SerialName("editTask")
  data class EditTask(
    val groupId: String,
    val taskId: String,
    val task: TaskInput,
  ) : ClientMessage

  // ---- FS25_CropRotation write-back (farm page). All drive the planner's own MP event wrappers, so
  // they run with no current vehicle (requiresVehicle = false mod-side). `rotationIndex` is the
  // plan's exported `index`; `slot` is the 1-based position in its sequence. ----

  /** Set the main crop of `slot` in plan `rotationIndex` to fruit-type [state] (idempotent). */
  @Serializable
  @SerialName("setRotationCrop")
  data class SetRotationCrop(
    val rotationIndex: Int,
    val slot: Int,
    val state: Int,
  ) : ClientMessage

  /** Set the catch crop of `slot` in plan `rotationIndex` to [catchCropState] (0 = none; idempotent). */
  @Serializable
  @SerialName("setRotationCatchCrop")
  data class SetRotationCatchCrop(
    val rotationIndex: Int,
    val slot: Int,
    val catchCropState: Int,
  ) : ClientMessage

  /** Append a slot to plan `rotationIndex`. */
  @Serializable
  @SerialName("addRotationSlot")
  data class AddRotationSlot(
    val rotationIndex: Int,
  ) : ClientMessage

  /** Drop the last slot of plan `rotationIndex` (the mod keeps at least one). */
  @Serializable
  @SerialName("removeRotationSlot")
  data class RemoveRotationSlot(
    val rotationIndex: Int,
  ) : ClientMessage

  /** Create a new one-slot rotation plan named [name] on the local player's farm (mod resolves the id). */
  @Serializable
  @SerialName("createRotation")
  data class CreateRotation(
    val name: String,
  ) : ClientMessage

  /** Delete plan `rotationIndex` entirely. */
  @Serializable
  @SerialName("deleteRotation")
  data class DeleteRotation(
    val rotationIndex: Int,
  ) : ClientMessage
}

/**
 * The user-facing fields of a Standard task, as entered in the app's create/edit form. The mod turns
 * these into a `Task` the same way its own wizard does — resolving the internal `period` / `nextN`
 * from [month] and the current game day — so only these intent values cross the wire (see
 * `src/command/TaskListControl.lua`). Non-Standard (husbandry/production) tasks aren't editable here.
 */
@Serializable
data class TaskInput(
  /** Free-text label (the mod caps it at 45 chars). */
  val detail: String = "",
  /** 1-10; lower runs first. */
  val priority: Int = 1,
  /** 1-5. */
  val effort: Int = 1,
  /** Task.RECUR_MODE: 0 Once, 1 Monthly, 2 Daily, 3 Every N months, 4 Every N days. */
  val recurMode: Int = 0,
  /** The N for the every-N modes; ignored otherwise. */
  val n: Int = 1,
  /** Start month 1-12; used by Once / Monthly / Every N months (ignored by the daily modes). */
  val month: Int = 1,
) {
  init {
    // These flow straight into TaskListControl.buildStandardTask, where `month` drives the period /
    // nextN arithmetic and the rest land on the task verbatim — an out-of-range value there produces a
    // silently malformed task rather than an error. Rejecting at the type boundary makes that
    // unrepresentable end to end, exactly as SetCruiseControl does for a non-finite speed: the
    // constructor also runs during kotlinx decode, so no wire value can smuggle one in either.
    // `n` is deliberately unbounded — the mod's wizard offers 24 and 36 beyond 1..12, and a task
    // authored elsewhere may hold any value, which the app must round-trip rather than clamp.
    require(priority in 1..10) { "priority must be 1..10, was $priority" }
    require(effort in 1..5) { "effort must be 1..5, was $effort" }
    require(recurMode in 0..4) { "recurMode must be 0..4, was $recurMode" }
    require(month in 1..12) { "month must be 1..12, was $month" }
    require(n >= 1) { "n must be >= 1, was $n" }
  }
}

/**
 * A cruise-control action. [token] is the wire vocabulary (the `action=` attribute in
 * `commands.xml`). `SET_SPEED` carries the target in `SetCruiseControl.speed`.
 */
@Serializable
enum class CruiseAction(
  val token: String,
) {
  ENABLE("enable"),
  DISABLE("disable"),
  SET_SPEED("setSpeed"),
}

/**
 * What a control command acts on. `vehicle` is the controlled vehicle itself (the mod calls its
 * native setter directly); `front`/`back` are its attached implements, routed mod-side through
 * FS25_additionalInputs' `vdAI…Front/Back` functions. [token] is the wire vocabulary (the `target=`
 * attribute in `commands.xml`), kept explicit so the enum can be renamed without breaking it.
 */
@Serializable
enum class ControlTarget(
  val token: String,
) {
  VEHICLE("vehicle"),
  FRONT("front"),
  BACK("back"),
}

/**
 * The settable lights. [token] is the wire vocabulary shared with the mod (the `light=` attribute in
 * `commands.xml`); it is kept explicit so the enum can be renamed without breaking the contract.
 */
@Serializable
enum class LightTarget(
  val token: String,
) {
  BEACON("beacon"),
  LOW_BEAM("lowBeam"),
  HIGH_BEAM("highBeam"),
  WORK_FRONT("workFront"),
  WORK_BACK("workBack"),
}

/** Turn-light state. [token] is the wire vocabulary shared with the mod (the `state=` attribute). */
@Serializable
enum class TurnLightState(
  val token: String,
) {
  OFF("off"),
  LEFT("left"),
  RIGHT("right"),
  HAZARD("hazard"),
}
