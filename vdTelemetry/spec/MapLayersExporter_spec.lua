-- Unit tests for the ground-layer export channel (src/collect/MapLayersExporter.lua): row encoding,
-- growth/soil classification against a stubbed fruit desc and ground system, and the sweep/tick
-- lifecycle (budget, pause, export gating, error containment) against a shrunk grid. Whether the real
-- engine objects still look like these stubs is what the in-game smoke test covers.
--
-- Run with `busted` from the vdTelemetry/ directory. The exporter self-registers a channel and reuses
-- VDT.MapExporter's normalization helpers, so both dependencies load first.

if VDT == nil or VDT.ExportChannels == nil then
  dofile("src/export/ExportChannels.lua")
end
if VDT.MapExporter == nil then
  dofile("src/collect/MapExporter.lua")
end
dofile("src/collect/MapLayersExporter.lua")

local function stubDebugger()
  return {
    error = function() end,
    info = function() end,
    trace = function() end,
  }
end

describe("MapLayers.encodeRow", function()
  it("returns an empty string for an all-zero row", function()
    assert.are.equal("", VDT.MapLayers.encodeRow({ 0, 0, 0 }, 3))
  end)

  it("right-trims trailing zero cells", function()
    assert.are.equal("0102", VDT.MapLayers.encodeRow({ 1, 2, 0, 0 }, 4))
  end)

  it("keeps interior zero cells, encoding up to the last non-zero one", function()
    assert.are.equal("01000203", VDT.MapLayers.encodeRow({ 1, 0, 2, 3 }, 4))
  end)

  it("encodes 0xff", function()
    assert.are.equal("ff01", VDT.MapLayers.encodeRow({ 255, 1 }, 2))
  end)
end)

describe("MapLayers.decodeRow", function()
  it("round-trips with encodeRow, keeping interior zeros", function()
    local buf = {}
    VDT.MapLayers.decodeRow("01000203", 4, buf)
    assert.are.same({ 1, 0, 2, 3 }, buf)
  end)

  it("zero-pads the trailing cells encodeRow trimmed", function()
    local buf = {}
    VDT.MapLayers.decodeRow("", 3, buf)
    assert.are.same({ 0, 0, 0 }, buf)
    VDT.MapLayers.decodeRow("ff", 3, buf)
    assert.are.same({ 255, 0, 0 }, buf)
  end)
end)

