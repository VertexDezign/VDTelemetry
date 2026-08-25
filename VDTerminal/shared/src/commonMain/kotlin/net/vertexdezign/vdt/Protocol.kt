package net.vertexdezign.vdt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.vertexdezign.vdt.model.CropCalendarData
import net.vertexdezign.vdt.model.CropRotationData
import net.vertexdezign.vdt.model.FieldInfoData
import net.vertexdezign.vdt.model.FinanceData
import net.vertexdezign.vdt.model.FleetData
import net.vertexdezign.vdt.model.GpsCourseData
import net.vertexdezign.vdt.model.HusbandriesData
import net.vertexdezign.vdt.model.InvoicesData
import net.vertexdezign.vdt.model.MapData
import net.vertexdezign.vdt.model.MapLayersInfo
import net.vertexdezign.vdt.model.MapVehiclesData
import net.vertexdezign.vdt.model.MissionsData
import net.vertexdezign.vdt.model.PricesData
import net.vertexdezign.vdt.model.ProductionData
import net.vertexdezign.vdt.model.StorageData
import net.vertexdezign.vdt.model.TaskListData
import net.vertexdezign.vdt.model.VdtData
import net.vertexdezign.vdt.model.WeatherForecastData

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

  /**
   * The GPS course channel (`gpsCourse.json`): the steering assist's guidance lines for the field
   * being driven. Its own cadence again — the mod rewrites it only when the course changes, which is
   * once per field rather than on any clock — so it is its own message.
   *
   * [data] is null when the file is absent (export disabled / no data yet). An *empty* course
   * ([GpsCourseData.isEmpty]) is a different statement: the mod publishes that when the driver leaves
   * the field, and both mean the app must stop drawing lines.
   */
  @Serializable
  @SerialName("gpsCourse")
  data class GpsCourse(
    val data: GpsCourseData? = null,
  ) : ServerMessage

  /**
   * The per-field agronomy channel (`fieldInfo.json`), feeding the field-info popup. Interval-driven
   * (the crop state grows over in-game time) — a fourth cadence besides the telemetry tick, the
   * event-driven [MapUpdate] and the ~1 s [MapVehicles] — so it is its own message. [data] is
   * **null when the file is absent** (export disabled / no data yet): the popup then falls back to
   * the [MapUpdate] geometry rows alone.
   */
  @Serializable
  @SerialName("fieldInfo")
  data class FieldInfo(
    val data: FieldInfoData? = null,
  ) : ServerMessage

  /**
   * The production channel (own-farm production points + factories, `production.json`).
   * Interval-driven on the mod's own ~2 s cadence (fill levels drift as material is
   * delivered/consumed) — its own cadence besides the telemetry tick, so it is its own message.
   * [data] is **null when `production.json` is absent** (export disabled / no data yet): the app
   * clears its overview then rather than freezing the last state.
   */
  @Serializable
  @SerialName("production")
  data class Production(
    val data: ProductionData? = null,
  ) : ServerMessage

  /**
   * The storage channel (own-farm standalone storages — silos + object storages, `storage.json`).
   * A sibling of [Production] split onto its own channel; interval-driven on the same ~2 s cadence,
   * so it is its own message. [data] is **null when `storage.json` is absent** (export disabled /
   * no data yet): the app clears its overview then rather than freezing the last state.
   */
  @Serializable
  @SerialName("storage")
  data class Storage(
    val data: StorageData? = null,
  ) : ServerMessage

  /**
   * The husbandry channel (own-farm animal pens, `husbandry.json`). Interval-driven on the mod's own
   * cadence (condition/productivity drift over in-game hours), so it is its own message. [data] is
   * **null when `husbandry.json` is absent** (export disabled / no data yet): the app clears its
   * overview then rather than freezing the last state.
   */
  @Serializable
  @SerialName("husbandry")
  data class Husbandry(
    val data: HusbandriesData? = null,
  ) : ServerMessage

  /**
   * The missions channel (the farm's contracts, `missions.json`). The mod writes it when a contract
   * is generated, accepted, finished or deleted, plus on a slow interval for the countdown — its own
   * cadence again, hence its own message. [data] is **null when `missions.json` is absent** (export
   * disabled / no data yet): the app clears its contract list then rather than offering contracts
   * that may no longer exist.
   */
  @Serializable
  @SerialName("missions")
  data class Missions(
    val data: MissionsData? = null,
  ) : ServerMessage

  /**
   * The finance channel (the local farm's books, `finance.json`). Interval-driven on the mod's own
   * slow cadence, kicked by a money notification, a month rollover or a loan change — its own cadence
   * again, hence its own message. [data] is **null when `finance.json` is absent** (export disabled /
   * no data yet): the app clears the panel then rather than showing a stale balance, which of all the
   * channels is the one most likely to be acted on.
   */
  @Serializable
  @SerialName("finance")
  data class Finance(
    val data: FinanceData? = null,
  ) : ServerMessage

  /**
   * The invoices channel (`invoices.json`, FS25_Invoices): billing between farms. Purely event-driven
   * — the mod hooks that mod's own "something changed" funnel, so a document arrives when an invoice
   * is raised, paid, withdrawn, answered or penalised, and not otherwise.
   *
   * [data] is **null when the file is absent, which here means the mod is not installed** — the app
   * must say so rather than showing an empty list, which is a different statement. An installed mod
   * with nothing to show sends a document whose `invoices` list is empty.
   */
  @Serializable
  @SerialName("invoices")
  data class Invoices(
    val data: InvoicesData? = null,
  ) : ServerMessage

  /**
   * The crop calendar channel (`cropCalendar.json`): which periods each crop may be sown and
   * harvested in. Event-driven and very nearly static — the mod rewrites it once per in-game day, for
   * the "today" marker alone — which is a cadence of its own again, hence its own message.
   *
   * [data] is **null when `cropCalendar.json` is absent** (export disabled / no data yet): the app
   * clears the grid then rather than leaving last session's crop list up, which on a different map is
   * an entirely different set of crops.
   */
  @Serializable
  @SerialName("cropCalendar")
  data class CropCalendar(
    val data: CropCalendarData? = null,
  ) : ServerMessage

  /**
   * The weather channel (`weather.json`): the forecast — now, twelve two-hourly steps, six days.
   * Event-driven on the in-game hour, the same beat the game's own weather menu refreshes on, so it
   * is its own message rather than a field on [Telemetry] (whose `environment.weather` carries only
   * the live temperature, at the ~100 ms tick).
   *
   * [data] is **null when `weather.json` is absent** (export disabled / no data yet): the app clears
   * the strip then. A stale forecast is worse than none — it is read to decide whether to cut hay.
   */
  @Serializable
  @SerialName("weather")
  data class Weather(
    val data: WeatherForecastData? = null,
  ) : ServerMessage

  /**
   * The fleet channel (`fleet.json`): every machine the farm owns, its condition, and Advanced Damage
   * System's maintenance record where that mod is installed. Interval-driven on the mod's own slow
   * cadence — condition and hours drift over in-game hours — so it is its own message.
   *
   * [data] is **null when `fleet.json` is absent** (export disabled / no data yet), which the app
   * must show as such: an empty fleet is a farm that owns nothing, and a machine that has quietly
   * stopped being reported is the one thing a list of machines must not do.
   */
  @Serializable
  @SerialName("fleet")
  data class Fleet(
    val data: FleetData? = null,
  ) : ServerMessage

  /**
   * The prices channel (`prices.json`): the map's price board — what every station pays for each
   * fill type, what the ones that sell to you charge, and the twelve-month curve behind each
   * commodity. Interval-driven on the mod's own 30 s cadence, which matches the interval the game
   * itself refreshes a multiplayer client's prices on.
   *
   * Unlike its neighbours this channel is **not farm-scoped** — a price is the same number for every
   * farm — and it carries no fill levels: valuing stock is a join against [Storage] and its
   * siblings, not a second stock walk.
   *
   * [data] is **null when `prices.json` is absent** (export disabled / no data yet): the app clears
   * the board then rather than valuing today's stock at last session's prices.
   */
  @Serializable
  @SerialName("prices")
  data class Prices(
    val data: PricesData? = null,
  ) : ServerMessage

  /**
   * Diagnostics: the **observed** write cadence of each channel file, as measured server-side (how
   * often the file actually changes on disk — what the consumer receives, independent of what the mod
   * intends). Broadcast on its own slow timer, not tied to any channel's data. Feeds the app's
   * diagnostics panel so the configured intervals/profile can be verified end to end.
   *
   * Resolution is floored by the file-watch debounce (`VDT_DEBOUNCE_MS`, 40 ms default), so it can't
   * distinguish cadences faster than ~25 Hz — fine for the 100 ms telemetry tick and the 1–5 s
   * secondary channels.
   */
  @Serializable
  @SerialName("channelStats")
  data class ChannelStats(
    val data: ChannelStatsData,
  ) : ServerMessage

  /**
   * The ground-layer channel (`mapLayers/`): the raster overlays this map offers — crops / growth /
   * soil today. Carries only each plane's legend and content version; the raster itself is fetched
   * separately as a PNG, never over the WebSocket (see [MapLayersInfo]). A plane the mod hasn't
   * swept (nobody has selected it) is still listed, with a null version.
   *
   * [data] is **null when `mapLayers/index.json` is absent** (export disabled, or the channel is off
   * under the current performance profile): same "the absence must be broadcast, not swallowed"
   * rule as [MapVehicles].
   */
  @Serializable
  @SerialName("mapLayers")
  data class MapLayers(
    val data: MapLayersInfo? = null,
  ) : ServerMessage

  @Serializable
  @SerialName("error")
  data class Error(
    val message: String,
  ) : ServerMessage
}

