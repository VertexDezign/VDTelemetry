-- Collects the motor subtree of a vehicle. Pure extraction: reads spec_motorized and returns a
-- plain MotorModel table (or nil when the vehicle has no motor). Mirrors the value logic of the
-- original VDTelemetry:populateXMLFromMotorized so emitted values stay identical.
-- Namespaced under VDT.* (see aspects/TurnOn.lua).
--
-- ValueMapper returns preformatted strings (for XML text); JSON needs real numbers, so numeric
-- fields run the mapper through tonumber() to keep the presentation rounding while emitting a number.

VDT = VDT or {}
VDT.Motor = {}

---Fuel fill unit (main fuel type): carries `type` (lowercased fill-type name).
---@return MotorFillUnitModel
local function buildFuelFillUnit(vehicle, fillType, fillUnitIndex, usage)
  local usageValue
  if usage ~= nil then
    usageValue = tonumber(ValueMapper.mapFloat(usage))
  end
  return {
    value = math.floor(vehicle:getFillUnitFillLevel(fillUnitIndex)),
    type = string.lower(fillType.name),
    title = fillType.title,
    unit = fillType.unitShort,
    capacity = math.floor(vehicle:getFillUnitCapacity(fillUnitIndex)),
    fillLevelPercentage = tonumber(ValueMapper.mapPercentage(vehicle:getFillUnitFillLevelPercentage(fillUnitIndex), 0)),
    usage = usageValue,
  }
end

---Secondary motor fill unit (def / air): no `type` field (nil), usage only when known.
---@return MotorFillUnitModel
local function buildSecondaryFillUnit(vehicle, fillType, fillUnitIndex, usage)
  local usageValue
  if usage ~= nil then
    usageValue = tonumber(ValueMapper.mapFloat(usage))
  end
  return {
    value = math.floor(vehicle:getFillUnitFillLevel(fillUnitIndex)),
    title = fillType.title,
    unit = fillType.unitShort,
    capacity = math.floor(vehicle:getFillUnitCapacity(fillUnitIndex)),
    fillLevelPercentage = tonumber(ValueMapper.mapPercentage(vehicle:getFillUnitFillLevelPercentage(fillUnitIndex), 0)),
    usage = usageValue,
  }
end

---@param vehicle Vehicle
---@return MotorModel|nil
function VDT.Motor.collect(vehicle)
  local mSpec = vehicle.spec_motorized
  if mSpec == nil then
    return nil
  end

  local motor = mSpec:getMotor()

  ---@type MotorModel
  local model = {
    state = ValueMapper.mapMotorState(mSpec:getMotorState()),
    temperatur = {
      value = math.floor(mSpec.motorTemperature.value),
      min = math.floor(mSpec.motorTemperature.valueMin),
      max = math.floor(mSpec.motorTemperature.valueMax),
      unit = "°C",
    },
    rpm = {
      value = math.floor(motor:getLastMotorRpm()),
      min = 0,
      max = math.floor(motor:getMaxRpm()),
    },
    load = {
      value = tonumber(ValueMapper.mapMotorLoad(motor:getSmoothLoadPercentage())),
      min = 0,
      max = 100,
      unit = "%",
    },
    gear = {
      value = motor:getGearToDisplay(),
      isNeutral = motor:getIsInNeutral(),
      group = motor:getGearGroupToDisplay(),
    },
  }

  -- max speed, converted m/s -> km/h
  local forward = motor:getMaximumForwardSpeed()
  local backward = motor:getMaximumBackwardSpeed()
  if forward ~= nil or backward ~= nil then
    ---@type MaxSpeedModel
    local maxSpeed = {}
    if forward ~= nil then
      maxSpeed.forward = math.floor(ValueMapper.convertFromMsToKMH(forward))
    end
    if backward ~= nil then
      maxSpeed.backward = math.floor(ValueMapper.convertFromMsToKMH(backward))
    end
    model.maxSpeed = maxSpeed
  end

  -- motor fill units: main fuel -> `fuel`, others keyed by lowercased fill-type name (def / air).
  -- AIR is included here (unlike the vehicle fill units, which filter it out).
  local fillUnits = {}
  local hasFillUnit = false
  for fillTypeIndex, consumer in pairs(mSpec.consumersByFillType) do
    local fillType = g_fillTypeManager:getFillTypeByIndex(fillTypeIndex)
    if VDTelemetry.mainFuelTypes:contains(fillType.name) then
      fillUnits.fuel = buildFuelFillUnit(vehicle, fillType, consumer.fillUnitIndex, mSpec.lastFuelUsage)
    else
      local usage
      if fillType.name == FillType.DEF then
        usage = mSpec.lastDefUsage
      elseif fillType.name == FillType.AIR then
        usage = mSpec.lastAirUsage
      end
      fillUnits[string.lower(fillType.name)] = buildSecondaryFillUnit(vehicle, fillType, consumer.fillUnitIndex, usage)
    end
    hasFillUnit = true
  end
  if hasFillUnit then
    model.fillUnits = fillUnits
  end

  return model
end
