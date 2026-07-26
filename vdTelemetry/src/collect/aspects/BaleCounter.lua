-- Aspect collector: bale counter. Applies to any object (vehicle or implement).
-- Namespaced under VDT.* (see TurnOn.lua).
--
-- `session` is resettable from the vehicle's own action; `lifetime` is not. Both are plain counters
-- the engine keeps on the baler.

VDT = VDT or {}
VDT.BaleCounter = {}

---@param object table
---@return BaleCounterModel|nil nil when the object counts no bales
function VDT.BaleCounter.collect(object)
  local spec = object.spec_baleCounter
  if spec == nil then
    return nil
  end

  return {
    session = spec.sessionCounter,
    lifetime = spec.lifetimeCounter,
  }
end
