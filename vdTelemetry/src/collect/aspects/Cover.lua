-- Aspect collector: cover state. Applies to any object (vehicle or implement).
-- Namespaced under VDT.* (see TurnOn.lua).

VDT = VDT or {}
VDT.Cover = {}

---@param object table
---@return string|nil mapped cover state, or nil when the object has no covers
function VDT.Cover.collect(object)
  local spec = object.spec_cover
  if spec == nil or not spec.hasCovers then
    return nil
  end
  return ValueMapper.mapCoverState(spec.state)
end
