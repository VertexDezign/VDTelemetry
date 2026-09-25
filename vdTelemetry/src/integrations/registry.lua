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

-- Named rather than held by reference, so a failing hook can be reported by name (see run). Each name
-- is a module under VDT.*, resolved at call time.
VDT.Integrations.all = {
  "EnhancedVehicle",
  -- Precision Farming is sourced far earlier than this file (it gates the channels that suppress the
  -- base-game data it supersedes), but its object hook belongs in the same list as any other optional
  -- mod's: it adds application rates to a sprayer that has them.
  "PrecisionFarming",
  -- Advanced Damage System: the dashboard lamps, the service interval and the electrical system. Last
  -- because it is the one integration that *corrects* a core-collected value rather than only adding
  -- to it — the engine temperature, which is ADS's under ADS.
  "AdvancedDamageSystem",
  -- Combine XP: the mod's own measurement of what the drum is taking, hung off the harvest aspect.
  "CombineXP",
}

-- "<integration>.<stage>" -> true once its failure has been logged, so a hook that throws on every
-- tick reports itself once rather than ten times a second.
local reported = {}

local function reportFailure(name, stage, err)
  local key = name .. "." .. stage
  if reported[key] then
    return
  end
  reported[key] = true
  local message = string.format("integration %s: %s failed (%s)", name, stage, tostring(err))
  local debugger = g_vdTelemetry ~= nil and g_vdTelemetry.debugger or nil
  if debugger ~= nil then
    debugger:error(message)
  else
    print("VDTelemetry - ERROR: " .. message)
  end
end

---Run a named stage hook for every integration that implements it.
---
---Each hook is contained on its own. A hook reads a third-party mod's internals, which that mod's next
---update is free to rename, and every stage runs inside a core collector: uncontained, one throwing
---hook would cost the whole channel its write -- for contributeObject that is vdTelemetry.json itself,
---every tick, for as long as the mod stays installed -- and would also skip every integration after it
---in the list. Contained, the object just goes without that one mod's decoration, whatever the hook
---had written before it threw stays, and the failure is logged once. The hook is not disabled: an
---error can be specific to one machine, and the next object may well decorate fine.
---@param stage string the hook name, e.g. "contributeObject" / "contributeEnvironment"
---@param subject table the game object/state for this stage (a vehicle, the environment, ...)
---@param model table the core-collected model fragment to decorate
function VDT.Integrations.run(stage, subject, model)
  for _, name in ipairs(VDT.Integrations.all) do
    local integration = VDT[name]
    local hook = integration ~= nil and integration[stage] or nil
    if hook ~= nil then
      local ok, err = pcall(hook, subject, model)
      if not ok then
        reportFailure(name, stage, err)
      end
    end
  end
end

-- Test seam: forget which failures were already logged.
function VDT.Integrations.resetReported()
  reported = {}
end
