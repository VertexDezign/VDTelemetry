-- Aspect collector: lowered state. Applies to any object (vehicle or implement).
-- Namespaced under VDT.* (see TurnOn.lua).
--
-- Unlike its neighbours, this one cannot gate on a spec: `getIsLowered` is registered on EVERY vehicle
-- by base Vehicle, whose body is a hard-coded `return false`. So a tractor -- which has nothing to
-- raise or lower -- used to export `lowered = false` and read as "Raised" on a terminal that has no
-- business offering it. Two tests separate a real answer from that default, and both are about who
-- answered rather than about what any particular spec looks like:
--
--   1. Did anything override it? SpecializationUtil.registerFunction stores the raw function, and
--      registerOverwrittenFunction REPLACES it with a wrapper closure. So an object whose
--      `getIsLowered` is still the identical `Vehicle.getIsLowered` reference has had no
--      specialization claim an opinion on lowering, and there is nothing to report. This is the
--      tractor.
--   2. Does the override actually know? `getIsLowered(default)` takes the answer to fall back on, and
--      Attachable returns it unchanged when the attacher joint neither `allowsLowering` nor
--      `isDefaultLowered` -- an implement on a hitch that does not move. Asking twice with opposite
--      defaults and getting opposite answers means the default is being handed straight back.
--
-- Deliberately NOT a reimplementation of the specs' conditions (Attachable's jointDesc flags,
-- Foldable's fold-middle configuration, Pickup's animation). Those are exactly the details that made
-- a hand-rolled setLoweredAll no-op on a Krone BigM: it reports lowered through Foldable's
-- fold-middle rather than through Attachable at all. Asking the engine who answered survives that,
-- and survives a modded spec we have never seen.
--
-- KNOWN RESIDUAL: an override that DEFERS to base is indistinguishable from a genuine `false`, since
-- base ignores the default it is handed. Foldable does exactly that when fold-middle is not
-- configured, so a self-propelled foldable without it still reports `lowered = false`. Narrow, and
-- not worth reaching into spec internals to catch on a machine nobody has produced yet -- the whole
-- point of both tests above is to stay out of those internals. See FUTURE.md.

VDT = VDT or {}
VDT.Lowered = {}

---@param object table
---@return boolean|nil lowered state, or nil when the object has no lowered state
function VDT.Lowered.collect(object)
  if object.getIsLowered == nil then
    return nil
  end

  -- Vehicle is one of FS25's bare-global specialization classes, so it is readable from here; guarded
  -- anyway, since a nil would silently turn this test off rather than fail.
  if Vehicle ~= nil and object.getIsLowered == Vehicle.getIsLowered then
    return nil
  end

  local ifLowered = object:getIsLowered(true)
  local ifRaised = object:getIsLowered(false)
  if ifLowered == nil or ifRaised == nil or ifLowered ~= ifRaised then
    return nil
  end
  return ifRaised
end
