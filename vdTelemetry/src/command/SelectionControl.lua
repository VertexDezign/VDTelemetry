-- Executes selection commands from the app -> mod back-channel: "make this machine the one my
-- controls act on, and put it on this control group". The write side of the VDT.Selection aspect.
--
-- A direct engine call, like pipe and cover. That is not a departure from the rule ImplementControl
-- follows: the rule is to use the vdAI* functions FS25_additionalInputs already has, NOT to extend
-- vdAI with functions only VDTelemetry needs. There is no selection function there, so a direct call
-- is the normal path (see PipeCoverControl.lua's header).
--
-- Selection is CLIENT-LOCAL. There is no SetSelection*Event anywhere under
-- vehicles/specializations/events/ and setSelectedObject sends nothing: it is input state belonging
-- to whoever is driving, saved per savegame as `#selectedObjectIndex` and read by that player's HUD.
-- So a plain local call is the whole of it -- no multiplayer event of ours, and nothing another
-- player sees.
--
-- The machine and its control group are ONE command because they are one engine call. Cylindered
-- owns no separate "current group" setter; it registers each group as a sub-selection of the machine
-- (`addSubselection`), and Vehicle:setSelectedObject takes the object and the sub-selection index
-- together.
--
-- Two things the engine does quietly, which shape everything below:
--   * setSelectedVehicle does NOT fail on a machine that cannot be selected. It walks
--     selectableObjects and picks THE FIRST ELIGIBLE ONE INSTEAD -- so a tap on an unselectable
--     trailer would jump the selection to the tractor, which is worse than doing nothing. Hence the
--     getCanBeSelected/getBlockSelection gate here, which is the same test the export publishes as
--     `selection.selectable` and the app greys on. Both halves are wanted: the export is a tick old,
--     this is not.
--   * setSelectedObject clamps an out-of-range sub-selection to 1, so a stale control group is safe.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.SelectionControl = {}

---Resolve the diagram's own node path ("0", "0/1", "0/1/0") to the object it names.
---
---Addressed by path rather than by the engine's `selectionObject.index` on purpose. That index is
---native but it is rebuilt by updateSelectableObjects on every attach and detach, so it can go stale
---between the app rendering a diagram and the mod reading the tap. The path is resolved by walking
---`attachedImplements` -- the same walk, in the same order, that VehicleExporter does when it builds
---the tree the app drew -- so what was drawn and what the command reaches are the same node by
---construction. It is the same reason TargetResolver goes through vdAIGetAttacherJointPosition
---rather than reading attacher joints itself.
---
---Segments after the first are 0-based (they are array indices in the exported JSON); Lua's are
---1-based, hence the +1.
---@param vehicle Vehicle the controlled vehicle -- the root of the rig the app drew
---@param node string
---@param debugger GrisuDebug
---@return table|nil object nil when the path names nothing on the current rig
function VDT.SelectionControl.resolve(vehicle, node, debugger)
  if vehicle == nil or type(node) ~= "string" or node == "" then
    return nil
  end

  local segments = {}
  for segment in string.gmatch(node, "[^/]+") do
    table.insert(segments, segment)
  end
  if #segments == 0 then
    debugger:warn("setSelected: empty node path")
    return nil
  end
  -- The root is the controlled vehicle and nothing else; a path rooted anywhere else is a mismatch
  -- between what the app drew and what we hold, not something to guess at.
  if segments[1] ~= "0" then
    debugger:warn("setSelected: node '%s' is not rooted at the vehicle", node)
    return nil
  end

  local object = vehicle
  for i = 2, #segments do
    local index = tonumber(segments[i])
    if index == nil then
      debugger:warn("setSelected: node '%s' has a non-numeric segment", node)
      return nil
    end
    local spec = object.spec_attacherJoints
    local attached = nil
    if spec ~= nil and spec.attachedImplements ~= nil then
      attached = spec.attachedImplements[index + 1]
    end
    if attached == nil or attached.object == nil then
      -- The rig changed between the app drawing it and this command arriving: unhitched, or hitched
      -- somewhere else. Dropping is right -- the alternative is moving the selection to whatever
      -- happens to sit at that index now.
      debugger:debug("setSelected: nothing at node '%s' any more", node)
      return nil
    end
    object = attached.object
  end

  return object
end

---The sub-selection index that reaches `controlGroup` on `object`, or nil when nothing does.
---
---The two are NOT the same number. `controlGroupMapping` is subSelectionIndex -> group index, built
---by Cylindered from the groups whose moving tools are active, so the mapping has to be inverted --
---and a group that is not in it has no sub-selection at all and cannot be reached.
local function subSelectionFor(object, controlGroup)
  local spec = object.spec_cylindered
  if spec == nil or spec.controlGroupMapping == nil then
    return nil
  end
  for subIndex, groupIndex in pairs(spec.controlGroupMapping) do
    if groupIndex == controlGroup then
      return subIndex
    end
  end
  return nil
end

---Select the machine at `node`, optionally on control group `controlGroup` (1-based into the
---machine's `controlGroup.names`).
---@param vehicle Vehicle the controlled vehicle
---@param node string the diagram's node path
---@param controlGroup number|nil
---@param debugger GrisuDebug
function VDT.SelectionControl.setSelected(vehicle, node, controlGroup, debugger)
  local object = VDT.SelectionControl.resolve(vehicle, node, debugger)
  if object == nil then
    return
  end

  if object.getCanBeSelected == nil or object.getBlockSelection == nil then
    debugger:debug("setSelected: %s cannot report selectability, ignoring", node)
    return
  end
  if not object:getCanBeSelected() or object:getBlockSelection() then
    debugger:debug("setSelected: %s cannot be selected, ignoring", node)
    return
  end

  -- selectableObjects only exists on the root, so that is who owns the call.
  local root = object.rootVehicle or vehicle
  if root.setSelectedVehicle == nil then
    debugger:warn("setSelected: root vehicle has no setSelectedVehicle")
    return
  end

  local subSelectionIndex = nil
  if controlGroup ~= nil and controlGroup > 0 then
    subSelectionIndex = subSelectionFor(object, controlGroup)
    if subSelectionIndex == nil then
      -- The group went inactive between the export and the tap. Select the machine anyway, on
      -- whatever group it lands on -- that is the half of the command that still means something.
      debugger:debug("setSelected: control group %s is not active on %s", tostring(controlGroup), node)
    end
  end
  if subSelectionIndex == nil and not object:getIsSelected() then
    -- Landing on a machine names its first group, which is what the game's own cycling key does when
    -- it steps onto a new object. Re-selecting the machine that is ALREADY selected passes nil
    -- instead, so a bare tap on it cannot knock the driver's control group back to the first one.
    subSelectionIndex = 1
  end

  root:setSelectedVehicle(object, subSelectionIndex)
  debugger:debug("setSelected(%s, subSelection=%s)", node, tostring(subSelectionIndex))
end

VDT.CommandRegistry.register("setSelected", {
  parse = function(xml, key)
    return {
      node = xml:getString(key .. "#node"),
      -- absent when the command names no group, which means "leave the group alone"
      controlGroup = xml:getInt(key .. "#controlGroup"),
    }
  end,
  execute = function(vehicle, params, debugger)
    VDT.SelectionControl.setSelected(vehicle, params.node, params.controlGroup, debugger)
  end,
})
