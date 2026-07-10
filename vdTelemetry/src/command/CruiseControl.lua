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
