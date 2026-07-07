-- Executes engine start/stop commands from the app -> mod back-channel. The inverse of the
-- VDT.Motor collector's `state`: it maps an absolute target (running / off) onto the engine setters.
-- Absolute (not toggle) for the same reason as LightControl -- the file channel is lossy/async.
--
-- startMotor/stopMotor are no-ops when already in the target state (they guard on getMotorState), so
-- a repeated command is harmless.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.MotorControl = {}

---Start (on=true) or stop the vehicle's engine.
---@param vehicle Vehicle
---@param on boolean
---@param debugger GrisuDebug
function VDT.MotorControl.setMotorState(vehicle, on, debugger)
  if vehicle.spec_motorized == nil then
    debugger:debug("setMotorState: vehicle has no motor, ignoring")
    return
  end
  if on then
    vehicle:startMotor()
    debugger:debug("startMotor")
  else
    vehicle:stopMotor()
    debugger:debug("stopMotor")
  end
end

-- Command handler (see CommandRegistry).
VDT.CommandRegistry.register("setMotorState", {
  parse = function(xml, key)
    return { on = xml:getBool(key .. "#on", false) }
  end,
  execute = function(vehicle, params, debugger)
    VDT.MotorControl.setMotorState(vehicle, params.on, debugger)
  end,
})
