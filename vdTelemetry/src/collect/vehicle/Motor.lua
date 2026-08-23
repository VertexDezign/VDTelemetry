-- Collects the motor subtree of a vehicle. Pure extraction: reads spec_motorized and returns a
-- plain MotorModel table (or nil when the vehicle has no motor).
-- Namespaced under VDT.* (see aspects/TurnOn.lua).
--
-- ValueMapper returns preformatted strings; JSON needs real numbers, so numeric
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

---Motor fill units: main fuel -> `fuel`, the others keyed by lowercased fill-type name (def / air).
---AIR is included here (unlike the vehicle fill units, which filter it out).
---
---Public rather than folded into [VDT.Motor.collect] because the fleet channel wants a machine's fuel
---without collecting the whole motor subtree: nothing else in a MotorModel means anything for a
---machine nobody is sitting in.
---@param vehicle Vehicle
---@return MotorFillUnitsModel|nil nil when the vehicle has no motor, or its motor consumes nothing
function VDT.Motor.collectFillUnits(vehicle)
  local mSpec = vehicle.spec_motorized
  if mSpec == nil or mSpec.consumersByFillType == nil then
    return nil
  end

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

  if not hasFillUnit then
    return nil
  end
  return fillUnits
end

---@param vehicle Vehicle
---@return MotorModel|nil
function VDT.Motor.collect(vehicle)
  local mSpec = vehicle.spec_motorized
  if mSpec == nil then
    return nil
  end

  local motor = mSpec:getMotor()
  local gearGroup, hasGearGroups = motor:getGearGroupToDisplay()

  local state = ValueMapper.mapMotorState(mSpec:getMotorState())

  -- Whether the crankshaft is actually going round: the engine running, or the starter turning it
  -- over. Exactly the test the engine itself makes before it lets the motor keep a speed
  -- (Motorized:setMotorState zeroes `lastMotorRpm` for every other state), applied here at read time
  -- instead of at the moment the state changed.
  --
  -- Both readings below go stale rather than fall away when the motor stops, because the engine only
  -- updates them while it is running (`Motorized:onUpdate` skips VehicleMotor:update entirely) — and
  -- the one-shot zeroing at the state change does not survive a multiplayer client applying an rpm
  -- update that was already in flight behind the stop event. That leaves an idle's worth of exponential
  -- smoothing behind: the client's own update is `last * 0.95 + incoming * 0.05`, which is where the
  -- stubborn sub-100 rpm on a stopped engine came from (issue #94). A stopped engine turns at zero and
  -- pulls nothing, so say so rather than repeating whatever the engine last happened to hold.
  local isTurning = state == "ON" or state == "STARTING"

  ---@type MotorModel
  local model = {
    state = state,
    temperatur = {
      value = math.floor(mSpec.motorTemperature.value),
      min = math.floor(mSpec.motorTemperature.valueMin),
      max = math.floor(mSpec.motorTemperature.valueMax),
      unit = "°C",
    },
    rpm = {
      value = isTurning and math.floor(motor:getLastMotorRpm()) or 0,
      min = 0,
      max = math.floor(motor:getMaxRpm()),
    },
    load = {
      value = isTurning and tonumber(ValueMapper.mapMotorLoad(motor:getSmoothLoadPercentage())) or 0,
      min = 0,
      max = 100,
      unit = "%",
    },
    gear = {
      value = motor:getGearToDisplay(),
      isNeutral = motor:getIsInNeutral(),
      -- Only a transmission that actually has ranges gets a group. `getGearGroupToDisplay` returns a
      -- name *and* whether groups exist at all, and the name is the placeholder "N" whenever they
      -- don't
      --
      -- The engine drops it on exactly this test (Motorized:getGearInfoToDisplay: `if not
      -- groupsAvailable then gearGroup = nil end`), and its own HUD only draws a group for a
      -- non-automatic transmission. Absent, not empty: nothing downstream should have to tell an
      -- unnamed group from a missing one.
      group = hasGearGroups and gearGroup or nil,
    },
    -- The direction the transmission is *in*, as opposed to `speed.direction`, which is the way the
    -- machine is actually travelling and reads STOPPED below walking pace. This is the motor's own
    -- answer and the one the game prints on a vehicle's dashboard as F / R / N (see Motorized's
    -- `movingDirectionLetter`), so it keeps saying "reverse" while the tractor stands still.
    --
    -- NOT vehicle:getReverserDirection() -- that is only ever written by the ReverseDriving
    -- specialization (a reversible driving position, i.e. the seat swivelled round), so it sits at 1
    -- forever on a machine that has no such console and says nothing about the shuttle.
    --
    -- It can legitimately be STOPPED: with automatic direction change the motor reports neutral below
    -- about 1 km/h, which is what the game shows there too.
    direction = ValueMapper.mapDirection(motor:getDrivingDirection()),
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

  model.fillUnits = VDT.Motor.collectFillUnits(vehicle)

  return model
end
