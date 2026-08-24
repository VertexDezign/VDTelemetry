-- Resolves a command's `target` token (vehicle|front|back|selected) to the object it names.
--
-- The command channel hands every control the *controlled vehicle*. That is all ImplementControl
-- needs, because FS25_additionalInputs' vdAI* functions take the address as part of the function
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
-- The three positional tokens reach the tractor's own attachers and nothing else. `selected` (issue
-- #120) is the fourth and the one that lifts that ceiling: it names whatever machine the game has
-- selected, at ANY depth of the rig, which is how a machine hitched behind another one becomes
-- reachable at all. The app moves the selection itself (issue #119), so this is not "wherever the
-- game happens to point" -- it is where the driver last tapped.
--
-- Resolved through the engine's own `Vehicle:getSelectedVehicle()`, not by reading
-- `currentSelection.object`: that field holds the *selection entry*, whose `.vehicle` is the machine.
-- getSelectedVehicle walks up to the root and unwraps it, so it is correct called on any object and
-- there is nothing here to keep in step with the engine's selection bookkeeping.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.TargetResolver = {}

-- Command token -> the position string the exporter labels an implement with.
local POSITION = { front = "FRONT", back = "BACK" }

---Resolve the object a command targets.
---@param vehicle Vehicle the controlled vehicle
---@param target string vehicle|front|back|selected
---@param debugger GrisuDebug
---@return table|nil object nil when the target names nothing attached or selected (or is unknown)
function VDT.TargetResolver.resolve(vehicle, target, debugger)
  if vehicle == nil then
    return nil
  end
  if target == "vehicle" then
    return vehicle
  end
  if target == "selected" then
    if vehicle.getSelectedVehicle == nil then
      debugger:warn("target resolver: vehicle cannot report its selection")
      return nil
    end
    -- nil is normal rather than an error: a rig where nothing can be selected has nothing selected,
    -- which is a bare tractor on a save with automatic motor start on (see aspects/Selection.lua).
    local selected = vehicle:getSelectedVehicle()
    if selected == nil then
      debugger:debug("target resolver: nothing is selected")
    end
    return selected
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
