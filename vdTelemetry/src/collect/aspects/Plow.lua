-- Aspect collector: plough. Applies to any object (vehicle or implement) -- the spec sits on towed
-- ploughs and on the self-propelled/mounted ones alike. Namespaced under VDT.* (see TurnOn.lua).
--
-- The readout a plough terminal exists for is **which way the bodies are turned**, and the engine
-- does not store that as a side: it stores `spec.rotationMax`, a bool meaning "at the max end of the
-- turn animation". Which end is left is a per-machine XML value, `spec.rotateLeftToMax` (default
-- true), and the engine's own left/right reasoning is
-- `Plow:getAIInvertMarkersOnTurn` (Plow.lua:507-515): `rotationMax == rotateLeftToMax` is left. That
-- comparison is the whole mapping; do not assume rotationMax means one side.
--
-- `side` is absent on a plough that does not turn at all -- no `rotationPart.turnAnimation` means
-- there is nothing to reverse, so a side would be an invented fact rather than a missing one.
--
-- MULTIPLAYER, two different stories in one spec:
--   * `rotationMax` is in the join stream (onReadStream/onWriteStream, :205-224), so the side is
--     correct on a client from the moment it joins.
--   * `limitToField` is NOT. It is broadcast on change (PlowLimitToFieldEvent, :316-338) but never
--     written into the initial stream, so a client that joins mid-session reads the engine's load
--     default (true) until somebody toggles it. Kept anyway -- it is right in single player, right
--     after any change, and the game's own HUD has exactly the same hole -- but it is the one field
--     here that can be stale, which is why it is called out rather than trusted silently.

VDT = VDT or {}
VDT.Plow = {}

---@param object table a vehicle or implement
---@return PlowModel|nil nil when the object is not a plough
function VDT.Plow.collect(object)
  local spec = object.spec_plow
  if spec == nil then
    return nil
  end

  ---@type PlowModel
  local model = {
    -- Both are engine predicates rather than stored state. `rotationAllowed` is the mechanical half
    -- (the plough is not mid-fold); `canToggle` adds lowered and powered, so it is the one that gates
    -- a control. They are separate because a terminal that later explains *why* the plough will not
    -- turn needs to tell "still folding" from "still in the ground".
    rotationAllowed = object:getIsPlowRotationAllowed() == true,
    canToggleRotation = object:getCanTogglePlowRotation() == true,
    limitToField = object:getPlowLimitToField() == true,
    -- True when the player does not get to choose -- either the machine forces it or the platform
    -- forbids creating fields at all.
    forceLimitToField = object:getPlowForceLimitToField() == true,
  }

  -- Only a reversible plough has a side to report. rotateLeftToMax defaults true in the engine
  -- (Plow.lua:145), so a machine that declares neither still maps consistently.
  if spec.rotationPart ~= nil and spec.rotationPart.turnAnimation ~= nil then
    -- Normalized to real booleans before comparing: the two are only ever compared to each other, so
    -- a nil on either side would silently resolve to a confident "RIGHT".
    local atMax = spec.rotationMax == true
    local leftIsMax = spec.rotateLeftToMax ~= false
    model.side = (atMax == leftIsMax) and "LEFT" or "RIGHT"
  end

  return model
end
