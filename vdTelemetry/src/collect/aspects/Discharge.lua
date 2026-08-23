-- Aspect collector: unloading state. Applies to any object (vehicle or implement).
-- Namespaced under VDT.* (see TurnOn.lua).
--
-- Covers every trailer, combine and auger wagon, and pairs with the pipe aspect: the pipe says where
-- the spout is, this says whether anything is actually coming out of it and — when it isn't — why.
-- That "why" is the part a dashboard can't derive for itself; the engine has already worked it out
-- for its own on-screen warning.
--
-- Everything here is a plain field read of engine-maintained state. The spec also exposes
-- getCanDischargeToGround/AtPosition/ToObject, but those run terrain and fill-type queries, so they
-- are deliberately NOT called on the export path.

VDT = VDT or {}
VDT.Discharge = {}

-- Dischargeable.DISCHARGE_STATE_*
local STATES = { [0] = "OFF", [1] = "OBJECT", [2] = "GROUND" }

-- Dischargeable.DISCHARGE_REASON_*, the codes behind the game's own "can't unload here" warnings.
local REASONS = {
  [1] = "NOT_ALLOWED_HERE",
  [2] = "NO_FREE_CAPACITY",
  [3] = "FILLTYPE_NOT_SUPPORTED",
  [4] = "TOOLTYPE_NOT_SUPPORTED",
  [5] = "NO_ACCESS",
  [6] = "NO_ACCESS_LAND",
}

---@param object table
---@return DischargeModel|nil nil when the object cannot discharge
function VDT.Discharge.collect(object)
  local spec = object.spec_dischargeable
  if spec == nil then
    return nil
  end

  ---@type DischargeModel
  local model = {
    state = STATES[spec.currentDischargeState] or "OFF",
    -- The master gate (setIsDischargeAllowed), which other specializations latch off -- a shut cover, a
    -- machine mid-fold -- and which the engine saves per vehicle. It is NOT "can it unload here": a
    -- captured mixer wagon has it true while `reason` below says the trough will not take the feed.
    allowed = spec.isDischargeAllowed,
  }

  -- The active node is what the raycast keeps up to date; a vehicle with several spouts reports the
  -- one currently selected.
  local node = spec.currentDischargeNode
  if node ~= nil then
    model.nodeIndex = node.index
    model.fillUnitIndex = node.fillUnitIndex
    -- What the node is pointed at right now, whether or not unloading is running.
    model.hasObject = node.dischargeObject ~= nil
    model.hitTerrain = node.dischargeHitTerrain == true
    model.reason = REASONS[node.dischargeFailedReason]
  end

  return model
end
