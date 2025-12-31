-- GameGlass
--
-- @author  Grisu118 - VertexDezign.net
-- @history     v1.0.0.0 - 2024-11-18 - Initial implementation
-- @Descripion: Exports game state into an xml for gameglass
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
  -- Mappers
  "src/mapper/ValueMapper.lua"
}

for _, file in ipairs(sourceFiles) do
  source(modDirectory .. file)
end

---@class CombinedInfo
---@field fillUnits table<string, CombinedFillUnit> @key is type
---@field wearable CombinedWearable
---@field frontState ImplementState|nil
---@field backState ImplementState|nil

---@class CombinedFillUnit
---@field type string
---@field title string
---@field unit string
---@field capacity number
---@field fillLevel number

---@class CombinedWearable
---@field damage number
---@field wear number
---@field dirt number

---@class ImplementState
---@field foldable string?
---@field lowered boolean?
---@field isTurnedOn boolean?

---@class GameGlass
---@field debugger GrisuDebug
---@field exportEnabled boolean
---@field updateTimer number
---@field settingsXmlFile string
---@field combinedInfo CombinedInfo
---@field pda PDA | nil
GameGlass = {}
GameGlass.STATE_FILE_NAME = "gameGlassInterface.xml"
GameGlass.XML_VERSION = 1
GameGlass.SETTINGS_XML = "gameGlassInterfaceSettings.xml"
GameGlass.SETTINGS_XML_VERSION = 1
GameGlass.VD_AI = {
  REQUIRED_MAJOR_VERSION = 1,
  REQUIRED_MIN_MINOR_VERSION = 0
}

GameGlass.mainFuelTypes = Set:new({ "DIESEL", "ELECTRICCHARGE", "METHANE" })

local GameGlass_mt = Class(GameGlass)

---@return GameGlass
function GameGlass.init()
  ---@type GameGlass
  local self = {}

  setmetatable(self, GameGlass_mt)

  self.debugger = GrisuDebug:create("GameGlass")
  self.debugger:setLogLvl(GrisuDebug.TRACE)

  self.exportEnabled = false
  self.specLogLevel = GrisuDebug.INFO
  self.updateTimer = 0
  self.combinedInfo = {
    fillUnits = {},
    wearable = {},
    frontState = nil,
    backState = nil
  }

  local modSettingsDir = getUserProfileAppPath() .. "modSettings/"
  self.settingsXmlFile = modSettingsDir .. GameGlass.SETTINGS_XML

  if not fileExists(self.settingsXmlFile) then
    self:writeDefaultSettings()
  end
  self:loadSettingsFromFile()

  self.debugger:info("GameGlass initialized")
  return self
end

function GameGlass:loadMap(filename)
  self.debugger:debug("GameGlass loading")
  -- check if FS25_additionalInputs is present in correct version
  -- TODO display warning in ui
  if FS25_additionalInputs == nil or g_vdAdditionalInputs == nil then
    self.debugger:error("FS25_additionalInputs is required but not present")
    self.exportEnabled = false
  else
    if GameGlass.VD_AI.REQUIRED_MAJOR_VERSION ~= g_vdAdditionalInputs.MAJOR_VERSION then
      self.debugger:error(string.format("FS25_additionalInputs with major version %s is required, but was %s", GameGlass.VD_AI.REQUIRED_MAJOR_VERSION, g_vdAdditionalInputs.MAJOR_VERSION))
      self.exportEnabled = false
    elseif GameGlass.VD_AI.REQUIRED_MIN_MINOR_VERSION < g_vdAdditionalInputs.MINOR_VERSION then
      self.debugger:error(string.format("FS25_additionalInputs with minimum minor version %s is required, but was %s", GameGlass.VD_AI.REQUIRED_MIN_MINOR_VERSION, g_vdAdditionalInputs.MINOR_VERSION))
      self.exportEnabled = false
    end
  end

  if g_dedicatedServerInfo == nil then
    local appPath = getUserProfileAppPath()
    self.xmlFileLocation = appPath .. GameGlass.STATE_FILE_NAME
  end

  self.pda = MapUtil.getMapPDAFile()

  self.debugger:info("GameGlass loaded")
