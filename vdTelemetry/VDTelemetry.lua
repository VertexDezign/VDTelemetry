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
  "src/collect/VehicleExporter.lua"
}

for _, file in ipairs(sourceFiles) do
  source(modDirectory .. file)
end

---@class VDTelemetry
---@field debugger GrisuDebug
---@field exportEnabled boolean
---@field updateTimer number
---@field settingsXmlFile string
---@field jsonFileLocation string
---@field pda PDA | nil
---@field prettyJson boolean
VDTelemetry = {}
VDTelemetry.STATE_FILE_NAME = "vdTelemetry.json"
VDTelemetry.VERSION = 1
VDTelemetry.SETTINGS_XML = "vdTelemetrySettings.xml"
VDTelemetry.SETTINGS_XML_VERSION = 1
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
  self.specLogLevel = GrisuDebug.INFO
  self.updateTimer = 0

  local modSettingsDir = getUserProfileAppPath() .. "modSettings/"
  self.settingsXmlFile = modSettingsDir .. VDTelemetry.SETTINGS_XML

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
  if g_dedicatedServerInfo == nil then
    self.jsonFileLocation = getUserProfileAppPath() .. VDTelemetry.STATE_FILE_NAME
  end

  self.pda = MapUtil.getMapPDAFile()

  self.debugger:info("VDTelemetry loaded")
end

function VDTelemetry:writeDefaultSettings()
  self.debugger:trace("writeDefaultSettings")
  local xml = XMLFile.create("VDTS", self.settingsXmlFile, "VDTS")

  xml:setInt("VDTS#version", 1)
  xml:setBool("VDTS.exportEnabled", g_dedicatedServerInfo == nil)
  xml:setString("VDTS.logging.level", "INFO")
  xml:setString("VDTS.logging.specLevel", "INFO")
  xml:setBool("VDTS.json.pretty", false)

  xml:save()
  xml:delete()
end

function VDTelemetry:loadSettingsFromFile()
  self.debugger:trace("loadSettingsFromFile")
  local xml = XMLFile.load("VDTS", self.settingsXmlFile)

  local version = xml:getInt("VDTS#version", 0)
  if version ~= VDTelemetry.SETTINGS_XML_VERSION then
    --TODO proper handling?
    self.debugger:error("Unknown settings xml version, setting defaults values")
    self:writeDefaultSettings()
  end

  self.exportEnabled = xml:getBool("VDTS.exportEnabled", true)
  local logLevel = xml:getString("VDTS.logging.level", "INFO")
  local specLogLevel = xml:getString("VDTS.logging.specLevel", "INFO")
  self.prettyJson = xml:getBool("VDTS.json.pretty", false)

  local parseLogLevel = GrisuDebug.parseLogLevel(logLevel)
  self.debugger:setLogLvl(parseLogLevel)
  self.specLogLevel = GrisuDebug.parseLogLevel(specLogLevel)

  xml:delete()
end

function VDTelemetry:update(dt)
  if self.exportEnabled == false then
    return
  end

  self.updateTimer = self.updateTimer + dt
  -- only update every 500ms
  if self.updateTimer < 500 then
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
