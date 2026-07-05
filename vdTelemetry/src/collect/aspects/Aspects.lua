-- Applies the shared aspect collectors (those valid for any vehicle OR implement) onto an object
-- model. Each collector is pure and returns nil when its spec is absent; a nil assignment leaves the
-- key out of the Lua table, so absent aspects become absent JSON keys (Model.kt supplies defaults).
-- Namespaced under VDT.* (see TurnOn.lua).
--
-- Field order follows Model.kt (isTurnedOn, foldable, lowered, fillUnits, pipe, cover, wearable);
-- JSON is key-addressed so order is cosmetic.

VDT = VDT or {}
VDT.Aspects = {}

---@param object table a vehicle or implement
---@param model table the object's model, decorated in place
function VDT.Aspects.apply(object, model)
  model.isTurnedOn = VDT.TurnOn.collect(object)
  model.foldable = VDT.Foldable.collect(object)
  model.lowered = VDT.Lowered.collect(object)
  model.fillUnits = VDT.FillUnit.collect(object)
  model.pipe = VDT.Pipe.collect(object)
  model.cover = VDT.Cover.collect(object)
  model.wearable = VDT.Wearable.collect(object)
end
