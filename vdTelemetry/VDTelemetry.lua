-- VDTelemetry
--
-- @author  Grisu118 - VertexDezign.net
-- @history     v1.0.0.0 - 2024-11-18 - Initial implementation
-- @Descripion: Exports game state into a json for telemetry consumers (e.g. GameGlass)
-- @web: https://grisu118.ch or https://vertexdezign.net
-- Copyright (c) 2024-2026 VertexDezign / Benjamin Leber. MIT licensed - see LICENSE.

local modDirectory = g_currentModDirectory
local modName = g_currentModName

---Source files to load, there are loaded in order, so if there is a dependency to another file, at it after the file it requires
---@type table<string> files to source.
local sourceFiles = {
  -- Utils
  "src/utils/Set.lua",
  "src/utils/MapUtil.lua",
  "src/utils/Json.lua",
  -- "which farm are we": read by collectors, integrations and the write side alike, so it is sourced
  -- before all of them
  "src/utils/Farm.lua",
  -- Mappers
  "src/mapper/ValueMapper.lua",
  -- Collectors (model/ holds annotation-only @class defs and is not sourced)
  "src/collect/EnvironmentExporter.lua",
  "src/collect/vehicle/Motor.lua",
  "src/collect/vehicle/Lights.lua",
  "src/collect/vehicle/Steering.lua",
  "src/collect/vehicle/SupportSystems.lua",
  -- Shared aspects (any vehicle or implement); Aspects.lua depends on the individual collectors
  "src/collect/aspects/TurnOn.lua",
  "src/collect/aspects/Foldable.lua",
  "src/collect/aspects/Lowered.lua",
  "src/collect/aspects/FillUnit.lua",
  "src/collect/aspects/Mass.lua",
  "src/collect/aspects/Pipe.lua",
  "src/collect/aspects/Cover.lua",
  "src/collect/aspects/Wearable.lua",
  "src/collect/aspects/Schema.lua",
  "src/collect/aspects/Selection.lua",
  "src/collect/aspects/Discharge.lua",
  "src/collect/aspects/Tipping.lua",
  "src/collect/aspects/Harvest.lua",
  "src/collect/aspects/Cutter.lua",
  "src/collect/aspects/Work.lua",
  -- Work areas read MapExporter's normalization at runtime (for the footprint), which is sourced
  -- further down; the call is inside the collector, so the order between the two does not matter.
  "src/collect/aspects/WorkAreas.lua",
  "src/collect/aspects/BaleCounter.lua",
  "src/collect/aspects/Sowing.lua",
  "src/collect/aspects/Spraying.lua",
  "src/collect/aspects/Plow.lua",
  "src/collect/aspects/Tillage.lua",
  "src/collect/aspects/Mixer.lua",
  "src/collect/aspects/Aspects.lua",
  -- Export-channel registry (must precede any integration that registers a channel into it)
  "src/export/ExportChannels.lua",
  -- Precision Farming detection (shared gate; sourced before the collectors that read it —
  -- MapLayers + FieldInfo suppress the fertilizer/lime data PF supersedes)
  "src/integrations/PrecisionFarming.lua",
  -- Map channels: base-game POIs + fields (event-driven), vehicle markers (own interval), and the
  -- ground-layer raster (own sweep cadence); all self-register into the registry above, and both
  -- MapVehicles and MapLayers reuse MapExporter's normalization/world-size helpers
  "src/collect/MapExporter.lua",
  "src/collect/MapVehiclesExporter.lua",
  "src/collect/MapLayersExporter.lua",
  -- GPS course channel: the steering assist's guidance lines for the field being driven. Reuses
  -- MapExporter's normalization, and owns the live `vehicle.gps.course` subtree that
  -- collect/vehicle/SupportSystems.lua reads back at telemetry cadence (runtime call, so the order
  -- between the two does not matter).
  "src/collect/GpsCourseExporter.lua",
  -- Production channel: own-farm production points + factories (own interval, base-game state only,
  -- self-registers into the channel registry)
  "src/collect/ProductionExporter.lua",
  -- Storage channel: own-farm standalone silos + object storages (reuses ProductionExporter's id /
  -- storage-row helpers, so it is sourced after it)
  "src/collect/StorageExporter.lua",
  -- Husbandry channel: own-farm animal pens (reuses ProductionExporter's id helper)
  "src/collect/HusbandryExporter.lua",
  -- Fleet channel: own-farm machines and their condition (own interval). Reuses MapVehicles' type
  -- token, MapExporter's normalization, the wearable/fill-unit aspects and Motor's fill units, so it
  -- is sourced after all of them.
  "src/collect/FleetExporter.lua",
  -- Missions channel: the farm's contracts (event-driven + a slow interval). Reuses MapExporter's
  -- normalization for the marker position, so it is sourced after it.
  "src/collect/MissionExporter.lua",
  -- Finance channel: the farm's books -- balance, loan, the month-by-month finances table and the
  -- money notifications as a log (interval + event-driven).
  "src/collect/FinanceExporter.lua",
  -- Calendar channels: the sowing/harvest periods per crop (event-driven, per in-game day) and the
  -- weather forecast (event-driven, per in-game hour). Both are world state rather than farm state,
  -- and both read only base-game managers.
  "src/collect/CropCalendarExporter.lua",
  "src/collect/WeatherExporter.lua",
  -- Prices channel: the map's price board -- what each station pays or charges, plus the twelve-month
  -- curve per commodity (own interval, world state rather than farm state). Reuses MapExporter's
  -- normalization for the station marker and ProductionExporter's id helper, so it follows both.
  "src/collect/PricesExporter.lua",
  -- Integrations (optional third-party mods) — registry depends on the integration files
  "src/integrations/EnhancedVehicle.lua",
  "src/integrations/AdvancedDamageSystem.lua",
  "src/integrations/CombineXP.lua",
  "src/integrations/registry.lua",
  "src/integrations/TaskList.lua",
  "src/integrations/CropRotation.lua",
  -- Enhanced Loan System detection. The finance channel asks it at *runtime* whether the base-game
  -- loan has been replaced, so its position relative to that collector does not matter.
  "src/integrations/EnhancedLoanSystem.lua",
  -- Invoices channel: billing between farms, when FS25_Invoices is installed (event-driven off that
  -- mod's own notifyUI funnel).
  "src/integrations/Invoices.lua",
  -- Per-field agronomy channel (field-info popup); reads base-game FieldState and, when present,
  -- enriches each field via the CropRotation integration above, so it is sourced after it.
  "src/collect/FieldInfoExporter.lua",
  -- Orchestrators depend on the collectors + aspects + integrations above
  "src/collect/VehicleExporter.lua",
  -- Command back-channel (app -> mod), read side; depends on Json above. CommandRegistry first: the
  -- controls self-register their command types into it when sourced.
  "src/command/CommandRegistry.lua",
  "src/command/CommandChannel.lua",
  "src/command/LightControl.lua",
  "src/command/ImplementControl.lua",
  -- Resolves a command's target token to the object it names; the direct-call controls below need
  -- the object itself, where ImplementControl only needs the vehicle vdAI walks from.
  "src/command/TargetResolver.lua",
  "src/command/PipeCoverControl.lua",
  "src/command/TrailerControl.lua",
  -- Drives the game's own machine selection (and, in the same call, the Cylindered control group).
  -- Addresses a machine by the rig diagram's node path rather than by a target token, so it resolves
  -- its own walk and does not use TargetResolver.
  "src/command/SelectionControl.lua",
  "src/command/MotorControl.lua",
  "src/command/CruiseControl.lua",
  -- Precision Farming application rate (auto/manual + the manual step). Resolves which machine on the
  -- rig to drive through src/integrations/PrecisionFarming.lua, sourced above.
  "src/command/PrecisionFarmingControl.lua",
  "src/command/GpsControl.lua",
  "src/command/TaskListControl.lua",
  "src/command/CropRotationControl.lua",
  -- Productions write-back (line on/off + output mode); depends on ProductionExporter (own-farm +
  -- id helpers) sourced with the collectors above
  "src/command/ProductionControl.lua",
  -- Object-storage unload (bales/pallets); same ProductionExporter helpers
  "src/command/ObjectStorageControl.lua",
  -- Contract accept/cancel/collect; drives the game's own mission events and reuses
  -- MissionExporter's permission + status helpers, so it is sourced after it
  "src/command/MissionControl.lua",
  -- Loan write-back (set the farm's loan to a target); drives the game's own ChangeLoanEvent and
  -- reuses FinanceExporter's farm + permission helpers, so it is sourced after it
  "src/command/FinanceControl.lua",
  -- Enhanced Loan System write-back (take / specially redeem an annuity loan); drives that mod's own
  -- manager and reuses its integration handle, sourced with the integrations above
  "src/command/EnhancedLoanControl.lua",
  -- Invoices write-back (pay / cancel / answer a proposal / issue one); drives that mod's own service
  -- and reuses its integration handle, sourced with the integrations above
  "src/command/InvoiceControl.lua",
  -- Ground-layer subscription: tells the mapLayers channel which raster planes the terminal is
  -- showing, so it sweeps only those (MapLayersExporter is sourced with the collectors above)
  "src/command/MapLayersControl.lua",
  -- GUI: injects settings controls into the in-game menu
  "src/gui/SettingsFrame.lua",
}

for _, file in ipairs(sourceFiles) do
  source(modDirectory .. file)
end

---@class VDTelemetry
---@field debugger GrisuDebug
---@field exportEnabled boolean
---@field writeIntervalMs number
---@field updateTimer number
---@field settingsXmlFile string
---@field jsonFileLocation string
---@field pda PDA | nil
---@field prettyJson boolean
---@field logLevelString string
---@field specLevelString string
---@field baseDir string modSettings/<modName>/ — holds the settings XML + the telemetry/ subfolder
---@field commandFileLocation string | nil path to the command channel's commands.xml (client-side only)
---@field lastCommandId number highest command id already handled (dedup watermark)
---@field commandsPolledThisCycle boolean guards the once-per-cycle command poll (offset from the write)
---@field staleFilesCleaned boolean guards the one-shot startup cleanup of never-written channel files
VDTelemetry = {}
VDTelemetry.STATE_FILE_NAME = "vdTelemetry.json"
-- Registry name of the main telemetry export channel (see src/export/ExportChannels.lua).
VDTelemetry.TELEMETRY_CHANNEL = "telemetry"
-- 2: fill-unit `value` is fractional (consumables are measured in slots and report the part-used one),
--    plus the optional `precision` / `display` hints.
-- 3: `pipe` and `cover` are objects rather than bare state strings — multi-state pipes and
--    multi-cover vehicles could not be expressed as one label.
-- 4: `schema` (the rig-diagram silhouette + attacher joints), `selection` (what the player's
--    controls act on, plus the Cylindered control group) and the implement's `jointDescIndex`.
-- 5: `discharge`, `tipping`, `harvest`, `workMode`, `workWidth` and `baleCounter` aspects.
--    Versions 2-5 were exported ahead of any UI; what still renders none of them is in FUTURE.md.
-- 6: `motor.direction` — the direction the transmission is *in*, as distinct from `speed.direction`,
--    which is the way the machine is actually travelling and reads STOPPED below walking pace.
-- 7: `gps.course` — the live half of the steering course (which line, how far off it, how far to its
--    end, which lines are worked). The geometry it indexes into is its own gpsCourse.json channel.
--    See issue #43.
-- 8: the section view — `workWidth.sections` (the shutoff bar, the base game's only section control),
--    `workAreas` (what each part of the tool is doing, plus its footprint in map coordinates) and
--    `precisionFarming` (application rates per boom sub-section where PF keeps them, plus the live
--    per-nozzle spray states, which are the only per-position signal that survives multiplayer).
--    See issue #43.
-- 9: the ISOBUS aspects — per-class implement state, starting with `sowing` (which crop is in the
--    hopper, out of the machine's declared seed list). The fill unit only ever said SEEDS; this says
--    which. See issue #58 and isobus-plan.md.
-- 10: `steering` — the steering mode a crab-steering machine is in (with the shape of it derived from
--    the wheels, since the mode's name is untranslatable free text) and whether the driving position
--    has been turned round. See issue #57.
-- 11: `precisionFarming.manual` — the manual application-rate step and what one pass at it does (the
--    nitrogen/pH it adds, and the product per hectare it costs, in PF's own units), plus
--    `canToggleAuto`. Enough to drive the rate from the terminal rather than only watch it. See
--    issue #77.
-- 12: `precisionFarming.rate`/`rateUnit` — what is actually leaving the machine per hectare, in the
--    same units. The rate PF's HUD leads with in AUTO, where the tool picks its own and no step
--    describes it; absent whenever the boom is up, because PF never clears the field. See issue #77.
-- 13: `ads` — Advanced Damage System: the dashboard lamps it drives, where the machine is in its
--     service interval, what the last inspection found, and system voltage. Also
--     *changes* `motor.temperatur.value`, which is ADS's engine temperature when ADS is installed —
--     the first correct engine temperature this mod has exported to a multiplayer client, since the
--     vanilla figure is never synced. Also `ads.load`, which is a DIFFERENT number from
--     `motor.load` rather than a replacement for it: ADS's is the plain engine load plus what the
--     driveline is doing under the draft, is what its own dashboard prints, and is what it charges
--     engine wear against. See issue #79.
-- 14: `motor.state` says what the engine's own enum says. It has four values and the mapper only
--     ever had three: 2 (IGNITION, the key turned and the starter untouched) was exported as
--     STARTING, and 3 (STARTING, the starter cranking) was folded in with 4 into ON — so a cranking
--     engine read as running and our STARTING never once meant cranking. IGNITION is new, STARTING
--     changes meaning, and ON is now only the state the game itself calls started. See issue #86.
-- 15: `motor.rpm.value` and `motor.load.value` are 0 on an engine that is neither running nor being
--     cranked. Both used to be whatever the engine last held: it stops updating them when the motor
--     stops, and its own one-shot zeroing at the state change does not survive a multiplayer client
--     applying an rpm update that was already in flight behind the stop event — which left a smoothed
--     remnant of idle sitting under 100 rpm on a machine that had been switched off. See issue #94.
-- 16: the mixer wagon's `mixer` aspect — the recipe it mixes to, how far each ingredient is from its
--     window (in LITRES, since the bars are a share of the load rather than of the tub), whether the
--     drum is turning, and the tip sides' names. Plus `mass` on any object. See issue #113.
-- 17: `mixer.mass` is the TUB's load, weighed as its level times the density of what the tub reports
--     — the same arithmetic FillUnit:getAdditionalComponentMass does for that unit. The machine's
--     mass minus its empty mass is not a payload and never was: Vehicle:updateMass adds every fill
--     unit including the diesel and DEF tanks, a hard-attached implement's whole mass and the tension
--     belts, so an empty wagon read 617 kg of "load". See issue #113.
-- 18: `lowered` is absent on a machine with nothing to raise. It used to be `false` on every vehicle
--     alive, because base Vehicle registers `getIsLowered` on all of them and its whole body is
--     `return false` — so a tractor exported a lowered state and a terminal offered a raise control
--     for it. Now reported only where a specialization actually overrode that function AND does not
--     hand the caller's default straight back. See issue #116.
-- 19: `discharge.canToggle` — whether a player can start unloading on this machine at all, or whether
--     the engine does it by itself while the machine works. A sprayer and a seeder are Dischargeable
--     exactly as a trailer is, which is how the material leaves them, so nothing already exported told
--     the two apart and a terminal offered an unload control that could not do anything. Both of the
--     engine's own getCanToggleDischarge* are false there, and it registers no tip action for them.
--     See issue #116.
-- 20: `selection.selectable` -- whether the game would let the player select this object at all
--     (getCanBeSelected and not getBlockSelection, the engine's own test) -- and
--     `selection.controlGroup.available`, which of the declared control groups currently has a
--     sub-selection to reach it by. Both exist because selection became WRITABLE: setSelectedVehicle
--     silently selects a different machine when handed one that fails the first test, and no
--     argument reaches a group that fails the second. See issue #119.
-- 21: the combine gained everything but the straw toggle it already had: `harvest.filling` (crop is
--     entering the tank right now), the crop and fill type being threshed, worked hectares total and
--     for the session, the buffer-combine flag, and the two rain states -- the one that stops
--     threshing and the earlier warning that it is about to. Plus a new `cutter` aspect for the
--     header: what it is cutting or picking up, what it hands on, whether it is taking crop, and its
--     load where that number is real. Plus `harvest.combineXp` when FS25_CombineXP is installed:
--     throughput, yield and drum load off the mod's own measurement, its high-moisture flag, and the
--     speed its limiter is allowing. See issue #139.
VDTelemetry.VERSION = 21
VDTelemetry.SETTINGS_XML = "vdTelemetrySettings.xml"
VDTelemetry.SETTINGS_XML_VERSION = 3
-- Everything lives under modSettings/<modName>/: the settings XML at its root and the telemetry
-- JSON in a telemetry/ subfolder (a future command channel gets its own sibling folder). The
-- subfolder matters because the engine only permits deleteFile() inside modSettings/<modName>/.
VDTelemetry.TELEMETRY_SUBDIR = "telemetry/"
-- Command back-channel: the server writes commands.xml here, the mod polls it (XML because the
-- sandbox io.open is write-only, so the mod reads via the engine XMLFile.load). Sibling of the
-- telemetry/ subfolder, same modSettings/<modName>/ deleteFile() constraint (only the server writes).
VDTelemetry.COMMAND_SUBDIR = "commands/"
-- Write interval (ms) between telemetry samples. Configurable via the in-game General Settings
-- page; clamped to MIN_INTERVAL_MS since sub-frame intervals are pointless (a game frame is ~16-33 ms).
VDTelemetry.DEFAULT_INTERVAL_MS = 100
VDTelemetry.MIN_INTERVAL_MS = 16
-- The FS25_additionalInputs the mod is built against. Major must match exactly (it is bumped for a
-- breaking change); minor is a floor, raised whenever we start calling a function a newer version
-- added. 2 is `vdAI<Action>Selected`, which ImplementControl needs to address the selected machine
-- (issue #120). Failing the check switches the export off rather than letting half the commands
-- silently do nothing.
VDTelemetry.VD_AI = {
  REQUIRED_MAJOR_VERSION = 1,
  REQUIRED_MIN_MINOR_VERSION = 2,
}

VDTelemetry.mainFuelTypes = Set:new({ "DIESEL", "ELECTRICCHARGE", "METHANE" })

local VDTelemetry_mt = Class(VDTelemetry)

---@return VDTelemetry
function VDTelemetry.init()
  ---@type VDTelemetry
  local self = {}

  setmetatable(self, VDTelemetry_mt)

  self.debugger = GrisuDebug:create("VDTelemetry")
  self.debugger:setLogLvl(GrisuDebug.TRACE)

  self.exportEnabled = false
  self.writeIntervalMs = VDTelemetry.DEFAULT_INTERVAL_MS
  self.logLevelString = "INFO"
  self.specLevelString = "INFO"
  self.specLogLevel = GrisuDebug.INFO
  self.updateTimer = 0
  self.lastCommandId = 0
  self.commandsPolledThisCycle = false
  self.staleFilesCleaned = false

  self.baseDir = getUserProfileAppPath() .. "modSettings/" .. modName .. "/"
  createFolder(self.baseDir)
  self.settingsXmlFile = self.baseDir .. VDTelemetry.SETTINGS_XML

  if not fileExists(self.settingsXmlFile) then
    self:writeDefaultSettings()
  end
  self:loadSettingsFromFile()

  self.debugger:info("VDTelemetry initialized")
  return self
end

function VDTelemetry:loadMap(filename)
  self.debugger:debug("VDTelemetry loading")
  -- check if FS25_additionalInputs is present in correct version
  -- TODO display warning in ui
  if FS25_additionalInputs == nil or g_vdAdditionalInputs == nil then
    self.debugger:error("FS25_additionalInputs is required but not present")
    self.exportEnabled = false
  else
    if VDTelemetry.VD_AI.REQUIRED_MAJOR_VERSION ~= g_vdAdditionalInputs.MAJOR_VERSION then
      self.debugger:error(
        string.format(
          "FS25_additionalInputs with major version %s is required, but was %s",
          VDTelemetry.VD_AI.REQUIRED_MAJOR_VERSION,
          g_vdAdditionalInputs.MAJOR_VERSION
        )
      )
      self.exportEnabled = false
    elseif VDTelemetry.VD_AI.REQUIRED_MIN_MINOR_VERSION > g_vdAdditionalInputs.MINOR_VERSION then
      self.debugger:error(
        string.format(
          "FS25_additionalInputs with minimum minor version %s is required, but was %s",
          VDTelemetry.VD_AI.REQUIRED_MIN_MINOR_VERSION,
          g_vdAdditionalInputs.MINOR_VERSION
        )
      )
      self.exportEnabled = false
    end
  end

  -- telemetry is client-side only: the file lives on the client's machine, not the dedicated server
  if self:isTelemetryAvailable() then
    self.telemetryDir = self.baseDir .. VDTelemetry.TELEMETRY_SUBDIR
    createFolder(self.telemetryDir)
    self.jsonFileLocation = self.telemetryDir .. VDTelemetry.STATE_FILE_NAME

    local commandDir = self.baseDir .. VDTelemetry.COMMAND_SUBDIR
    createFolder(commandDir)
    self.commandFileLocation = commandDir .. VDT.CommandChannel.FILE_NAME
    -- Start each session with a clean command channel: delete any leftover commands.xml so stale
    -- commands can't fire on load and ids restart from scratch (the server resets its id counter
    -- when it finds the file gone). The mod may deleteFile under modSettings/<modName>/.
    if fileExists(self.commandFileLocation) then
      deleteFile(self.commandFileLocation)
    end
    self.lastCommandId = 0
    -- Resolved paths at debug: on Proton these are Wine paths, handy when pointing the server's
    -- command writer at the right prefix, but not needed in normal operation.
    -- The main telemetry file is itself an export channel, marked dirty every write interval.
    -- Event-driven channels (TaskList, ...) self-register when their integration file is sourced.
    VDT.ExportChannels.register({
      name = VDTelemetry.TELEMETRY_CHANNEL,
      fileName = VDTelemetry.STATE_FILE_NAME,
      -- The live-dashboard channel bypasses ExportChannels' one-heavy-channel-per-frame spread: it's
      -- cheap and latency-sensitive (10 Hz), so it must flush on its own interval regardless of the
      -- heavy channels' backlog. The main loop marks it dirty every write interval.
      latencyCritical = true,
      isAvailable = function()
        return true
      end,
      collect = function()
        return {
          version = tostring(VDTelemetry.VERSION),
          environment = VDT.EnvironmentExporter.collect(self.pda),
          vehicle = VDT.VehicleExporter.collect(self.currentVehicle),
        }
      end,
    })
    -- A channel may write into a subfolder of telemetry/ (mapLayers/ holds one file per raster plane);
    -- io.open does not create one, so every folder the registered channels name is created here. After
    -- the registration above, so the telemetry channel is included in the walk -- every other channel
    -- self-registered when its file was sourced.
    for _, subDir in ipairs(VDT.ExportChannels.subDirs()) do
      createFolder(self.telemetryDir .. subDir)
    end

    -- Serializer shared by every channel; reads prettyJson live so the settings toggle applies.
    self.encode = function(model)
      return Json.encode(model, self.prettyJson)
    end

    self.debugger:debug("Telemetry file: %s", self.jsonFileLocation)
    self.debugger:debug("Command file:   %s", self.commandFileLocation)
  else
    self.debugger:debug("Telemetry + command channel disabled (dedicated server / not available)")
  end

  self.pda = MapUtil.getMapPDAFile()

  -- add the export toggle + write-interval selector to the in-game General Settings page
  VDT.SettingsFrame.install()

  self.debugger:info("VDTelemetry loaded")
end

function VDTelemetry:writeDefaultSettings()
  self.debugger:trace("writeDefaultSettings")
  self.exportEnabled = g_dedicatedServer == nil
  self.writeIntervalMs = VDTelemetry.DEFAULT_INTERVAL_MS
  self.logLevelString = "INFO"
  self.specLevelString = "INFO"
  self.prettyJson = false
  self:saveSettingsToFile()
end

-- Persist the current in-memory settings back to vdTelemetrySettings.xml. Writes the whole
-- document (values, not defaults) so the settings UI can flip a single field without dropping
-- the others.
function VDTelemetry:saveSettingsToFile()
  self.debugger:trace("saveSettingsToFile")
  local xml = XMLFile.create("VDTS", self.settingsXmlFile, "VDTS")
  if xml == nil then
    self.debugger:error("could not create settings xml %s", tostring(self.settingsXmlFile))
    return
  end

  xml:setInt("VDTS#version", VDTelemetry.SETTINGS_XML_VERSION)
  xml:setBool("VDTS.export.enabled", self.exportEnabled)
  xml:setInt("VDTS.export.intervalMs", self.writeIntervalMs)
  xml:setString("VDTS.logging.level", self.logLevelString)
  xml:setString("VDTS.logging.specLevel", self.specLevelString)
  xml:setBool("VDTS.json.pretty", self.prettyJson)

  -- Performance profile: preset that scales the secondary channels' cadence (or "custom" to honour the
  -- per-channel intervals below). Owned by ExportChannels; the in-game selector drives it.
  xml:setString("VDTS.profile", VDT.ExportChannels.getProfile())

  -- Per-channel config (enable toggle + interval override). Enumerated from the registry so the
  -- channel list isn't duplicated here; event-driven channels have no intervalMs. Fine-tuned via the
  -- app / this XML only (there's no in-game per-channel UI); applied at load, see loadSettingsFromFile.
  for i, cfg in ipairs(VDT.ExportChannels.configurableChannels()) do
    local key = string.format("VDTS.channels.channel(%d)", i - 1)
    xml:setString(key .. "#id", cfg.name)
    xml:setBool(key .. "#enabled", cfg.enabled)
    if cfg.intervalMs ~= nil then
      xml:setInt(key .. "#intervalMs", cfg.intervalMs)
    end
  end

  xml:save()
  xml:delete()
end

function VDTelemetry:loadSettingsFromFile()
  self.debugger:trace("loadSettingsFromFile")
  local xml = XMLFile.load("VDTS", self.settingsXmlFile)
  if xml == nil then
    self.debugger:error("could not load settings xml, writing defaults")
    self:writeDefaultSettings()
    return
  end

  local version = xml:getInt("VDTS#version", 0)
  if version ~= VDTelemetry.SETTINGS_XML_VERSION then
    -- schema changed (or corrupt) -> regenerate from defaults. writeDefaultSettings also
    -- populates the in-memory fields, so there's nothing more to read here.
    self.debugger:error("Unknown settings xml version %d, resetting to defaults", version)
    xml:delete()
    self:writeDefaultSettings()
    return
  end

  self.exportEnabled = xml:getBool("VDTS.export.enabled", g_dedicatedServer == nil)
  self.writeIntervalMs =
    math.max(xml:getInt("VDTS.export.intervalMs", VDTelemetry.DEFAULT_INTERVAL_MS), VDTelemetry.MIN_INTERVAL_MS)
  self.logLevelString = xml:getString("VDTS.logging.level", "INFO")
  self.specLevelString = xml:getString("VDTS.logging.specLevel", "INFO")
  self.prettyJson = xml:getBool("VDTS.json.pretty", false)

  -- Performance profile: set it before applying channel config below (channelInterval resolves against
  -- it). An unknown/absent value falls back to the default.
  VDT.ExportChannels.setProfile(xml:getString("VDTS.profile", VDT.ExportChannels.DEFAULT_PROFILE))

  -- Per-channel config: apply each entry to its registered channel (all configurable channels have
  -- self-registered by now, since this runs from init() after the sourceFiles loop). Unknown ids and
  -- nil fields are ignored by configure(), so a stale/partial entry falls back to the channel default.
  xml:iterate("VDTS.channels.channel", function(_, key)
    local id = xml:getString(key .. "#id")
    if id ~= nil then
      VDT.ExportChannels.configure(id, {
        enabled = xml:getBool(key .. "#enabled"),
        intervalMs = xml:getInt(key .. "#intervalMs"),
      })
    end
  end)

  self.debugger:setLogLvl(GrisuDebug.parseLogLevel(self.logLevelString))
  self.specLogLevel = GrisuDebug.parseLogLevel(self.specLevelString)

  xml:delete()
end

-- Telemetry is client-side only: the file lives on the client's machine, not the dedicated
-- server box. The settings UI is gated on this too.
function VDTelemetry:isTelemetryAvailable()
  return g_dedicatedServer == nil
end

---Live-apply an export enabled/disabled change from the settings UI and persist it.
---@param enabled boolean
function VDTelemetry:setExportEnabled(enabled)
  if self.exportEnabled == enabled then
    return
  end
  self.exportEnabled = enabled
  self.updateTimer = 0
  self.commandsPolledThisCycle = false
  if enabled then
    -- repopulate every available channel promptly rather than waiting for the next change event
    VDT.ExportChannels.markAllDirty()
  else
    -- drop the stale files so the terminal's file-watch sees export stop
    self:deleteChannelFiles()
  end
  self:saveSettingsToFile()
  self.debugger:info("Export %s", enabled and "enabled" or "disabled")
end

---Live-apply a write-interval change from the settings UI and persist it.
---@param intervalMs number
function VDTelemetry:setWriteIntervalMs(intervalMs)
  intervalMs = math.max(intervalMs, VDTelemetry.MIN_INTERVAL_MS)
  if self.writeIntervalMs == intervalMs then
    return
  end
  self.writeIntervalMs = intervalMs
  self.updateTimer = 0
  self.commandsPolledThisCycle = false
  self:saveSettingsToFile()
  self.debugger:info("Write interval set to %d ms", intervalMs)
end

---Live-apply a performance-profile change from the settings UI and persist it. The profile lives in
---ExportChannels (it resolves the per-channel cadence); this just applies + persists + refreshes.
---@param name string one of VDT.ExportChannels.PROFILES
function VDTelemetry:setProfile(name)
  if VDT.ExportChannels.getProfile() == name then
    return
  end
  if not VDT.ExportChannels.setProfile(name) then
    self.debugger:warn("ignoring unknown profile '%s'", tostring(name))
    return
  end
  -- refresh promptly so the new cadence's first samples land without waiting a full (possibly long)
  -- interval; each channel is still gated by its own availability + enabled at write time
  VDT.ExportChannels.markAllDirty()
  -- A profile can switch a channel off outright (minProfile), and the app reads a channel file's
  -- absence as "off" -- so drop the files of everything the new profile won't write. Same call the
  -- startup cleanup makes, for the same reason: otherwise the last profile's mapLayers.json sits there
  -- and the app keeps rendering an overlay that is no longer being updated.
  self:deleteStaleChannelFiles()
  self:saveSettingsToFile()
  self.debugger:info("Profile set to %s", name)
end

-- Delete the named files from the telemetry folder. deleteFile is permitted under
-- modSettings/<modName>/.
---@param fileNames string[]
function VDTelemetry:deleteTelemetryFiles(fileNames)
  if self.telemetryDir == nil then
    return
  end
  for _, fileName in ipairs(fileNames) do
    local path = self.telemetryDir .. fileName
    if fileExists(path) then
      deleteFile(path)
      self.debugger:debug("Deleted %s", path)
    end
  end
end

-- Delete every channel's file (called when export is disabled) so the terminal's file-watch sees
-- export stop.
function VDTelemetry:deleteChannelFiles()
  self:deleteTelemetryFiles(VDT.ExportChannels.fileNames())
end

-- One-shot startup cleanup: drop every channel file that this session will never write, so the
-- terminal can't serve last session's data. A file's absence is exactly how the app learns an
-- optional mod isn't installed, so uninstalling FS25_TaskList / FS25_CropRotation would otherwise
-- leave its json behind and the app would keep rendering that stale panel forever. With export off
-- nothing is written at all, so everything goes.
--
-- Deferred to the first update rather than loadMap because isAvailable() only turns true once the
-- integration's mod has loaded; by the first update tick every mod is up, so an unavailable channel
-- really means "not installed". A channel that is available rewrites its file on the same tick or
-- shortly after (both integrations queue an initial write), so nothing useful is dropped.
function VDTelemetry:deleteStaleChannelFiles()
  if self.exportEnabled then
    self:deleteTelemetryFiles(VDT.ExportChannels.unavailableFileNames())
  else
    self:deleteChannelFiles()
  end
end

function VDTelemetry:update(dt)
  -- Client-side only (the files live on the client's machine). The command channel runs even when
  -- telemetry export is off — sending commands is independent of exporting telemetry.
  if not self:isTelemetryAvailable() then
    return
  end

  -- Let event-driven channels subscribe/settle and interval-driven ones advance their own timer
  -- (cheap once ready); independent of export + the main write interval.
  VDT.ExportChannels.tick(self.debugger, dt)

  -- After that first tick every integration has had its chance to come up, so now (and only now) an
  -- unavailable channel means "mod not installed" -- see deleteStaleChannelFiles().
  if not self.staleFilesCleaned then
    self.staleFilesCleaned = true
    self:deleteStaleChannelFiles()
  end

  self.updateTimer = self.updateTimer + dt

  -- Poll commands once per cycle at the half-interval mark, and mark telemetry dirty at the full
  -- interval — offset so the command read and the telemetry write land on different frames, spreading
  -- the per-frame cost. Command latency stays ≈ one interval, fine for button presses.
  if not self.commandsPolledThisCycle and self.updateTimer >= self.writeIntervalMs * 0.5 then
    self.commandsPolledThisCycle = true
    self:pollCommands()
  elseif self.updateTimer >= self.writeIntervalMs then
    -- Reset the timer before the work so a slow tick doesn't compound.
    self.updateTimer = 0
    self.commandsPolledThisCycle = false
    VDT.ExportChannels.markDirty(VDTelemetry.TELEMETRY_CHANNEL)
  end

  -- The export master switch gates every file write; the command channel above runs regardless.
  -- Flushes telemetry on its interval and any event-driven channel a message marked dirty this cycle.
  if self.exportEnabled then
    VDT.ExportChannels.writeDirty(self.telemetryDir, self.encode, self.debugger)
  end
end

-- Poll the command back-channel and dispatch any commands newer than lastCommandId. Same cadence
-- as the telemetry write; command latency ≈ poll interval, fine for button presses.
function VDTelemetry:pollCommands()
  if self.commandFileLocation == nil then
    -- warn once, not every tick: the channel is inactive (loadMap never set the path)
    if not self.warnedNoCommandFile then
      self.debugger:warn("pollCommands: commandFileLocation is nil — command channel inactive")
      self.warnedNoCommandFile = true
    end
    return
  end

  self.lastCommandId = VDT.CommandChannel.poll(
    self.commandFileLocation,
    self.lastCommandId,
    VDT.CommandRegistry,
    function(cmd)
      self:onCommand(cmd)
    end,
    self.debugger
  )
end

-- Handle a single received command. Parsing + execution live in the control that owns the command
-- type (registered in VDT.CommandRegistry); poll() has already parsed the payload, so here we just
-- supply the current vehicle and run it. Unknown types are handled (warned) in poll().
function VDTelemetry:onCommand(cmd)
  self.debugger:debug("Received command id=%s type=%s", tostring(cmd.id), tostring(cmd.type))

  -- Most commands drive the current vehicle and are meaningless on foot, so they're dropped when
  -- there's none. A handler that targets global client state (e.g. setGpsLinesVisible) declares
  -- requiresVehicle = false and runs regardless — the vehicle arg is simply nil for those.
  local vehicle = self.currentVehicle
  if cmd.requiresVehicle and vehicle == nil then
    self.debugger:debug("no current vehicle; ignoring command %s", tostring(cmd.type))
    return
  end

  cmd.execute(vehicle, cmd.params, self.debugger)
end

---@param vehicle VDTelemetrySpec
function VDTelemetry:setCurrentVehicle(vehicle)
  self.currentVehicle = vehicle
end

function VDTelemetry:clearCurrentVehicle()
  self.currentVehicle = nil
end

local function init()
  g_vdTelemetry = VDTelemetry.init()

  -- make vdTelemetry globally available
  getmetatable(_G).__index.g_vdTelemetry = g_vdTelemetry

  -- add event listener
  addModEventListener(g_vdTelemetry)
end

init()
