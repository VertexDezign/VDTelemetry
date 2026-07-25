-- Aspect collector: cover state. Applies to any object (vehicle or implement).
-- Namespaced under VDT.* (see TurnOn.lua).
--
-- A vehicle can carry several covers (a trailer with separate tarp sections). spec.state is 0 for
-- "all closed", otherwise the 1-based index of the one that is open — so `index` says *which*, and
-- `count` how many there are.

VDT = VDT or {}
VDT.Cover = {}

---@param object table
---@return CoverModel|nil nil when the object has no covers
function VDT.Cover.collect(object)
  local spec = object.spec_cover
  if spec == nil or not spec.hasCovers then
    return nil
  end
  return {
    state = ValueMapper.mapCoverState(spec.state),
    index = spec.state,
    count = #spec.covers,
  }
end
