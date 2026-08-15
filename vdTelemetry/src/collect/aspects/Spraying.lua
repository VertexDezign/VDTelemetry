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
--    usageScale, effects, sounds (Sprayer:loadSprayTypeFromXML). They carry no name and no category. The
--    named record -- `name`, `isFertilizer`, `isLime`, `isHerbicide`, `litersPerSecond` -- belongs to
--    g_sprayTypeManager (SprayTypeManager:addSprayType) and is reached from the fill type. This
--    collector reads the manager's; the vehicle's is only good for finding the fill unit.
-- 2. The tank is addressed by `getSprayerFillUnitIndex()`, NOT by `spec.fillUnitIndex`: the
--    active spray type may override it, which is exactly what happens on a machine with more than one
--    tank. Reading the spec field directly reports the wrong tank on those.
-- 3. `getSprayerDoubledAmountActive()` returns **two** values, `active, isAllowed`, and
--    the second is what says whether the machine has the control at all. The base game allows it only
--    when `not isFertilizerSprayer` -- i.e. on SLURRY TANKERS AND MANURE SPREADERS, and not on
--    fertilizer sprayers, which is the opposite way round from how it reads. Taking the first value
--    alone would offer a toggle half the machines do not have. (Same shape of trap as
--    getGearGroupToDisplay; check the arity before trusting a getter.)
--    Precision Farming **hard-overrides this to `return false, false`**
--    (ExtendedSprayer:getSprayerDoubledAmountActive) because its variable-rate control replaces
--    doubling outright, so
--    with PF installed the field is false on everything. That is the honest answer -- the control
--    really is gone -- but it means every PF capture says false and the base-game behaviour above is
--    only observable without PF.
--
-- MULTIPLAYER: better than it looks. `workAreaParameters` is written from
-- Sprayer:onStartWorkAreaProcessing, which WorkArea:onUpdateTick raises with **no isServer gate**,
-- so it runs on a client for the vehicle being driven --
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

---Whether `fillUnitIndex` is one of the motor's propellant tanks (diesel, electric, methane).
---
---This guards a real and non-obvious failure. `getSprayerFillUnitIndex()` falls back to
---`spec.fillUnitIndex`, whose XML default is **1** -- and on a self-propelled machine fill unit 1 is
---very often the FUEL tank. A capture of the Vredo VT5536 (a self-propelled manure barrel) reported
---`fillType: DIESEL` with a nominal usage to match, because the engine resolved the sprayer's tank to
---the diesel one and then computed everything from it. The engine's own `isSlurryTanker` flag is
---derived from the same index, so on such a machine *nothing* the spec says about material is worth
---reading -- which is why the collector gives up entirely rather than reporting a subset.
---@param object table
---@param fillUnitIndex number|nil
---@return boolean
local function isPropellantUnit(object, fillUnitIndex)
  local motorized = object.spec_motorized
  if motorized == nil or fillUnitIndex == nil then
    return false
  end
  for _, index in ipairs(motorized.propellantFillUnitIndices or {}) do
    if index == fillUnitIndex then
      return true
    end
  end
  return false
end

