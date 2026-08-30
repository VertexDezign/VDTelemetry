-- Unit tests for the two harvest-side aspect collectors:
-- src/collect/aspects/{Harvest,Cutter}.lua.
--
-- Run with `busted` from the vdTelemetry/ directory. Both are field reads of their spec table plus a
-- type lookup, so the objects below are plain tables shaped like the engine's, with the two type
-- managers stubbed from tables (as in Sowing_spec / FillUnit_spec).
--
-- What is actually worth testing here is the multiplayer shape of the two collectors, because that is
-- what decided which field each one reads:
--   * the header's `working` comes from `lastAreaBiggerZero` (streamed) and NOT from `isWorking`
--     (written only inside server-side work-area processing), so a client that sees crop being taken
--     still reports it;
--   * `load` is exported only on the server, and absent -- not zero -- everywhere else;
--   * the combine's session hectares are a difference the collector takes, not a field it reads.

if ValueMapper == nil then
  dofile("src/mapper/ValueMapper.lua")
end
for name, file in pairs({
  Harvest = "src/collect/aspects/Harvest.lua",
  Cutter = "src/collect/aspects/Cutter.lua",
}) do
  if VDT == nil or VDT[name] == nil then
    dofile(file)
  end
end

-- Index 1 is the engine's UNKNOWN sentinel in both managers.
local FRUIT_NAMES = { [1] = "UNKNOWN", [4] = "WHEAT", [9] = "MAIZE" }
local FILL_TYPES = {
  [1] = { name = "UNKNOWN", title = "Unbekannt" },
  [4] = { name = "WHEAT", title = "Weizen" },
  [7] = { name = "CHAFF", title = "Häcksel" },
  [11] = { name = "STRAW", title = "Stroh" },
}

---A combine with sane engine defaults; `over` replaces any spec field, `extra` the object's own.
local function combine(over, extra)
  local spec = {
    isSwathActive = false,
    isFilling = false,
    isBufferCombine = false,
    workedHectars = 0,
    workedHectarsInitial = 0,
    lastValidInputFruitType = 1,
    swath = { isAvailable = true },
    chopper = { isAvailable = true },
  }
  for k, v in pairs(over or {}) do
    spec[k] = v
  end

  local object = {
    spec_combine = spec,
    getCombineLastValidFillType = function()
      return spec.lastValidInputFillType or 1
    end,
    getIsThreshingDuringRain = function(_, earlyWarning)
      if earlyWarning then
        return spec.rainEarly == true
      end
      return spec.rainNow == true
    end,
  }
  for k, v in pairs(extra or {}) do
    object[k] = v
  end
  return object
end

---A header with sane engine defaults.
local function cutter(over, extra)
  local spec = {
    lastAreaBiggerZero = false,
    useWindrow = false,
    allowCuttingWhileRaised = false,
    currentInputFruitType = 1,
    currentOutputFillType = 1,
    currentInputFillType = 1,
    strawRatio = 1,
    cutterLoad = 0,
    isWorking = false,
  }
  for k, v in pairs(over or {}) do
    spec[k] = v
  end

  local object = { spec_cutter = spec }
  for k, v in pairs(extra or {}) do
    object[k] = v
  end
  return object
end

local function stubManagers()
  rawset(_G, "MathUtil", {
    round = function(v, decimals)
      local mult = 10 ^ (decimals or 0)
      return math.floor(v * mult + 0.5) / mult
    end,
  })
  _G.g_fruitTypeManager = {
    getFruitTypeNameByIndex = function(_, index)
      return FRUIT_NAMES[index]
    end,
  }
  _G.g_fillTypeManager = {
    getFillTypeByIndex = function(_, index)
      return FILL_TYPES[index]
    end,
  }
end

describe("Harvest.collect", function()
  before_each(stubManagers)

  it("returns nil for anything that is not a combine", function()
    assert.is_nil(VDT.Harvest.collect({}))
  end)

  it("reports the straw choice and whether the machine offers it", function()
    -- A machine with a swath but no chopper: the availability flags are what tells a terminal
    -- whether to draw a toggle, and an absent chopper table is how the engine says there is none.
    local object = combine({ isSwathActive = true })
    object.spec_combine.chopper = nil

    local model = VDT.Harvest.collect(object)
    assert.is_true(model.swathActive)
    assert.is_true(model.swathAvailable)
    assert.is_nil(model.chopperAvailable)
  end)

  it("reports crop flowing into the tank separately from the machine running", function()
    assert.is_true(VDT.Harvest.collect(combine({ isFilling = true })).filling)
    assert.is_false(VDT.Harvest.collect(combine()).filling)
  end)

  it("takes the session hectares as a difference rather than reading a field", function()
    local model = VDT.Harvest.collect(combine({ workedHectars = 12.5, workedHectarsInitial = 4.25 }))
    assert.are.equal(12.5, model.hectares)
    assert.are.equal(8.25, model.hectaresSession)
  end)

  it("names the crop going in and the material reaching the tank", function()
    local model = VDT.Harvest.collect(combine({ lastValidInputFruitType = 9, lastValidInputFillType = 7 }))
    assert.are.equal("MAIZE", model.fruitType)
    assert.are.equal("CHAFF", model.fillType)
    assert.are.equal("Häcksel", model.title)
  end)

  it("leaves both types out while the machine has threshed nothing", function()
    local model = VDT.Harvest.collect(combine())
    assert.is_nil(model.fruitType)
    assert.is_nil(model.fillType)
    assert.is_nil(model.title)
  end)

  it("asks the engine for the rain stop and the earlier warning separately", function()
    local model = VDT.Harvest.collect(combine({ rainNow = false, rainEarly = true }))
    assert.is_false(model.rainBlocked)
    assert.is_true(model.rainWarning)
  end)
end)

describe("Cutter.collect", function()
  before_each(stubManagers)

  it("returns nil for anything that is not a header", function()
    assert.is_nil(VDT.Cutter.collect({}))
  end)

  it("reads 'is it taking crop' from the streamed flag, not from the server-only one", function()
    -- The shape a multiplayer client sees: work-area processing never ran there, so isWorking is
    -- false while the streamed area flag says the header is cutting.
    local model = VDT.Cutter.collect(cutter({ lastAreaBiggerZero = true, isWorking = false }))
    assert.is_true(model.working)
    assert.is_false(VDT.Cutter.collect(cutter({ lastAreaBiggerZero = false, isWorking = true })).working)
  end)

  it("exports the header load on the server and leaves it absent on a client", function()
    assert.are.equal(0.62, VDT.Cutter.collect(cutter({ cutterLoad = 0.618 }, { isServer = true })).load)
    assert.is_nil(VDT.Cutter.collect(cutter({ cutterLoad = 0.618 })).load)
  end)

  it("names what a converting header hands on beside the crop it cuts", function()
    local model = VDT.Cutter.collect(cutter({ currentInputFruitType = 9, currentOutputFillType = 7 }))
    assert.are.equal("MAIZE", model.fruitType)
    assert.are.equal("CHAFF", model.fillType)
    assert.are.equal("Häcksel", model.title)
    assert.is_nil(model.inputFillType)
  end)

  it("names the material a windrow pickup lifts, since it has no standing crop", function()
    local model = VDT.Cutter.collect(cutter({ useWindrow = true, currentInputFillType = 11 }))
    assert.is_true(model.windrow)
    assert.are.equal("STRAW", model.inputFillType)
  end)

  it("leaves the crop out over bare ground", function()
    assert.is_nil(VDT.Cutter.collect(cutter()).fruitType)
  end)
end)
