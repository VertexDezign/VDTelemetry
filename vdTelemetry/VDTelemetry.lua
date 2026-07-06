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
  -- GUI: injects settings controls into the in-game menu
  "src/gui/SettingsFrame.lua"
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
VDTelemetry = {}
VDTelemetry.STATE_FILE_NAME = "vdTelemetry.json"
VDTelemetry.VERSION = 1
VDTelemetry.SETTINGS_XML = "vdTelemetrySettings.xml"
VDTelemetry.SETTINGS_XML_VERSION = 2
-- Everything lives under modSettings/<modName>/: the settings XML at its root and the telemetry
-- JSON in a telemetry/ subfolder (a future command channel gets its own sibling folder). The
-- subfolder matters because the engine only permits deleteFile() inside modSettings/<modName>/.
VDTelemetry.TELEMETRY_SUBDIR = "telemetry/"
-- Write interval (ms) between telemetry samples. Configurable via the in-game General Settings
-- page; clamped to MIN_INTERVAL_MS since sub-frame intervals are pointless (a game frame is ~16-33 ms).
VDTelemetry.DEFAULT_INTERVAL_MS = 100
VDTelemetry.MIN_INTERVAL_MS = 16
VDTelemetry.VD_AI = {
  REQUIRED_MAJOR_VERSION = 1,
  REQUIRED_MIN_MINOR_VERSION = 1
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
      self.debugger:error(string.format("FS25_additionalInputs with major version %s is required, but was %s", VDTelemetry.VD_AI.REQUIRED_MAJOR_VERSION, g_vdAdditionalInputs.MAJOR_VERSION))
      self.exportEnabled = false
    elseif VDTelemetry.VD_AI.REQUIRED_MIN_MINOR_VERSION < g_vdAdditionalInputs.MINOR_VERSION then
      self.debugger:error(string.format("FS25_additionalInputs with minimum minor version %s is required, but was %s", VDTelemetry.VD_AI.REQUIRED_MIN_MINOR_VERSION, g_vdAdditionalInputs.MINOR_VERSION))
      self.exportEnabled = false
    end
  end

  -- telemetry is client-side only: the file lives on the client's machine, not the dedicated server
  if self:isTelemetryAvailable() then
    local telemetryDir = self.baseDir .. VDTelemetry.TELEMETRY_SUBDIR
    createFolder(telemetryDir)
    self.jsonFileLocation = telemetryDir .. VDTelemetry.STATE_FILE_NAME
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
  self.writeIntervalMs = math.max(xml:getInt("VDTS.export.intervalMs", VDTelemetry.DEFAULT_INTERVAL_MS), VDTelemetry.MIN_INTERVAL_MS)
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
  if self.exportEnabled == false then
    return
  end

  self.updateTimer = self.updateTimer + dt
  if self.updateTimer < self.writeIntervalMs then
    return
  end

  self:writeJsonFile()

  -- Reset the timer after execution
  self.updateTimer = 0
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
