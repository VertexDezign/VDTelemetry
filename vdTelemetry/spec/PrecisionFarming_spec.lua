-- Unit tests for the shared Precision Farming detection (src/integrations/PrecisionFarming.lua):
-- isActive() reads the shared g_modIsLoaded table keyed by the mod name, matching the game's own gate.
--
-- Run with `busted` from the vdTelemetry/ directory.

dofile("src/integrations/PrecisionFarming.lua")

describe("PrecisionFarming.isActive", function()
  after_each(function()
    rawset(_G, "g_modIsLoaded", nil)
  end)

  it("is false when g_modIsLoaded is absent", function()
    assert.is_false(VDT.PrecisionFarming.isActive())
  end)

  it("is true only when FS25_precisionFarming is loaded", function()
    rawset(_G, "g_modIsLoaded", { FS25_precisionFarming = true })
    assert.is_true(VDT.PrecisionFarming.isActive())
  end)

  it("is false when a different mod is loaded", function()
    rawset(_G, "g_modIsLoaded", { FS25_SomeOtherMod = true })
    assert.is_false(VDT.PrecisionFarming.isActive())
  end)
end)

-- The value maps PF exposes as extra ground-layer planes. What's under test is our reading of PF's
-- internals: which planes exist, how a cell is sampled, and how PF's own value tables become legends.
-- The stubs mirror the real shapes (scripts/maps/*.lua in the PF mod) — whether the mod still looks
-- like this is what the in-game smoke test covers.
describe("PrecisionFarming layers", function()
  local reads

  local function layer(id)
    for _, entry in ipairs(VDT.PrecisionFarming.LAYERS) do
      if entry.id == id then
        return entry
      end
    end
    error("no such layer: " .. id)
  end

  before_each(function()
    reads = {}
    rawset(_G, "g_modIsLoaded", { FS25_precisionFarming = true })
    rawset(_G, "MathUtil", {
      round = function(v)
        return math.floor(v + 0.5)
      end,
    })
    rawset(_G, "g_currentMission", { terrainSize = 1024 })
    rawset(_G, "getBitVectorMapPoint", function(map, x, z, firstChannel, numChannels)
      reads[#reads + 1] = { map = map, x = x, z = z, firstChannel = firstChannel, numChannels = numChannels }
      return 7
    end)
    rawset(_G, "g_precisionFarming", {
      soilMap = {
        getOverviewLabel = function()
          return "Bodentyp"
        end,
        getTypeIndexAtWorldPos = function(_, x, z)
          reads[#reads + 1] = { x = x, z = z }
          return 2
        end,
        -- Index-keyed: the point read returns a 1-based index into this list, not a stored value.
        soilTypes = {
          { name = "Sand", color = { 1, 0, 0 } },
          { name = "Loam", color = { 0, 1, 0 } },
        },
      },
      pHMap = {
        getLevelAtWorldPos = function()
          return 3
        end,
        pHValues = {
          { value = 1, realValue = 5.5, color = { 0.1, 0.2, 0.3 } },
          { value = 3, realValue = 6.5, color = { 0.4, 0.5, 0.6 } },
        },
      },
      nitrogenMap = {
        getLevelAtWorldPos = function()
          return 2
        end,
        nitrogenValues = {
          { value = 1, realValue = 30, color = { 0, 0, 1 } },
          { value = 2, realValue = 60, color = { 0, 0, 0.5 } },
        },
      },
      yieldMap = {
        bitVectorMap = "yield-bvm",
        numChannels = 4,
        sizeX = 2048,
        sizeY = 2048,
        yieldValues = {
          { value = 7, displayValue = 80, color = { 0.2, 0.9, 0.1 } },
        },
      },
      seedRateMap = {
        bitVectorMap = "seed-bvm",
        numChannels = 2,
        sizeX = 1024,
        sizeY = 1024,
        rateValues = {
          { value = 7, text = "high", color = { 0.3, 0.3, 0.3 } },
        },
      },
    })
  end)

  after_each(function()
    rawset(_G, "g_modIsLoaded", nil)
    rawset(_G, "g_precisionFarming", nil)
    rawset(_G, "g_currentMission", nil)
    rawset(_G, "MathUtil", nil)
    rawset(_G, "getBitVectorMapPoint", nil)
  end)

  it("offers exactly PF's five menu-visible value maps", function()
    local ids = {}
    for _, entry in ipairs(VDT.PrecisionFarming.LAYERS) do
      ids[#ids + 1] = entry.id
    end
    -- Cover and tramline are the two PF keeps out of its own map selector, and they stay out here.
    assert.are.same({ "pfSoilType", "pfPh", "pfNitrogen", "pfYield", "pfSeedRate" }, ids)
  end)

  it("is unavailable when PF isn't loaded, or hasn't built that map", function()
    assert.is_true(VDT.PrecisionFarming.isLayerAvailable(layer("pfNitrogen")))

    g_precisionFarming.nitrogenMap = nil
    assert.is_false(VDT.PrecisionFarming.isLayerAvailable(layer("pfNitrogen")))

    rawset(_G, "g_modIsLoaded", nil)
    assert.is_false(VDT.PrecisionFarming.isLayerAvailable(layer("pfSoilType")))
  end)

  -- PF's l10n keys live in its own mod namespace, which g_i18n can't resolve from here, so the label
  -- has to come from the map itself.
  it("labels a plane the way PF's own map selector does", function()
    assert.are.equal("Bodentyp", VDT.PrecisionFarming.layerLabel(layer("pfSoilType")))
  end)

  it("falls back to an English label when the map can't be asked", function()
    g_precisionFarming.soilMap.getOverviewLabel = nil
    assert.are.equal("Soil type", VDT.PrecisionFarming.layerLabel(layer("pfSoilType")))
    g_precisionFarming.soilMap = nil
    assert.are.equal("Soil type", VDT.PrecisionFarming.layerLabel(layer("pfSoilType")))
  end)

  it("samples the soil map by world position and keys its legend by type index", function()
    local built = VDT.PrecisionFarming.resolveLayer(layer("pfSoilType"))
    reads = {} -- resolveLayer probes the map once (see the probe case below)
    assert.are.equal(2, built.sample(10, -20))
    assert.are.same({ x = 10, z = -20 }, reads[1])
    assert.are.equal("Loam", built.legend[2].label)
    assert.are.same({ 0, 1, 0 }, built.legend[2].color)
  end)

  it("keys the pH and nitrogen legends by PF's internal value, labelled as PF displays them", function()
    local ph = VDT.PrecisionFarming.resolveLayer(layer("pfPh"))
    assert.are.equal(3, ph.sample(0, 0))
    assert.are.equal("6.5", ph.legend[3].label)
    assert.is_nil(ph.legend[2]) -- gaps stay gaps: PF's values are not contiguous

    local nitrogen = VDT.PrecisionFarming.resolveLayer(layer("pfNitrogen"))
    assert.are.equal(2, nitrogen.sample(0, 0))
    assert.are.equal("60 kg/ha", nitrogen.legend[2].label)
  end)

  -- Yield and seed rate have no point read in PF at all, so we do what its own modifiers do: convert
  -- the position with PF's formula and read channel 0 of the bit-vector map.
  it("reads yield and seed rate straight out of their bit-vector maps", function()
    local yield = VDT.PrecisionFarming.resolveLayer(layer("pfYield"))
    reads = {} -- resolveLayer probes the map once (see the probe case below)
    assert.are.equal(7, yield.sample(0, 0))
    -- Center of a 1024 m terrain on a 2048-cell map -> the middle cell.
    assert.are.same({ map = "yield-bvm", x = 1024, z = 1024, firstChannel = 0, numChannels = 4 }, reads[1])
    assert.are.equal("80%", yield.legend[7].label)

    local seedRate = VDT.PrecisionFarming.resolveLayer(layer("pfSeedRate"))
    reads = {}
    assert.are.equal(7, seedRate.sample(-512, -512))
    assert.are.same({ map = "seed-bvm", x = 0, z = 0, firstChannel = 0, numChannels = 2 }, reads[1])
    assert.are.equal("high", seedRate.legend[7].label)
  end)

  it("resolves to nil rather than throwing when PF's internals have moved", function()
    g_precisionFarming.nitrogenMap.nitrogenValues = nil -- a rename in a PF update
    assert.is_nil(VDT.PrecisionFarming.resolveLayer(layer("pfNitrogen")))

    g_precisionFarming.yieldMap = nil
    assert.is_nil(VDT.PrecisionFarming.resolveLayer(layer("pfYield")))
  end)

  -- The sweep reads ~262k cells with no per-cell guard, so a map that throws would abort every sweep
  -- the channel ever runs. One probe read up front demotes that to "plane unavailable".
  it("resolves to nil when the map builds but can't be read", function()
    g_precisionFarming.pHMap.getLevelAtWorldPos = function()
      error("PF internals moved")
    end
    assert.is_nil(VDT.PrecisionFarming.resolveLayer(layer("pfPh")))
  end)
end)
