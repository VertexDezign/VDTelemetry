-- Aspect collector: wear & wash state. Applies to any object (vehicle or implement).
-- Mirrors VDTelemetry:populateXMLFromWearAndWashable (the combinedInfo feed is out of scope).
-- Namespaced under VDT.* (see TurnOn.lua).
--
-- damage/wear come from the wearable spec, dirt from the washable spec; an object may have either or
-- both. Missing components are simply absent (Model.kt defaults them to 0). Percentages are integers.

VDT = VDT or {}
VDT.Wearable = {}

---@param object table
---@return WearableModel|nil nil when the object has neither wearable nor washable spec
function VDT.Wearable.collect(object)
  local wearable = object.spec_wearable
  local washable = object.spec_washable
  if wearable == nil and washable == nil then
    return nil
  end

  ---@type WearableModel
  local model = { unit = "%" }
  if wearable ~= nil then
    model.damage = tonumber(ValueMapper.mapPercentage(object:getDamageAmount(), 0))
    model.wear = tonumber(ValueMapper.mapPercentage(object:getWearTotalAmount(), 0))
  end
  if washable ~= nil then
    model.dirt = tonumber(ValueMapper.mapPercentage(object:getDirtAmount(), 0))
  end
  return model
end
