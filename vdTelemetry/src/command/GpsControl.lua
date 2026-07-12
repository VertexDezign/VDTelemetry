-- Executes the steering-assist line commands from the app -> mod back-channel. The inverse of the
-- `linesVisible` field VDT.SupportSystems.collectGps reports.
--
-- Unlike every other control, this one does not touch the vehicle: whether AIAutomaticSteering draws
-- its guide lines is a global client setting, GameSettings.SETTING.STEERING_ASSIST_LINES. So
-- `execute` ignores its vehicle argument. Like the game's own keybind handler
-- (AIAutomaticSteering.actionEventSteeringLines) we pass doSave=true, persisting the choice to
-- gameSettings.xml just as a keypress would.
--
-- Absolute (not toggle), like LightControl/MotorControl -- the file channel is lossy/async, so an
-- idempotent set-to-state is self-correcting. But setValue is NOT itself idempotent: it assigns,
-- publishes a SETTING_CHANGED message, and (with doSave) rewrites gameSettings.xml every call. So we
-- compare against the current value first and skip the write when it already matches, keeping a
-- repeated command off the disk.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.GpsControl = {}

---Show (on=true) or hide the steering-assist lines. No-op when already in the target state.
---@param on boolean
---@param debugger GrisuDebug
function VDT.GpsControl.setLinesVisible(on, debugger)
  local setting = GameSettings.SETTING.STEERING_ASSIST_LINES
  if (g_gameSettings:getValue(setting) == true) == on then
    debugger:debug("setGpsLinesVisible=%s: already set, ignoring", tostring(on))
    return
  end
  g_gameSettings:setValue(setting, on, true)
  debugger:debug("setGpsLinesVisible=%s", tostring(on))
end

-- Command handler (see CommandRegistry). requiresVehicle = false: the steering-assist lines are a
-- global client setting, not vehicle state, so this must run even when the player is on foot (the
-- dispatcher otherwise drops commands with no current vehicle).
VDT.CommandRegistry.register("setGpsLinesVisible", {
  requiresVehicle = false,
  parse = function(xml, key)
    return { on = xml:getBool(key .. "#on", false) }
  end,
  execute = function(_, params, debugger)
    VDT.GpsControl.setLinesVisible(params.on, debugger)
  end,
})
