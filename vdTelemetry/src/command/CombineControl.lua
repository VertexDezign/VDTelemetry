-- Executes the combine's straw command from the app -> mod back-channel. The write side of the
-- `swathActive` half of VDT.Harvest (see collect/aspects/Harvest.lua).
--
-- One command, and it is the one a harvester operator actually changes mid-field: lay the straw in a
-- swath to bale later, or chop it back onto the ground. Everything else the combine screen offers --
-- the pipe, unloading, turn-on, the header's raise and fold -- was already commandable through
-- PipeCoverControl, TrailerControl and ImplementControl, so this file is small on purpose.
--
-- Direct engine call, like PipeCoverControl: there is no vdAI function for the straw toggle, and the
-- rule is to use the vdAI* functions FS25_additionalInputs already has rather than to extend vdAI
-- with functions only VDTelemetry needs.
--
-- `Combine:setIsSwathActive` is a good fit for a lossy channel with no work on our side. It takes an
-- ABSOLUTE state -- so a resent or doubled command is idempotent, unlike the game's own key, which
-- toggles -- and it owns its multiplayer event (CombineStrawEnableEvent), so a client's command
-- reaches the server exactly as the player's own keypress does.
--
-- What this file does NOT do is re-derive whether the toggle is allowed. `Combine.actionEventToggleChopper`
-- refuses on a crop with no windrow and the action event is only bound when the machine has both a
-- swath and a chopper; that whole verdict is collected as `harvest.canToggleSwath` and the app gates
-- its control on it. Here it is asked again -- state moves between the export and the command, and a
-- command arriving after the crop changed must fail the same way the key would -- and refusing is a
-- debug line rather than the game's blinking warning: the command came from a terminal the driver is
-- looking at, and the control there simply stops offering itself.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.CombineControl = {}

---Lay the straw in a swath (`on = true`) or chop it (`on = false`).
---@param vehicle Vehicle the controlled vehicle
---@param target string vehicle|front|back|selected
---@param on boolean
---@param debugger GrisuDebug
function VDT.CombineControl.setSwath(vehicle, target, on, debugger)
  local object = VDT.TargetResolver.resolve(vehicle, target, debugger)
  if object == nil then
    return
  end
  if object.setIsSwathActive == nil then
    debugger:debug("setSwath: %s is not a combine, ignoring", target)
    return
  end
  if not VDT.Harvest.canToggleSwath(object) then
    debugger:debug("setSwath: %s cannot toggle its straw right now, ignoring", target)
    return
  end
  object:setIsSwathActive(on)
  debugger:debug("setSwath(%s, %s)", target, tostring(on))
end

VDT.CommandRegistry.register("setSwath", {
  parse = function(xml, key)
    return {
      target = xml:getString(key .. "#target"),
      on = xml:getBool(key .. "#on", false),
    }
  end,
  execute = function(vehicle, params, debugger)
    VDT.CombineControl.setSwath(vehicle, params.target, params.on, debugger)
  end,
})