---What kind of machine this is, as a **capability** -- derived from what the tank *accepts*, not from
---what is loaded right now. A universal tanker reports SLURRY_TANKER even while carrying water.
---"What is it doing" is `category` / `fillType`; this is "what is it for", and the two are separate
---questions a panel needs both of (the unit a rate is quoted in follows this one: kg/ha for solid,
---l/ha for liquid, m3/ha for slurry, t/ha for manure).
---
---The base game only splits out slurry and manure (both derived in Sprayer:onLoad) and lumps *everything* else
---into `isFertilizerSprayer`, which swallows solid fertilizer spreaders, lime spreaders and herbicide
---sprayers alike. Precision Farming splits that catch-all further, and does it from base-game calls
---only (ExtendedSprayer:onLoad) -- so the same split is made here, and it works whether or not
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

  -- Resolved once: `kind` (what the tank accepts) and `fillType` (what is in it) must describe the
  -- same unit, or a combination machine reports the seed hopper's kind against the sprayer's load.
  local fillUnitIndex = object:getSprayerFillUnitIndex()

  -- The spec resolved its tank to a fuel tank, so every material answer it can give is wrong -- see
  -- isPropellantUnit. Emit nothing at all rather than a plausible-looking subset: on the machine this
  -- was found on the real applicator is a separate attached implement, which reports for itself.
  if isPropellantUnit(object, fillUnitIndex) then
    return nil
  end

  local doubledAmount, doubledAmountAllowed = object:getSprayerDoubledAmountActive()

  ---@type SprayingModel
  local model = {
    kind = kindOf(object, spec, fillUnitIndex),
    -- "Material is leaving the machine", not merely "switched on": the engine's own effect predicate
    -- is `g_time < lastSprayTime + 100`, and lastSprayTime only moves when ground was actually
    -- treated. isTurnedOn (a separate aspect) is the switch.
    --
    -- CAVEAT, seen on a capture: it only moves for work areas the *sprayer* processes. A combination
    -- machine that applies through its cultivator areas instead -- the SKY Methys HDS, a fertilizing
    -- cultivator -- reports active=false while visibly injecting, with workAreas[].processing=true
    -- beside it. So this is a positive signal only; `workAreas` remains the reliable "is it working".
    active = object:getAreEffectsVisible() == true,
    doubledAmount = doubledAmount == true,
    doubledAmountAvailable = doubledAmountAllowed == true,
    allowsSpraying = spec.allowsSpraying ~= false,
  }

  local params = spec.workAreaParameters or {}

  -- What is in the tank. `fillType` is the join key to the matching fillUnits entry -- the fill unit
  -- list carries no indices, and a combination machine has more than one tank, so this is the only
  -- way for a consumer to know which one the sprayer draws from.
  local fillTypeIndex = object:getFillUnitFillType(fillUnitIndex)

  -- ...except a great many applicators have no tank of their own: a dribble bar, an injector or a
  -- disc harrow carries nothing and draws from the barrel it is hitched to. Two of the first eleven
  -- captures were this shape, so it is the common case rather than an exotic one. The engine has
  -- already worked out which vehicle's tank feeds this one (onStartWorkAreaProcessing walks the
  -- fill-type sources) and leaves the
  -- answer in `sprayFillType`, so use it when the machine's own tank is empty -- otherwise a dribble
  -- bar reports nothing at all while visibly applying slurry.
  --
  -- It only fills in once work areas have been processed at least once, so a machine that has not
  -- worked yet this session still reports no material. That is honest: nothing has been applied.
  if (fillTypeIndex == nil or fillTypeIndex == FILL_TYPE_UNKNOWN) and params.sprayFillType ~= nil then
    fillTypeIndex = params.sprayFillType
    -- Taking that fallback IS the signal: this machine has nothing in its own tank yet has a material
    -- to apply, so the level worth watching belongs to whatever is feeding it.
    --
    -- Deliberately NOT `workAreaParameters.lastIsExternallyFilled`, which sounds like exactly this and
    -- is not: getIsSprayerExternallyFilled returns false unless getIsAIActive(), so it
    -- means "a hired worker is being topped up by the game", a different mechanic entirely. It reads
    -- false on a player-driven dribble bar drawing from its own barrel, which is this whole case.
    if fillTypeIndex ~= FILL_TYPE_UNKNOWN then
      model.externalSource = true
    end
  end

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
  -- speed -- that is how the game keeps consumption per hectare constant -- so
  -- dividing it back out by dt yields a figure that does not change as you slow down. It is "what
  -- this machine burns per minute at full speed", which is a real and useful readout, but it is not
  -- the current draw and must not be drawn as one. Precision Farming publishes true rates when it is
  -- installed; see integrations/PrecisionFarming.lua.
  if params.usagePerMin ~= nil and params.usagePerMin > 0 then
    model.nominalUsagePerMin = tonumber(ValueMapper.mapFloat(params.usagePerMin))
  end

  return model
end
