-- Aspect collector: cultivators, power harrows and subsoilers -- one engine spec (Cultivator) with
-- two XML flags telling the three apart. Applies to any object (vehicle or implement).
-- Namespaced under VDT.* (see TurnOn.lua).
--
-- The thinnest of the four ISOBUS aspects, and deliberately so: it exists mainly so the commonest
-- implement on a farm is not the one with no section in the panel. Most of what a cultivator has to
-- say is already answered generically -- working width and the shutoff bar by `workWidth`, what each
-- part is doing by `workAreas`, the depth setting by `workMode` where the machine declares modes.
--
-- FertilizingCultivator is a *separate* spec that bolts a sprayer onto one of these and carries no
-- state of its own worth reading, so it needs no collector: its fertilizer half shows up through the
-- spraying aspect, on the same object.
--
-- MULTIPLAYER: Cultivator registers no onReadStream/onWriteStream and no events (Cultivator.lua:
-- 42-51) -- none of this is synchronized. `isSubsoiler` / `isPowerHarrow` are read from the vehicle
-- XML at load so they are identical everywhere and safe; `limitToField` is engine state that a client
-- only ever sees at its load default. It is kept for the same reason the plough's is (right in single
-- player, and the game's own HUD has the same hole) but it is the field to distrust here.
--
-- NOT collected: `spec.isWorking` (`:115`, literally `0.5 < getLastSpeed()`) and `spec.isEnabled`
-- (`:69`, which the engine switches off itself mid-tick). The first is a speed threshold dressed up
-- as a state -- `speed` already says that, better -- and the second changes under the reader without
-- meaning anything a display can act on. workAreas[].active/processing is the honest answer to "is
-- this thing working", from the engine's own predicate.

VDT = VDT or {}
VDT.Tillage = {}

---@param spec table spec_cultivator
---@return string
local function kindOf(spec)
  if spec.isSubsoiler then
    return "SUBSOILER"
  end
  if spec.isPowerHarrow then
    return "POWER_HARROW"
  end
  return "CULTIVATOR"
end

---@param object table a vehicle or implement
---@return TillageModel|nil nil when the object does not till
function VDT.Tillage.collect(object)
  local spec = object.spec_cultivator
  if spec == nil then
    return nil
  end

  return {
    kind = kindOf(spec),
    -- Whether the machine works deep enough to count as a proper cultivation pass; the engine uses it
    -- to decide which ground state it leaves behind.
    deepMode = spec.useDeepMode ~= false,
    limitToField = spec.limitToField ~= false,
  }
end
