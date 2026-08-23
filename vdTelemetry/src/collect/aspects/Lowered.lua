-- Aspect collector: lowered state. Applies to any object (vehicle or implement).
-- Namespaced under VDT.* (see TurnOn.lua).
--
-- Unlike its neighbours this one cannot simply gate on a spec, because `getIsLowered` is registered on
-- EVERY vehicle by base Vehicle and its whole body is `return false`. Taken at face value, a tractor
-- claims to be raised and a terminal offers a control for something it cannot do.
--
-- Exactly three specializations overwrite it -- Attachable, Foldable and Pickup -- so "does this
-- machine have a lowered state at all" is the union of what those three can answer, and each is asked
-- in the way that machine's own spec asks it:
--
--   * Pickup always knows: its getIsLowered returns spec.isLowered outright.
--   * Foldable knows only when FOLD-MIDDLE is configured. Otherwise it hands the call to superFunc,
--     and on a self-propelled machine that is base Vehicle -- whose `false` is indistinguishable from
--     a real one, since it ignores the default it was given. Configuration is therefore read here
--     directly, matching the guard in Foldable:getIsLowered.
--   * Attachable knows when its attacher joint `allowsLowering` or `isDefaultLowered`; otherwise it
--     returns the caller's default unchanged. That is asked WITHOUT touching jointDesc: call twice
--     with opposite defaults, and opposite answers mean the default is being handed straight back.
--     The probe also sees through a Foldable sitting on top of it, which a flag check would not.
--
-- Only the *question* is answered here; the value itself always comes from the engine. That matters
-- because the details differ per machine in ways a reimplementation gets wrong -- a Krone BigM reports
-- lowering through Foldable's fold-middle and not through Attachable at all, which is what made a
-- hand-rolled setLoweredAll no-op on it.
--
-- `getIsFoldMiddleAllowed()` is deliberately NOT part of the fold-middle test even though
-- Foldable:getIsLowered includes it: it goes false mid-fold, and a state that vanishes from the
-- terminal for the duration of a fold is worse than one that reads through it. The two XML-loaded
-- fields are the machine's *configuration* and do not move.

VDT = VDT or {}
VDT.Lowered = {}

---Whether any specialization on this object can actually answer "am I lowered".
---@param object table
---@return boolean
local function hasLoweredState(object)
  if object.spec_pickup ~= nil then
    return true
  end

  local foldable = object.spec_foldable
  if foldable ~= nil and foldable.foldMiddleAnimTime ~= nil and foldable.foldMiddleInputButton ~= nil then
    return true
  end

  if object.spec_attachable ~= nil then
    return object:getIsLowered(true) == object:getIsLowered(false)
  end

  return false
end

---@param object table
---@return boolean|nil lowered state, or nil when the object has no lowered state
function VDT.Lowered.collect(object)
  if object.getIsLowered == nil or not hasLoweredState(object) then
    return nil
  end
  return object:getIsLowered()
end
