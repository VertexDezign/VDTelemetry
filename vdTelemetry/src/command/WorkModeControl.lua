-- Executes the work-mode command from the app -> mod back-channel. The write side of VDT.Work's
-- `workMode` (see collect/aspects/Work.lua): which of the modes a tool declares it is switched to -- a
-- merger's delivery side, a mower-conditioner's swath against spread, a cultivator's depth.
--
-- Direct engine call, like CombineControl: there is no vdAI function for the work mode.
--
-- `WorkMode:setWorkMode` fits a lossy channel with no work on our side. It takes an ABSOLUTE mode
-- index -- so a resent or doubled command is idempotent, unlike the game's own TOGGLE_WORKMODE key,
-- which steps to the next one -- ignores an index the machine does not declare, and owns its
-- multiplayer event (SetWorkModeEvent), so a client's command reaches the server as the key would.
--
-- The one thing it does NOT do is ask whether the change is allowed: the game's gates are on its
-- action event -- WorkMode:onUpdate switches it off whenever getIsWorkModeChangeAllowed says no, and
-- addPoweredActionEvent refuses it with the motor off. Calling the setter bypasses both, and would
-- swing a merger's belts while it is folded for the road or with nothing running. So the same verdict
-- is asked here, VDT.Work.canChangeMode, the one the export carries as `workMode.canChange`.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.WorkModeControl = {}

---Switch the tool to work mode `mode` (1-based, as the export's `current`).
---@param vehicle Vehicle the controlled vehicle
---@param target string vehicle|front|back|selected
---@param mode number
---@param debugger GrisuDebug
function VDT.WorkModeControl.setWorkMode(vehicle, target, mode, debugger)
  local object = VDT.TargetResolver.resolve(vehicle, target, debugger)
  if object == nil then
    return
  end
  local spec = object.spec_workMode
  if object.setWorkMode == nil or spec == nil or spec.stateMax == nil or spec.stateMax <= 0 then
    debugger:debug("setWorkMode: %s has no work modes, ignoring", target)
    return
  end
  if mode == nil or mode < 1 or spec.stateMax < mode then
    debugger:debug("setWorkMode: %s has no mode %s, ignoring", target, tostring(mode))
    return
  end
  if mode == spec.state then
    debugger:debug("setWorkMode: %s already in mode %d", target, mode)
    return
  end
  if VDT.Work.canChangeMode(object) == false then
    debugger:debug("setWorkMode: %s cannot change mode right now, ignoring", target)
    return
  end
  object:setWorkMode(mode)
  debugger:debug("setWorkMode(%s, %d)", target, mode)
end

VDT.CommandRegistry.register("setWorkMode", {
  parse = function(xml, key)
    return {
      target = xml:getString(key .. "#target"),
      mode = xml:getInt(key .. "#mode"),
    }
  end,
  execute = function(vehicle, params, debugger)
    VDT.WorkModeControl.setWorkMode(vehicle, params.target, params.mode, debugger)
  end,
})