describe("MapLayers.classifyCell growth", function()
  local desc

  local function ctx(overrides)
    local c = {
      dataPlaneId = 1,
      fieldGroundSystem = {
        getValueAtWorldPos = function()
          return 0
        end,
      },
      cultivatedValue = 3,
      plowedValue = 5,
      stubbleTillageValue = 2,
      seedbedValue = 4,
      rolledSeedbedValue = 6,
      plowingRequiredEnabled = false,
      limeRequired = false,
      maxSprayLevel = 0,
      weedAvailable = false,
      stoneAvailable = false,
      weedTitle = "Weeds",
      stoneTitle = "Stones",
      cropsSeen = {},
      growthSeen = {},
      soilSeen = {},
    }
    for k, v in pairs(overrides or {}) do
      c[k] = v
    end
    return c
  end

  before_each(function()
    desc = {
      index = 5,
      shownOnMap = true,
      name = "WHEAT",
      fillType = { title = "Wheat" },
      defaultMapColor = {
        unpack = function()
          return 0.5, 0.4, 0.1
        end,
      },
      harvestTransitions = { [7] = 8 }, -- last harvest state (7) transitions to cut state 8
      witheredState = 9,
      minHarvestingGrowthState = 6,
      maxHarvestingGrowthState = 7,
      minPreparingGrowthState = -1,
      maxPreparingGrowthState = -1,
      getGrowthStateByDensityState = function(_, state)
        return state
      end,
    }
    rawset(_G, "FieldDensityMap", { GROUND_TYPE = 1, SPRAY_LEVEL = 4, LIME_LEVEL = 5, PLOW_LEVEL = 6 })
    rawset(_G, "g_fruitTypeManager", {
      getFruitTypeByDensityTypeIndex = function()
        return desc
      end,
    })
    rawset(_G, "getDensityTypeIndexAtWorldPos", function()
      return 1
    end)
  end)

  after_each(function()
    rawset(_G, "FieldDensityMap", nil)
    rawset(_G, "g_fruitTypeManager", nil)
    rawset(_G, "getDensityTypeIndexAtWorldPos", nil)
    rawset(_G, "getDensityStatesAtWorldPos", nil)
  end)

  it("classifies a harvest-ready state and exports the fruitTypeIndex as crops", function()
    rawset(_G, "getDensityStatesAtWorldPos", function()
      return 6
    end)
    local cropsV, growthV = VDT.MapLayers.classifyCell(ctx(), 0, 0)
    assert.are.equal(5, cropsV)
    assert.are.equal(21, growthV) -- GROWTH_HARVEST
  end)

  it("classifies the withered state", function()
    rawset(_G, "getDensityStatesAtWorldPos", function()
      return 9
    end)
    local _, growthV = VDT.MapLayers.classifyCell(ctx(), 0, 0)
    assert.are.equal(23, growthV) -- GROWTH_WITHERED
  end)

  it("classifies a harvestTransitions target state as cut", function()
    rawset(_G, "getDensityStatesAtWorldPos", function()
      return 8
    end)
    local _, growthV = VDT.MapLayers.classifyCell(ctx(), 0, 0)
    assert.are.equal(22, growthV) -- GROWTH_CUT
  end)

  it("classifies a growing gradient step within 10..17", function()
    rawset(_G, "getDensityStatesAtWorldPos", function()
      return 3
    end) -- growing range is 1..5 here (minHarvestingGrowthState - 1)
    local _, growthV = VDT.MapLayers.classifyCell(ctx(), 0, 0)
    assert.is_true(growthV >= 10 and growthV <= 17)
  end)

  it("classifies a topping state when a preparing range is defined", function()
    desc.minPreparingGrowthState = 4
    desc.maxPreparingGrowthState = 5
    rawset(_G, "getDensityStatesAtWorldPos", function()
      return 4
    end)
    local _, growthV = VDT.MapLayers.classifyCell(ctx(), 0, 0)
    assert.are.equal(20, growthV) -- GROWTH_TOPPING
  end)

  it("resolves each fruit density-type once per ctx, memoized across cells", function()
    local calls = 0
    rawset(_G, "g_fruitTypeManager", {
      getFruitTypeByDensityTypeIndex = function()
        calls = calls + 1
        return desc
      end,
    })
    rawset(_G, "getDensityStatesAtWorldPos", function()
      return 6
    end)
    -- All three cells read the same density-type index (the stub returns 1), so the fruit is resolved
    -- once and reused -- not re-fetched from the manager per cell.
    local c = ctx()
    VDT.MapLayers.classifyCell(c, 0, 0)
    VDT.MapLayers.classifyCell(c, 10, 10)
    VDT.MapLayers.classifyCell(c, 20, 20)
    assert.are.equal(1, calls)
  end)

  it("falls back to ground-type classification when the fruit isn't shownOnMap", function()
    desc.shownOnMap = false
    rawset(_G, "getDensityStatesAtWorldPos", function()
      return 6
    end)
    local c = ctx({
      fieldGroundSystem = {
        getValueAtWorldPos = function(_, densityType)
          if densityType == FieldDensityMap.GROUND_TYPE then
            return 3 -- ctx.cultivatedValue
          end
          return 0
        end,
      },
    })
    local cropsV, growthV = VDT.MapLayers.classifyCell(c, 0, 0)
    assert.are.equal(0, cropsV)
    assert.are.equal(1, growthV) -- GROWTH_CULTIVATED
  end)
end)

