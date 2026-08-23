-- Resolves a command's `target` token (vehicle|front|back) to the object it names.
--
-- The command channel hands every control the *controlled vehicle*. That is all ImplementControl
-- needs, because FS25_additionalInputs' vdAI* functions take the position as part of the function
-- name and do the walking themselves. The controls that call an engine setter directly (pipe, cover,
-- tip side, discharge -- none of which vdAI has a counterpart for) need the object itself, so they
-- have to do that walk here.
--
-- The position comes from `vdAIGetAttacherJointPosition`, which is the SAME call VehicleExporter uses
-- to label an implement's `position` in the telemetry. That is the whole point of routing through it
-- rather than reading the attacher joint's own type: whatever the app drew as FRONT is what a command
-- addressed at "front" reaches, and the two cannot drift apart. It is also still inside the standing
-- rule -- we use what vdAI already has, we do not extend it.
--
-- Only the tractor's own attachers are reachable, which is exactly what ControlTarget can name. A
-- machine hitched behind another has no token, and the app renders its controls inert rather than
-- sending a command that would move the wrong machine (see controlTargetOf in the terminal).
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.TargetResolver = {}

-- Command token -> the position string the exporter labels an implement with.
local POSITION = { front = "FRONT", back = "BACK" }

---Resolve the object a command targets.
---@param vehicle Vehicle the controlled vehicle
---@param target string vehicle|front|back
---@param debugger GrisuDebug
---@return table|nil object nil when the target names nothing attached (or is unknown)
function VDT.TargetResolver.resolve(vehicle, target, debugger)
  if vehicle == nil then
    return nil
  end
  if target == "vehicle" then
    return vehicle
  end

  local wanted = POSITION[target]
  if wanted == nil then
    debugger:warn("target resolver: unknown target '%s'", tostring(target))
    return nil
  end

  local spec = vehicle.spec_attacherJoints
  if spec == nil then
    debugger:debug("target resolver: vehicle has no attacher joints")
    return nil
  end
  -- additionalInputs is a hard dependency, so a missing function means it failed to load rather than
  -- that it is absent; warn rather than silently resolving nothing.
  if vehicle.vdAIGetAttacherJointPosition == nil then
    debugger:warn("target resolver: vdAIGetAttacherJointPosition missing (FS25_additionalInputs not loaded?)")
    return nil
  end

  for _, attachedImplement in ipairs(spec.attachedImplements) do
    if vehicle:vdAIGetAttacherJointPosition(attachedImplement) == wanted then
      return attachedImplement.object
    end
  end

  debugger:debug("target resolver: nothing attached at %s", wanted)
  return nil
end
