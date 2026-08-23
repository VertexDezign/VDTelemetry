-- Aspect collector: what the player currently has selected. Applies to any object (vehicle or
-- implement). Namespaced under VDT.* (see TurnOn.lua).
--
-- Three related things, all per-object rather than root-only:
--
--   * `selected` — whether this object is the one the player's controls are acting on. The root
--     vehicle owns the authoritative pointer (rootVehicle.currentSelection), but each object mirrors
--     it via getIsSelected(), so walking the tree is enough and we never need the root's ordered
--     selectableObjects list. Do NOT try to recompute eligibility: base Vehicle:getCanBeSelected()
--     is `VehicleDebug.state ~= 0` and specializations override it, so the engine's own flag is the
--     only correct answer -- which is what `selectable` below asks it for.
--
--   * `selectable` — whether the game would let the player select this object at all. Exactly the
--     test Vehicle:registerSelectableObjects applies before putting an object in the root's
--     selectableObjects list, and it matters because setSelectedVehicle does NOT fail on an object
--     that fails it: it walks the list and selects the first eligible object instead. Without this
--     flag a dashboard offering a tap on the diagram would sometimes move the selection to a
--     different machine entirely (see command/SelectionControl.lua). Almost everything hitched to a
--     tractor passes -- Attachable overrides getCanBeSelected to `true`, as do TurnOnVehicle,
--     Foldable, Trailer, Cover, Pipe and a dozen more -- so in practice this marks the machines with
--     nothing to control, the bare tractor among them.
--
--   * `controlGroup` — the sub-selection *within* a selected object: a crane or front loader splits
--     its moving tools into named groups the player cycles through (spec_cylindered). The game's own
--     HUD only ever shows the group NUMBER; the names are right there in the spec, so we export both
--     and let the dashboard do better.

VDT = VDT or {}
VDT.Selection = {}

---The control groups the player can switch to right now, in the order the game's own selection cycle
---visits them.
---
---NOT a subset of `names` by construction, and not the same question. `controlGroupNames` is every
---group the vehicle XML declares; `controlGroupMapping` is subSelectionIndex -> group index and
---holds only the groups whose moving tools are currently active. Cylindered:updateControlGroups
---rebuilds it whenever that changes, so a group can come and go while the machine is hitched. A
---group missing from it has no sub-selection at all, which means there is no argument
---setSelectedVehicle could be given that would reach it -- so a dashboard must offer the groups from
---here, not from `names`, or it draws a control that cannot do anything.
---@param spec table spec_cylindered
---@return number[]|nil group indices into controlGroupNames, ascending by sub-selection index; nil when none is reachable
local function availableGroups(spec)
  local mapping = spec.controlGroupMapping
  if mapping == nil then
    return nil
  end

  -- Keyed by sub-selection index, and rebuilt by clearing keys rather than by replacing the table,
  -- so walk it with pairs() and sort -- the ascending sub-selection order IS the cycling order.
  local subIndices = {}
  for subIndex in pairs(mapping) do
    table.insert(subIndices, subIndex)
  end
  if #subIndices == 0 then
    return nil
  end
  table.sort(subIndices)

  local groups = {}
  for _, subIndex in ipairs(subIndices) do
    table.insert(groups, mapping[subIndex])
  end
  return groups
end

---@param object table
---@return SelectionModel|nil nil when the object cannot report a selection state
function VDT.Selection.collect(object)
  if object.getIsSelected == nil then
    return nil
  end

  local model = { selected = object:getIsSelected() }

  -- Both are registered on base Vehicle, so an object that reports a selection state normally has
  -- them; guarded anyway, since a missing flag must read as "unknown" rather than as "yes".
  if object.getCanBeSelected ~= nil and object.getBlockSelection ~= nil then
    model.selectable = object:getCanBeSelected() and not object:getBlockSelection()
  end

  local spec = object.spec_cylindered
  -- currentControlGroupIndex is 0 when nothing is active; the names are 1-based and indexed by it
  -- (the game does exactly this in Cylindered:onDraw). A single group means there is nothing to
  -- cycle, which is why the game gates its own readout on `1 < #controlGroupNames`.
  if spec ~= nil and spec.controlGroupNames ~= nil and #spec.controlGroupNames > 0 then
    local names = {}
    for _, name in ipairs(spec.controlGroupNames) do
      table.insert(names, name)
    end
    model.controlGroup = {
      current = spec.currentControlGroupIndex,
      name = names[spec.currentControlGroupIndex],
      names = names,
      available = availableGroups(spec),
    }
  end

  return model
end
