-- Aspect collector: lowered state. Applies to any object (vehicle or implement).
-- Namespaced under VDT.* (see TurnOn.lua).

VDT = VDT or {}
VDT.Lowered = {}

---@param object table
---@return boolean|nil lowered state, or nil when the object has no lowered state
function VDT.Lowered.collect(object)
  if object.getIsLowered == nil or object:getIsLowered() == nil then
    return nil
  end
  return object:getIsLowered()
end