end

function GameGlass:writeDefaultSettings()
  self.debugger:trace("writeDefaultSettings")
  local xml = XMLFile.create("GGS", self.settingsXmlFile, "GGS")

  xml:setInt("GGS#version", 1)
  xml:setBool("GGS.exportEnabled", g_dedicatedServerInfo == nil)
  xml:setString("GGS.logging.level", "INFO")
  xml:setString("GGS.logging.specLevel", "INFO")

  xml:save()
  xml:delete()
end

function GameGlass:loadSettingsFromFile()
  self.debugger:trace("loadSettingsFromFile")
  local xml = XMLFile.load("GGS", self.settingsXmlFile)

  local version = xml:getInt("GGS#version", 0)
  if version ~= GameGlass.SETTINGS_XML_VERSION then
    --TODO proper handling?
    self.debugger:error("Unknown settings xml version, setting defaults values")
    self:writeDefaultSettings()
  end

  self.exportEnabled = xml:getBool("GGS.exportEnabled", true)
  local logLevel = xml:getString("GGS.logging.level", "INFO")
  local specLogLevel = xml:getString("GGS.logging.specLevel", "INFO")

  local parseLogLevel = GrisuDebug.parseLogLevel(logLevel)
  self.debugger:setLogLvl(parseLogLevel)
  self.specLogLevel = GrisuDebug.parseLogLevel(specLogLevel)

  xml:delete()
end

function GameGlass:update(dt)
  if self.exportEnabled == false then
    return
  end

  self.updateTimer = self.updateTimer + dt
  -- only update every 500ms
  if self.updateTimer < 500 then
    return
  end

  -- Execute the desired statement
  self:writeXMLFile()
  self.debugger:trace("Wrote xml file")

  -- Reset the timer after execution
  self.updateTimer = 0


end

