-- Unit tests for the shared Precision Farming detection (src/integrations/PrecisionFarming.lua):
-- isActive() reads the shared g_modIsLoaded table keyed by the mod name, matching the game's own gate.
--
-- Run with `busted` from the vdTelemetry/ directory.

-- collectSprayer rounds through ValueMapper.mapFloat, so the mapper loads with the integration.
if ValueMapper == nil then
  dofile("src/mapper/ValueMapper.lua")
end
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
  local reads, pf

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
    -- Installed under the mod's OWN env global, which is the only place it is readable from: FS25
    -- gives each mod its own Lua env, so PF's g_precisionFarming is NOT in the shared _G. Stubbing
    -- the bare global (what an earlier version of this spec did) tests a world that doesn't exist.
    rawset(_G, "FS25_precisionFarming", {})
    FS25_precisionFarming.g_precisionFarming = {
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
    }
    pf = FS25_precisionFarming.g_precisionFarming
  end)

  after_each(function()
    rawset(_G, "g_modIsLoaded", nil)
    rawset(_G, "FS25_precisionFarming", nil)
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

  -- The bug a playtest found: reading the bare global instead of the mod's env global, which is nil
  -- from here, so PF was installed and its layers silently never offered.
  it("reaches PF through its own env global, not the shared _G", function()
    assert.is_nil(rawget(_G, "g_precisionFarming"))
    assert.is_true(VDT.PrecisionFarming.isLayerAvailable(layer("pfSoilType")))
    assert.is_false(VDT.PrecisionFarming.isUnreachable())
  end)

  it("reports PF as unreachable when it is loaded but its singleton can't be seen", function()
    rawset(_G, "FS25_precisionFarming", {}) -- env there, singleton not (or renamed)
    assert.is_true(VDT.PrecisionFarming.isUnreachable())
    assert.is_false(VDT.PrecisionFarming.isLayerAvailable(layer("pfSoilType")))

    -- Not installed at all is a different thing, and not a warning.
    rawset(_G, "g_modIsLoaded", nil)
    assert.is_false(VDT.PrecisionFarming.isUnreachable())
  end)

  it("still finds the singleton if a future version does put it in the shared _G", function()
    local instance = FS25_precisionFarming.g_precisionFarming
    rawset(_G, "FS25_precisionFarming", nil)
    rawset(_G, "g_precisionFarming", instance)
    assert.is_true(VDT.PrecisionFarming.isLayerAvailable(layer("pfSoilType")))
  end)

  it("is unavailable when PF isn't loaded, or hasn't built that map", function()
    assert.is_true(VDT.PrecisionFarming.isLayerAvailable(layer("pfNitrogen")))

    pf.nitrogenMap = nil
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
    pf.soilMap.getOverviewLabel = nil
    assert.are.equal("Soil type", VDT.PrecisionFarming.layerLabel(layer("pfSoilType")))
    pf.soilMap = nil
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

  it("takes PF's colorblind palette when the player has that mode on", function()
    pf.soilMap.soilTypes[2].colorBlind = { 0, 0, 1 }
    assert.are.same({ 0, 1, 0 }, VDT.PrecisionFarming.resolveLayer(layer("pfSoilType")).legend[2].color)
    assert.are.same({ 0, 0, 1 }, VDT.PrecisionFarming.resolveLayer(layer("pfSoilType"), true).legend[2].color)
  end)

  it("falls back to the default color for a value PF gives no colorblind variant", function()
    -- PF leaves colorBlind nil in places; a missing variant must not mean a missing color.
    assert.are.same({ 0, 1, 0 }, VDT.PrecisionFarming.resolveLayer(layer("pfSoilType"), true).legend[2].color)
  end)

  -- The sweep walks the frame the HUD map reports, which can be bigger than the terrain, so a sampled
  -- position can fall outside the value map. "Nothing here" is the honest answer for it.
  it("reads a position outside the value map as no data", function()
    local yield = VDT.PrecisionFarming.resolveLayer(layer("pfYield"))
    reads = {}
    assert.are.equal(0, yield.sample(5000, 0))
    assert.are.equal(0, yield.sample(0, -5000))
    assert.are.equal(0, #reads, "an out-of-range cell must not reach the engine call")
  end)

  it("resolves to nil rather than throwing when PF's internals have moved", function()
    pf.nitrogenMap.nitrogenValues = nil -- a rename in a PF update
    assert.is_nil(VDT.PrecisionFarming.resolveLayer(layer("pfNitrogen")))

    pf.yieldMap = nil
    assert.is_nil(VDT.PrecisionFarming.resolveLayer(layer("pfYield")))
  end)

  -- The sweep reads ~262k cells with no per-cell guard, so a map that throws would abort every sweep
  -- the channel ever runs. One probe read up front demotes that to "plane unavailable".
  it("resolves to nil when the map builds but can't be read", function()
    pf.pHMap.getLevelAtWorldPos = function()
      error("PF internals moved")
    end
    assert.is_nil(VDT.PrecisionFarming.resolveLayer(layer("pfPh")))
  end)
end)

-- The application rates on the tool itself (collectSprayer): PF's ExtendedSprayer spec, converted
-- into the numbers a player reads. The stubs mirror the real spec (its scripts/specializations/
-- ExtendedSprayer.lua) — including the part that only exists on the server, which is exactly what the
-- absent-sub-sections cases below stand in for.
describe("PrecisionFarming.collectSprayer", function()
  local function levelMap(method, values)
    return {
      maxValue = 10,
      [method] = function(_, internal)
        return values[internal] or 0
      end,
    }
  end

  local function sprayer(over)
    local spec = {
      isFertilizing = true,
      sprayAmountAutoMode = true,
      nActualValue = 3,
      nTargetValue = 6,
      phActualValue = 2,
      phTargetValue = 4,
      nitrogenMap = levelMap("getNitrogenValueFromInternalValue", { [3] = 45, [6] = 90, [10] = 150 }),
      pHMap = levelMap("getPhValueFromInternalValue", { [2] = 6.2, [4] = 6.8 }),
    }
    for k, v in pairs(over or {}) do
      spec[k] = v
    end
    return { [VDT.PrecisionFarming.SPRAYER_SPEC] = spec }
  end

  before_each(function()
    rawset(_G, "g_modIsLoaded", { FS25_precisionFarming = true })
    rawset(_G, "MathUtil", {
      round = function(v, decimals)
        local mult = 10 ^ (decimals or 0)
        return math.floor(v * mult + 0.5) / mult
      end,
    })
  end)

  after_each(function()
    rawset(_G, "g_modIsLoaded", nil)
    rawset(_G, "MathUtil", nil)
  end)

  it("returns nil without PF, and for a tool that has no rates", function()
    rawset(_G, "g_modIsLoaded", nil)
    assert.is_nil(VDT.PrecisionFarming.collectSprayer(sprayer()))

    rawset(_G, "g_modIsLoaded", { FS25_precisionFarming = true })
    assert.is_nil(VDT.PrecisionFarming.collectSprayer({}))
  end)

  it("converts the boom averages into the units the HUD shows", function()
    local pf = VDT.PrecisionFarming.collectSprayer(sprayer())
    assert.are.equal("FERTILIZER", pf.mode)
    assert.is_true(pf.auto)
    assert.are.same({ level = 45, target = 90, unit = "kg/ha" }, pf.nitrogen)
    assert.are.same({ level = 6.2, target = 6.8 }, pf.ph)
  end)

  it("clamps a level past the map's maximum, as PF's own HUD does", function()
    local pf = VDT.PrecisionFarming.collectSprayer(sprayer({ nActualValue = 99 }))
    assert.are.equal(150, pf.nitrogen.level)
  end)

  it("names the three modes", function()
    assert.are.equal("LIME", VDT.PrecisionFarming.collectSprayer(sprayer({ isLiming = true })).mode)
    -- Neither flag: a sprayer with herbicide in the tank, which PF has no rates for.
    local other = sprayer({ isFertilizing = false })
    assert.are.equal("OTHER", VDT.PrecisionFarming.collectSprayer(other).mode)
  end)

  it("omits a value pair PF has no reading for", function()
    local pf = VDT.PrecisionFarming.collectSprayer(sprayer({ phActualValue = 0, phTargetValue = 0 }))
    assert.is_nil(pf.ph)
    -- The other half is unaffected: off-field lime data doesn't hide the nitrogen the tool knows.
    assert.are.equal(45, pf.nitrogen.level)
  end)

  it("exports the sub-section strip against the work area it belongs to", function()
    local object = sprayer()
    object.spec_workArea = {
      workAreas = {
        {
          index = 1,
          numSubSections = 2,
          subSectionData = {
            { isValid = true, nitrogenLevel = 3, nitrogenTargetLevel = 6, phLevel = 2, phTargetLevel = 4 },
            { isValid = false, nitrogenLevel = 0, nitrogenTargetLevel = 0, phLevel = 0, phTargetLevel = 0 },
          },
        },
      },
    }

    local areas = VDT.PrecisionFarming.collectSprayer(object).workAreas
    assert.are.equal(1, #areas)
    assert.are.equal(1, areas[1].index)
    assert.are.same({
      { valid = true, n = 45, nTarget = 90, ph = 6.2, phTarget = 6.8 },
      { valid = false, n = 0, nTarget = 0, ph = 0, phTarget = 0 },
    }, areas[1].subSections)
  end)

  it("omits the strip on a client, where PF never fills it in", function()
    -- updateWorkAreaSubSectionData runs inside `if self.isServer`, so a multiplayer client has the
    -- work areas but no sub-section data on them. The averages still arrive, over the stream.
    local object = sprayer()
    object.spec_workArea = { workAreas = { { index = 1 } } }
    local pf = VDT.PrecisionFarming.collectSprayer(object)
    assert.is_nil(pf.workAreas)
    assert.are.equal(45, pf.nitrogen.level)
  end)

  it("survives a PF whose converters have moved", function()
    local pf = VDT.PrecisionFarming.collectSprayer(sprayer({
      nitrogenMap = { maxValue = 10 },
      pHMap = {
        maxValue = 10,
        getPhValueFromInternalValue = function()
          error("PF internals moved")
        end,
      },
    }))
    assert.is_nil(pf.nitrogen)
    assert.is_nil(pf.ph)
    assert.are.equal("FERTILIZER", pf.mode)
  end)
end)