/** A snapshot of every channel's observed cadence, plus the server clock it was taken at. */
@Serializable
data class ChannelStatsData(
  /**
   * The server wall clock (epoch ms) at snapshot time. Paired with [ChannelStat.lastWriteEpochMs] —
   * both on the server clock — the app computes each channel's staleness as `serverNowEpochMs -
   * lastWriteEpochMs` without any client/server clock-skew.
   */
  val serverNowEpochMs: Long,
  val channels: List<ChannelStat>,
)

/**
 * Observed cadence of one channel file. All interval fields are null until at least two writes have
 * been seen (one write gives a baseline but no interval). [name] is the file name (e.g.
 * `production.json`); the app maps it to a friendly label.
 */
@Serializable
data class ChannelStat(
  val name: String,
  /** Successful (content) reparses seen this session — the initial read counts as the first. */
  val writes: Long,
  /** Server-clock epoch ms of the last write, or null if never written. */
  val lastWriteEpochMs: Long? = null,
  /** The most recent write-to-write interval (ms). */
  val lastIntervalMs: Long? = null,
  /** EMA-smoothed write interval (ms) — the headline "observed cadence". */
  val meanIntervalMs: Double? = null,
  val minIntervalMs: Long? = null,
  val maxIntervalMs: Long? = null,
)

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

  /**
   * Move the [target]'s pipe to an absolute [state]: `1` is fully retracted, up to the machine's
   * `numStates`. Mod-side this is a direct `Pipe:setPipeState` — additionalInputs has no counterpart,
   * so there is nothing to route through.
   *
   * The engine **clamps** to the machine's own `numStates`, which is the right authority: the app's
   * copy of that number is a tick old, the machine's is not.
   */
  @Serializable
  @SerialName("setPipeState")
  data class SetPipeState(
    val target: ControlTarget,
    val state: Int,
  ) : ClientMessage

  /**
   * Set the [target]'s cover to an absolute [state]: `0` closes everything, `1..count` opens that
   * cover. A machine with several covers has one open at a time, which is why this is an index and
   * not a boolean.
   *
   * `Cover:setCoverState` silently **no-ops** unless the machine `hasCovers` and the state is within
   * `0..#covers`, so an out-of-range state does nothing at all rather than erroring.
   */
  @Serializable
  @SerialName("setCoverState")
  data class SetCoverState(
    val target: ControlTarget,
    val state: Int,
  ) : ClientMessage

  /**
   * Choose which tip side the [target]'s next tip will use — `Tipping.preferredSide`, 1-based.
   *
   * The mod gates this on the engine's own `getCanTogglePreferdTipSide`, which requires the trough to
   * be closed, so a command sent mid-tip is dropped rather than yanking a raised trough sideways.
   */
  @Serializable
  @SerialName("setTipSide")
  data class SetTipSide(
    val target: ControlTarget,
    val side: Int,
  ) : ClientMessage

  /**
   * Start (`on = true`) or stop the [target] unloading.
   *
   * A boolean rather than the absolute [net.vertexdezign.vdt.model.DischargeState] the telemetry
   * reports, and deliberately so: **which** flavour of unloading applies — into an object or onto the
   * ground — is a fact about the spot the machine is standing on, which the app cannot know and which
   * changes between the command being written and being read. The mod asks the engine the same two
   * questions the game's own tip action asks, in the same order, and picks.
   *
   * Still absolute in the sense the channel needs: "unloading" and "not unloading" are both states,
   * so a resend is a no-op rather than a toggle.
   */
  @Serializable
  @SerialName("setDischarging")
  data class SetDischarging(
    val target: ControlTarget,
    val on: Boolean,
  ) : ClientMessage

  /**
   * Make the machine at [node] the one the player's controls act on, and — in the same command — put
   * it on control group [controlGroup].
   *
   * One command with two arguments because it is one engine call: `Cylindered` owns no separate
   * "current group" setter, it registers each group as a *sub-selection* of the machine, and
   * `Vehicle:setSelectedObject` takes the object and the sub-selection together.
   *
   * [node] is the **rig diagram's own path** (`0`, `0/1`, `0/1/0` — see
   * `net.vertexdezign.vdt.app.panels.RigNode.id`), not the engine's `selectionObject.index`. The
   * engine's index is native but it is rebuilt on every attach and detach, so it can go stale between
   * this being rendered and being read; the path is resolved mod-side by the same walk over
   * `attachedImplements` that built the tree the app drew, so what was drawn and what the command
   * reaches are the same node by construction.
   *
   * [controlGroup] is 1-based into [net.vertexdezign.vdt.model.ControlGroup.names], or null for
   * "leave the group alone". Only send one the machine reports in
   * [net.vertexdezign.vdt.model.ControlGroup.available]: a group whose moving tools are inactive has
   * no sub-selection to reach it by, and the mod selects the machine without it.
   *
   * **Absolute**, like the rest of the channel — it names a state rather than a step, so a dropped or
   * doubled command is harmless. Selection is client-local: there is no engine event behind it, so
   * this moves nothing for any other player.
   *
   * The mod applies the same `selectable` gate the app draws with, and drops the command when it
   * fails. That is not belt-and-braces: `setSelectedVehicle` silently selects a *different* machine
   * when handed one that cannot be selected, and the app's copy of the flag is a tick old.
   */
  @Serializable
  @SerialName("setSelected")
  data class SetSelected(
    val node: String,
    val controlGroup: Int? = null,
  ) : ClientMessage

  /** Start (`on = true`) or stop the vehicle's engine. */
  @Serializable
  @SerialName("setMotorState")
  data class SetMotorState(
    val on: Boolean,
  ) : ClientMessage

  /**
   * Put the rig's Precision Farming sprayer into automatic (`auto = true`) or manual rate mode.
   *
   * Addressed at the rig rather than at a slot: PF drives whichever machine on it is the valid
   * sprayer (its own `getValidSprayerToUse`), and a rig you would tow two of is not a rig you drive.
   */
  @Serializable
  @SerialName("setSprayAmountAuto")
  data class SetSprayAmountAuto(
    val auto: Boolean,
  ) : ClientMessage

  /**
   * Set the manual rate to an absolute [step] — PF's `sprayAmountManual`, an index into its level
   * tables, not a rate.
   *
   * Absolute like everything else here, so a `+` tap sends `step + 1` computed from what it renders
   * rather than an increment the channel could drop or double. Out-of-range values are safe: PF
   * clamps to the machine's own bounds, which move with the fill type, so the machine has the last
   * word rather than the app's copy of them.
   *
   * Leaves the mode alone. PF stores the step in either mode — it simply does nothing until manual is
   * on — so a rate can be dialled in before switching.
   */
  @Serializable
  @SerialName("setSprayAmountStep")
  data class SetSprayAmountStep(
    val step: Int,
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

  // ---- Production write-back (production app). Both drive the base-game ProductionPoint setters
  // via their MP events, so they run with no current vehicle (requiresVehicle = false mod-side).
  // `pointId` is the production point's exported id; own-farm ownership is enforced mod-side. ----

  /**
   * Switch production line `productionId` of point `pointId` on (`enabled = true`) or off. Absolute
   * state (idempotent), matching the mod's `setProductionState`.
   */
  @Serializable
  @SerialName("setProductionEnabled")
  data class SetProductionEnabled(
    val pointId: String,
    val productionId: String,
    val enabled: Boolean,
  ) : ClientMessage

  /**
   * Set the distribution [mode] of buffered output [fillType] (its internal name) in point `pointId`.
   * Absolute state (idempotent), matching the mod's `setOutputDistributionMode`. Direct-sell outputs
   * have no mode and are not targeted here.
   */
  @Serializable
  @SerialName("setProductionOutputMode")
  data class SetProductionOutputMode(
    val pointId: String,
    val fillType: String,
    val mode: OutputMode,
  ) : ClientMessage

  /**
   * Unload [amount] stored objects (bales/pallets) of one group out of object storage [storageId] —
   * the same action as the in-game trigger dialog (the mod spawns them at the storage's spawn area).
   * The group is addressed by its [index] (`objectInfoIndex`); [title] rides along so the mod can
   * re-resolve the group if the index shifted since the read snapshot. Not idempotent (it's an
   * action, like `createTask`): the amount is clamped mod-side to the live limits, and the server
   * refuses more than is stored, so a stale value can't over-unload — but it must not be blindly
   * resent, so it carries no target-state and is never replayed on reconnect.
   */
  @Serializable
  @SerialName("unloadObjectStorage")
  data class UnloadObjectStorage(
    val storageId: String,
    val index: Int,
    val title: String,
    val amount: Int,
  ) : ClientMessage

  /**
   * Take on the contract [missionId] — the same action as the in-game contracts screen's Accept, and
   * with [lease] its "with equipment" variant, which spawns the contract's machines at the shop for a
   * fee ([Mission.vehicleCosts]).
   *
   * [missionId] is [Mission.id], the mission's network object id. It identifies a contract in the
   * *live* game only, so this must never be replayed on reconnect: like `unloadObjectStorage` it is
   * an action, not a target state, and by the time a stale one arrives the id may name a different
   * contract or none. The mod re-checks the contract is still on offer before sending anything.
   */
  @Serializable
  @SerialName("acceptMission")
  data class AcceptMission(
    val missionId: Int,
    val lease: Boolean = false,
  ) : ClientMessage

  /**
   * Give up the running contract [missionId] — the in-game screen's Cancel, which forfeits it. The
   * app confirms first for the same reason the game does. Same non-replayable id rules as
   * [AcceptMission].
   */
  @Serializable
  @SerialName("cancelMission")
  data class CancelMission(
    val missionId: Int,
  ) : ClientMessage

  /**
   * Collect the finished contract [missionId] — the in-game screen's "complete", which pays out
   * [Mission.totalReward] and clears the contract. Same non-replayable id rules as [AcceptMission].
   */
  @Serializable
  @SerialName("dismissMission")
  data class DismissMission(
    val missionId: Int,
  ) : ClientMessage

  /**
   * Set the farm's base-game loan to [amount] — borrowing the difference, or repaying it when the
   * target is lower. Absolute state (idempotent) rather than the in-game screen's ±5000 delta: the
   * mod converts it to the delta `ChangeLoanEvent` wants at execution time, so a redelivered command
   * computes a zero delta and does nothing, where a redelivered delta would borrow twice.
   *
   * The mod **clamps** a target above the ceiling (matching what the server would do with the event
   * anyway) but **refuses** a repayment larger than the balance (the engine would happily push the
   * money negative; the in-game screen just doesn't offer the button). Both are re-checked mod-side,
   * so an app one write out of date cannot do damage — but the app should still snap the target to
   * [net.vertexdezign.vdt.model.FinanceData.loanStep] and respect
   * [net.vertexdezign.vdt.model.FinanceData.loanMax], so the button says what will happen.
   *
   * `Int` rather than `Long` unlike the read model's amounts: the engine's `Farm.MAX_LOAN` is
   * 3 000 000 and the mod parses this with the engine's 32-bit `XMLFile:getInt`.
   */
  @Serializable
  @SerialName("setLoan")
  data class SetLoan(
    val amount: Int,
  ) : ClientMessage {
    init {
      // A negative loan is not a thing the engine can represent (it clamps at 0), and the mod's
      // guard would reject it — rejecting at the type boundary instead makes it unrepresentable end
      // to end, the way SetCruiseControl does for a non-finite speed. The constructor also runs
      // during kotlinx decode, so no wire value can smuggle one in either.
      require(amount >= 0) { "loan target must be >= 0, was $amount" }
    }
  }

  /**
   * Take out an FS25_EnhancedLoanSystem annuity loan of [amount] over [durationYears] years, at
   * whatever rate the bank is currently offering.
   *
   * An **action**, not a target state, and the one command here that creates something: a doubled
   * delivery is a second loan. Like `createTask` it carries no target state and is never replayed on
   * reconnect; safety comes from the command channel's at-most-once id watermark.
   *
   * Both values are **clamped mod-side** against freshly derived limits — the borrowing ceiling and
   * the bank's longest term — because ELS's own `addLoan` clamps nothing at all (its dialog does it in
   * the text input, which a terminal never goes through). The app should still bound its inputs by
   * [net.vertexdezign.vdt.model.EnhancedLoans.maxAmount] and `maxDurationYears` so the button says
   * what will happen.
   */
  @Serializable
  @SerialName("takeLoan")
  data class TakeLoan(
    val amount: Int,
    val durationYears: Int,
  ) : ClientMessage {
    init {
      // The mod rejects both, so rejecting at the type boundary makes them unrepresentable end to end
      // (the constructor also runs during kotlinx decode). The upper bounds are deliberately NOT here:
      // they are server settings that change at runtime, so only the mod can know them.
      require(amount > 0) { "loan amount must be > 0, was $amount" }
      require(durationYears > 0) { "loan duration must be > 0 years, was $durationYears" }
    }
  }

  /**
   * Make a special redemption payment of [amount] against the FS25_EnhancedLoanSystem loan [loanId] —
   * an extra payment beyond the monthly instalment, which shortens the term.
   *
   * [loanId] is [net.vertexdezign.vdt.model.EnhancedLoan.id], the loan's network object id, so this is
   * a live-game handle: same non-replayable rules as [AcceptMission]. An **action**, not a target
   * state.
   *
   * [amount] is clamped mod-side in the mod's own order — the farm's money, then (only while
   * `multipleRedemptions` is false) the fraction of the loan's original sum ELS permits, then what is
   * actually outstanding. A loan that has already had its redemption this year is **refused** rather
   * than clamped: there is no smaller amount that would be allowed.
   */
  @Serializable
  @SerialName("repayLoan")
  data class RepayLoan(
    val loanId: Int,
    val amount: Int,
  ) : ClientMessage {
    init {
      require(amount > 0) { "repayment must be > 0, was $amount" }
    }
  }

  /**
   * The ground-layer raster planes this dashboard is currently showing (empty = none). The mod
   * grid-samples only what someone is looking at — its most expensive channel by far — so this is
   * what causes a plane to be swept at all.
   *
   * The only **session-scoped** message: every other command describes the world and is forwarded
   * to the mod as sent, but this one describes a viewer. The server keeps it per WebSocket session,
   * drops it when that session closes, and sends the mod the union across connected dashboards — so
   * this exact type also carries that union onward to `commands.xml`, with the scope being the only
   * difference. Absolute state, and re-sent by the client on reconnect (the server can only forget
   * a session's selection, never inherit it).
   */
  @Serializable
  @SerialName("setMapLayers")
  data class SetMapLayers(
    val ids: List<String> = emptyList(),
  ) : ClientMessage

  /**
   * Settle an FS25_Invoices invoice billed to this farm.
   *
   * This and the three below are **actions, not target states** — like [TakeLoan] they carry nothing
   * to restate, are never replayed on reconnect, and rely on the command channel's at-most-once id
   * watermark. A redelivered pay is harmless only because the mod refuses to settle something already
   * paid; that is the mod's guard, not a property of this message.
   *
   * The mod re-checks everything the app used to decide the button existed — the `farmManager` right,
   * the invoice's state, that this farm is its payer, and that it can afford it — so a stale command
   * is refused rather than acted on.
   */
  @Serializable
  @SerialName("payInvoice")
  data class PayInvoice(
    val invoiceId: Int,
  ) : ClientMessage

  /**
   * Withdraw an invoice: one we issued and nobody has paid, or a proposal we raised. Deletes the
   * record outright — the mod keeps no cancelled state — so the app confirms before sending it.
   */
  @Serializable
  @SerialName("cancelInvoice")
  data class CancelInvoice(
    val invoiceId: Int,
  ) : ClientMessage

  /**
   * Accept a proposal addressed to this farm, turning it into a real unpaid invoice. Only the issuer a
   * proposal names may do this, and the mod restamps its creation date on the way through — so a
   * proposal that sat pending for months does not land already overdue.
   */
  @Serializable
  @SerialName("validateProposal")
  data class ValidateProposal(
    val invoiceId: Int,
  ) : ClientMessage

  /** Reject a proposal addressed to this farm. Deletes it, same as [CancelInvoice]. */
  @Serializable
  @SerialName("refuseProposal")
  data class RefuseProposal(
    val invoiceId: Int,
  ) : ClientMessage

  /**
   * Raise a new FS25_Invoices invoice against [farmId], or — with [proposal] set — ask that farm to
   * bill *us* for the work, which they then validate or refuse.
   *
   * The **only command that carries a list**, and so the only one the mod reads child elements for
   * (`<line/>` under `<command>`). Everything a line needs beyond its work type and quantity is
   * optional: the mod fills the price from its own catalogue, the VAT from the server's settings, and
   * computes the line total with its own arithmetic — the app never sends an amount, because the two
   * would then have to agree and one of them would eventually be wrong.
   */
  @Serializable
  @SerialName("createInvoice")
  data class CreateInvoice(
    val farmId: Int,
    val lines: List<InvoiceLineInput>,
    val proposal: Boolean = false,
  ) : ClientMessage {
    init {
      // The mod refuses all three, so rejecting them at the type boundary makes them unrepresentable
      // end to end (the constructor also runs during kotlinx decode). MAX_LINES is the mod's own cap.
      require(farmId > 0) { "invoice recipient must be a real farm, was $farmId" }
      require(lines.isNotEmpty()) { "an invoice needs at least one line" }
      require(lines.size <= MAX_LINES) { "an invoice may carry at most $MAX_LINES lines, had ${lines.size}" }
    }

    companion object {
      /** The mod's own per-invoice line cap (`InvoiceCreateEvent:run`). */
      const val MAX_LINES = 100
    }
  }
}

