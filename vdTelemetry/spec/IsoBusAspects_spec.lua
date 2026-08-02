-- Unit tests for the remaining three ISOBUS aspect collectors (issue #58):
-- src/collect/aspects/{Spraying,Plow,Tillage}.lua. The sowing one has its own file (Sowing_spec.lua)
-- because it landed a commit earlier.
--
-- Run with `busted` from the vdTelemetry/ directory. All three read their spec table; the sprayer and
-- the plough also call back into the object, so those methods are stubbed on the fake objects below,
-- as are the two fill/spray type managers.
--
-- What is actually worth testing here is the three engine traps the collectors exist to absorb:
--   * the plough stores a rotation *bool* whose left/right meaning is per-machine (rotateLeftToMax);
--   * the sprayer's doubled-amount getter returns two values and the second one gates the control;
--   * "spray type" names two unrelated tables, and only the manager's has a name and a category.

if ValueMapper == nil then
  dofile("src/mapper/ValueMapper.lua")
end
for name, file in pairs({
  Spraying = "src/collect/aspects/Spraying.lua",
  Plow = "src/collect/aspects/Plow.lua",
  Tillage = "src/collect/aspects/Tillage.lua",
}) do
  if VDT == nil or VDT[name] == nil then
    dofile(file)
  end
end

-- The engine's FillType enum, as much of it as these collectors name. Indices are arbitrary but must
-- match FILL_TYPES below.
local FILL_TYPE = {
  UNKNOWN = 1,
  FERTILIZER = 5,
  LIQUIDFERTILIZER = 6,
  LIME = 7,
  LIQUIDMANURE = 8,
  DIGESTATE = 9,
  MANURE = 10,
  WATER = 11,
  HERBICIDE = 12,
  DIESEL = 20,
}

local FILL_TYPES = {
  [1] = { name = "UNKNOWN", title = "Unknown" },
  [5] = { name = "FERTILIZER", title = "Mineraldünger" },
  [6] = { name = "LIQUIDFERTILIZER", title = "Flüssigdünger" },
  [7] = { name = "LIME", title = "Kalk" },
  [8] = { name = "LIQUIDMANURE", title = "Gülle" },
  [10] = { name = "MANURE", title = "Mist" },
  [11] = { name = "WATER", title = "Wasser" },
  [12] = { name = "HERBICIDE", title = "Herbizid" },
}

-- The *manager's* spray types — the ones that carry a name and a category. Keyed by fill type index.
-- WATER deliberately has none: a material the game registers no spray type for is a normal case.
local SPRAY_TYPES = {
  [5] = { name = "FERTILIZER", isFertilizer = true },
  [7] = { name = "LIME", isLime = true },
  [8] = { name = "LIQUIDMANURE", isFertilizer = true },
  [12] = { name = "HERBICIDE", isHerbicide = true },
}

describe("Spraying.collect", function()
  before_each(function()
    rawset(_G, "MathUtil", {
      round = function(v, decimals)
        local mult = 10 ^ (decimals or 0)
        return math.floor(v * mult + 0.5) / mult
      end,
    })
    _G.g_fillTypeManager = {
      getFillTypeByIndex = function(_, idx)
        return FILL_TYPES[idx]
      end,
    }
    _G.g_sprayTypeManager = {
      getSprayTypeByFillTypeIndex = function(_, idx)
        return SPRAY_TYPES[idx]
      end,
    }
    rawset(_G, "FillType", FILL_TYPE)
  end)

  after_each(function()
    rawset(_G, "MathUtil", nil)
    rawset(_G, "FillType", nil)
    _G.g_fillTypeManager = nil
    _G.g_sprayTypeManager = nil
  end)

  -- `over` replaces spec fields; `opts` drives the stubbed object methods.
  local function sprayer(over, opts)
    opts = opts or {}
    local spec = {
      isSlurryTanker = false,
      isManureSpreader = false,
      isFertilizerSprayer = true,
      allowsSpraying = true,
      fillUnitIndex = 1,
      workAreaParameters = { usagePerMin = 0 },
    }
    for k, v in pairs(over or {}) do
      spec[k] = v
    end
    return {
      spec_sprayer = spec,
      -- The active spray type may point the machine at a different tank than spec.fillUnitIndex.
      getSprayerFillUnitIndex = function()
        return opts.sprayerFillUnitIndex or spec.fillUnitIndex
      end,
      getFillUnitFillType = function(_, index)
        return (opts.tanks or {})[index]
      end,
      -- What each tank *accepts*, as opposed to what is in it. `accepts` is a map of fill unit index
      -- to a set of fill type indices; a tank with no entry accepts nothing, which is what makes the
      -- kind fall through to the catch-all.
      getFillUnitAllowsFillType = function(_, index, fillType)
        return ((opts.accepts or {})[index] or {})[fillType] == true
      end,
      getAreEffectsVisible = function()
        return opts.active == true
      end,
      getSprayerDoubledAmountActive = function()
        return opts.doubled == true, opts.doubledAllowed ~= false
      end,
    }
  end

  it("returns nil when the object does not spray", function()
    assert.is_nil(VDT.Spraying.collect({}))
  end)

  it("names the material and its category off the manager's spray type", function()
    local s = VDT.Spraying.collect(sprayer({}, { tanks = { [1] = 5 } }))
    assert.are.equal("FERTILIZER", s.fillType)
    assert.are.equal("Mineraldünger", s.title)
    assert.are.equal("FERTILIZER", s.sprayType)
    assert.are.equal("FERTILIZER", s.category)
  end)

  it("still reports the tank when the material has no spray type", function()
    local s = VDT.Spraying.collect(sprayer({}, { tanks = { [1] = 11 } }))
    assert.are.equal("WATER", s.fillType)
    assert.is_nil(s.sprayType)
    assert.is_nil(s.category)
  end)

  it("reports no material for an empty tank rather than a type named UNKNOWN", function()
    local s = VDT.Spraying.collect(sprayer({}, { tanks = { [1] = 1 } }))
    assert.is_nil(s.fillType)
    assert.is_nil(s.title)
    -- The machine itself is still described.
    assert.are.equal("SPRAYER", s.kind)
  end)

  it("reads the tank the active spray type points at, not spec.fillUnitIndex", function()
    -- A combination machine: unit 1 is seed, unit 2 is the sprayer's. Taking spec.fillUnitIndex would
    -- report the wrong tank entirely.
    local s = VDT.Spraying.collect(sprayer({}, { sprayerFillUnitIndex = 2, tanks = { [1] = 11, [2] = 5 } }))
    assert.are.equal("FERTILIZER", s.fillType)
  end)

  -- A tank at index 1 accepting exactly the listed fill types.
  local function tankAccepting(...)
    local set = {}
    for _, fillType in ipairs({ ... }) do
      set[fillType] = true
    end
    return { [1] = set }
  end

  it("separates the five machine kinds", function()
    -- Solid and liquid fertilizer are the split the base game does NOT make: it lumps both into
    -- isFertilizerSprayer. Without this a disc spreader and a boom look identical, and they take
    -- their rates in different units (kg/ha vs l/ha).
    local solid = VDT.Spraying.collect(sprayer({}, { accepts = tankAccepting(FILL_TYPE.FERTILIZER) }))
    assert.are.equal("SOLID_FERTILIZER", solid.kind)

    local liquid = VDT.Spraying.collect(sprayer({}, { accepts = tankAccepting(FILL_TYPE.LIQUIDFERTILIZER) }))
    assert.are.equal("LIQUID_FERTILIZER", liquid.kind)

    assert.are.equal("SLURRY_TANKER", VDT.Spraying.collect(sprayer({ isSlurryTanker = true })).kind)
    assert.are.equal("MANURE_SPREADER", VDT.Spraying.collect(sprayer({ isManureSpreader = true })).kind)

    -- The remainder: a herbicide boom accepts none of the fertilizer types and is neither slurry nor
    -- manure, so it lands on the catch-all.
    assert.are.equal(
      "SPRAYER",
      VDT.Spraying.collect(sprayer({}, { accepts = tankAccepting(FILL_TYPE.HERBICIDE) })).kind
    )
  end)

  it("treats a lime spreader as solid fertilizer hardware carrying lime", function()
    -- The kind is what the hopper is (kg/ha, same as fertilizer); the category is what is in it.
    -- Answering only one of those loses either the unit or the material.
    local limer = sprayer({}, { accepts = tankAccepting(FILL_TYPE.LIME), tanks = { [1] = FILL_TYPE.LIME } })
    local s = VDT.Spraying.collect(limer)
    assert.are.equal("SOLID_FERTILIZER", s.kind)
    assert.are.equal("LIME", s.category)
    assert.are.equal("LIME", s.fillType)
  end)

  it("keeps kind as a capability, independent of what is loaded", function()
    -- A fertilizer spreader standing empty is still a fertilizer spreader.
    local empty = sprayer({}, { accepts = tankAccepting(FILL_TYPE.FERTILIZER), tanks = { [1] = FILL_TYPE.UNKNOWN } })
    local s = VDT.Spraying.collect(empty)
    assert.are.equal("SOLID_FERTILIZER", s.kind)
    assert.is_nil(s.fillType)
    assert.is_nil(s.category)
  end)

  it("gives up entirely when the spec resolved its tank to a fuel tank", function()
    -- Found on a real capture: the Vredo VT5536, a self-propelled manure barrel, reported
    -- `fillType: DIESEL` with a nominal usage to match. getSprayerFillUnitIndex falls back to
    -- spec.fillUnitIndex, whose XML default is 1, and on a self-propelled machine unit 1 is the fuel
    -- tank. The engine derives isSlurryTanker from the same index, so nothing the spec says about
    -- material is worth reading — hence nil rather than a plausible-looking subset.
    local vredo = sprayer({}, { tanks = { [1] = FILL_TYPE.DIESEL } })
    vredo.spec_motorized = { propellantFillUnitIndices = { 1 } }
    assert.is_nil(VDT.Spraying.collect(vredo))
  end)

  it("still reports a self-propelled sprayer whose tank is not a fuel tank", function()
    -- The guard must not swallow every self-propelled machine: a Rogator's spray tank is a normal
    -- fill unit that simply is not in the propellant list.
    local rogator = sprayer({}, { sprayerFillUnitIndex = 3, tanks = { [3] = FILL_TYPE.HERBICIDE } })
    rogator.spec_motorized = { propellantFillUnitIndices = { 1, 2 } }
    local s = VDT.Spraying.collect(rogator)
    assert.is_not_nil(s)
    assert.are.equal("HERBICIDE", s.fillType)
  end)

  it("names the material a tankless applicator draws from the machine in front", function()
    -- A dribble bar / injector / disc harrow carries nothing of its own; the engine resolves the
    -- feeding vehicle's tank into workAreaParameters.sprayFillType. Without this fallback the
    -- implement reports no material at all while visibly applying slurry.
    -- Note `lastIsExternallyFilled` is NOT set here and must not be needed: it is gated on
    -- getIsAIActive() and means "a hired worker is being topped up by the game", so it reads false on
    -- exactly this rig. Taking the fallback is the signal.
    local dribbleBar = sprayer({
      isSlurryTanker = true,
      isFertilizerSprayer = false,
      workAreaParameters = { usagePerMin = 0, sprayFillType = FILL_TYPE.LIQUIDMANURE },
    }, { tanks = { [1] = FILL_TYPE.UNKNOWN } })

    local s = VDT.Spraying.collect(dribbleBar)
    assert.are.equal("LIQUIDMANURE", s.fillType)
    assert.are.equal("FERTILIZER", s.category)
    assert.is_true(s.externalSource)
  end)

  it("prefers its own tank over the resolved source, and flags nothing when self-fed", function()
    local own = sprayer({
      workAreaParameters = { usagePerMin = 0, sprayFillType = FILL_TYPE.LIQUIDMANURE },
    }, { tanks = { [1] = FILL_TYPE.FERTILIZER } })
    local s = VDT.Spraying.collect(own)
    assert.are.equal("FERTILIZER", s.fillType)
    assert.is_nil(s.externalSource)
  end)

  it("gives slurry precedence over manure on a tank that takes both", function()
    -- The engine's two flags are not mutually exclusive; PF's HUD resolves this the same way.
    local both = sprayer({ isSlurryTanker = true, isManureSpreader = true })
    assert.are.equal("SLURRY_TANKER", VDT.Spraying.collect(both).kind)
  end)

  it("classifies the kind from the same tank it reads the load from", function()
    -- A combination machine: unit 1 is seed, unit 2 is the fertilizer hopper. Classifying off unit 1
    -- would report the seed hopper's capability against the sprayer's load.
    local combo = sprayer({}, {
      sprayerFillUnitIndex = 2,
      accepts = { [1] = { [FILL_TYPE.WATER] = true }, [2] = { [FILL_TYPE.FERTILIZER] = true } },
      tanks = { [1] = FILL_TYPE.WATER, [2] = FILL_TYPE.FERTILIZER },
    })
    local s = VDT.Spraying.collect(combo)
    assert.are.equal("SOLID_FERTILIZER", s.kind)
    assert.are.equal("FERTILIZER", s.fillType)
  end)

  it("takes the doubled-amount availability from the getter's second return", function()
    -- The trap: a slurry tanker reports (false, false) — doubling is a fertilizer-only control, and
    -- reading the first value alone would offer a toggle the machine does not have.
    local allowed = VDT.Spraying.collect(sprayer({}, { doubled = true, doubledAllowed = true }))
    assert.is_true(allowed.doubledAmount)
    assert.is_true(allowed.doubledAmountAvailable)

    local tanker = VDT.Spraying.collect(sprayer({ isSlurryTanker = true }, { doubledAllowed = false }))
    assert.is_false(tanker.doubledAmount)
    assert.is_false(tanker.doubledAmountAvailable)
  end)

  it("reports spraying only while material is actually leaving the machine", function()
    assert.is_false(VDT.Spraying.collect(sprayer()).active)
    assert.is_true(VDT.Spraying.collect(sprayer({}, { active = true })).active)
  end)

  it("leaves the nominal usage out when the engine has not computed one", function()
    assert.is_nil(VDT.Spraying.collect(sprayer()).nominalUsagePerMin)
    local s = VDT.Spraying.collect(sprayer({ workAreaParameters = { usagePerMin = 42.375 } }))
    assert.are.equal(42.38, s.nominalUsagePerMin)
  end)
end)

describe("Plow.collect", function()
  -- `over` replaces spec fields; `opts` drives the stubbed predicates.
  local function plow(over, opts)
    opts = opts or {}
    local spec = {
      rotationMax = false,
      rotateLeftToMax = true,
      limitToField = true,
      forceLimitToField = false,
      rotationPart = { turnAnimation = "turnAnim" },
    }
    for k, v in pairs(over or {}) do
      spec[k] = v
    end
    return {
      spec_plow = spec,
      getIsPlowRotationAllowed = function()
        return opts.rotationAllowed ~= false
      end,
      getCanTogglePlowRotation = function()
        return opts.canToggle ~= false
      end,
      getPlowLimitToField = function()
        return spec.limitToField
      end,
      getPlowForceLimitToField = function()
        return spec.forceLimitToField
      end,
    }
  end

  it("returns nil when the object is not a plough", function()
    assert.is_nil(VDT.Plow.collect({}))
  end)

  it("maps the rotation bool to a side through the machine's own rotateLeftToMax", function()
    -- The whole trap in one test: the same rotationMax means opposite sides on two machines.
    assert.are.equal("LEFT", VDT.Plow.collect(plow({ rotationMax = true, rotateLeftToMax = true })).side)
    assert.are.equal("RIGHT", VDT.Plow.collect(plow({ rotationMax = false, rotateLeftToMax = true })).side)
    assert.are.equal("RIGHT", VDT.Plow.collect(plow({ rotationMax = true, rotateLeftToMax = false })).side)
    assert.are.equal("LEFT", VDT.Plow.collect(plow({ rotationMax = false, rotateLeftToMax = false })).side)
  end)

  it("reports no side for a plough that does not reverse", function()
    -- A rotationPart with no turn animation: the machine has the spec but nothing to turn.
    assert.is_nil(VDT.Plow.collect(plow({ rotationPart = {} })).side)

    -- And no rotationPart at all. Built inline rather than through the helper: `over` is applied with
    -- pairs(), which cannot carry a nil, so it can express "replace this key" but never "remove it".
    local bare = { spec_plow = { rotationMax = true, rotateLeftToMax = true, limitToField = true } }
    bare.getIsPlowRotationAllowed = function()
      return true
    end
    bare.getCanTogglePlowRotation = function()
      return true
    end
    bare.getPlowLimitToField = function()
      return true
    end
    bare.getPlowForceLimitToField = function()
      return false
    end
    assert.is_nil(VDT.Plow.collect(bare).side)
  end)

  it("keeps the two rotation predicates separate", function()
    -- Mid-fold: neither. Lowered but unfolded: mechanically allowed, still not toggleable.
    local folding = VDT.Plow.collect(plow({}, { rotationAllowed = false, canToggle = false }))
    assert.is_false(folding.rotationAllowed)
    assert.is_false(folding.canToggleRotation)

    local lowered = VDT.Plow.collect(plow({}, { rotationAllowed = true, canToggle = false }))
    assert.is_true(lowered.rotationAllowed)
    assert.is_false(lowered.canToggleRotation)
  end)

  it("reports the field limit and whether it is the player's to change", function()
    local free = VDT.Plow.collect(plow())
    assert.is_true(free.limitToField)
    assert.is_false(free.forceLimitToField)

    local forced = VDT.Plow.collect(plow({ forceLimitToField = true }))
    assert.is_true(forced.forceLimitToField)
  end)
end)

describe("Tillage.collect", function()
  local function tiller(over)
    local spec = { isSubsoiler = false, isPowerHarrow = false, useDeepMode = true, limitToField = true }
    for k, v in pairs(over or {}) do
      spec[k] = v
    end
    return { spec_cultivator = spec }
  end

  it("returns nil when the object does not till", function()
    assert.is_nil(VDT.Tillage.collect({}))
  end)

  it("separates the three machine kinds", function()
    assert.are.equal("CULTIVATOR", VDT.Tillage.collect(tiller()).kind)
    assert.are.equal("SUBSOILER", VDT.Tillage.collect(tiller({ isSubsoiler = true })).kind)
    assert.are.equal("POWER_HARROW", VDT.Tillage.collect(tiller({ isPowerHarrow = true })).kind)
  end)

  it("reports the depth mode and the field limit", function()
    local shallow = VDT.Tillage.collect(tiller({ useDeepMode = false, limitToField = false }))
    assert.is_false(shallow.deepMode)
    assert.is_false(shallow.limitToField)
  end)
end)
