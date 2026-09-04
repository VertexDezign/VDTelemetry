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
--     still reports it -- read over the engine's own 300 ms window, because the flag itself is
--     per-frame and the first real capture of a chopper mid-pass landed on a false frame;
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
-- Only what `canToggleSwath` reads: whether the crop drops a windrow. Wheat does, maize does not,
-- which is the split the engine's own straw toggle refuses on.
local FRUIT_TYPES = {
  [1] = { hasWindrow = false },
  [4] = { hasWindrow = true },
  [9] = { hasWindrow = false },
}
-- Fill type -> the fruit behind it, the lookup Combine's own action event makes off the tank.
local FRUIT_BY_FILL = { [4] = 4, [9] = 9 }
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
    fillUnitIndex = 1,
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
    getFillUnitFillType = function()
      return spec.tankFillType or 1
    end,
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
    lastAreaBiggerZeroTime = -1,
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
    getFruitTypeByIndex = function(_, index)
      return FRUIT_TYPES[index]
    end,
    getFruitTypeIndexByFillTypeIndex = function(_, fillTypeIndex)
      return FRUIT_BY_FILL[fillTypeIndex]
    end,
  }
  _G.g_fillTypeManager = {
    getFillTypeByIndex = function(_, index)
      return FILL_TYPES[index]
    end,
  }
  -- The mission clock the collecting window is measured against.
  _G.g_currentMission = { time = 100000 }
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

  describe("canToggleSwath", function()
    -- The engine's own verdict on the straw toggle, reproduced here rather than left to the app: the
    -- game binds TOGGLE_CHOPPER only on a machine with both halves, and its handler then refuses on a
    -- crop that drops no windrow.

    it("is true on a machine with both halves and an empty tank", function()
      -- Nothing in the tank names no fruit, and the engine lets the toggle through -- which is how a
      -- combine can be set up either way before the pass starts.
      assert.is_true(VDT.Harvest.collect(combine()).canToggleSwath)
    end)

    it("is true while the tank holds a crop that drops a windrow", function()
      assert.is_true(VDT.Harvest.collect(combine({ tankFillType = 4 })).canToggleSwath)
    end)

    it("is false while the tank holds a crop that drops none", function()
      -- Maize. The machine offers both, the key is bound, and pressing it shows a blinking warning
      -- and changes nothing -- so a terminal that offered the control here would be lying.
      assert.is_false(VDT.Harvest.collect(combine({ tankFillType = 9 })).canToggleSwath)
    end)

    it("is false on a machine missing either half", function()
      local noChopper = combine()
      noChopper.spec_combine.chopper = nil
      assert.is_false(VDT.Harvest.collect(noChopper).canToggleSwath)

      local noSwath = combine()
      noSwath.spec_combine.swath = { isAvailable = false }
      assert.is_false(VDT.Harvest.collect(noSwath).canToggleSwath)
    end)

    it("is false for anything that is not a combine", function()
      assert.is_false(VDT.Harvest.canToggleSwath({}))
    end)

    it("reads the BUFFER fill unit where the machine has one", function()
      -- A buffer combine threshes out of a different unit, and the engine's own lookup prefers it.
      -- Reading `fillUnitIndex` on such a machine would ask the wrong tank what the crop is.
      local object = combine({ bufferFillUnitIndex = 2, fillUnitIndex = 1 })
      local asked
      object.getFillUnitFillType = function(_, index)
        asked = index
        return 9
      end
      assert.is_false(VDT.Harvest.collect(object).canToggleSwath)
      assert.equals(2, asked)
    end)
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

  it("holds 'working' across the frames between crop, over the engine's own 300 ms window", function()
    -- The captured failure: the poll lands on a frame where the flag has been cleared again, 100 ms
    -- after the header last took crop. The engine calls that still collecting, and so must this.
    local recent = cutter({ lastAreaBiggerZero = false, lastAreaBiggerZeroTime = 99900 })
    assert.is_true(VDT.Cutter.collect(recent).working)

    local stale = cutter({ lastAreaBiggerZero = false, lastAreaBiggerZeroTime = 99600 })
    assert.is_false(VDT.Cutter.collect(stale).working)
  end)

  it("does not read a header that has never cut as collecting", function()
    -- lastAreaBiggerZeroTime starts at -1 and the mission clock starts near 0, so the bare window
    -- comparison is true for the first fraction of a second of every save.
    _G.g_currentMission = { time = 50 }
    assert.is_false(VDT.Cutter.collect(cutter()).working)
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
