-- Aspect collector: turn-on state. Applies to any object (vehicle or implement).
--
-- Namespaced under VDT.* so module names never collide with FS25 engine specialization globals
-- (Lights, FillUnit, Foldable, Cover, Pipe, Wearable are all engine globals).

VDT = VDT or {}
VDT.TurnOn = {}

---@param object table
---@return boolean|nil the turned-on state, or nil when the object has no turn-on spec
function VDT.TurnOn.collect(object)
  local spec = object.spec_turnOnVehicle
  if spec == nil then
    return nil
  end
  return object:getIsTurnedOn()
end
