-- Aspect collector: foldable state. Applies to any object (vehicle or implement).
-- Mirrors VDTelemetry:populateXMLFromFoldable. Namespaced under VDT.* (see TurnOn.lua).

VDT = VDT or {}
VDT.Foldable = {}

---@param object table
---@return string|nil "FOLDED" / "EXTENDED", or nil when not foldable (or only-lowering)
function VDT.Foldable.collect(object)
  local spec = object.spec_foldable
  if spec == nil or #spec.foldingParts <= 0 then
    return nil
  end
  -- some implements only lower (not fold); don't report those as foldable
  local isOnlyLowering = spec.foldMiddleAnimTime ~= nil and spec.foldMiddleAnimTime == 1
  if isOnlyLowering then
    return nil
  end

  if object:getToggledFoldDirection() == spec.turnOnFoldDirection then
    return "FOLDED"
  else
    return "EXTENDED"
  end
end
