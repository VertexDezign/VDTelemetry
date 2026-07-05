-- Registry of OPTIONAL third-party mod integrations (see EnhancedVehicle.lua). Namespaced under
-- VDT.* (see aspects/TurnOn.lua). Each integration self-detects whether its mod is installed, so its
-- hooks are safe to run unconditionally.
--
-- Integrations extend the model at named STAGES. An integration opts into a stage by defining a
-- function of that name; stages it doesn't care about it simply omits. Current stages:
--   * contributeObject(object, model)        -- per vehicle/implement during the walk
--   * contributeEnvironment(environment, model)
-- Add more stages as new collectors gain extension points (e.g. a document/root stage). Add new
-- integrations to `all`; they run in list order after the core collectors build the model.

VDT = VDT or {}
VDT.Integrations = {}

VDT.Integrations.all = {
  VDT.EnhancedVehicle,
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
