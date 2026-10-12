-- Executes the speed-sync command from the app -> mod back-channel: the write side of the
-- Vehicle Speed Sync integration (src/integrations/VehicleSpeedSync.lua, issue #145).
--
-- What the mod's own key does, through the functions the mod registers on the vehicle: find the
-- nearest machine in range, then vehicleSpeedSyncSetSyncedSpeedState, which sends the mod's own
-- multiplayer event and plays its sounds. Nothing is read from the mod's environment.
--
-- An ABSOLUTE state rather than the key's toggle, so a resent or doubled command is idempotent. The
-- key's gates are asked through VDT.VehicleSpeedSync.findPartner -- the same verdict the export
-- carries as `speedSync.available` -- so the terminal cannot sync where the key would refuse: the
-- feature switched off in the mod's settings, an AI helper driving, nothing in range. Where the key
-- would blink "not possible", this logs and does nothing; the terminal's control is already greyed.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.SpeedSyncControl = {}

---@param vehicle Vehicle the controlled vehicle
---@param active boolean|nil
---@param debugger GrisuDebug
function VDT.SpeedSyncControl.setSpeedSync(vehicle, active, debugger)
  if type(vehicle.spec_vehicleSpeedSync) ~= "table" or vehicle.vehicleSpeedSyncSetSyncedSpeedState == nil then
    debugger:debug("setSpeedSync: no Vehicle Speed Sync on this vehicle, ignoring")
    return
  end
  if active == nil then
    debugger:warn("setSpeedSync: no state given, ignoring")
    return
  end
  if vehicle:vehicleSpeedSyncGetSyncedSpeedActive() == active then
    debugger:debug("setSpeedSync: already %s", tostring(active))
    return
  end

  if not active then
    vehicle:vehicleSpeedSyncSetSyncedSpeedState(false)
    debugger:debug("setSpeedSync(false)")
    return
  end

  local partner = VDT.VehicleSpeedSync.findPartner(vehicle)
  if partner == nil then
    debugger:debug("setSpeedSync: the mod would refuse (no key or nothing in range), ignoring")
    return
  end
  vehicle:vehicleSpeedSyncSetSyncedSpeedState(true, partner)
  debugger:debug("setSpeedSync(true)")
end

VDT.CommandRegistry.register("setSpeedSync", {
  parse = function(xml, key)
    return { active = xml:getBool(key .. "#active") }
  end,
  execute = function(vehicle, params, debugger)
    VDT.SpeedSyncControl.setSpeedSync(vehicle, params.active, debugger)
  end,
})
