-- Aspect collector: trailer tipping. Applies to any object (vehicle or implement).
-- Namespaced under VDT.* (see TurnOn.lua).
--
-- Distinct from the discharge aspect: `discharge` is about material leaving a node, this is about the
-- trough itself moving. A tipper can be mid-OPENING with nothing coming out yet, which is exactly the
-- window a dashboard wants to show.
--
-- `side` is which of the trailer's tip sides is in use (left / right / back on a three-way tipper) and
-- is nil until one is picked; `preferredSide` is the one the next tip will use, so it is always set.
-- `sides` names them, index-aligned with both, so a panel can print "Left" instead of "2".
--
-- MULTIPLAYER: the state is sound. `tipState` and `currentTipSideIndex` are in Trailer's join stream
-- and the preferred side both joins and rides its own TrailerToggleTipSideEvent, so a client that
-- joined mid-session still reads the right side.

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

  ---@type TippingModel
  local model = {
    state = STATES[spec.tipState] or "CLOSED",
    side = spec.currentTipSideIndex,
    preferredSide = spec.preferedTipSideIndex,
    count = spec.tipSideCount,
  }

  -- Already localized: Trailer:loadTipSide runs the XML's #name through g_i18n:convertText with the
  -- machine's own customEnvironment at load, so a modded trailer's side names resolve against the
  -- mod's l10n rather than the base game's. Nothing to look up here, and nothing to look up wrongly.
  --
  -- The fallback keeps the list index-aligned with `side` / `preferredSide`. loadTipSide rejects a
  -- side whose #name does not resolve, so in practice every entry has one; if that ever stops
  -- holding, a skipped entry would relabel every side after it rather than merely miss one.
  local sides = {}
  for _, tipSide in ipairs(spec.tipSides or {}) do
    sides[#sides + 1] = type(tipSide.name) == "string" and tipSide.name or ""
  end
  if 0 < #sides then
    model.sides = sides
  end

  return model
end
