-- VDTelemetry
--
-- @author  Grisu118 - VertexDezign.net
-- @history     v1.0.0.0 - 2024-11-18 - Initial implementation
-- @Descripion: Exports game state into a json for telemetry consumers (e.g. GameGlass)
-- @web: https://grisu118.ch or https://vertexdezign.net
-- Copyright (C) Grisu118, All Rights Reserved.

local modDirectory = g_currentModDirectory
local modName = g_currentModName

---Source files to load, there are loaded in order, so if there is a dependency to another file, at it after the file it requires
---@type table<string> files to source.
local sourceFiles = {
  -- Utils
  "src/utils/Set.lua",
  "src/utils/MapUtil.lua",
  "src/utils/Json.lua",
  -- Mappers
  "src/mapper/ValueMapper.lua",
  -- Collectors (model/ holds annotation-only @class defs and is not sourced)
  "src/collect/EnvironmentExporter.lua",
  "src/collect/vehicle/Motor.lua",
  "src/collect/vehicle/Lights.lua",
  "src/collect/vehicle/SupportSystems.lua",
  -- Shared aspects (any vehicle or implement); Aspects.lua depends on the individual collectors
  "src/collect/aspects/TurnOn.lua",
  "src/collect/aspects/Foldable.lua",
  "src/collect/aspects/Lowered.lua",
  "src/collect/aspects/FillUnit.lua",
  "src/collect/aspects/Pipe.lua",
  "src/collect/aspects/Cover.lua",
  "src/collect/aspects/Wearable.lua",
  "src/collect/aspects/Aspects.lua",
  -- Integrations (optional third-party mods) — registry depends on the integration files
  "src/integrations/EnhancedVehicle.lua",
  "src/integrations/registry.lua",
  -- Orchestrators depend on the collectors + aspects + integrations above
  "src/collect/VehicleExporter.lua",
  -- Command back-channel (app -> mod), read side; depends on Json above. CommandRegistry first: the
  -- controls self-register their command types into it when sourced.
  "src/command/CommandRegistry.lua",
  "src/command/CommandChannel.lua",
  "src/command/LightControl.lua",
  "src/command/ImplementControl.lua",
  "src/command/MotorControl.lua",
  "src/command/CruiseControl.lua",
  "src/command/GpsControl.lua",
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
VDTelemetry = {}
VDTelemetry.STATE_FILE_NAME = "vdTelemetry.json"
VDTelemetry.VERSION = 1
VDTelemetry.SETTINGS_XML = "vdTelemetrySettings.xml"
VDTelemetry.SETTINGS_XML_VERSION = 2
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
VDTelemetry.VD_AI = {
  REQUIRED_MAJOR_VERSION = 1,
  REQUIRED_MIN_MINOR_VERSION = 1,
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
    local telemetryDir = self.baseDir .. VDTelemetry.TELEMETRY_SUBDIR
    createFolder(telemetryDir)
    self.jsonFileLocation = telemetryDir .. VDTelemetry.STATE_FILE_NAME

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
  self.exportEnabled = g_dedicatedServerInfo == nil
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

  self.exportEnabled = xml:getBool("VDTS.export.enabled", g_dedicatedServerInfo == nil)
  self.writeIntervalMs =
    math.max(xml:getInt("VDTS.export.intervalMs", VDTelemetry.DEFAULT_INTERVAL_MS), VDTelemetry.MIN_INTERVAL_MS)
  self.logLevelString = xml:getString("VDTS.logging.level", "INFO")
  self.specLevelString = xml:getString("VDTS.logging.specLevel", "INFO")
  self.prettyJson = xml:getBool("VDTS.json.pretty", false)

  self.debugger:setLogLvl(GrisuDebug.parseLogLevel(self.logLevelString))
  self.specLogLevel = GrisuDebug.parseLogLevel(self.specLevelString)

  xml:delete()
end

-- Telemetry is client-side only: the file lives on the client's machine, not the dedicated
-- server box. The settings UI is gated on this too.
function VDTelemetry:isTelemetryAvailable()
  return g_dedicatedServerInfo == nil
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
  if not enabled then
    -- drop the stale file so the terminal's file-watch sees export stop
    self:deleteJsonFile()
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

function VDTelemetry:deleteJsonFile()
  if self.jsonFileLocation ~= nil and fileExists(self.jsonFileLocation) then
    deleteFile(self.jsonFileLocation)
    self.debugger:debug("Deleted json file %s", self.jsonFileLocation)
  end
end

function VDTelemetry:update(dt)
  -- Client-side only (the files live on the client's machine). The command channel runs even when
  -- telemetry export is off — sending commands is independent of exporting telemetry.
  if not self:isTelemetryAvailable() then
    return
  end

  self.updateTimer = self.updateTimer + dt

  -- Poll commands once per cycle at the half-interval mark, so the command read lands on a different
  -- frame than the telemetry write below — spreading the per-frame cost rather than doing both at
  -- once. Command latency stays ≈ one interval, fine for button presses.
  if not self.commandsPolledThisCycle and self.updateTimer >= self.writeIntervalMs * 0.5 then
    self.commandsPolledThisCycle = true
    self:pollCommands()
    return
  end

  if self.updateTimer < self.writeIntervalMs then
    return
  end
  -- Reset the timer before the work so a slow tick doesn't compound.
  self.updateTimer = 0
  self.commandsPolledThisCycle = false

  if self.exportEnabled then
    self:writeJsonFile()
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

-- Build the telemetry model from the collectors and write it as JSON (the mod's on-disk format).
-- collect -> model -> Json.encode: each collector returns a plain-table fragment; the whole model
-- serializes with no per-field format code. `version` is emitted as a string to match the shared
-- model (Model.kt: VdtData.version: String).
function VDTelemetry:writeJsonFile()
  if self.jsonFileLocation == nil then
    return
  end

  local model = {
    version = tostring(VDTelemetry.VERSION),
    environment = VDT.EnvironmentExporter.collect(self.pda),
    vehicle = VDT.VehicleExporter.collect(self.currentVehicle),
  }

  local file = io.open(self.jsonFileLocation, "w")
  if file == nil then
    self.debugger:error("could not open json file " .. tostring(self.jsonFileLocation))
    return
  end
  file:write(Json.encode(model, self.prettyJson))
  file:close()
  self.debugger:trace("Wrote json file")
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
