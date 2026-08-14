-- Which farm is the local player on: the one question a dozen modules ask, and the one answer they all
-- have to get. Every farm-scoped channel scopes its collect to it (production, storage, husbandry,
-- finance, missions, invoices, crop rotation) and every write side enforces ownership against it — so a
-- second definition drifting from this one would let the terminal offer what the game will refuse, or
-- refuse what it just showed. It is also what the `farmScoped` flag in src/export/ExportChannels.lua
-- means: the channels whose content moves when this answer changes.
--
-- It had two homes before this one — `VDT.ProductionExporter.ownFarmId` (an odd owner for a rule ten
-- other modules depend on) and `VDT.CropRotation.localFarmId`, identical bodies in a collector and an
-- integration. Folded here by issue #78.
--
-- NOT the same question as "whose vehicle is this" (`getOwnerFarmId` on the vehicle), and not the same
-- as "which farm does that mod think we are": FS25_TaskList answers the latter with its own
-- `getCurrentFarmId`, and the TaskList integration deliberately keeps asking it rather than this.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.Farm = {}

---The local player's farm, or nil while spectating / before a player exists (see EnvironmentExporter).
---Farm 0 is the engine's "no farm" rather than a farm id, so it comes back as nil too — no caller can
---accidentally scope a document to it, or write against it.
---@return number|nil
function VDT.Farm.ownFarmId()
  if g_localPlayer ~= nil and type(g_localPlayer.farmId) == "number" and g_localPlayer.farmId > 0 then
    return g_localPlayer.farmId
  end
  return nil
end
