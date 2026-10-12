-- Optional integration: Vehicle Speed Sync (FS25_VehicleSpeedSync, by GtX). Namespaced under VDT.* (see
-- aspects/TurnOn.lua).
--
-- The mod lets the driver tie their speed to the nearest moving machine within 25 m -- the tractor
-- beside a combine being unloaded on the move -- and draws a lamp beside the speedometer while it is
-- doing so. That lamp is all this exports (issue #145): whether the machine is synced, and whether it
-- is still waiting for the other one to move. Its cruise-control copy is a one-shot that sets the
-- ordinary cruise speed, which the core export already reports.
--
-- Reached through `object.spec_vehicleSpeedSync`, a plain alias the mod sets on the vehicle in its own
-- onLoad (the real key is mod-name-prefixed), and through the two getters it registers on the
-- vehicle type -- so nothing is read from the mod's environment, and the alias's absence is the "not
-- installed" check (it is also absent on a machine the mod skipped: not drivable, or a locomotive).
--
-- MULTIPLAYER: the sync is switched on the client that is driving, which sets its own state before
-- telling the server, and a lost connection is decided on the server and sent back to that client
-- (SetVehicleSpeedSyncEvent, to the owner connection). Either way the driver's own copy is current, and
-- the driver's is the only one this export reads -- it describes the entered machine.
--
-- Deliberately NOT collected: the machine it is synced to. The mod prints its name and driver in the
-- F1 box, but a reference to another vehicle is not something the cluster can draw, and the speed
-- being followed is the machine's own speed, already on the speedometer.

---@class SpeedSyncModel
---@field active boolean the machine's speed is tied to another's
---@field waiting boolean synced, but the other machine has not started moving yet

---@class VehicleModel
---@field speedSync SpeedSyncModel?

VDT = VDT or {}
VDT.VehicleSpeedSync = {}

-- Object stage: runs per vehicle/implement during the walk (see registry.lua). Only a drivable
-- machine carries the mod's spec, so it no-ops for implements.
---@param object table a vehicle or implement
---@param model table the object's already core-collected model
function VDT.VehicleSpeedSync.contributeObject(object, model)
  if type(object.spec_vehicleSpeedSync) ~= "table" then
    return
  end
  local getActive = object.vehicleSpeedSyncGetSyncedSpeedActive
  local getWaiting = object.vehicleSpeedSyncGetSyncedSpeedWaiting
  if type(getActive) ~= "function" or type(getWaiting) ~= "function" then
    return
  end

  -- The getter, not the spec's own speedSyncActive flag: it also requires a machine to be synced to,
  -- which is the condition the mod's lamp is drawn on.
  local active = getActive(object) == true
  model.speedSync = {
    active = active,
    -- Only a sync can be waiting; gated so the pair never reads as a contradiction.
    waiting = active and getWaiting(object) == true,
  }
end
