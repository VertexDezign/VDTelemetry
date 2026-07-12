---@class ValueMapper
ValueMapper = {}

---@param direction number The direction
---@return string The enumified value
function ValueMapper.mapDirection(direction)
  if direction == 1 then
    return "FORWARD"
  elseif direction == -1 then
    return "BACKWARD"
  else
    return "STOPPED"
  end
end

---@param state number The ignition state
---@return string The enumified value
function ValueMapper.mapMotorState(state)
  if state == 1 then
    return "OFF"
  elseif state == 2 then
    return "STARTING"
  elseif state == 3 or state == 4 then
    return "ON"
  else
    return string.format("<unknown(%d))>", state)
  end
end

---@param load number 0..1
---@return string The load in percent as float
function ValueMapper.mapMotorLoad(load)
  if load < 0 then
    return "0"
  else
    return ValueMapper.mapPercentage(load, 2)
  end
end

--- Converts from m/s to km/h
---@param speedInMs number The speed in m/s
---@return number the speed in km/h
function ValueMapper.convertFromMsToKMH(speedInMs)
  if speedInMs == nil then
    return nil
  end
  return speedInMs * 3.6
end

---@param value number 0..1
---@param decimals number How much decimals it should return, defaults to 2
---@return string The formated value as percentage
function ValueMapper.mapPercentage(value, decimals)
  if not decimals or decimals < 0 then
    decimals = 2
  end
  local percentage = value * 100
  return string.format("%." .. tostring(decimals) .. "f", percentage)
end

---@param value number a float value
---@param decimals number How much decimals it should return, defaults to 2
---@return string The formated value as string
function ValueMapper.mapFloat(value, decimals)
  if not decimals or decimals < 0 then
    decimals = 2
  end
  local rounded = MathUtil.round(value, decimals)
  return string.format("%." .. tostring(decimals) .. "f", rounded)
end

---@param operatingTime number operation time in milliseconds
function ValueMapper.formatOperatingTime(operatingTime)
  -- Convert milliseconds to minutes
  local totalMinutes = operatingTime / 60000

  -- Calculate full hours
  local hours = math.floor(totalMinutes / 60)

  -- Calculate remaining minutes as a decimal rounded to two decimals
  local remainingMinutes = (totalMinutes - (hours * 60)) / 60
  local remainingMinutesString = string.format("%.2f", remainingMinutes)

  -- Combine hours and remaining minutes as a string
  local formattedTime = string.format("%d.%s", hours, string.sub(remainingMinutesString, 3))

  return formattedTime
end

---@param currentPeriod number
---@return number
function ValueMapper.mapPeriodToMonth(currentPeriod)
  local adapted = (currentPeriod + 2) % 12

  if adapted == 0 then
    return 12
  else
    return adapted
  end
end

--- Compass heading (degrees) from an engine y-rotation, i.e. the yaw of a facing direction as
--- produced by MathUtil.getYRotationFromDirection / consumed by MathUtil.getDirectionFromYRotation.
--- Both the vehicle heading and the on-foot player heading go through here, so the map marker means
--- the same thing in either mode. `%` wraps yRot into [0, 2pi) for any input (Lua's modulo follows
--- the divisor's sign), so callers may hand in an angle they shifted by half a turn. The outer `%
--- 360` maps the resulting 360 back to 0: the subtraction lands in (0, 360], so dead-on north would
--- otherwise export as "360°" rather than "0°" (identical to a compass, junk to a consumer reading
--- the number).
---@param yRot number yaw in radians
---@return number heading in degrees, [0, 360)
function ValueMapper.headingFromYRotation(yRot)
  return (360 - math.deg(yRot % (2 * math.pi))) % 360
end

--- The player's yaw sits exactly half a turn from the yaw a vehicle heading is built from (that one
--- comes from the vehicle's local -z forward axis, see calculateHeading below). Feeding it in raw
--- leaves the on-foot marker pointing backwards -- verified in game. The engine's own map readout
--- applies the same pi offset to this value (IngameMap: math.abs(playerRotation - math.pi)), so
--- shift here rather than teaching the compass conversion about two conventions.
---@param yaw number player yaw in radians (ingameMap.playerRotation / g_localPlayer:getYaw())
---@return number
function ValueMapper.calculatePlayerHeading(yaw)
  return ValueMapper.headingFromYRotation(yaw + math.pi)
end

---@param vehicle Vehicle
---@return number
function ValueMapper.calculateHeading(vehicle)
  local dx, _, dz = localDirectionToWorld(vehicle.rootNode, 0, 0, -1)

  return ValueMapper.headingFromYRotation(MathUtil.getYRotationFromDirection(dx, dz))
end

---@param state number
---@return string
function ValueMapper.mapPipeState(state)
  if state == 1 then
    return "RETRACTED"
  elseif state == 0 then
    return "MOVING"
  else
    return "EXTENDED"
  end
end

---@param state number
---@return string
function ValueMapper.mapCoverState(state)
  if state == 1 then
    return "OPEN"
  elseif state == 0 then
    return "CLOSED"
  else
    return "UNKNOWN"
  end
end

---@class Brand
---@field name string
---@field title string

---@param obj Vehicle
---@return Brand BrandTable
function ValueMapper.resolveBrand(obj)
  if obj == nil or obj.getBrand == nil then
    return nil
  end
  local brandIndex = obj:getBrand()
  local brand = g_brandManager:getBrandByIndex(brandIndex)
  return brand
end
