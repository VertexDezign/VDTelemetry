-- Applies the shared aspect collectors (those valid for any vehicle OR implement) onto an object
-- model. Each collector is pure and returns nil when its spec is absent; a nil assignment leaves the
-- key out of the Lua table, so absent aspects become absent JSON keys (the Kotlin model supplies defaults).
-- Namespaced under VDT.* (see TurnOn.lua).
--
-- Field order follows the Kotlin model's Implement (isTurnedOn, foldable, lowered, fillUnits, mass, pipe,
-- cover, wearable, schema, selection, discharge, tipping, harvest, workMode, workWidth, workAreas,
-- baleCounter, sowing, spraying, plow, tillage, mixer); JSON is key-addressed so order is cosmetic.
--
-- sowing/spraying/plow/tillage/mixer are the per-class ISOBUS aspects (issues #58, #113). They are
-- what the panel dispatches on: a section is drawn iff its aspect is present, which is why they must
-- stay nil-when-absent like everything else here. A machine can carry more than one -- a fertilizing
-- seeder has sowing AND spraying -- so nothing downstream may treat them as mutually exclusive.
--
-- Every collector is a cheap spec-field read and each returns nil when its spec is absent, so a given
-- object only pays for the aspects it actually has. This runs on the export timer, not per frame.

VDT = VDT or {}
VDT.Aspects = {}

---@param object table a vehicle or implement
---@param model table the object's model, decorated in place
function VDT.Aspects.apply(object, model)
  model.isTurnedOn = VDT.TurnOn.collect(object)
  model.foldable = VDT.Foldable.collect(object)
  model.lowered = VDT.Lowered.collect(object)
  model.fillUnits = VDT.FillUnit.collect(object)
  model.mass = VDT.Mass.collect(object)
  model.pipe = VDT.Pipe.collect(object)
  model.cover = VDT.Cover.collect(object)
  model.wearable = VDT.Wearable.collect(object)
  model.schema = VDT.Schema.collect(object)
  model.selection = VDT.Selection.collect(object)
  model.discharge = VDT.Discharge.collect(object)
  model.tipping = VDT.Tipping.collect(object)
  model.harvest = VDT.Harvest.collect(object)
  model.workMode = VDT.Work.collectMode(object)
  model.workWidth = VDT.Work.collectWidth(object)
  model.workAreas = VDT.WorkAreas.collect(object)
  model.baleCounter = VDT.BaleCounter.collect(object)
  model.sowing = VDT.Sowing.collect(object)
  model.spraying = VDT.Spraying.collect(object)
  model.plow = VDT.Plow.collect(object)
  model.tillage = VDT.Tillage.collect(object)
  model.mixer = VDT.Mixer.collect(object)
end