/**
 * One line of a [ClientMessage.CreateInvoice]. Deliberately thin: the work type and how much of it,
 * plus the two things a player can override.
 */
@Serializable
data class InvoiceLineInput(
  /** The mod's work type id — see [net.vertexdezign.vdt.model.WorkType.id]. */
  val workTypeId: Int,
  /** Hectares / hours / pieces / **litres** — the last of which is priced per 1000 l by the mod. */
  val quantity: Double,
  /** Unit price. Null takes the catalogue's difficulty-adjusted price, which is the usual case. */
  val price: Double? = null,
  /** Fraction knocked off, `0.1` being 10%. The mod clamps into `[0,1]`. */
  val discount: Double? = null,
  /** Free text for the line. */
  val note: String? = null,
  /** The field this line bills for, when it is field work. */
  val fieldId: Int? = null,
) {
  init {
    // Finiteness is checked as well as the range, because an infinity passes every range test and
    // then reaches CommandWriter, whose BigDecimal cannot represent it at all. NaN is excluded by
    // `> 0` on its own — comparisons are how it fails — but the infinities are not, and this
    // constructor (which also runs on decode) is the only place both are unrepresentable.
    require(quantity.isFinite() && quantity > 0) { "an invoice line needs a finite quantity > 0, was $quantity" }
    require(
      price == null || (price.isFinite() && price >= 0),
    ) { "a line price cannot be negative or infinite, was $price" }
    require(discount == null || discount.isFinite()) { "a line discount must be a finite fraction, was $discount" }
  }
}