function GameGlass:writeXMLFile()
  self.debugger:trace("Write xml file")
  -- reset combined info
  self.combinedInfo = {
    fillUnits = {},
    wearable = {},
    frontState = nil,
    backState = nil
  }

  local xml = XMLFile.create("GameGlass", self.xmlFileLocation, "GGI")
  xml:setInt("GGI#version", GameGlass.XML_VERSION)
  xml:setString("GGI#xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
  xml:setString("GGI#xsi:noNamespaceSchemaLocation", "https://raw.githubusercontent.com/VertexDezign/GameGlassInterface/refs/heads/main/gameGlassInterfaceSchema.xsd")
  self:populateXMLFromEnvironment(xml)
  self:populateXMLFromVehicle(xml)
  xml:save()
  xml:delete()
end

---@param xml XMLFile
function GameGlass:populateXMLFromEnvironment(xml)
  local environment = g_currentMission.environment

  -- set initial year to 2024, when the game was released
  xml:setString("GGI.environment.date", string.format("%02d.%02d.%04d", environment.currentDayInPeriod, ValueMapper.mapPeriodToMonth(environment.currentPeriod), 2023 + environment.currentYear))
  -- export current time (fixed 24h format)
  xml:setString("GGI.environment.time", string.format("%02d:%02d", environment.currentHour, environment.currentMinute))

  -- weather
  local weather = environment.weather
  local minTemperatureInC, maxTemperatureInC = weather:getCurrentMinMaxTemperatures()
  local currentTemperatureInC = weather.forecast:getCurrentWeather()

  local minTemperatureExpanded = MathUtil.round(g_i18n:getTemperature(minTemperatureInC), 0)
  local maxTemperatureExpanded = MathUtil.round(g_i18n:getTemperature(maxTemperatureInC), 0)
  local currentTemperatureExpanded = MathUtil.round(g_i18n:getTemperature(currentTemperatureInC.temperature), 0)

  xml:setInt("GGI.environment.weather.temperature#min", minTemperatureExpanded)
  xml:setInt("GGI.environment.weather.temperature#max", maxTemperatureExpanded)
  xml:setInt("GGI.environment.weather.temperature#current", currentTemperatureExpanded)
  xml:setString("GGI.environment.weather.temperature#unit", "°C")

  -- player position
  local ingameMap = g_currentMission.hud.ingameMap
  if self.pda ~= nil then
    xml:setString("GGI.environment.pda#filename", self.pda.filename)
    xml:setInt("GGI.environment.pda#width", self.pda.width)
    xml:setInt("GGI.environment.pda#height", self.pda.height)
  end
  xml:setFloat("GGI.environment.pda.player#posX", ingameMap.normalizedPlayerPosX)
  xml:setFloat("GGI.environment.pda.player#posZ", ingameMap.normalizedPlayerPosZ)
end

---@param xml XMLFile
function GameGlass:populateXMLFromVehicle(xml)
  local vehicle = self.currentVehicle
  if vehicle == nil then
    -- no vehicle -> nothing to do
    return
  end

  xml:setString("GGI.vehicle.speed", ValueMapper.mapFloat(vehicle:getLastSpeed()))
  xml:setString("GGI.vehicle#name", vehicle:getFullName())
  xml:setString("GGI.vehicle#type", vehicle.typeName)
  local brand = ValueMapper.resolveBrand(vehicle)
  xml:setString("GGI.vehicle.brand#name", brand.name)
  xml:setString("GGI.vehicle.brand#title", brand.title)
  if vehicle.getDrivingDirection ~= nil then
    xml:setString("GGI.vehicle.speed#unit", "km/h")
    xml:setString("GGI.vehicle.speed#direction", ValueMapper.mapDirection(vehicle:getDrivingDirection()))
  end
  if vehicle.operatingTime ~= nil then
    xml:setString("GGI.vehicle.operatingTime", ValueMapper.formatOperatingTime(vehicle.operatingTime))
    xml:setString("GGI.vehicle.operatingTime#unit", "h")
  end
  self:populateXMLFromMotorized(xml)
  self:populateXMLFromLights(xml)
  self:populateXMLWithSupportSystems(xml)
  self:populateXMLFromTurnOnVehicle(xml, "GGI.vehicle", self.currentVehicle)
  self:populateXMLFromFoldable(xml, "GGI.vehicle", self.currentVehicle)
  self:populateXMLFromLowered(xml, "GGI.vehicle", self.currentVehicle)
  self:populateXMLFromFillUnit(xml, "GGI.vehicle", self.currentVehicle)
  self:populateXMLFromPipe(xml, "GGI.vehicle", self.currentVehicle)
  self:populateXMLFromCover(xml, "GGI.vehicle", self.currentVehicle)
  self:populateXMLFromWearAndWashable(xml, "GGI.vehicle", self.currentVehicle)
  self:populateXMLFromAttacherJoints(xml, "GGI.vehicle", self.currentVehicle)

  self:populateXMLFromCombinedInfo(xml)
end

---@param xml XMLFile
function GameGlass:populateXMLFromMotorized(xml)
  local mSpec = self.currentVehicle.spec_motorized
  if mSpec == nil then
    return
  end
  -- ignition state
  xml:setString("GGI.vehicle.motor#state", ValueMapper.mapMotorState(mSpec:getMotorState()))

  -- motor temp
  xml:setInt("GGI.vehicle.motor.temperatur", mSpec.motorTemperature.value)
  xml:setInt("GGI.vehicle.motor.temperatur#min", mSpec.motorTemperature.valueMin)
  xml:setInt("GGI.vehicle.motor.temperatur#max", mSpec.motorTemperature.valueMax)
  xml:setString("GGI.vehicle.motor.temperatur#unit", "°C")

  -- rpm
  local motor = mSpec:getMotor()
  xml:setInt("GGI.vehicle.motor.rpm", motor:getLastMotorRpm())
  xml:setInt("GGI.vehicle.motor.rpm#min", 0)
  xml:setInt("GGI.vehicle.motor.rpm#max", motor:getMaxRpm())
  -- motor load
  xml:setString("GGI.vehicle.motor.load", ValueMapper.mapMotorLoad(motor:getSmoothLoadPercentage()))
  xml:setInt("GGI.vehicle.motor.load#min", 0)
  xml:setInt("GGI.vehicle.motor.load#max", 100)
  xml:setString("GGI.vehicle.motor.load#unit", "%")
  -- gear
  xml:setBool("GGI.vehicle.motor.gear#isNeutral", motor:getIsInNeutral())
  xml:setString("GGI.vehicle.motor.gear#group", motor:getGearGroupToDisplay())
  xml:setString("GGI.vehicle.motor.gear", motor:getGearToDisplay())

  -- max speed
  xml:setInt("GGI.vehicle.motor.maxSpeed#forward", ValueMapper.convertFromMsToKMH(motor:getMaximumForwardSpeed()))
  xml:setInt("GGI.vehicle.motor.maxSpeed#backward", ValueMapper.convertFromMsToKMH(motor:getMaximumBackwardSpeed()))

  for fillTypeIndex, v in pairs(mSpec.consumersByFillType) do
    local fillType = g_fillTypeManager:getFillTypeByIndex(fillTypeIndex)
    if GameGlass.mainFuelTypes:contains(fillType.name) then
      self:writeFuelFillUnitToXML(xml, "GGI.vehicle.motor.fillUnits", fillType, v.fillUnitIndex, mSpec.lastFuelUsage)
    else
      local usage = nil
      if fillType.name == FillType.DEF then
        usage = mSpec.lastDefUsage
      elseif fillType.name == FillType.AIR then
        usage = mSpec.lastAirUsage
      end
      self:writeSecondaryMotorFillUnitToXML(xml, "GGI.vehicle.motor.fillUnits", fillType, v.fillUnitIndex, usage)
    end
  end

  --if enhanced vehicle present, write parking brake, diff and 4wd drive
  local vData = self.currentVehicle.vData
  -- we access internal state here, so defensive coding to reduce fatal errors
  if vData ~= nil then
    if vData.is[1] ~= nil and type(vData.is[1]) == "boolean" then
      xml:setBool("GGI.vehicle.motor.diffLock#front", vData.is[1])
    end
    if vData.is[2] ~= nil and type(vData.is[2]) == "boolean" then
      xml:setBool("GGI.vehicle.motor.diffLock#back", vData.is[2])
    end
    if vData.is[3] ~= nil then
      xml:setBool("GGI.vehicle.motor.awd", vData.is[3] == 1)
    end
    if vData.is[13] ~= nil and type(vData.is[13]) == "boolean" then
      xml:setBool("GGI.vehicle.motor.parkingBrake", vData.is[13])
    end
  end
end

---@param xml XMLFile
---@param path string
---@param fillType table The fill type table
---@param fillUnitIndex number The index of the fillUnit
---@param usage number The current usage of the fillUnit
function GameGlass:writeFuelFillUnitToXML(xml, path, fillType, fillUnitIndex, usage)
  local capacity = self.currentVehicle:getFillUnitCapacity(fillUnitIndex)
  local fillLevel = self.currentVehicle:getFillUnitFillLevel(fillUnitIndex)
  local fillLevelPercentage = self.currentVehicle:getFillUnitFillLevelPercentage(fillUnitIndex)
  local unit = fillType.unitShort
  local name = fillType.name
  local title = fillType.title

  local type = string.lower(name)
  xml:setInt(string.format("%s.fuel", path), fillLevel)
  xml:setString(string.format("%s.fuel#type", path), type)
  xml:setString(string.format("%s.fuel#title", path), title)
  xml:setString(string.format("%s.fuel#unit", path), unit)
  xml:setInt(string.format("%s.fuel#capacity", path), capacity)
  xml:setString(string.format("%s.fuel#fillLevelPercentage", path), ValueMapper.mapPercentage(fillLevelPercentage, 0))
  xml:setString(string.format("%s.fuel#usage", path), ValueMapper.mapFloat(usage))
end

---@param xml XMLFile
---@param path string
---@param fillType table The fill type table
---@param fillUnitIndex number The index of the fillUnit
---@param usage number The current usage of the fillUnit
function GameGlass:writeSecondaryMotorFillUnitToXML(xml, path, fillType, fillUnitIndex, usage)
  local capacity = self.currentVehicle:getFillUnitCapacity(fillUnitIndex)
  local fillLevel = self.currentVehicle:getFillUnitFillLevel(fillUnitIndex)
  local fillLevelPercentage = self.currentVehicle:getFillUnitFillLevelPercentage(fillUnitIndex)
  local unit = fillType.unitShort
  local name = fillType.name
  local title = fillType.title

  local pathType = string.lower(name)
  xml:setInt(string.format("%s.%s", path, pathType), fillLevel)
  xml:setString(string.format("%s.%s#title", path, pathType), title)
  xml:setString(string.format("%s.%s#unit", path, pathType), unit)
  xml:setInt(string.format("%s.%s#capacity", path, pathType), capacity)
  xml:setString(string.format("%s.%s#fillLevelPercentage", path, pathType), ValueMapper.mapPercentage(fillLevelPercentage, 0))
  if usage ~= nil then
    xml:setString(string.format("%s.%s#usage", path, pathType), ValueMapper.mapFloat(usage))
  end
end

---@param xml XMLFile
function GameGlass:populateXMLFromLights(xml)
  local spec = self.currentVehicle.spec_lights
  if spec == nil then
    return
  end

  -- indicators
  xml:setBool("GGI.vehicle.lights.indicator#left", spec.turnLightState == Lights.TURNLIGHT_LEFT or spec.turnLightState == Lights.TURNLIGHT_HAZARD)
  xml:setBool("GGI.vehicle.lights.indicator#right", spec.turnLightState == Lights.TURNLIGHT_RIGHT or spec.turnLightState == Lights.TURNLIGHT_HAZARD)
  xml:setBool("GGI.vehicle.lights.indicator#hazard", spec.turnLightState == Lights.TURNLIGHT_HAZARD)

  -- beacon beacon light
  xml:setBool("GGI.vehicle.lights.beaconLight", next(spec.beaconLights) ~= nil and spec.beaconLightsActive)

  -- normal lights
  xml:setBool("GGI.vehicle.lights.light#lowBeam", bitAND(spec.lightsTypesMask, 2 ^ Lights.LIGHT_TYPE_DEFAULT) ~= 0)
  xml:setBool("GGI.vehicle.lights.light#highBeam", bitAND(spec.lightsTypesMask, 2 ^ Lights.LIGHT_TYPE_HIGHBEAM) ~= 0)

  --work lights
  xml:setBool("GGI.vehicle.lights.workLight#front", bitAND(spec.lightsTypesMask, 2 ^ Lights.LIGHT_TYPE_WORK_FRONT) ~= 0)
  xml:setBool("GGI.vehicle.lights.workLight#back", bitAND(spec.lightsTypesMask, 2 ^ Lights.LIGHT_TYPE_WORK_BACK) ~= 0)
end

---@param xml XMLFile
function GameGlass:populateXMLWithSupportSystems(xml)
  local vehicle = self.currentVehicle
  local dSpec = vehicle.spec_drivable
  local aiDSpec = vehicle.spec_aiDrivable
  local aiMSpec = vehicle.spec_aiModeSelection
  local aiSSpec = vehicle.spec_aiAutomaticSteering

  --gps
  if aiMSpec ~= nil and aiSSpec ~= nil then
    xml:setBool("GGI.vehicle.gps#enabled", vehicle:getAIModeSelection() == AIModeSelection.MODE.STEERING_ASSIST)
    xml:setBool("GGI.vehicle.gps#active", aiSSpec.steeringEnabled)
    xml:setInt("GGI.vehicle.gps#heading", ValueMapper.calculateHeading(vehicle))
    xml:setString("GGI.vehicle.gps#headingUnit", "°")
  end
  --ai
  if self.currentVehicle.getIsFieldWorkActive ~= nil then
    xml:setBool("GGI.vehicle.ai#active", self.currentVehicle:getIsFieldWorkActive() or (aiDSpec ~= nil and aiDSpec.isRunning))
  end

  -- cruise control
  if dSpec ~= nil then
    local cruiseControl = dSpec.cruiseControl
    xml:setInt("GGI.vehicle.cruiseControl#targetSpeed", cruiseControl.speed)
    xml:setBool("GGI.vehicle.cruiseControl#active", cruiseControl.state ~= Drivable.CRUISECONTROL_STATE_OFF)
  end
end

---@param xml XMLFile
---@param object table
function GameGlass:populateXMLFromAttacherJoints(xml, path, rootObject)
  local ajSpec = rootObject.spec_attacherJoints

  -- check if the current vehicle has attacher joins
  if ajSpec == nil then
    return
  end

  for index, attachedImplement in pairs(ajSpec.attachedImplements) do
    ---@type AttacherJointPosition
    local position
    if rootObject.vdAIGetAttacherJointPosition ~= nil then
      position = rootObject:vdAIGetAttacherJointPosition(attachedImplement)
    else
      position = ""
    end

    local combinedState
    if position == "FRONT" and self.combinedInfo.frontState == nil then
      self.combinedInfo.frontState = {}
      combinedState = self.combinedInfo.frontState
    elseif position == "BACK" and self.combinedInfo.backState == nil then
      self.combinedInfo.backState = {}
      combinedState = self.combinedInfo.backState
    end

    -- lua table index starts with 1, but xml index must start with 0
    local xmlBasePath = string.format("%s.implement(%d)", path, index - 1)
    xml:setString(string.format("%s#position", xmlBasePath), position)

    ---@type Vehicle
    local object = attachedImplement.object
    if object ~= nil then
      xml:setString(string.format("%s#name", xmlBasePath), object:getFullName())
      xml:setString(string.format("%s#type", xmlBasePath), object.typeName)
      local brand = ValueMapper.resolveBrand(object)
      xml:setString(string.format("%s.brand#name", xmlBasePath), brand.name)
      xml:setString(string.format("%s.brand#title", xmlBasePath), brand.title)
    end

    self:populateXMLFromTurnOnVehicle(xml, xmlBasePath, object, combinedState)
    self:populateXMLFromFoldable(xml, xmlBasePath, object, combinedState)
    self:populateXMLFromLowered(xml, xmlBasePath, object, combinedState)
    self:populateXMLFromFillUnit(xml, xmlBasePath, object)
    self:populateXMLFromPipe(xml, xmlBasePath, object)
    self:populateXMLFromCover(xml, xmlBasePath, object)
    self:populateXMLFromWearAndWashable(xml, xmlBasePath, object)
    self:populateXMLFromAttacherJoints(xml, xmlBasePath, object)
  end
end

---@param xml XMLFile
---@param path string
---@param object table
---@param combinedState ImplementState|nil
function GameGlass:populateXMLFromTurnOnVehicle(xml, path, object, combinedState)
  local spec = object.spec_turnOnVehicle
  if spec == nil then
    return
  end

  local isOn = object:getIsTurnedOn()
  xml:setBool(string.format("%s.isTurnedOn", path), isOn)
  if combinedState ~= nil then
    combinedState.isTurnedOn = isOn
  end
end

---@param xml XMLFile
---@param path string
---@param object table
---@param combinedState ImplementState|nil
function GameGlass:populateXMLFromFoldable(xml, path, object, combinedState)
  local spec = object.spec_foldable
  if spec == nil or #spec.foldingParts <= 0 then
    return
  end
  local isOnlyLowering = spec.foldMiddleAnimTime ~= nil and spec.foldMiddleAnimTime == 1
  if isOnlyLowering then
    return
  end

  local direction = object:getToggledFoldDirection()
  local text = nil

  if direction == spec.turnOnFoldDirection then
    text = "FOLDED"
  else
    text = "EXTENDED"
  end
  xml:setString(string.format("%s.foldable", path), text)
  if combinedState ~= nil then
    combinedState.foldable = text
  end
end

---@param xml XMLFile
---@param path string
---@param object table
---@param combinedState ImplementState?
function GameGlass:populateXMLFromLowered(xml, path, object, combinedState)
  if object.getIsLowered == nil or object:getIsLowered() == nil then
    return
  end

  local state = object:getIsLowered()

  xml:setBool(string.format("%s.lowered", path), state)
  if combinedState ~= nil then
    combinedState.lowered = state
  end
end

---@param xml XMLFile
---@param path string
---@param fillUnit CombinedFillUnit
function GameGlass:writeFillUnit(xml, path, fillUnit)
  xml:setInt(string.format("%s", path), fillUnit.fillLevel)
  xml:setString(string.format("%s#type", path), fillUnit.type)
  xml:setString(string.format("%s#title", path), fillUnit.title)
  xml:setString(string.format("%s#unit", path), fillUnit.unit)
  xml:setInt(string.format("%s#capacity", path), fillUnit.capacity)
  local fillPercentage = 0
  -- some mods have a capacity of zero, we cannot divide by zero
  if fillUnit.capacity > 0 then
    fillPercentage = fillUnit.fillLevel / fillUnit.capacity
  end
  xml:setString(string.format("%s#fillLevelPercentage", path), ValueMapper.mapPercentage(fillPercentage, 0))
end

---@param xml XMLFile
---@param path string
---@param object table
function GameGlass:populateXMLFromFillUnit(xml, path, object)
  local spec = object.spec_fillUnit
  if spec == nil or #spec.fillUnits <= 0 then
    return
  end
  local mSpec = object.spec_motorized
  ---@type Set
  local propellantFillUnitIndices
  if mSpec ~= nil then
    propellantFillUnitIndices = Set:new(mSpec.propellantFillUnitIndices)
  else
    propellantFillUnitIndices = Set:new()
  end

  local index = 0
  for fillUnitIndex, fillUnit in ipairs(spec.fillUnits) do
    local fillTypeIndex = fillUnit.fillType
    local fillType = g_fillTypeManager:getFillTypeByIndex(fillTypeIndex)
    if not (propellantFillUnitIndices:contains(fillUnitIndex) or fillType.name == "AIR") then
      basePath = string.format("%s.fillUnits.fillUnit(%d)", path, index)
      local capacity = fillUnit.capacity
      local fillLevel = fillUnit.fillLevel
      local unit = fillType.unitShort
      local name = fillType.name
      local title = fillType.title
      if fillTypeIndex == 1 then
        unit = ""
        name = ""
        title = ""
      end

      -- write to xml
      self:writeFillUnit(xml, basePath, { type = name, title = title, unit = unit, capacity = capacity, fillLevel = fillLevel })

      -- append to combined info
      local existing = self.combinedInfo.fillUnits[name]
      if existing == nil then
        existing = {
          type = name,
          title = title,
          unit = unit,
          capacity = 0,
          fillLevel = 0
        }
      end
      existing.capacity = existing.capacity + capacity
      existing.fillLevel = existing.fillLevel + fillLevel
      self.combinedInfo.fillUnits[name] = existing

      index = index + 1
    end
  end
end

---@param xml XMLFile
---@param path string
---@param object table
function GameGlass:populateXMLFromPipe(xml, path, object)
  if object.getCurrentPipeState == nil or object:getCurrentPipeState() == nil then
    return
  end

  local state = object:getCurrentPipeState()

  xml:setString(string.format("%s.pipe", path), ValueMapper.mapPipeState(state))
end

---@param xml XMLFile
---@param path string
---@param object table
function GameGlass:populateXMLFromCover(xml, path, object)
  local spec = object.spec_cover
  if spec == nil or not spec.hasCovers then
    return
  end

  xml:setString(string.format("%s.cover", path), ValueMapper.mapCoverState(spec.state))
end

---@param xml XMLFile
---@param path string
---@param object table
function GameGlass:populateXMLFromWearAndWashable(xml, path, object)
  local wearable = object.spec_wearable
  local washable = object.spec_washable
  if wearable ~= nil then
    xml:setString(string.format("%s.wearable#damage", path), ValueMapper.mapPercentage(object:getDamageAmount(), 0))
    xml:setString(string.format("%s.wearable#wear", path), ValueMapper.mapPercentage(object:getWearTotalAmount(), 0))
  end
  if washable ~= nil then
    xml:setString(string.format("%s.wearable#dirt", path), ValueMapper.mapPercentage(object:getDirtAmount(), 0))
  end
  if washable ~= nil or wearable ~= nil then
    xml:setString(string.format("%s.wearable#unit", path), "%")

    table.insert(self.combinedInfo.wearable, {
      damage = object:getDamageAmount(),
      wear = object:getWearTotalAmount(),
      dirt = object:getDirtAmount()
    })
  end
end

---@param xml XMLFile
function GameGlass:populateXMLFromCombinedInfo(xml)
  local path = "GGI.vehicle.combined"

  -- fillUnits
  local index = 0
  for _, fillUnit in pairs(self.combinedInfo.fillUnits) do
    self:writeFillUnit(xml, string.format("%s.fillUnits.fillUnit(%d)", path, index), fillUnit)

    index = index + 1
  end

  --wearable
  local numEntries = #self.combinedInfo.wearable
  if numEntries > 0 then
    local damageSum = 0
    local wearSum = 0
    local dirtSum = 0
    for _, wearable in pairs(self.combinedInfo.wearable) do
      damageSum = damageSum + wearable.damage
      wearSum = wearSum + wearable.wear
      dirtSum = dirtSum + wearable.dirt
    end

    xml:setString(string.format("%s.wearable#damage", path), ValueMapper.mapPercentage(damageSum / numEntries, 0))
    xml:setString(string.format("%s.wearable#wear", path), ValueMapper.mapPercentage(wearSum / numEntries, 0))
    xml:setString(string.format("%s.wearable#dirt", path), ValueMapper.mapPercentage(dirtSum / numEntries, 0))
    xml:setString(string.format("%s.wearable#unit", path), "%")
  end

  --implements
  if self.combinedInfo.frontState ~= nil then
    self:populateXMLFromCombinedImplementState(xml, string.format("%s.implement.front", path), self.combinedInfo.frontState)
  end
  if self.combinedInfo.backState ~= nil then
    self:populateXMLFromCombinedImplementState(xml, string.format("%s.implement.back", path), self.combinedInfo.backState)
  end
end

---@param xml XMLFile
---@param basePath string
---@param implementState ImplementState
function GameGlass:populateXMLFromCombinedImplementState(xml, basePath, implementState)
  xml:setBool(string.format("%s.isTurnedOn", basePath), implementState.isTurnedOn or false)
  if implementState.foldable ~= nil then
    xml:setString(string.format("%s.foldable", basePath), implementState.foldable)
  end
  xml:setBool(string.format("%s.lowered", basePath), implementState.lowered or false)
end

---@param vehicle GameGlassSpec
function GameGlass:setCurrentVehicle(vehicle)
  self.currentVehicle = vehicle
end

function GameGlass:clearCurrentVehicle()
  self.currentVehicle = nil
end

local function init()
  g_gameGlass = GameGlass.init()

  -- make gameGlass globally available
  getmetatable(_G).__index.g_gameGlass = g_gameGlass

  -- add event listener
  addModEventListener(g_gameGlass)
end

init()