describe("MapLayers.classifyCell soil", function()
  local function ctx(overrides)
    local c = {
      dataPlaneId = nil, -- no fruit plane: crops/growth aren't under test here
      fieldGroundSystem = {
        getValueAtWorldPos = function(_, densityType)
          if densityType == FieldDensityMap.GROUND_TYPE then
            return 3 -- on-field
          elseif densityType == FieldDensityMap.PLOW_LEVEL then
            return 0
          elseif densityType == FieldDensityMap.LIME_LEVEL then
            return 0
          elseif densityType == FieldDensityMap.SPRAY_LEVEL then
            return 2
          end
          return 0
        end,
      },
      cultivatedValue = 3,
      plowedValue = 5,
      stubbleTillageValue = 2,
      seedbedValue = 4,
      rolledSeedbedValue = 6,
      plowingRequiredEnabled = true,
      limeRequired = true,
      maxSprayLevel = 3,
      weedAvailable = false,
      stoneAvailable = false,
      weedTitle = "Weeds",
      stoneTitle = "Stones",
      cropsSeen = {},
      growthSeen = {},
      soilSeen = {},
    }
    for k, v in pairs(overrides or {}) do
      c[k] = v
    end
    return c
  end

  before_each(function()
    rawset(_G, "FieldDensityMap", { GROUND_TYPE = 1, SPRAY_LEVEL = 4, LIME_LEVEL = 5, PLOW_LEVEL = 6 })
  end)

  after_each(function()
    rawset(_G, "FieldDensityMap", nil)
  end)

  it("prioritizes weeds over stones/plowing/lime/fertilized", function()
    local c = ctx({
      weedAvailable = true,
      weedSystem = {
        getWeedStateAtWorldPos = function()
          return 2
        end,
      },
      weedStateToGroup = { [2] = 1 },
    })
    local _, _, soilV = VDT.MapLayers.classifyCell(c, 0, 0)
    assert.are.equal(1, soilV) -- SOIL_WEED_BASE + group(1) - 1
  end)

  it("prioritizes stones over plowing/lime/fertilized", function()
    local c = ctx({
      stoneAvailable = true,
      stoneSystem = {
        getStoneStateAtWorldPos = function()
          return 4
        end,
      },
      stoneStateToGroup = { [4] = 2 },
    })
    local _, _, soilV = VDT.MapLayers.classifyCell(c, 0, 0)
    assert.are.equal(11, soilV) -- SOIL_STONE_BASE + group(2) - 1
  end)

  it("flags needs-plowing when the plow level is 0 and the setting is on", function()
    local _, _, soilV = VDT.MapLayers.classifyCell(ctx(), 0, 0)
    assert.are.equal(20, soilV) -- SOIL_NEEDS_PLOWING
  end)

  it("flags needs-lime once plowing is satisfied but lime isn't", function()
    local c = ctx({
      fieldGroundSystem = {
        getValueAtWorldPos = function(_, densityType)
          if densityType == FieldDensityMap.GROUND_TYPE then
            return 3
          elseif densityType == FieldDensityMap.PLOW_LEVEL then
            return 5
          elseif densityType == FieldDensityMap.LIME_LEVEL then
            return 0
          elseif densityType == FieldDensityMap.SPRAY_LEVEL then
            return 2
          end
          return 0
        end,
      },
    })
    local _, _, soilV = VDT.MapLayers.classifyCell(c, 0, 0)
    assert.are.equal(21, soilV) -- SOIL_NEEDS_LIME
  end)

  it("falls back to the fertilized level once plowing and lime are satisfied", function()
    local c = ctx({
      fieldGroundSystem = {
        getValueAtWorldPos = function(_, densityType)
          if densityType == FieldDensityMap.GROUND_TYPE then
            return 3
          elseif densityType == FieldDensityMap.PLOW_LEVEL then
            return 5
          elseif densityType == FieldDensityMap.LIME_LEVEL then
            return 2
          elseif densityType == FieldDensityMap.SPRAY_LEVEL then
            return 2
          end
          return 0
        end,
      },
    })
    local _, _, soilV = VDT.MapLayers.classifyCell(c, 0, 0)
    assert.are.equal(32, soilV) -- SOIL_FERTILIZED_BASE + level(2)
  end)

  it("is none when off-field, even if plow/lime levels happen to read 0", function()
    local c = ctx({
      fieldGroundSystem = {
        getValueAtWorldPos = function(_, densityType)
          if densityType == FieldDensityMap.GROUND_TYPE then
            return 0 -- off-field
          end
          return 0
        end,
      },
    })
    local _, _, soilV = VDT.MapLayers.classifyCell(c, 0, 0)
    assert.are.equal(0, soilV)
  end)

  it("skips every soil density read off-field, including weeds/stones", function()
    local soilReads = 0
    local c = ctx({
      fieldGroundSystem = {
        getValueAtWorldPos = function(_, densityType)
          if densityType == FieldDensityMap.GROUND_TYPE then
            return 0 -- off-field: classifySoil must bail before any other read
          end
          soilReads = soilReads + 1
          return 2
        end,
      },
      weedAvailable = true,
      weedSystem = {
        getWeedStateAtWorldPos = function()
          soilReads = soilReads + 1
          return 2 -- would classify as a weed if it were ever consulted off-field
        end,
      },
      weedStateToGroup = { [2] = 1 },
    })
    local _, _, soilV = VDT.MapLayers.classifyCell(c, 0, 0)
    assert.are.equal(0, soilV)
    assert.are.equal(0, soilReads)
  end)
end)

