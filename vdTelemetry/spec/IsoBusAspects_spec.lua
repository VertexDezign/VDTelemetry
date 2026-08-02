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

local FILL_TYPES = {
  [1] = { name = "UNKNOWN", title = "Unknown" },
  [5] = { name = "FERTILIZER", title = "Mineraldünger" },
  [8] = { name = "LIQUIDMANURE", title = "Gülle" },
  [11] = { name = "WATER", title = "Wasser" },
}

-- The *manager's* spray types — the ones that carry a name and a category. Keyed by fill type index.
-- WATER deliberately has none: a material the game registers no spray type for is a normal case.
local SPRAY_TYPES = {
  [5] = { name = "FERTILIZER", isFertilizer = true },
  [8] = { name = "LIQUIDMANURE", isFertilizer = true },
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
  end)

  after_each(function()
    rawset(_G, "MathUtil", nil)
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

  it("separates the three machine kinds", function()
    assert.are.equal("SPRAYER", VDT.Spraying.collect(sprayer()).kind)
    assert.are.equal("SLURRY_TANKER", VDT.Spraying.collect(sprayer({ isSlurryTanker = true })).kind)
    assert.are.equal("MANURE_SPREADER", VDT.Spraying.collect(sprayer({ isManureSpreader = true })).kind)
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
