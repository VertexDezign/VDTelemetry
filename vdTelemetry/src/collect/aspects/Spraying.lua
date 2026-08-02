-- Aspect collector: everything that puts material on the ground -- liquid sprayers, SOLID fertilizer
-- and LIME spreaders, slurry tankers and manure spreaders. The engine's Sprayer spec covers the lot;
-- there is no separate spreader specialization, so a disc spreader is a "sprayer" as far as the game
-- is concerned. `kind` is what separates them here (see kindOf, which splits further than the base
-- game does). Applies to any object (vehicle or implement): the spec sits on trailed sprayers, on
-- self-propelled ones, and on the sprayer half of a combination machine (a FertilizingSowingMachine
-- has this spec *and* spec_sowingMachine; a manure barrel's ManureBarrel spec *requires* this one).
-- Namespaced under VDT.* (see TurnOn.lua).
--
-- NOT covered, and correctly so: SaltSpreader is a different specialization entirely (it requires
-- only WorkArea + TurnOnVehicle, not Sprayer), so a road salt spreader gets no spraying aspect. That
-- is winter/road equipment rather than a field implement; if it ever matters it needs its own
-- collector rather than a widening of this one.
--
-- THREE TRAPS, all of which cost a wrong reading if ignored:
--
-- 1. There are two unrelated tables called "spray type". `spec.sprayTypes`, and what
--    `getActiveSprayType()` returns, are the *vehicle XML's* entries -- fillUnitIndex, fillTypes,
--    usageScale, effects, sounds (Sprayer.lua:563-597). They carry no name and no category. The
--    named record -- `name`, `isFertilizer`, `isLime`, `isHerbicide`, `litersPerSecond` -- belongs to
--    g_sprayTypeManager (SprayTypeManager.lua:61-68) and is reached from the fill type. This
--    collector reads the manager's; the vehicle's is only good for finding the fill unit.
-- 2. The tank is addressed by `getSprayerFillUnitIndex()` (`:555`), NOT by `spec.fillUnitIndex`: the
--    active spray type may override it, which is exactly what happens on a machine with more than one
--    tank. Reading the spec field directly reports the wrong tank on those.
-- 3. `getSprayerDoubledAmountActive()` returns **two** values, `active, isAllowed` (`:630-646`), and
--    the second is false on a slurry tanker or manure spreader -- doubling is a fertilizer-only
--    control. Taking the first alone would offer a toggle the machine does not have. (Same shape of
--    trap as getGearGroupToDisplay; check the arity before trusting a getter.)
--
-- MULTIPLAYER: better than it looks. `workAreaParameters` is written from
-- Sprayer:onStartWorkAreaProcessing (`:843-925`), which WorkArea:onUpdateTick raises with **no
-- isServer gate** (WorkArea.lua:131-133), so it runs on a client for the vehicle being driven --
-- which is the only vehicle this mod reports. `doubledAmountIsActive` rides its own broadcast event.
--
-- NOT collected, deliberately: `workAreaParameters.sprayVehicle`. A boom can draw from a *different*
-- vehicle (a trailed tank feeding a mounted boom) and the engine tracks which -- worth surfacing one
-- day as "drawing from the trailer", but it is a vehicle reference rather than a value, so it needs
-- a naming decision this round does not have to make.

VDT = VDT or {}
VDT.Spraying = {}

-- FillType.UNKNOWN -- an empty tank. Reported as absent rather than as a fill type named "UNKNOWN",
-- matching how aspects/FillUnit.lua blanks the same index.
local FILL_TYPE_UNKNOWN = 1

---What kind of machine this is, as a **capability** -- derived from what the tank *accepts*, not from
---what is loaded right now. A universal tanker reports SLURRY_TANKER even while carrying water.
---"What is it doing" is `category` / `fillType`; this is "what is it for", and the two are separate
---questions a panel needs both of (the unit a rate is quoted in follows this one: kg/ha for solid,
---l/ha for liquid, m3/ha for slurry, t/ha for manure).
---
---The base game only splits out slurry and manure (Sprayer.lua:204-206) and lumps *everything* else
---into `isFertilizerSprayer`, which swallows solid fertilizer spreaders, lime spreaders and herbicide
---sprayers alike. Precision Farming splits that catch-all further, and does it from base-game calls
---only (ExtendedSprayer.lua:125-126) -- so the same split is made here, and it works whether or not
---PF is installed. The precedence below is PF's, so our labels agree with the HUD it draws.
---
---Note the engine's own flags are not mutually exclusive: a tank accepting both LIQUIDMANURE and
---MANURE sets isSlurryTanker *and* isManureSpreader. Slurry wins, as it does in PF.
---@param object table
---@param spec table spec_sprayer
---@param fillUnitIndex number the tank `fillType` is read from, so both describe the same unit
---@return string
local function kindOf(object, spec, fillUnitIndex)
  local function accepts(fillType)
    return fillType ~= nil and object:getFillUnitAllowsFillType(fillUnitIndex, fillType) == true
  end

  -- Lime rides with solid fertilizer, as in PF: same hopper hardware, same kg/ha rate. Which of the
  -- two is actually loaded is `category`.
  if accepts(FillType.FERTILIZER) or accepts(FillType.LIME) then
    return "SOLID_FERTILIZER"
  end
  if accepts(FillType.LIQUIDFERTILIZER) then
    return "LIQUID_FERTILIZER"
  end
  if spec.isSlurryTanker then
    return "SLURRY_TANKER"
  end
  if spec.isManureSpreader then
    return "MANURE_SPREADER"
  end
  -- Everything left: herbicide sprayers, water, a modded material nobody classified.
  return "SPRAYER"
end

---@param object table a vehicle or implement
---@return SprayingModel|nil nil when the object does not spray
function VDT.Spraying.collect(object)
  local spec = object.spec_sprayer
  if spec == nil then
    return nil
  end

  local doubledAmount, doubledAmountAllowed = object:getSprayerDoubledAmountActive()

  -- Resolved once: `kind` (what the tank accepts) and `fillType` (what is in it) must describe the
  -- same unit, or a combination machine reports the seed hopper's kind against the sprayer's load.
  local fillUnitIndex = object:getSprayerFillUnitIndex()

  ---@type SprayingModel
  local model = {
    kind = kindOf(object, spec, fillUnitIndex),
    -- "Material is leaving the machine", not merely "switched on": the engine's own effect predicate
    -- is `g_time < lastSprayTime + 100`, and lastSprayTime only moves when ground was actually
    -- treated. isTurnedOn (a separate aspect) is the switch.
    active = object:getAreEffectsVisible() == true,
    doubledAmount = doubledAmount == true,
    doubledAmountAvailable = doubledAmountAllowed == true,
    allowsSpraying = spec.allowsSpraying ~= false,
  }

  -- What is in the tank. `fillType` is the join key to the matching fillUnits entry -- the fill unit
  -- list carries no indices, and a combination machine has more than one tank, so this is the only
  -- way for a consumer to know which one the sprayer draws from.
  local fillTypeIndex = object:getFillUnitFillType(fillUnitIndex)
  if fillTypeIndex ~= nil and fillTypeIndex ~= FILL_TYPE_UNKNOWN then
    local fillType = g_fillTypeManager:getFillTypeByIndex(fillTypeIndex)
    if fillType ~= nil then
      model.fillType = fillType.name
      model.title = fillType.title
    end

    -- The category the game treats this material as. Absent for a material with no spray type at all
    -- (water, a modded filltype nobody registered), which is not an error -- the tank still reports.
    local sprayType = g_sprayTypeManager:getSprayTypeByFillTypeIndex(fillTypeIndex)
    if sprayType ~= nil then
      model.sprayType = sprayType.name
      if sprayType.isFertilizer then
        model.category = "FERTILIZER"
      elseif sprayType.isLime then
        model.category = "LIME"
      elseif sprayType.isHerbicide then
        model.category = "HERBICIDE"
      end
    end
  end

  -- NOMINAL, not live. getSprayerUsage scales by the machine's *speed limit* rather than its actual
  -- speed (Sprayer.lua:472-496) -- that is how the game keeps consumption per hectare constant -- so
  -- dividing it back out by dt yields a figure that does not change as you slow down. It is "what
  -- this machine burns per minute at full speed", which is a real and useful readout, but it is not
  -- the current draw and must not be drawn as one. Precision Farming publishes true rates when it is
  -- installed; see integrations/PrecisionFarming.lua.
  local params = spec.workAreaParameters
  if params ~= nil and params.usagePerMin ~= nil and params.usagePerMin > 0 then
    model.nominalUsagePerMin = tonumber(ValueMapper.mapFloat(params.usagePerMin))
  end

  return model
end
