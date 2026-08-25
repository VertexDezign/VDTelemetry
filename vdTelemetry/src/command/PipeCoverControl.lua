-- Executes pipe and cover commands from the app -> mod back-channel. The inverse of the VDT.Pipe and
-- VDT.Cover aspect collectors.
--
-- These are the first controls to reach an implement by calling an engine setter DIRECTLY rather than
-- through FS25_additionalInputs. That is not a departure from the rule ImplementControl follows: the
-- rule is to use the vdAI* functions additionalInputs already has, NOT to extend vdAI with functions
-- only VDTelemetry needs. There is no pipe or cover function there, so a direct call is the normal
-- path -- the same one LightControl and MotorControl take.
--
-- Both setters are a good fit for the lossy command channel without any work on our side: each takes
-- an ABSOLUTE state and each owns its multiplayer event (SetPipeStateEvent, SetCoverStateEvent), so a
-- resent or doubled command is idempotent and a client's command reaches the server the same way the
-- player's own keypress does.
--
-- Two engine quirks, both silent:
--   * setPipeState clamps to spec.numStates, so an out-of-range state lands on the last position
--     rather than erroring. We let it -- the machine has the last word on its own limits.
--   * setCoverState no-ops unless the vehicle hasCovers AND the state is within 0..#covers. Passing a
--     state a machine does not have therefore does nothing at all, which is why the app only offers
--     the states the aspect reported.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.PipeCoverControl = {}

---Move the target's pipe to an absolute state (1 = fully retracted, up to `numStates`).
---@param vehicle Vehicle the controlled vehicle
---@param target string vehicle|front|back|selected
---@param state number
---@param debugger GrisuDebug
function VDT.PipeCoverControl.setPipeState(vehicle, target, state, debugger)
  local object = VDT.TargetResolver.resolve(vehicle, target, debugger)
  if object == nil then
    return
  end
  if object.setPipeState == nil then
    debugger:debug("setPipeState: %s has no pipe, ignoring", target)
    return
  end
  object:setPipeState(state)
  debugger:debug("setPipeState(%s, %s)", target, tostring(state))
end

---Set the target's cover to an absolute state: 0 closes everything, 1..count opens that cover.
---@param vehicle Vehicle the controlled vehicle
---@param target string vehicle|front|back|selected
---@param state number
---@param debugger GrisuDebug
function VDT.PipeCoverControl.setCoverState(vehicle, target, state, debugger)
  local object = VDT.TargetResolver.resolve(vehicle, target, debugger)
  if object == nil then
    return
  end
  if object.setCoverState == nil then
    debugger:debug("setCoverState: %s has no cover, ignoring", target)
    return
  end
  object:setCoverState(state)
  debugger:debug("setCoverState(%s, %s)", target, tostring(state))
end

-- Command handlers (see CommandRegistry). Both share the {target, state} payload.
local function parseTargetState(xml, key)
  return {
    target = xml:getString(key .. "#target"),
    state = xml:getInt(key .. "#state", 0),
  }
end

VDT.CommandRegistry.register("setPipeState", {
  parse = parseTargetState,
  execute = function(vehicle, params, debugger)
    VDT.PipeCoverControl.setPipeState(vehicle, params.target, params.state, debugger)
  end,
})

VDT.CommandRegistry.register("setCoverState", {
  parse = parseTargetState,
  execute = function(vehicle, params, debugger)
    VDT.PipeCoverControl.setCoverState(vehicle, params.target, params.state, debugger)
  end,
})
