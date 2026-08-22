-- Aspect collector: what the machine weighs right now. Applies to any object (vehicle or implement).
-- Namespaced under VDT.* (see TurnOn.lua).
--
-- `value` is the machine's live mass -- its own plus whatever is loaded -- and `empty` is what it
-- weighs with nothing in it, so the payload a panel prints is the difference between the two. Both
-- are in TONNES, the engine's mass unit throughout (its own I18N:formatMass switches to kg below 1 t,
-- which is a formatting decision and belongs on the app side).
--
-- Per-machine, never per-train: getTotalMass sums THIS object's components only, so a tractor and its
-- implements each report their own and anything wanting a train weight adds them up itself.
--
-- MULTIPLAYER: fine. Vehicle:update calls updateMass() with no isServer gate -- only the physics
-- setMass() behind it is server-side -- so component masses track fill levels on a client too. The
-- catch is that it runs only for a vehicle the client is actually updating, which is the one being
-- driven and its implements, and that is all this mod reports anyway.
--
-- `empty` is absent on a machine's very first export: Vehicle:updateMass is what fills in
-- component.defaultMass, and getDefaultMass reads `component.defaultMass or 0` until it has run once.
-- Reported absent rather than as a zero, which would make the whole machine look like payload.

VDT = VDT or {}
VDT.Mass = {}

---@param object table a vehicle or implement
---@return MassModel|nil nil when the object does not report a mass
function VDT.Mass.collect(object)
  if type(object.getTotalMass) ~= "function" then
    return nil
  end

  local total = object:getTotalMass()
  -- NaN fails every comparison including this one, which is how it is caught.
  if type(total) ~= "number" or total ~= total or total <= 0 or total == math.huge then
    return nil
  end

  ---@type MassModel
  local model = { value = tonumber(ValueMapper.mapFloat(total, 3)) }

  if type(object.getDefaultMass) == "function" then
    local empty = object:getDefaultMass()
    if type(empty) == "number" and 0 < empty and empty ~= math.huge then
      model.empty = tonumber(ValueMapper.mapFloat(empty, 3))
    end
  end

  return model
end
