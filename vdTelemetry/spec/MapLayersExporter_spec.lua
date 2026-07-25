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
dofile("src/integrations/PrecisionFarming.lua")
dofile("src/collect/MapLayersExporter.lua")

local function stubDebugger()
  return {
    error = function() end,
    warn = function() end,
    info = function() end,
    trace = function() end,
    debug = function() end,
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
      seen = { crops = {}, growth = {}, soil = {} },
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
      seen = { crops = {}, growth = {}, soil = {} },
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

  it("clamps a weed group past its band instead of running into the stone band", function()
    local c = ctx({
      weedAvailable = true,
      weedSystem = {
        getWeedStateAtWorldPos = function()
          return 2
        end,
      },
      weedStateToGroup = { [2] = 12 }, -- a soil mod with more color groups than the band reserves
    })
    local _, _, soilV = VDT.MapLayers.classifyCell(c, 0, 0)
    assert.are.equal(9, soilV) -- clamped to SOIL_WEED_MAX_GROUPS, NOT 12 (which reads as a stone group)
    assert.is_not_nil(c.seen.soil[9])
  end)

  it("clamps a stone group past its band instead of colliding with needs-plowing", function()
    local c = ctx({
      stoneAvailable = true,
      stoneSystem = {
        getStoneStateAtWorldPos = function()
          return 4
        end,
      },
      stoneStateToGroup = { [4] = 15 },
    })
    local _, _, soilV = VDT.MapLayers.classifyCell(c, 0, 0)
    assert.are.equal(19, soilV) -- clamped to SOIL_STONE_MAX_GROUPS, NOT 24
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

  it("under Precision Farming, drops the lime + fertilized layers but keeps plowing", function()
    -- PF supersedes the base lime/fertilizer model with its own soil maps, so those two layers are
    -- omitted; plowing (which PF leaves alone) still classifies.
    local _, _, plowV = VDT.MapLayers.classifyCell(ctx({ precisionFarming = true }), 0, 0)
    assert.are.equal(20, plowV) -- default ctx reads plow level 0 -> SOIL_NEEDS_PLOWING, unaffected by PF

    -- plow satisfied, lime needed, spray present -- without PF this is needs-lime; with PF, SOIL_NONE.
    local c = ctx({
      precisionFarming = true,
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
  local markDirtyOrig, markedByChannel

  -- Writes queued for one plane's channel. The planes are separate files now, so "the channel
  -- published" is a per-plane count: a full sweep marks all three, a patch only the planes whose rows
  -- actually moved, and `marked` below is the count they share whenever a case doesn't care which.
  local function marks(id)
    return markedByChannel["mapLayers" .. id:sub(1, 1):upper() .. id:sub(2)] or 0
  end

  -- The per-plane count when every plane agrees (the usual case: a full sweep publishes all of them).
  local function marked()
    local crops, growth, soil = marks("crops"), marks("growth"), marks("soil")
    assert.are.equal(crops, growth)
    assert.are.equal(crops, soil)
    return crops
  end

  before_each(function()
    VDT.MapLayers.GRID_SIZE = 8
    VDT.MapLayers.CELLS_PER_FRAME = 16
    VDT.MapLayers.sweep = nil
    VDT.MapLayers.dirty = true
    VDT.MapLayers.subscribed = false
    VDT.MapLayers.models = {}
    VDT.MapLayers.catalogue = nil
    VDT.MapLayers.patchCtx = nil
    VDT.MapLayers.patchTimerMs = 0
    VDT.MapLayers.auditTimerMs = 0
    -- The channel does nothing at all until something subscribes (see the "subscription" block
    -- below); these cases are about what a sweep does, so they start with all three planes wanted.
    VDT.MapLayers.subscribedLayers = { crops = true, growth = true, soil = true }

    markedByChannel = {}
    markDirtyOrig = VDT.ExportChannels.markDirty
    VDT.ExportChannels.markDirty = function(name)
      markedByChannel[name] = (markedByChannel[name] or 0) + 1
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
    VDT.MapLayers.models = {}
    VDT.MapLayers.catalogue = nil
    VDT.MapLayers.patchCtx = nil
    VDT.MapLayers.patchTimerMs = 0
    VDT.MapLayers.auditTimerMs = 0
    VDT.MapLayers.subscribedLayers = {}
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

  it("completes an 8x8 sweep over 4 ticks of 16 cells and marks each plane dirty exactly once", function()
    for _ = 1, 4 do
      VDT.MapLayers.tick(stubDebugger(), 16)
    end
    assert.are.equal(1, marked())

    -- One self-contained model per plane, each carrying the grid geometry: the server parses and
    -- renders these files independently, so none of them may depend on another to be decodable.
    -- Driven off the catalogue rather than LAYERS: LAYERS also holds the Precision Farming planes,
    -- which this map (no PF in the spec fixtures) doesn't offer.
    for _, layer in ipairs(VDT.MapLayers.collect().layers) do
      local model = VDT.MapLayers.collectLayer(layer.id)
      assert.is_not_nil(model)
      assert.are.equal(layer.id, model.id)
      assert.are.equal(8, model.gridSize)
      assert.are.equal(8, model.terrainSize)
      assert.is_nil(model.legend) -- bare ground only -> nothing seen -> omitted
      assert.are.equal(8, #model.rows)
      for _, row in ipairs(model.rows) do
        assert.are.equal("", row)
      end
    end
  end)

  it("publishes the catalogue without a sweep, so the app can offer a layer before its raster exists", function()
    -- One tick is a single 16-cell batch: nowhere near the 64 the 8x8 grid needs, so no plane has
    -- been published yet -- but the catalogue naming them is already there.
    VDT.MapLayers.tick(stubDebugger(), 16)
    assert.is_nil(VDT.MapLayers.collectLayer("crops"))
    assert.are.equal(1, markedByChannel[VDT.MapLayers.CHANNEL])

    local catalogue = VDT.MapLayers.collect()
    assert.are.equal(8, catalogue.gridSize)
    assert.are.equal(8, catalogue.terrainSize)
    assert.are.same({ "crops", "growth", "soil" }, {
      catalogue.layers[1].id,
      catalogue.layers[2].id,
      catalogue.layers[3].id,
    })
    -- Labels come from the game's own overlay selector; with no g_i18n in the spec they fall back.
    assert.are.equal("Crops", catalogue.layers[1].label)
    -- Each entry reports whether the mod is currently sweeping that plane (all three here, per this
    -- block's before_each) -- the readable half of the subscription, which is what lets the server
    -- notice one that never arrived. The "subscription" block below covers the unsubscribed case.
    for _, entry in ipairs(catalogue.layers) do
      assert.is_true(entry.active)
    end

    -- Published once, not once per tick: the catalogue is fixed for the session.
    for _ = 1, 4 do
      VDT.MapLayers.tick(stubDebugger(), 16)
    end
    assert.are.equal(1, markedByChannel[VDT.MapLayers.CHANNEL])
  end)

  it("reports the world size the grid was actually sampled in, not mission.terrainSize", function()
    -- The two disagree here: resolveWorldSize prefers the HUD map's worldSizeX, and that is the frame
    -- runBatch walks, so it is the one the file must claim.
    g_currentMission.terrainSize = 9999
    for _ = 1, 4 do
      VDT.MapLayers.tick(stubDebugger(), 16)
    end
    assert.are.equal(8, VDT.MapLayers.collect().terrainSize)
    assert.are.equal(8, VDT.MapLayers.collectLayer("growth").terrainSize)
  end)

  it("drops a partial sweep when the profile switches the channel off", function()
    VDT.MapLayers.tick(stubDebugger(), 16) -- one batch in: sweep started, not finished
    assert.is_not_nil(VDT.MapLayers.sweep)

    VDT.MapLayers.onDisabled()

    -- The half-walked grid is gone rather than parked for a later resume, and a full sweep is armed
    -- so re-enabling starts clean instead of finishing a raster stitched across the gap.
    assert.is_nil(VDT.MapLayers.sweep)
    assert.is_true(VDT.MapLayers.dirty)
    for _ = 1, 4 do
      VDT.MapLayers.tick(stubDebugger(), 16)
    end
    assert.are.equal(1, marked())
    assert.is_not_nil(VDT.MapLayers.collectLayer("crops"))
  end)

  -- The multiplayer staleness audit. A client's density maps arrive in bandwidth-limited batches, so
  -- the first sweep can read land that hasn't synced yet; nothing else in this module would revisit it.
  -- On the 8x8 spec grid AUDIT_CELLS clamps to the 64 cells that exist, so every cell is sampled and
  -- these cases don't depend on which ones the stratified draw picks.
  describe("staleness audit", function()
    -- A striped world instead of the uniform bare ground the sweep cases run on. The audit compares the
    -- engine against values it reads back out of the ENCODED rows (storedCell), and over an all-zero
    -- raster every wrong byte offset -- and every read of the wrong row, or of something that isn't the
    -- row string at all -- still yields 0 and agrees with a bare-ground world. Here ground type varies
    -- by column and fertilizer level by row, so the retained rows differ both from each other and
    -- within themselves, and only an exact extraction leaves the unchanged case quiet.
    local GROUND_BY_COL = { 3, 3, 0, 5, 0, 2, 0, 0 } -- CULTIVATED, off-field, PLOWED, STUBBLE_TILLAGE
    local SPRAY_BY_ROW = { 1, 0, 2, 3, 0, 1, 2, 3 }
    -- Growth from ground type: 01 cultivated, 04 plowed, 02 stubble tillage, off-field cells zero, and
    -- the trailing two columns trimmed. Same for every row (ground type here depends only on column).
    local BASE_GROWTH_ROW = "010100040002"
    -- Soil is SOIL_FERTILIZED_BASE + spray level, and only on the on-field columns: 0x1f at level 1,
    -- 0x20 at level 2. A row whose level is 0 has no soil state at all and encodes empty.
    local BASE_SOIL_ROW_1 = "1f1f001f001f"
    local BASE_SOIL_ROW_2 = ""
    local BASE_SOIL_ROW_3 = "202000200020"

    -- The 8x8 grid spans an 8m world, so cells are 1m and their centers sit at -3.5 .. 3.5.
    local function cellIndex(world)
      return math.floor(world + 4) + 1
    end

    local function stripeTheWorld()
      g_currentMission.fieldGroundSystem.getValueAtWorldPos = function(_, densityType, x, _, z)
        if densityType == FieldDensityMap.GROUND_TYPE then
          return GROUND_BY_COL[cellIndex(x)]
        end
        if densityType == FieldDensityMap.SPRAY_LEVEL then
          return SPRAY_BY_ROW[cellIndex(z)]
        end
        return 0
      end
      g_currentMission.fieldGroundSystem.getMaxValue = function(_, densityType)
        return densityType == FieldDensityMap.SPRAY_LEVEL and 3 or 0
      end
    end

    local function completeSweep()
      for _ = 1, 4 do
        VDT.MapLayers.tick(stubDebugger(), 16)
      end
    end

    -- Every cell now classifies as CULTIVATED and fertilized where the sweep recorded the stripes: the
    -- world moved under the retained model, exactly as a batch of synced cells landing would look.
    local function changeTheWorld()
      g_currentMission.fieldGroundSystem.getValueAtWorldPos = function(_, densityType)
        if densityType == FieldDensityMap.GROUND_TYPE then
          return 3
        end
        if densityType == FieldDensityMap.SPRAY_LEVEL then
          return 2
        end
        return 0
      end
    end

    before_each(function()
      VDT.MapLayers.AUDIT_INTERVAL_MS = 100
      VDT.MapLayers.AUDIT_BACKOFF_MS = 500
      g_currentMission.missionDynamicInfo = { isMultiplayer = true }
      stripeTheWorld()
    end)

    after_each(function()
      VDT.MapLayers.AUDIT_INTERVAL_MS = 10000
      VDT.MapLayers.AUDIT_BACKOFF_MS = 30000
    end)

    it("leaves an unchanged map alone", function()
      completeSweep()
      for _ = 1, 20 do
        VDT.MapLayers.tick(stubDebugger(), 16)
      end
      assert.are.equal(1, marked())
      assert.is_false(VDT.MapLayers.dirty)

      -- ...over a raster that is exactly the striped one, byte for byte. The audit staying quiet only
      -- means anything if what it compared the engine against is what the sweep really stored.
      local growth, soil = VDT.MapLayers.collectLayer("growth"), VDT.MapLayers.collectLayer("soil")
      assert.are.equal(BASE_GROWTH_ROW, growth.rows[1])
      assert.are.equal(BASE_GROWTH_ROW, growth.rows[8])
      assert.are.equal(BASE_SOIL_ROW_1, soil.rows[1])
      assert.are.equal(BASE_SOIL_ROW_2, soil.rows[2])
      assert.are.equal(BASE_SOIL_ROW_3, soil.rows[3])
    end)

    it("resweeps once the world no longer matches the model", function()
      completeSweep()
      changeTheWorld()
      -- Nothing re-dirties the channel on its own: without the audit this stays stale until the next
      -- PERIOD/DAY event, which is the multiplayer bug.
      for _ = 1, 7 do
        VDT.MapLayers.tick(stubDebugger(), 16) -- >= AUDIT_INTERVAL_MS of idle time
      end
      assert.is_true(VDT.MapLayers.dirty or VDT.MapLayers.sweep ~= nil)

      completeSweep()
      assert.are.equal(2, marked())
      -- The resweep picked the new state up rather than re-publishing the stale striped raster.
      assert.are.equal("0101010101010101", VDT.MapLayers.collectLayer("growth").rows[1])
      local soil = VDT.MapLayers.collectLayer("soil")
      assert.are.equal("2020202020202020", soil.rows[1])
      assert.are.equal("2020202020202020", soil.rows[2]) -- was empty before the change
    end)

    it("does not audit in singleplayer", function()
      g_currentMission.missionDynamicInfo = { isMultiplayer = false }
      completeSweep()
      changeTheWorld()
      for _ = 1, 40 do
        VDT.MapLayers.tick(stubDebugger(), 16)
      end
      assert.are.equal(1, marked())
      assert.is_false(VDT.MapLayers.dirty)
    end)

    it("backs off after tripping instead of resweeping back to back", function()
      completeSweep()
      changeTheWorld()
      for _ = 1, 7 do
        VDT.MapLayers.tick(stubDebugger(), 16)
      end
      completeSweep() -- the audit-driven resweep
      assert.are.equal(2, marked())

      -- The world still disagrees (the sweep re-read it, but changeTheWorld stays in effect and the
      -- model now matches) -- what's under test is the timer: one interval of idle time must NOT be
      -- enough for another audit, or a still-streaming client would resweep continuously.
      g_currentMission.fieldGroundSystem.getValueAtWorldPos = function()
        return 0 -- flip it back, so any audit that DOES run trips
      end
      for _ = 1, 7 do
        VDT.MapLayers.tick(stubDebugger(), 16)
      end
      assert.is_false(VDT.MapLayers.dirty)

      -- ...but the backoff does elapse, rather than switching the audit off for good. Asserted on the
      -- write count, not on `dirty`: by the end of this run the resweep it triggered has already
      -- finished, which clears both dirty and sweep again.
      for _ = 1, 30 do
        VDT.MapLayers.tick(stubDebugger(), 16)
      end
      assert.are.equal(3, marked())
    end)
  end)

  -- The subscription gate: the terminal tells the mod which planes its dashboards are showing, and
  -- everything else -- classification, encoding, writing, patching, auditing -- is skipped for the rest.
  describe("subscription", function()
    local function completeSweep()
      for _ = 1, 4 do
        VDT.MapLayers.tick(stubDebugger(), 16)
      end
    end

    it("does nothing at all while no plane is subscribed", function()
      VDT.MapLayers.subscribedLayers = {}
      local reads = 0
      g_currentMission.fieldGroundSystem.getValueAtWorldPos = function()
        reads = reads + 1
        return 0
      end

      for _ = 1, 40 do
        VDT.MapLayers.tick(stubDebugger(), 16)
      end

      -- Not one density read, not one sweep, not one queued write: with no terminal running (or the
      -- map page closed) the mod's most expensive channel costs nothing.
      assert.are.equal(0, reads)
      assert.are.equal(0, marked())
      assert.is_nil(VDT.MapLayers.sweep)
      -- ...but the catalogue is still published, or the app could never learn what to subscribe TO.
      assert.are.equal(1, markedByChannel[VDT.MapLayers.CHANNEL])
      assert.is_not_nil(VDT.MapLayers.collect())
    end)

    it("sweeps and writes only the subscribed planes", function()
      VDT.MapLayers.subscribedLayers = { growth = true }
      completeSweep()

      assert.are.equal(1, marks("growth"))
      assert.are.equal(0, marks("crops"))
      assert.are.equal(0, marks("soil"))
      assert.is_not_nil(VDT.MapLayers.collectLayer("growth"))
      assert.is_nil(VDT.MapLayers.collectLayer("crops"))
      assert.is_nil(VDT.MapLayers.collectLayer("soil"))
    end)

    it("skips the engine reads an unsubscribed plane would need", function()
      -- Crops comes off the fruit plane alone, so a crops-only sweep must not touch the ground type
      -- (growth's fallback) or anything soil reads. That skipped work is the point of the gate.
      VDT.MapLayers.subscribedLayers = { crops = true }
      local groundReads, stateReads = 0, 0
      g_currentMission.fieldGroundSystem.getValueAtWorldPos = function()
        groundReads = groundReads + 1
        return 0
      end
      rawset(_G, "getDensityStatesAtWorldPos", function()
        stateReads = stateReads + 1
        return 0
      end)

      completeSweep()

      assert.are.equal(1, marks("crops"))
      assert.are.equal(0, groundReads)
      assert.are.equal(0, stateReads) -- the growth state is a second read on the fruit plane
    end)

    it("resweeps immediately when a plane is subscribed, and keeps the dropped plane's raster", function()
      VDT.MapLayers.subscribedLayers = { crops = true }
      completeSweep()
      assert.are.equal(1, marks("crops"))
      assert.is_false(VDT.MapLayers.dirty)

      -- Switching the dashboard to the growth overlay: the mod must not wait for the next in-game day.
      VDT.MapLayers.setSubscription({ "growth" }, stubDebugger())
      assert.is_true(VDT.MapLayers.dirty)
      completeSweep()
      assert.are.equal(1, marks("growth"))

      -- Crops was published once and then dropped -- its model (and so its file) is left exactly as it
      -- was, so switching back shows that raster at once instead of a blank map.
      assert.are.equal(1, marks("crops"))
      assert.is_not_nil(VDT.MapLayers.collectLayer("crops"))
    end)

    it("drops a sweep in flight when the subscription changes", function()
      VDT.MapLayers.subscribedLayers = { crops = true }
      VDT.MapLayers.tick(stubDebugger(), 16) -- one batch in, 16 of 64 cells
      assert.is_not_nil(VDT.MapLayers.sweep)

      VDT.MapLayers.setSubscription({ "crops", "soil" }, stubDebugger())

      -- Finishing it would publish a raster for the old set while the newly wanted plane waited for
      -- the sweep after this one.
      assert.is_nil(VDT.MapLayers.sweep)
      completeSweep()
      assert.are.equal(1, marks("crops"))
      assert.are.equal(1, marks("soil"))
    end)

    it("reports the subscribed planes in the catalogue, and republishes when it changes", function()
      VDT.MapLayers.tick(stubDebugger(), 16) -- builds the catalogue
      local writes = markedByChannel[VDT.MapLayers.CHANNEL]

      VDT.MapLayers.setSubscription({ "growth" }, stubDebugger())

      local catalogue = VDT.MapLayers.collect()
      assert.are.same({ false, true, false }, {
        catalogue.layers[1].active,
        catalogue.layers[2].active,
        catalogue.layers[3].active,
      })
      -- Rewritten, not just mutated in memory: the file is the only thing the server can read this
      -- from, and it is how a subscription lost with the command file gets noticed.
      assert.are.equal(writes + 1, markedByChannel[VDT.MapLayers.CHANNEL])

      -- ...and back to nothing when the last dashboard stops looking.
      VDT.MapLayers.setSubscription({}, stubDebugger())
      assert.is_false(VDT.MapLayers.collect().layers[2].active)
      assert.are.equal(writes + 2, markedByChannel[VDT.MapLayers.CHANNEL])
    end)

    it("carries a subscription that arrived before the catalogue existed", function()
      -- The order the mod can't control: the terminal's command can land before the world size
      -- resolves, so the catalogue must be born with the subscription already applied.
      VDT.MapLayers.setSubscription({ "soil" }, stubDebugger())
      VDT.MapLayers.tick(stubDebugger(), 16)

      assert.is_true(VDT.MapLayers.collect().layers[3].active)
    end)

    it("ignores an unchanged set, so a repeated command doesn't restart the sweep", function()
      VDT.MapLayers.subscribedLayers = { crops = true }
      completeSweep()
      VDT.MapLayers.setSubscription({ "crops" }, stubDebugger())
      assert.is_false(VDT.MapLayers.dirty)
      assert.are.equal(1, marks("crops"))
    end)

    it("ignores unknown layer ids rather than sweeping for them", function()
      VDT.MapLayers.setSubscription({ "growth", "nutrientMap" }, stubDebugger())
      assert.are.same({ growth = true }, VDT.MapLayers.subscribedLayers)
    end)

    it("goes idle again on the empty set, without dropping what it already published", function()
      VDT.MapLayers.subscribedLayers = { growth = true }
      completeSweep()

      VDT.MapLayers.setSubscription({}, stubDebugger())
      assert.is_false(VDT.MapLayers.dirty)
      for _ = 1, 20 do
        VDT.MapLayers.tick(stubDebugger(), 16)
      end
      assert.are.equal(1, marks("growth")) -- nothing further written
      assert.is_not_nil(VDT.MapLayers.collectLayer("growth")) -- and the last raster stands
    end)

    it("patches and audits only the subscribed planes", function()
      VDT.MapLayers.subscribedLayers = { growth = true }
      g_currentMission.missionDynamicInfo = { isMultiplayer = true }
      completeSweep()

      -- A vehicle works plowed ground: growth moves, and the planes nobody subscribed to are neither
      -- re-sampled nor re-encoded over their retained (here: never published) rasters.
      VDT.MapLayers.PATCH_RADIUS_M = 1
      g_currentMission.fieldGroundSystem.getValueAtWorldPos = function(_, densityType)
        return densityType == FieldDensityMap.GROUND_TYPE and 5 or 0
      end
      g_currentMission.vehicleSystem = {
        vehicles = { { rootNode = 1, spec_enterable = { isControlled = true } } },
      }
      rawset(_G, "getWorldTranslation", function()
        return 0, 0, 0
      end)
      VDT.MapLayers.tick(stubDebugger(), 4000)

      assert.are.equal(2, marks("growth"))
      assert.are.equal(0, marks("crops"))
      assert.are.equal(0, marks("soil"))
      assert.is_nil(VDT.MapLayers.collectLayer("soil"))
    end)
  end)

  -- Precision Farming's value maps ride the same machinery as the base planes: same files, same
  -- subscription gate, same per-plane dirty tracking. What's specific to them is that they only exist
  -- when PF does, and that they are sampled through PF rather than through the density maps above.
  describe("precision farming planes", function()
    before_each(function()
      rawset(_G, "g_modIsLoaded", { FS25_precisionFarming = true })
      g_currentMission.terrainSize = 8
      rawset(_G, "g_precisionFarming", {
        nitrogenMap = {
          getOverviewLabel = function()
            return "Stickstoff"
          end,
          getLevelAtWorldPos = function(_, x, _)
            -- Non-uniform, so the encoded row proves the sampler is fed per-cell world positions.
            return x < 0 and 1 or 2
          end,
          nitrogenValues = {
            { value = 1, realValue = 30, color = { 1, 0, 0 } },
            { value = 2, realValue = 60, color = { 0, 1, 0 } },
          },
        },
      })
    end)

    after_each(function()
      rawset(_G, "g_modIsLoaded", nil)
      rawset(_G, "g_precisionFarming", nil)
    end)

    it("offers only the PF planes whose value map exists", function()
      VDT.MapLayers.tick(stubDebugger(), 16)

      local ids = {}
      for _, entry in ipairs(VDT.MapLayers.collect().layers) do
        ids[#ids + 1] = entry.id
      end
      -- Nitrogen is the only PF map stubbed here; the other four are not offered at all.
      assert.are.same({ "crops", "growth", "soil", "pfNitrogen" }, ids)
      assert.are.equal("Stickstoff", VDT.MapLayers.collect().layers[4].label)
    end)

    it("sweeps a subscribed PF plane into its own file, with its own legend", function()
      VDT.MapLayers.subscribedLayers = { pfNitrogen = true }
      for _ = 1, 4 do
        VDT.MapLayers.tick(stubDebugger(), 16)
      end

      local model = VDT.MapLayers.collectLayer("pfNitrogen")
      assert.are.equal("pfNitrogen", model.id)
      -- Cell centers run -3.5 .. 3.5, so the left half reads level 1 and the right half level 2.
      assert.are.equal("0101010102020202", model.rows[1])
      -- Only the values actually seen, labelled and colored the way PF displays them.
      assert.are.equal(2, #model.legend)
      assert.are.equal(1, model.legend[1].v)
      assert.are.equal("30 kg/ha", model.legend[1].label)
      assert.are.equal("60 kg/ha", model.legend[2].label)
      assert.is_not_nil(model.legend[1].color)

      -- ...and the base planes stay untouched: subscribing to a PF plane sweeps that plane alone.
      assert.are.equal(1, marks("pfNitrogen"))
      assert.are.equal(0, marks("crops"))
    end)

    it("never samples a PF plane whose map is gone, even if something subscribed to it", function()
      -- The app remembering a layer from a save that had PF, opened on one that doesn't.
      rawset(_G, "g_precisionFarming", nil)
      VDT.MapLayers.subscribedLayers = { pfNitrogen = true }

      assert.has_no.errors(function()
        for _ = 1, 4 do
          VDT.MapLayers.tick(stubDebugger(), 16)
        end
      end)
      assert.is_nil(VDT.MapLayers.collectLayer("pfNitrogen"))
      assert.are.equal(0, marks("pfNitrogen"))
    end)
  end)

  it("stays idle after a completed sweep until something re-dirties it", function()
    for _ = 1, 4 do
      VDT.MapLayers.tick(stubDebugger(), 16)
    end
    assert.are.equal(1, marked())
    -- No resweep event fired, so further ticks do nothing (no wall-clock timer to elapse).
    for _ = 1, 10 do
      VDT.MapLayers.tick(stubDebugger(), 16)
    end
    assert.are.equal(1, marked())
    assert.is_nil(VDT.MapLayers.sweep)
  end)

  it("re-sweeps after being marked dirty again (a day / period change)", function()
    for _ = 1, 4 do
      VDT.MapLayers.tick(stubDebugger(), 16)
    end
    assert.are.equal(1, marked())
    -- A new in-game day/period fires markDirty; the next batch of ticks runs a fresh sweep.
    VDT.MapLayers.markDirty()
    for _ = 1, 4 do
      VDT.MapLayers.tick(stubDebugger(), 16)
    end
    assert.are.equal(2, marked())
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
    assert.are.equal(1, marked())

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
    assert.are.equal(1, marked())
    VDT.MapLayers.tick(stubDebugger(), 3000) -- crosses 4000 ms: patch runs

    -- Plowing moves the growth plane and nothing else (no fruit here, and soil needs plow/lime/spray
    -- to be enabled), so ONLY growth is queued for a write. This is what the file split buys: under
    -- one combined file this patch rewrote the crops raster too, every time.
    assert.are.equal(2, marks("growth"))
    assert.are.equal(1, marks("crops"))
    assert.are.equal(1, marks("soil"))

    local growth = VDT.MapLayers.collectLayer("growth")
    -- Vehicle at origin -> center row 4 (0-based) on an 8-grid; rows 3..5 patched, row 0 untouched.
    assert.is_true(#growth.rows[5] > 0)
    assert.are.equal("", growth.rows[1])
    -- ...and the planes it did not touch still hold the sweep's rows, not a re-encoded copy.
    assert.are.equal("", VDT.MapLayers.collectLayer("crops").rows[5])
  end)

  it("does not rewrite when a patch re-samples unchanged cells", function()
    for _ = 1, 4 do
      VDT.MapLayers.tick(stubDebugger(), 16)
    end
    assert.are.equal(1, marked())

    -- A controlled vehicle sits on the same bare ground the sweep already recorded; re-sampling
    -- produces identical rows, so nothing is rewritten and the channel is not re-marked.
    VDT.MapLayers.PATCH_RADIUS_M = 1
    g_currentMission.vehicleSystem = {
      vehicles = { { rootNode = 1, spec_enterable = { isControlled = true } } },
    }
    rawset(_G, "getWorldTranslation", function()
      return 0, 0, 0
    end)
    VDT.MapLayers.tick(stubDebugger(), 4000)
    assert.are.equal(1, marked())
  end)

  it("does not patch until a sweep has completed, and no-ops with no active vehicles", function()
    -- No completed sweep yet -> patchCtx nil -> a long idle tick can't patch.
    VDT.MapLayers.dirty = false
    VDT.MapLayers.tick(stubDebugger(), 10000)
    assert.are.equal(0, marked())
  end)

  it("does not progress a sweep while export is disabled", function()
    g_vdTelemetry.exportEnabled = false
    VDT.MapLayers.tick(stubDebugger(), 16)
    assert.are.equal(0, marked())
    assert.is_nil(VDT.MapLayers.collect()) -- not even the catalogue: export off means nothing at all
    assert.is_nil(VDT.MapLayers.sweep)
  end)

  it("aborts the sweep without propagating when a batch throws", function()
    g_currentMission.fieldGroundSystem.getValueAtWorldPos = function()
      error("boom")
    end
    assert.has_no.errors(function()
      VDT.MapLayers.tick(stubDebugger(), 16)
    end)
    assert.are.equal(0, marked())
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

    local growth = VDT.MapLayers.collectLayer("growth")
    assert.is_not_nil(growth.legend)
    assert.are.equal(1, #growth.legend)
    assert.are.equal(4, growth.legend[1].v) -- GROWTH_PLOWED
    -- Each plane carries only its own legend now, so the crops one stays empty rather than riding
    -- along in a file the app fetches for the growth overlay.
    assert.is_nil(VDT.MapLayers.collectLayer("crops").legend)
  end)
end)
