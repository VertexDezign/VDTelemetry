-- Aspect collector: trailer tipping. Applies to any object (vehicle or implement).
-- Namespaced under VDT.* (see TurnOn.lua).
--
-- Distinct from the discharge aspect: `discharge` is about material leaving a node, this is about the
-- trough itself moving. A tipper can be mid-OPENING with nothing coming out yet, which is exactly the
-- window a dashboard wants to show.
--
-- `side` is which of the trailer's tip sides is in use (left / right / back on a three-way tipper) and
-- is nil until one is picked; `preferredSide` is the one the next tip will use, so it is always set.

VDT = VDT or {}
VDT.Tipping = {}

-- Trailer.TIPSTATE_*
local STATES = { [0] = "CLOSED", [1] = "OPENING", [2] = "OPEN", [3] = "CLOSING" }

---@param object table
---@return TippingModel|nil nil when the object does not tip
function VDT.Tipping.collect(object)
  local spec = object.spec_trailer
  if spec == nil then
    return nil
  end

  return {
    state = STATES[spec.tipState] or "CLOSED",
    side = spec.currentTipSideIndex,
    preferredSide = spec.preferedTipSideIndex,
    count = spec.tipSideCount,
  }
end