/**
 * A production output's distribution mode. [token] is the wire vocabulary (the `mode=` attribute in
 * `commands.xml`, and the same token the read model's [net.vertexdezign.vdt.model.ProductionIo.mode]
 * carries), kept explicit so the enum can be renamed without breaking the contract.
 */
@Serializable
enum class OutputMode(
  val token: String,
) {
  KEEP("keep"),
  DIRECT_SELL("directSell"),
  AUTO_DELIVER("autoDeliver"),
  ;

  companion object {
    /** The [OutputMode] for a read-model token, or null for an unknown/absent one (e.g. direct-sell). */
    fun fromToken(token: String?): OutputMode? = entries.firstOrNull { it.token == token }
  }
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
 * What a control command acts on. [token] is the wire vocabulary (the `target=` attribute in
 * `commands.xml`), kept explicit so the enum can be renamed without breaking it.
 *
 * Three of the four are **positional**, and they are positions on the *controlled vehicle*:
 * [VEHICLE] is that vehicle itself, [FRONT] and [BACK] are the implements on its own attachers — and
 * everything hitched behind those, since a positional command cascades down the chain it names.
 * Nothing deeper has a position of its own, which is the ceiling [SELECTED] lifts.
 *
 * Lower / fold / activate route mod-side through FS25_additionalInputs' `vdAI<Action><Address>`
 * functions, one per member; pipe / cover / tip side / discharge resolve the token to an object in
 * the mod's own `TargetResolver` and call the engine setter on it.
 */
@Serializable
enum class ControlTarget(
  val token: String,
) {
  VEHICLE("vehicle"),
  FRONT("front"),
  BACK("back"),

  /**
   * Whatever machine the **game** has selected — at any depth of the rig, so this is the only way to
   * address a machine hitched behind another one (issue #120).
   *
   * Not "wherever the game happens to point": a tap on the rig diagram *moves* the game's selection
   * (issue #119), so this addresses what the driver last touched, and the terminal and the keyboard
   * agree about which machine that is. A `setSelected` written before a control command carries a
   * lower id, and the mod dispatches strictly in id order within one poll, so the two arrive in the
   * order they were sent.
   *
   * The only token whose scope is **one machine**: it acts on the selected machine and nothing else,
   * where [FRONT] and [BACK] name a position and cascade into what is hitched behind it. That is why
   * `net.vertexdezign.vdt.app.panels.controlTargetOf` prefers this one wherever the game has made a
   * selection — the rig diagram shows one machine per box and its controls read that machine's own
   * state, so this is the address that matches what the driver is looking at. On the controlled
   * vehicle the two coincide: `vdAILowerSelected` on a machine hitched to nothing routes through the
   * same pickup / fold-middle / attacher lowering `vdAILowerVehicle` does.
   *
   * Resolves to nothing when nothing is selected, which is an ordinary state rather than an error —
   * a rig where nothing can be selected has nothing selected.
   */
  SELECTED("selected"),
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
