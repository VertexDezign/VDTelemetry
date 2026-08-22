-- Unit tests for the mixer wagon aspect and the mass aspect (issue #113):
-- src/collect/aspects/{Mixer,Mass}.lua.
--
-- Run with `busted` from the vdTelemetry/ directory. The mixer reads its spec table and calls back
-- into the object for the tub's level/capacity/type and for the two "is anything turning" questions,
-- so those are stubbed on the fake object below, as is g_fillTypeManager and the map's food system.
--
-- What is worth pinning here is the handful of places where the obvious reading is the wrong one:
--   * "running" is NOT the isTurnedOn aspect -- on a mixer wagon turn-on is the pickup, and the drum
--     also turns while discharging and for a while after the last thing went in;
--   * `activeTimer` runs negative on a machine left switched on, so a remaining time is its positive
--     part only;
--   * an ingredient's fill types are a SET, so the exported order has to be imposed, not observed;
--   * an ingredient pooling several materials gets no weight, because there is no honest one;
--   * a mixer with no recipe is still a mixer.

if ValueMapper == nil then
  dofile("src/mapper/ValueMapper.lua")
end
for name, file in pairs({
  Mixer = "src/collect/aspects/Mixer.lua",
  Mass = "src/collect/aspects/Mass.lua",
}) do
  if VDT == nil or VDT[name] == nil then
    dofile(file)
  end
end

local FILL_TYPE = {
  UNKNOWN = 1,
  FORAGE = 30,
  FORAGE_MIXING = 31,
  SILAGE = 32,
  HAY = 33,
  STRAW = 34,
  MINERAL_FEED = 35,
}

-- massPerLiter as the engine stores it: scaled by FillTypeManager.MASS_SCALE (1000 below), so a
-- SILAGE record of 0.4 means 0.0004 t/l.
local FILL_TYPES = {
  [1] = { name = "UNKNOWN", title = "Unknown", massPerLiter = 0 },
  [30] = { name = "FORAGE", title = "Mischration", massPerLiter = 0.4 },
  [31] = { name = "FORAGE_MIXING", title = "Mischvorgang", massPerLiter = 0.4 },
  [32] = { name = "SILAGE", title = "Silage", massPerLiter = 0.4 },
  [33] = { name = "HAY", title = "Heu", massPerLiter = 0.25 },
  [34] = { name = "STRAW", title = "Stroh", massPerLiter = 0.15 },
  [35] = { name = "MINERAL_FEED", title = "Mineralfutter", massPerLiter = 0.5 },
}

-- The map's FORAGE recipe, shaped like AnimalFoodSystem:loadRecipe leaves it. Two of its ingredients
-- accept one material each and the third pools two, which is the case that must not be weighed.
local RECIPE = {
  fillType = FILL_TYPE.FORAGE,
  ingredients = {
    { name = "SILAGE", title = "Grundfutter", minPercentage = 0.4, maxPercentage = 0.75 },
    { name = "HAY", title = "Raufutter", minPercentage = 0.2, maxPercentage = 0.4 },
    { name = "MINERALS", title = "Kraftfutter", minPercentage = 0.05, maxPercentage = 0.15 },
  },
}

-- What MixerWagon:onLoad copies out of the recipe: the name, the window, the ratio and a live level,
-- but neither the title nor the recipe's own fill type.
local function entries(levels)
  return {
    {
      name = "SILAGE",
      fillTypes = { [FILL_TYPE.SILAGE] = true },
      minPercentage = 0.4,
      maxPercentage = 0.75,
      fillLevel = (levels or {})[1] or 0,
    },
    {
      name = "HAY",
      -- Pools two materials: one litre count, no record of which of them went in.
      fillTypes = { [FILL_TYPE.STRAW] = true, [FILL_TYPE.HAY] = true },
      minPercentage = 0.2,
      maxPercentage = 0.4,
      fillLevel = (levels or {})[2] or 0,
    },
    {
      name = "MINERALS",
      fillTypes = { [FILL_TYPE.MINERAL_FEED] = true },
      minPercentage = 0.05,
      maxPercentage = 0.15,
      fillLevel = (levels or {})[3] or 0,
    },
  }
end

---A mixer wagon; `over` replaces any of the spec defaults, `object` any of the object's answers.
local function mixer(over, object)
  local spec = {
    fillUnitIndex = 1,
    activeTimer = 0,
    activeTimerMax = 5000,
    mixerWagonFillTypes = entries(),
  }
  for k, v in pairs(over or {}) do
    spec[k] = v
  end

  local levels = 0
  for _, entry in ipairs(spec.mixerWagonFillTypes) do
    levels = levels + entry.fillLevel
  end

  local self = {
    spec_mixerWagon = spec,
    spec_trailer = { tipState = 0 },
    getIsPowered = function()
      -- Two return values, as the engine's has (isPowered, warning): a collector taking both would
      -- assign the warning somewhere.
      return true, nil
    end,
    getIsTurnedOn = function()
      return false
    end,
    getFillUnitFillLevel = function()
      return levels
    end,
    getFillUnitCapacity = function()
      return 12000
    end,
    getFillUnitFillType = function()
      return FILL_TYPE.UNKNOWN
    end,
  }
  for k, v in pairs(object or {}) do
    self[k] = v
  end
  return self
end

describe("Mixer.collect", function()
  before_each(function()
    rawset(_G, "MathUtil", {
      round = function(v, decimals)
        local mult = 10 ^ (decimals or 0)
        return math.floor(v * mult + 0.5) / mult
      end,
    })
    rawset(_G, "FillTypeManager", { MASS_SCALE = 1000 })
    _G.g_fillTypeManager = {
      getFillTypeByIndex = function(_, index)
        return FILL_TYPES[index]
      end,
    }
    _G.g_currentMission = { animalFoodSystem = { recipes = { RECIPE } } }
  end)

  after_each(function()
    _G.g_fillTypeManager = nil
    _G.g_currentMission = nil
    rawset(_G, "FillTypeManager", nil)
    rawset(_G, "MathUtil", nil)
  end)

  it("returns nil for anything that is not a mixer wagon", function()
    assert.is_nil(VDT.Mixer.collect({}))
  end)

  it("reports the tub and the machine's mixing time", function()
    local model = VDT.Mixer.collect(mixer({ mixerWagonFillTypes = entries({ 6000, 2400, 900 }) }))
    assert.are.equal(9300, model.value)
    assert.are.equal(12000, model.capacity)
    assert.are.equal(5000, model.mixingTime)
  end)

  describe("running", function()
    it("is false on a parked, mixed, switched-off machine", function()
      local model = VDT.Mixer.collect(mixer())
      assert.is_false(model.running)
      assert.is_true(model.powered)
    end)

    it("is true while the mix cycle is still counting down", function()
      assert.is_true(VDT.Mixer.collect(mixer({ activeTimer = 3200 })).running)
    end)

    it("is true while the pickup is switched on, which is what isTurnedOn means here", function()
      local model = VDT.Mixer.collect(mixer(nil, {
        getIsTurnedOn = function()
          return true
        end,
      }))
      assert.is_true(model.running)
    end)

    it("is true while discharging, with nothing switched on and no cycle left", function()
      local object = mixer()
      object.spec_trailer.tipState = 2 -- Trailer.TIPSTATE_OPEN
      assert.is_true(VDT.Mixer.collect(object).running)
    end)

    it("is false without power, whatever else is on", function()
      local model = VDT.Mixer.collect(mixer({ activeTimer = 3200 }, {
        getIsPowered = function()
          return false, "attach to a tractor"
        end,
        getIsTurnedOn = function()
          return true
        end,
      }))
      assert.is_false(model.running)
      assert.is_false(model.powered)
    end)
  end)

  describe("remaining", function()
    it("counts the mix cycle down", function()
      assert.are.equal(3200, VDT.Mixer.collect(mixer({ activeTimer = 3200 })).remaining)
    end)

    it("floors at zero -- the engine never clamps the timer it decrements", function()
      -- A machine left switched on runs activeTimer far below zero; a negative remaining time is not
      -- a thing a panel can print.
      assert.are.equal(0, VDT.Mixer.collect(mixer({ activeTimer = -184000 })).remaining)
    end)
  end)

  describe("fillType", function()
    it("is absent on an empty tub rather than reported as UNKNOWN", function()
      local model = VDT.Mixer.collect(mixer())
      assert.is_nil(model.fillType)
      assert.is_nil(model.title)
    end)

    it("is the engine's own verdict on the mix, not something we re-derive", function()
      -- Deliberately a ratio that is INSIDE every window while the engine says FORAGE_MIXING. Only
      -- the engine gets to decide, so the collector must report what it was told.
      local model = VDT.Mixer.collect(mixer({ mixerWagonFillTypes = entries({ 6000, 2400, 900 }) }, {
        getFillUnitFillType = function()
          return FILL_TYPE.FORAGE_MIXING
        end,
      }))
      assert.are.equal("FORAGE_MIXING", model.fillType)
      assert.are.equal("Mischvorgang", model.title)
    end)
  end)

  describe("the recipe", function()
    it("names the fill type the finished mix becomes", function()
      assert.are.equal("FORAGE", VDT.Mixer.collect(mixer()).recipe)
    end)

    it("is absent when the map defines no matching one, and the aspect survives", function()
      _G.g_currentMission = { animalFoodSystem = { recipes = {} } }
      local model = VDT.Mixer.collect(mixer())
      assert.is_nil(model.recipe)
      assert.are.equal(3, #model.ingredients)
      -- Without the recipe the authored labels are gone, so the material's own title carries the bar.
      assert.are.equal("Silage", model.ingredients[1].title)
    end)
  end)

  describe("ingredients", function()
    it("carries one entry per recipe ingredient, in the recipe's order", function()
      local model = VDT.Mixer.collect(mixer({ mixerWagonFillTypes = entries({ 6000, 2400, 900 }) }))
      assert.are.equal(3, #model.ingredients)
      assert.are.same({ "SILAGE", "HAY", "MINERALS" }, {
        model.ingredients[1].name,
        model.ingredients[2].name,
        model.ingredients[3].name,
      })
    end)

    it("labels a bar with the recipe's authored title, not the material's", function()
      local model = VDT.Mixer.collect(mixer())
      assert.are.equal("Grundfutter", model.ingredients[1].title)
      assert.are.equal("Kraftfutter", model.ingredients[3].title)
    end)

    it("exports litres, leaving the share to whoever draws the bar", function()
      local model = VDT.Mixer.collect(mixer({ mixerWagonFillTypes = entries({ 6000, 2400, 900 }) }))
      assert.are.equal(6000, model.ingredients[1].value)
      assert.are.equal(2400, model.ingredients[2].value)
      assert.are.equal(900, model.ingredients[3].value)
    end)

    it("exports the window as whole percentages", function()
      local model = VDT.Mixer.collect(mixer())
      assert.are.equal(40, model.ingredients[1].minPercentage)
      assert.are.equal(75, model.ingredients[1].maxPercentage)
      assert.are.equal(5, model.ingredients[3].minPercentage)
      assert.are.equal(15, model.ingredients[3].maxPercentage)
    end)

    it("orders the accepted materials by fill type index, not by set iteration", function()
      -- HAY(33) and STRAW(34) live in a set, whose iteration order Lua does not define. The list has
      -- to come out the same way every export or the first entry -- which is what a fallback label
      -- and a weight would be read off -- is a coin flip.
      local model = VDT.Mixer.collect(mixer())
      assert.are.same({ "SILAGE" }, model.ingredients[1].fillTypes)
      assert.are.same({ "HAY", "STRAW" }, model.ingredients[2].fillTypes)
    end)

    it("weighs an ingredient that pools a single material", function()
      local model = VDT.Mixer.collect(mixer({ mixerWagonFillTypes = entries({ 6000, 2400, 900 }) }))
      -- 6000 l at 0.4/1000 t/l
      assert.are.equal(2.4, model.ingredients[1].mass)
      assert.are.equal(0.45, model.ingredients[3].mass)
    end)

    it("leaves an ingredient pooling several materials unweighed", function()
      -- One litre count, two densities and no record of which went in: any weight here would be a
      -- guess dressed as a measurement.
      local model = VDT.Mixer.collect(mixer({ mixerWagonFillTypes = entries({ 6000, 2400, 900 }) }))
      assert.is_nil(model.ingredients[2].mass)
    end)
  end)

  describe("the tub's own weight", function()
    it("weighs the load with the density of what the tub reports", function()
      -- The same arithmetic FillUnit:getAdditionalComponentMass does for this unit: level x density
      -- of the *mix*. Deliberately not the sum of the ingredients' masses, which is short by whatever
      -- the pooled ones weigh.
      local model = VDT.Mixer.collect(mixer({ mixerWagonFillTypes = entries({ 6000, 2400, 900 }) }, {
        getFillUnitFillType = function()
          return FILL_TYPE.FORAGE
        end,
      }))
      -- 9300 l at 0.4/1000 t/l
      assert.are.equal(3.72, model.mass)
    end)

    it("reads zero on an empty tub rather than going absent", function()
      -- The number a panel prints has to reach zero. A machine's mass minus its empty mass does not:
      -- that difference carries the fuel and everything else the engine adds, and an empty wagon read
      -- 617 kg of "load" in a real game.
      local model = VDT.Mixer.collect(mixer())
      assert.are.equal(0, model.mass)
    end)

    it("is absent when the material has no density to read", function()
      local model = VDT.Mixer.collect(mixer({ mixerWagonFillTypes = entries({ 6000, 0, 0 }) }, {
        getFillUnitFillType = function()
          return 99 -- a fill type the manager does not know
        end,
      }))
      assert.is_nil(model.mass)
    end)
  end)

  it("still reports a mixer whose XML names no recipe", function()
    -- No recipe means an empty mixerWagonFillTypes and a machine that takes any one material -- a
    -- trailer with a drum. The tub, the drum and the mixing time are all still true of it.
    local model = VDT.Mixer.collect(mixer({ mixerWagonFillTypes = {} }))
    assert.is_not_nil(model)
    assert.is_nil(model.ingredients)
    assert.are.equal(12000, model.capacity)
    assert.are.equal(5000, model.mixingTime)
  end)
end)

describe("Mass.collect", function()
  before_each(function()
    rawset(_G, "MathUtil", {
      round = function(v, decimals)
        local mult = 10 ^ (decimals or 0)
        return math.floor(v * mult + 0.5) / mult
      end,
    })
  end)

  after_each(function()
    rawset(_G, "MathUtil", nil)
  end)

  it("returns nil for something that reports no mass at all", function()
    assert.is_nil(VDT.Mass.collect({}))
  end)

  it("reports the live mass and the empty one, so the payload is their difference", function()
    local model = VDT.Mass.collect({
      getTotalMass = function()
        return 12.75
      end,
      getDefaultMass = function()
        return 7.2
      end,
    })
    assert.are.equal(12.75, model.value)
    assert.are.equal(7.2, model.empty)
  end)

  it("omits the empty mass before the engine's first mass update has run", function()
    -- getDefaultMass reads `component.defaultMass or 0` until updateMass has filled it in. Reporting
    -- that zero would make the whole machine look like payload.
    local model = VDT.Mass.collect({
      getTotalMass = function()
        return 7.2
      end,
      getDefaultMass = function()
        return 0
      end,
    })
    assert.are.equal(7.2, model.value)
    assert.is_nil(model.empty)
  end)

  it("returns nil rather than a nonsense mass", function()
    assert.is_nil(VDT.Mass.collect({
      getTotalMass = function()
        return 0
      end,
    }))
    assert.is_nil(VDT.Mass.collect({
      getTotalMass = function()
        return 0 / 0
      end,
    }))
  end)
end)
