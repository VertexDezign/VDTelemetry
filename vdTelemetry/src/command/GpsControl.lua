-- Executes the steering-assist line commands from the app -> mod back-channel. The inverse of the
-- `linesVisible` field VDT.SupportSystems.collectGps reports.
--
-- Unlike every other control, this one does not touch the vehicle: whether AIAutomaticSteering draws
-- its guide lines is a global client setting, GameSettings.SETTING.STEERING_ASSIST_LINES. So
-- `execute` ignores its vehicle argument. We mirror the game's own keybind handler
-- (AIAutomaticSteering.actionEventSteeringLines) exactly, including its third setValue argument.
--
-- Absolute (not toggle), like LightControl/MotorControl -- the file channel is lossy/async, and
-- setValue to the value it already holds is a no-op, so a repeated command is harmless.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.GpsControl = {}

---Show (on=true) or hide the steering-assist lines.
---@param on boolean
---@param debugger GrisuDebug
function VDT.GpsControl.setLinesVisible(on, debugger)
  g_gameSettings:setValue(GameSettings.SETTING.STEERING_ASSIST_LINES, on, true)
  debugger:debug("setGpsLinesVisible=%s", tostring(on))
end

-- Command handler (see CommandRegistry).
VDT.CommandRegistry.register("setGpsLinesVisible", {
  parse = function(xml, key)
    return { on = xml:getBool(key .. "#on", false) }
  end,
  execute = function(_, params, debugger)
    VDT.GpsControl.setLinesVisible(params.on, debugger)
  end,
})
