-- Registry of OPTIONAL third-party mod integrations (see EnhancedVehicle.lua). Namespaced under
-- VDT.* (see aspects/TurnOn.lua). Each integration self-detects whether its mod is installed, so its
-- hooks are safe to run unconditionally.
--
-- Integrations extend the model at named STAGES. An integration opts into a stage by defining a
-- function of that name; stages it doesn't care about it simply omits. Current stages:
--   * contributeObject(object, model)        -- per vehicle/implement during the walk
--   * contributeEnvironment(environment, model)
--   * contributeFleetVehicle(vehicle, row)   -- per machine of the fleet channel
-- Add more stages as new collectors gain extension points (e.g. a document/root stage). Add new
-- integrations to `all`; they run in list order after the core collectors build the model.
--
-- A stage is a QUESTION, not a place: the fleet stage exists beside the object one because the two
-- ask different things of the same mod -- what the machine you are in is doing right now, versus what
-- the machine in the shed has had done to it -- and an integration answers whichever it has an answer
-- for (see AdvancedDamageSystem.lua, which answers both, with different blocks).

VDT = VDT or {}
VDT.Integrations = {}

VDT.Integrations.all = {
  VDT.EnhancedVehicle,
  -- Precision Farming is sourced far earlier than this file (it gates the channels that suppress the
  -- base-game data it supersedes), but its object hook belongs in the same list as any other optional
  -- mod's: it adds application rates to a sprayer that has them.
  VDT.PrecisionFarming,
  -- Advanced Damage System: the dashboard lamps, the service interval and the electrical system. Last
  -- because it is the one integration that *corrects* a core-collected value rather than only adding
  -- to it — the engine temperature, which is ADS's under ADS.
  VDT.AdvancedDamageSystem,
}

---Run a named stage hook for every integration that implements it.
---@param stage string the hook name, e.g. "contributeObject" / "contributeEnvironment"
---@param subject table the game object/state for this stage (a vehicle, the environment, ...)
---@param model table the core-collected model fragment to decorate
function VDT.Integrations.run(stage, subject, model)
  for _, integration in ipairs(VDT.Integrations.all) do
    local hook = integration[stage]
    if hook ~= nil then
      hook(subject, model)
    end
  end
end
