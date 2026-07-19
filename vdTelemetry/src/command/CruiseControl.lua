-- Executes cruise-control commands from the app -> mod back-channel. The inverse of
-- VDT.SupportSystems.collectCruiseControl: it maps enable/disable/setSpeed onto the Drivable setters.
--
-- Named tokens (enable/disable/setSpeed) rather than an absolute bool because cruise carries two
-- knobs (on/off state + target speed) that the app drives independently; setCruiseControlMaxSpeed
-- clamps the speed to the vehicle's own min/max, so out-of-range values are handled by the engine.
--
-- Namespaced under VDT.* -- there's no engine `CruiseControl` global (the states live on Drivable.*),
-- but we stay under VDT.* for consistency (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.CruiseControl = {}

---Multiplayer sync for the target speed. Unlike setCruiseControlState (which sends its own
---SetCruiseControlStateEvent internally), setCruiseControlMaxSpeed only mutates local state and the
---local, non-authoritative motor speed limit -- so on a client the UI updates but the *server* never
---learns the new speed and keeps driving at the old one. Drivable's own +/- input handler works
---because it broadcasts/sends SetCruiseControlSpeedEvent right after setCruiseControlMaxSpeed; we do
---the same. Reads back spec.cruiseControl.speed so the wire value is the engine-clamped one.
---No-op off-engine (unit tests) where the event class / g_server / g_client don't exist.
---@param vehicle Vehicle
local function syncCruiseSpeed(vehicle)
  if SetCruiseControlSpeedEvent == nil then
    return
  end
  local spec = vehicle.spec_drivable
  local cc = spec ~= nil and spec.cruiseControl or nil
  if cc == nil then
    return
  end
  local event = SetCruiseControlSpeedEvent.new(vehicle, cc.speed, cc.speedReverse)
  if g_server ~= nil then
    g_server:broadcastEvent(event, nil, nil, vehicle)
  elseif g_client ~= nil then
    g_client:getServerConnection():sendEvent(event)
  end
end

---Apply a cruise-control action.
---@param vehicle Vehicle
---@param action string enable|disable|setSpeed
---@param speed number|nil target km/h, required for setSpeed
---@param debugger GrisuDebug
function VDT.CruiseControl.apply(vehicle, action, speed, debugger)
  if vehicle.spec_drivable == nil then
    debugger:debug("cruise: vehicle not drivable, ignoring")
    return
  end

  if action == "enable" then
    vehicle:setCruiseControlState(Drivable.CRUISECONTROL_STATE_ACTIVE)
    debugger:debug("cruise enable")
  elseif action == "disable" then
    vehicle:setCruiseControlState(Drivable.CRUISECONTROL_STATE_OFF)
    debugger:debug("cruise disable")
  elseif action == "setSpeed" then
    if speed == nil then
      debugger:warn("cruise setSpeed: no speed given, ignoring")
      return
    end
    vehicle:setCruiseControlMaxSpeed(speed)
    syncCruiseSpeed(vehicle)
    debugger:debug("cruise setSpeed=%s", tostring(speed))
  else
    debugger:warn("cruise: unknown action '%s'", tostring(action))
  end
end

-- Command handler (see CommandRegistry).
VDT.CommandRegistry.register("setCruiseControl", {
  parse = function(xml, key)
    return {
      action = xml:getString(key .. "#action"),
      speed = xml:getFloat(key .. "#speed"),
    }
  end,
  execute = function(vehicle, params, debugger)
    VDT.CruiseControl.apply(vehicle, params.action, params.speed, debugger)
  end,
})
