-- Optional integration: Variable Tire Pressure (FS25_VariableTirePressure, by HotShotPepper). Namespaced
-- under VDT.* (see aspects/TurnOn.lua). Issue #193.
--
-- **Written against Variable Tire Pressure 1.0.0.13.** The mod is a central tyre inflation system: each
-- machine runs at a road pressure or a lower field pressure, and the compressor moves it between the two
-- over a few seconds. A tractor's axles run apart once the player gives front and rear different
-- setpoints in the mod's window, and can then even sit in different modes. This exports, per machine
-- that has it, what its own dashboard values show: the pressure now and where it is heading, the mode,
-- and the two setpoints the pressure moves between -- plus each axle while they run apart. The write side is src/command/TirePressureControl.lua.
--
-- Every machine with wheels CARRIES the mod's spec (it injects itself into every such vehicle type),
-- but it only works on one whose wheel configuration was bought with it -- `isConfigEnabled`. That
-- flag, not the spec's presence, is the "has it" check, so an ordinary trailer exports nothing.
--
-- Read through the getters the mod registers on the vehicle type for Dashboard Live
-- (vtpGetDashboard*), not the spec's fields: they hold the interpolation from deformation factor to bar
-- that the mod's own HUD uses, so the two cannot disagree. Reached through `spec_variableTirePressure`,
-- a plain alias the mod sets in its onLoad (the real key is mod-name-prefixed).
--
-- MULTIPLAYER: the server runs the transitions and streams the factors and modes to every client
-- (onWriteUpdateStream), so a client's getters report the host's tyres.
--
-- Deliberately NOT collected: the deformation and minimum-radius tuning from the mod's settings
-- window. It is how the tyre looks, not what it is doing.

---@class TirePressureAxleModel
---@field bar number the axle's pressure now, in bar
---@field mode string ROAD | FIELD

---@class TirePressureModel
---@field mode string ROAD | FIELD | MIXED (axles apart, one at each)
---@field bar number the pressure now, in bar; the mean of the axles while they run apart
---@field targetBar number where the compressor is taking it; equal to `bar` once it settles
---@field roadBar number the road setpoint; the mean of the axles' while they run apart
---@field fieldBar number the field setpoint, likewise
---@field front TirePressureAxleModel? only while the axles run apart
---@field rear TirePressureAxleModel?

---@class VehicleModel
---@field tirePressure TirePressureModel?

---@class ImplementModel
---@field tirePressure TirePressureModel?

VDT = VDT or {}
VDT.VariableTirePressure = {}

-- vtpGetDashboardMode's codes: 2 is front at road and rear at field, 3 the reverse.
local MODES = { [0] = "ROAD", [1] = "FIELD", [2] = "MIXED", [3] = "MIXED" }

---The machine's spec when the mod works on it: installed, and the wheel configuration bought with it.
---@param object table a vehicle or implement
---@return table|nil
function VDT.VariableTirePressure.specOf(object)
  local spec = object.spec_variableTirePressure
  if type(spec) ~= "table" or spec.isConfigEnabled ~= true then
    return nil
  end
  return spec
end

---The mode the machine is in, as vtpGetDashboardMode reports it; nil when the getter is missing.
---@param object table
---@return string|nil ROAD | FIELD | MIXED
function VDT.VariableTirePressure.modeOf(object)
  if type(object.vtpGetDashboardMode) ~= "function" then
    return nil
  end
  return MODES[object:vtpGetDashboardMode()]
end

---@param value any
---@return number|nil
local function bar(value)
  if type(value) ~= "number" or value ~= value then
    return nil
  end
  return tonumber(ValueMapper.mapFloat(value, 2))
end

local function axle(object, getBar, getMode)
  if type(object[getBar]) ~= "function" or type(object[getMode]) ~= "function" then
    return nil
  end
  local value = bar(object[getBar](object))
  if value == nil then
    return nil
  end
  return { bar = value, mode = object[getMode](object) == 0 and "ROAD" or "FIELD" }
end

-- Object stage: runs per vehicle/implement during the walk (see registry.lua), so the tractor and
-- every machine hitched behind it decorate themselves alike.
---@param object table a vehicle or implement
---@param model table the object's already core-collected model
function VDT.VariableTirePressure.contributeObject(object, model)
  local spec = VDT.VariableTirePressure.specOf(object)
  if spec == nil then
    return
  end
  local getBar = object.vtpGetDashboardPressureBar
  local getTarget = object.vtpGetDashboardPressureTarget
  local getRoad = object.vtpGetRoadPressure
  local getField = object.vtpGetFieldPressure
  if
    type(getBar) ~= "function"
    or type(getTarget) ~= "function"
    or type(getRoad) ~= "function"
    or type(getField) ~= "function"
  then
    return
  end
  local mode = VDT.VariableTirePressure.modeOf(object)
  local now = bar(getBar(object))
  if mode == nil or now == nil then
    return
  end

  -- The mod's HUD swaps the two when a player sets road below field; so does its own interpolation.
  local roadBar, fieldBar = bar(getRoad(object)), bar(getField(object))
  if roadBar ~= nil and fieldBar ~= nil and roadBar < fieldBar then
    roadBar, fieldBar = fieldBar, roadBar
  end

  ---@type TirePressureModel
  local tirePressure = {
    mode = mode,
    bar = now,
    targetBar = bar(getTarget(object)) or now,
    roadBar = roadBar,
    fieldBar = fieldBar,
  }
  -- The axles only while they run apart (different front and rear setpoints): otherwise the mod moves
  -- both as one and the per-axle getters just mirror the unified value.
  if spec.tractorAxleSplitActive == true then
    tirePressure.front = axle(object, "vtpGetDashboardPressureBarFront", "vtpGetDashboardModeFront")
    tirePressure.rear = axle(object, "vtpGetDashboardPressureBarRear", "vtpGetDashboardModeRear")
  end
  model.tirePressure = tirePressure
end
