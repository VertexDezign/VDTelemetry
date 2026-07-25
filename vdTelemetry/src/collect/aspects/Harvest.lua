-- Aspect collector: combine straw handling. Applies to any object (vehicle or implement) — the
-- Combine spec sits on self-propelled harvesters but also on towed and stationary ones.
-- Namespaced under VDT.* (see TurnOn.lua).
--
-- The one setting a harvester operator actually toggles mid-field: drop a straw swath to bale later,
-- or chop it back onto the ground. `swathActive` is the live state; the two `*Available` flags say
-- whether this machine offers the choice at all (some have no chopper, some no swath), which is what
-- tells a consumer whether to show a toggle or nothing.

VDT = VDT or {}
VDT.Harvest = {}

---@param object table
---@return HarvestModel|nil nil when the object is not a combine
function VDT.Harvest.collect(object)
  local spec = object.spec_combine
  if spec == nil then
    return nil
  end

  local model = { swathActive = spec.isSwathActive == true }
  if spec.swath ~= nil then
    model.swathAvailable = spec.swath.isAvailable
  end
  if spec.chopper ~= nil then
    model.chopperAvailable = spec.chopper.isAvailable
  end
  return model
end
