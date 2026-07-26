-- Aspect collector: what the player currently has selected. Applies to any object (vehicle or
-- implement). Namespaced under VDT.* (see TurnOn.lua).
--
-- Two related things, both per-object rather than root-only:
--
--   * `selected` — whether this object is the one the player's controls are acting on. The root
--     vehicle owns the authoritative pointer (rootVehicle.currentSelection), but each object mirrors
--     it via getIsSelected(), so walking the tree is enough and we never need the root's ordered
--     selectableObjects list. Do NOT try to recompute eligibility: base Vehicle:getCanBeSelected()
--     is `VehicleDebug.state ~= 0` and specializations override it (Baler, for one), so the engine's
--     own flag is the only correct answer.
--
--   * `controlGroup` — the sub-selection *within* a selected object: a crane or front loader splits
--     its moving tools into named groups the player cycles through (spec_cylindered). The game's own
--     HUD only ever shows the group NUMBER; the names are right there in the spec, so we export both
--     and let the dashboard do better.

VDT = VDT or {}
VDT.Selection = {}

---@param object table
---@return SelectionModel|nil nil when the object cannot report a selection state
function VDT.Selection.collect(object)
  if object.getIsSelected == nil then
    return nil
  end

  local model = { selected = object:getIsSelected() }

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
    }
  end

  return model
end