describe("MapLayers.tick sweep", function()
  local markDirtyOrig, marked

  before_each(function()
    VDT.MapLayers.GRID_SIZE = 8
    VDT.MapLayers.CELLS_PER_FRAME = 16
    VDT.MapLayers.sweep = nil
    VDT.MapLayers.dirty = true
    VDT.MapLayers.subscribed = false
    VDT.MapLayers.model = nil
    VDT.MapLayers.patchCtx = nil
    VDT.MapLayers.patchTimerMs = 0

    marked = 0
    markDirtyOrig = VDT.ExportChannels.markDirty
    VDT.ExportChannels.markDirty = function(name)
      assert.are.equal(VDT.MapLayers.CHANNEL, name)
      marked = marked + 1
    end

    rawset(_G, "FieldDensityMap", { GROUND_TYPE = 1, SPRAY_LEVEL = 4, LIME_LEVEL = 5, PLOW_LEVEL = 6 })
    rawset(_G, "FieldGroundType", {
      NONE = 1,
      STUBBLE_TILLAGE = 2,
      CULTIVATED = 3,
      SEEDBED = 4,
      PLOWED = 5,
      ROLLED_SEEDBED = 6,
      getValueByType = function(t)
        return t
      end,
    })
    rawset(_G, "g_fruitTypeManager", {
      getDefaultDataPlaneId = function()
        return 1
      end,
      getFruitTypeByDensityTypeIndex = function()
        return nil
      end, -- bare ground everywhere
    })
    rawset(_G, "getDensityTypeIndexAtWorldPos", function()
      return 0
    end)
    rawset(_G, "getDensityStatesAtWorldPos", function()
      return 0
    end)
    rawset(_G, "g_vdTelemetry", { exportEnabled = true })
    rawset(_G, "g_currentMission", {
      isMissionStarted = true,
      terrainSize = 8,
      hud = { ingameMap = { worldSizeX = 8, worldSizeZ = 8 } },
      missionInfo = {
        weedsEnabled = false,
        stonesEnabled = false,
        plowingRequiredEnabled = false,
        limeRequired = false,
      },
      weedSystem = nil,
      stoneSystem = nil,
      fieldGroundSystem = {
        getValueAtWorldPos = function()
          return 0
        end,
        getMaxValue = function()
          return 0
        end,
      },
    })
  end)

  after_each(function()
    VDT.ExportChannels.markDirty = markDirtyOrig
    VDT.MapLayers.GRID_SIZE = 512
    VDT.MapLayers.CELLS_PER_FRAME = 1024
    VDT.MapLayers.PATCH_RADIUS_M = 32
    VDT.MapLayers.sweep = nil
    VDT.MapLayers.dirty = true
    VDT.MapLayers.subscribed = false
    VDT.MapLayers.model = nil
    VDT.MapLayers.patchCtx = nil
    VDT.MapLayers.patchTimerMs = 0
    rawset(_G, "FieldDensityMap", nil)
    rawset(_G, "MessageType", nil)
    rawset(_G, "g_messageCenter", nil)
    rawset(_G, "getWorldTranslation", nil)
    rawset(_G, "FieldGroundType", nil)
    rawset(_G, "g_fruitTypeManager", nil)
    rawset(_G, "getDensityTypeIndexAtWorldPos", nil)
    rawset(_G, "getDensityStatesAtWorldPos", nil)
    rawset(_G, "g_vdTelemetry", nil)
    rawset(_G, "g_currentMission", nil)
  end)

  it("completes an 8x8 sweep over 4 ticks of 16 cells and marks dirty exactly once", function()
    for _ = 1, 4 do
      VDT.MapLayers.tick(stubDebugger(), 16)
    end
    assert.are.equal(1, marked)

    local model = VDT.MapLayers.collect()
    assert.is_not_nil(model)
    assert.are.equal(8, model.gridSize)
    assert.are.equal(3, #model.layers)
    for _, layer in ipairs(model.layers) do
      assert.is_nil(layer.legend) -- bare ground only -> nothing seen -> omitted
      assert.are.equal(8, #layer.rows)
      for _, row in ipairs(layer.rows) do
        assert.are.equal("", row)
      end
    end
  end)

  it("stays idle after a completed sweep until something re-dirties it", function()
    for _ = 1, 4 do
      VDT.MapLayers.tick(stubDebugger(), 16)
    end
    assert.are.equal(1, marked)
    -- No resweep event fired, so further ticks do nothing (no wall-clock timer to elapse).
    for _ = 1, 10 do
      VDT.MapLayers.tick(stubDebugger(), 16)
    end
    assert.are.equal(1, marked)
    assert.is_nil(VDT.MapLayers.sweep)
  end)

  it("re-sweeps after being marked dirty again (a day / period change)", function()
    for _ = 1, 4 do
      VDT.MapLayers.tick(stubDebugger(), 16)
    end
    assert.are.equal(1, marked)
    -- A new in-game day/period fires markDirty; the next batch of ticks runs a fresh sweep.
    VDT.MapLayers.markDirty()
    for _ = 1, 4 do
      VDT.MapLayers.tick(stubDebugger(), 16)
    end
    assert.are.equal(2, marked)
  end)

  it("subscribes to PERIOD_CHANGED and DAY_CHANGED once, when the message center is up", function()
    local subscribed = {}
    rawset(_G, "MessageType", { PERIOD_CHANGED = 11, DAY_CHANGED = 12 })
    rawset(_G, "g_messageCenter", {
      subscribe = function(_, msgType, cb)
        subscribed[#subscribed + 1] = msgType
        assert.are.equal(VDT.MapLayers.markDirty, cb)
      end,
    })
    VDT.MapLayers.tick(stubDebugger(), 16)
    VDT.MapLayers.tick(stubDebugger(), 16) -- idempotent: no re-subscribe
    assert.are.same({ 11, 12 }, subscribed)
  end)

  it("patches cells around an active vehicle between full sweeps, on the throttle", function()
    -- Complete the initial bare-ground sweep (all rows "").
    for _ = 1, 4 do
      VDT.MapLayers.tick(stubDebugger(), 16)
    end
    assert.are.equal(1, marked)

    -- A controlled vehicle now sits at world origin on plowed ground; a patch should re-sample the
    -- cells around it (radius 1 here) to GROWTH_PLOWED, leaving far rows untouched.
    VDT.MapLayers.PATCH_RADIUS_M = 1
    g_currentMission.fieldGroundSystem.getValueAtWorldPos = function(_, densityType)
      if densityType == FieldDensityMap.GROUND_TYPE then
        return 5 -- FieldGroundType.PLOWED's stubbed value
      end
      return 0
    end
    g_currentMission.vehicleSystem = {
      vehicles = { { rootNode = 1, spec_enterable = { isControlled = true } } },
    }
    rawset(_G, "getWorldTranslation", function()
      return 0, 0, 0
    end)

    VDT.MapLayers.tick(stubDebugger(), 1000) -- below the 4000 ms throttle: no patch yet
    assert.are.equal(1, marked)
    VDT.MapLayers.tick(stubDebugger(), 3000) -- crosses 4000 ms: patch runs
    assert.are.equal(2, marked)

    local growth
    for _, layer in ipairs(VDT.MapLayers.collect().layers) do
      if layer.id == "growth" then
        growth = layer
      end
    end
    -- Vehicle at origin -> center row 4 (0-based) on an 8-grid; rows 3..5 patched, row 0 untouched.
    assert.is_true(#growth.rows[5] > 0)
    assert.are.equal("", growth.rows[1])
  end)

  it("does not patch until a sweep has completed, and no-ops with no active vehicles", function()
    -- No completed sweep yet -> patchCtx nil -> a long idle tick can't patch.
    VDT.MapLayers.dirty = false
    VDT.MapLayers.tick(stubDebugger(), 10000)
    assert.are.equal(0, marked)
  end)

  it("does not progress a sweep while export is disabled", function()
    g_vdTelemetry.exportEnabled = false
    VDT.MapLayers.tick(stubDebugger(), 16)
    assert.are.equal(0, marked)
    assert.is_nil(VDT.MapLayers.sweep)
  end)

  it("aborts the sweep without propagating when a batch throws", function()
    g_currentMission.fieldGroundSystem.getValueAtWorldPos = function()
      error("boom")
    end
    assert.has_no.errors(function()
      VDT.MapLayers.tick(stubDebugger(), 16)
    end)
    assert.are.equal(0, marked)
    assert.is_nil(VDT.MapLayers.sweep)
  end)

  it("records only seen legend values, sorted by v", function()
    g_currentMission.fieldGroundSystem.getValueAtWorldPos = function(_, densityType)
      if densityType == FieldDensityMap.GROUND_TYPE then
        return 5 -- FieldGroundType.PLOWED's stubbed value
      end
      return 0
    end
    for _ = 1, 4 do
      VDT.MapLayers.tick(stubDebugger(), 16)
    end

    local model = VDT.MapLayers.collect()
    local growthLayer
    for _, layer in ipairs(model.layers) do
      if layer.id == "growth" then
        growthLayer = layer
      end
    end
    assert.is_not_nil(growthLayer.legend)
    assert.are.equal(1, #growthLayer.legend)
    assert.are.equal(4, growthLayer.legend[1].v) -- GROWTH_PLOWED
  end)
end)
