-- Aspect collector: the machine is wrecked. Applies to any object (vehicle or implement).
-- Namespaced under VDT.* (see TurnOn.lua).
--
-- `vehicle.isBroken` is the engine's one-way flag for a machine that went into water deeper than it
-- tolerates (Vehicle:setBroken, from the tailwater check): from then on getIsActive and
-- getIsInteractive are false and the info box says it needs a reset. It has nothing to do with wear
-- -- a brand-new machine can be broken and a worn-out one is not -- which is why this is not part of
-- the wearable aspect.
--
-- Emitted only when true, so the key is absent on every working machine (the Kotlin model defaults
-- it to false).
--
-- MULTIPLAYER: fine. VehicleBrokenEvent carries the flag to every client, and a joining one reads it
-- from the vehicle's stream.

VDT = VDT or {}
VDT.Broken = {}

---@param object table
---@return boolean|nil true when broken, nil otherwise
function VDT.Broken.collect(object)
  if object.isBroken == true then
    return true
  end
  return nil
end
