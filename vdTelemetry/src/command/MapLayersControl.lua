-- Executes the ground-layer subscription command from the app -> mod back-channel: which raster
-- planes the terminal's dashboards are actually showing.
--
-- Unlike the other controls this one doesn't act on the world at all -- it tells an EXPORT channel what
-- to bother producing. The mapLayers sweep is the mod's most expensive channel, a dashboard shows one
-- overlay at a time, and nobody may be looking at all; without this the mod grid-samples and writes
-- every plane regardless (see src/collect/MapLayersExporter.lua).
--
-- Absolute (the whole set, not a delta), like LightControl/MotorControl: the file channel is lossy and
-- async, so a set-to-state is self-correcting -- a dropped or duplicated command leaves the mod
-- sweeping exactly what the last command it did see asked for. The empty set is a legitimate value and
-- means "nothing is being shown"; the server sends it when its last dashboard disconnects.
--
-- requiresVehicle = false: this is about what the terminal is displaying, not about a vehicle, so it
-- must run when the player is on foot (the dispatcher otherwise drops commands with no vehicle).
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.MapLayersControl = {}

---Split a comma-separated id list ("crops,growth") into ids. Tolerates spaces and empty entries, so a
---trailing comma or a joined-empty list degrades to "nothing subscribed" rather than an odd id.
---@param value string|nil
---@return string[]
function VDT.MapLayersControl.parseIds(value)
  local ids = {}
  if type(value) ~= "string" then
    return ids
  end
  for id in string.gmatch(value, "[^,]+") do
    local trimmed = string.gsub(id, "^%s*(.-)%s*$", "%1")
    if trimmed ~= "" then
      ids[#ids + 1] = trimmed
    end
  end
  return ids
end

-- Command handler (see CommandRegistry).
VDT.CommandRegistry.register("setMapLayers", {
  requiresVehicle = false,
  parse = function(xml, key)
    return { ids = VDT.MapLayersControl.parseIds(xml:getString(key .. "#ids", "")) }
  end,
  execute = function(_, params, debugger)
    VDT.MapLayers.setSubscription(params.ids, debugger)
  end,
})
