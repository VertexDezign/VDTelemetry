-- Optional integration: Combine XP (FS25_CombineXP). Namespaced under VDT.* (see aspects/TurnOn.lua).
--
-- The mod replaces the flat "combines harvest at whatever speed you drive" model with a capacity
-- one: it measures how much crop the machine is actually taking, compares that against a rated
-- performance derived from the machine's power, and caps the harvesting speed when the drum is
-- over-fed. Its own HUD shows three numbers beside the speedometer — throughput, yield and load —
-- and those are what this integration exports, because they are the ones the mod itself considers
-- the readout.
--
-- Fields land in a `combineXp` table on the harvest aspect rather than flat beside the engine's own
-- (see aspects/Harvest.lua). These are the MOD's model of the machine — its estimate of the yield,
-- its own limiter — not game state read back, and nesting says so; it also keeps `load` from
-- colliding with the header load in aspects/Cutter.lua, which answers a different question.
--
-- Reached through `object.spec_xpCombine`, a plain alias the mod sets on the vehicle in its own
-- onLoad. The real spec key is mod-name-prefixed ("spec_FS25_CombineXP.xpCombine"), which a renamed
-- zip would change; the alias is set by the spec itself and so survives that, and the prefixed key
-- is kept only as a fallback for the ordering case where onLoad has not run yet.
--
-- MULTIPLAYER: the three exported numbers plus highMoisture are the exact set the mod streams
-- (xpCombine:onWriteStream / onWriteUpdateStream), so they are true on a joined client. `speedLimit`
-- is NOT streamed -- it is computed in onUpdateTick under `if self.isServer` and stays at the onLoad
-- default of 15 on a client -- so it is exported only where it is real, like the header load.
--
-- Deliberately NOT collected:
--   * `mrCombineLimiter.basePerfAvgArea` / `currentAvgArea` -- the rated and current performance. The
--     ratio between them IS exported (`load`, which is what the mod's own HUD draws); the absolutes
--     are scaled by factors the mod applies inside its measurement (fertiliser normalisation, a
--     material factor) and are not the ha/h they look like.
--   * `g_combinexp` (the mod's settings: power boost, which of the speed models are active). Global
--     of another mod's environment, and a setting rather than machine state.

---@class CombineXpModel
---@field throughput number? tonnes per hour, as the mod's HUD prints it
---@field yield number? tonnes per hectare over the measured window
---@field load number? 0..1 of the machine's rated capacity (the HUD shows this x100 as a percentage)
---@field highMoisture boolean? the crop is too damp for full speed right now
---@field speedLimit number? km/h the limiter is currently allowing; absent on a multiplayer client

---@class HarvestModel
---@field combineXp CombineXpModel?

VDT = VDT or {}
VDT.CombineXP = {}

-- The mod's env key, which is its folder/zip name. Only used for the fallback spec lookup.
local MOD_NAME = "FS25_CombineXP"
local SPEC_KEY = "spec_" .. MOD_NAME .. ".xpCombine"

---A number worth exporting: the mod divides by measured quantities that can be zero, so NaN reaches
---these fields (its own HUD checks for it before drawing). NaN is exported as null by the encoder,
---and a null throughput claims less than an absent one does.
---@param value any
---@return boolean
local function isFinite(value)
  return type(value) == "number" and value == value and value ~= math.huge and value ~= -math.huge
end

-- Object stage: runs per vehicle/implement during the walk (see registry.lua). No-ops for anything
-- that is not a combine with the mod's spec on it.
---@param object table a vehicle or implement
---@param model table the object's already core-collected model
function VDT.CombineXP.contributeObject(object, model)
  local spec = object.spec_xpCombine or object[SPEC_KEY]
  -- absent when the mod isn't installed, or on a machine it doesn't cover; nothing to attach to
  if type(spec) ~= "table" or model.harvest == nil then
    return
  end

  local limiter = spec.mrCombineLimiter
  if type(limiter) ~= "table" then
    return
  end

  local combineXp = {}

  if isFinite(limiter.tonPerHour) then
    combineXp.throughput = tonumber(ValueMapper.mapFloat(limiter.tonPerHour))
  end
  if isFinite(limiter.yield) then
    combineXp.yield = tonumber(ValueMapper.mapFloat(limiter.yield))
  end
  -- The HUD's load is engineLoad scaled by loadMultiplier -- the moisture/time penalty, which is 1
  -- while neither speed model is limiting. Exported as the fraction; the percentage is the panel's.
  if isFinite(limiter.engineLoad) then
    local multiplier = isFinite(limiter.loadMultiplier) and limiter.loadMultiplier or 1
    combineXp.load = tonumber(ValueMapper.mapFloat(limiter.engineLoad * multiplier))
  end
  if type(limiter.highMoisture) == "boolean" then
    combineXp.highMoisture = limiter.highMoisture
  end

  -- Server-only; see the multiplayer note in the header.
  if object.isServer and isFinite(spec.speedLimit) then
    combineXp.speedLimit = tonumber(ValueMapper.mapFloat(spec.speedLimit))
  end

  if next(combineXp) ~= nil then
    model.harvest.combineXp = combineXp
  end
end
