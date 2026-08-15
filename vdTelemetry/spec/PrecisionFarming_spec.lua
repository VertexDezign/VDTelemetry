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

    local lime = VDT.PrecisionFarming.collectSprayer(sprayer({ isFertilizing = false, isLiming = true }))
    assert.are.same({ level = 6.2, target = 6.8 }, lime.ph)
  end)

  -- The trap: PF only refreshes nitrogen while fertilizing and pH while liming
  -- (ExtendedSprayer:updateWorkAreaSubSectionData), and never resets either. A sprayer that fertilized
  -- this morning and
  -- is on herbicide now still holds this morning's nitrogen, so emitting it would put a stale number
  -- next to a live nozzle bar -- the one place it would be believed.
  it("emits each reading only in the mode that maintains it", function()
    local fertilizing = VDT.PrecisionFarming.collectSprayer(sprayer())
    assert.is_not_nil(fertilizing.nitrogen)
    assert.is_nil(fertilizing.ph, "pH is left over from the last liming pass while fertilizing")

    local liming = VDT.PrecisionFarming.collectSprayer(sprayer({ isFertilizing = false, isLiming = true }))
    assert.is_not_nil(liming.ph)
    assert.is_nil(liming.nitrogen)

    -- Herbicide: PF maintains neither, however recently the tank held something else.
    local herbicide = VDT.PrecisionFarming.collectSprayer(sprayer({ isFertilizing = false }))
    assert.is_nil(herbicide.nitrogen)
    assert.is_nil(herbicide.ph)
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
    -- Off the field, or on ground the soil sample hasn't uncovered: PF reports 0, which is "no
    -- reading" rather than "pH zero".
    local pf = VDT.PrecisionFarming.collectSprayer(sprayer({
      isFertilizing = false,
      isLiming = true,
      phActualValue = 0,
      phTargetValue = 0,
    }))
    assert.is_nil(pf.ph)
    assert.are.equal("LIME", pf.mode)
  end)

  it("reports whether spot spraying is fitted, and tells that from not having the config", function()
    local object = sprayer()
    assert.is_nil(VDT.PrecisionFarming.collectSprayer(object).spotSpray)

    object[VDT.PrecisionFarming.SPOT_SPRAY_SPEC] = { isEnabled = false }
    assert.is_false(VDT.PrecisionFarming.collectSprayer(object).spotSpray)

    object[VDT.PrecisionFarming.SPOT_SPRAY_SPEC] = { isEnabled = true }
    assert.is_true(VDT.PrecisionFarming.collectSprayer(object).spotSpray)
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

-- A slurry tanker with a dribble bar or an injecting disc harrow hitched to it (rateSource). The
-- barrel applies nothing itself, so PF keeps the rates on the tool -- and the barrel is the implement
-- a rig panel finds, with the tool one level below it where no slot looks.
describe("PrecisionFarming barrel with an attached tool", function()
  local function specOf(over)
    local spec = {
      isFertilizing = true,
      nActualValue = 3,
      nTargetValue = 6,
      nitrogenMap = {
        maxValue = 10,
        getNitrogenValueFromInternalValue = function(_, internal)
          return ({ [3] = 45, [6] = 90 })[internal] or 0
        end,
      },
    }
    for k, v in pairs(over or {}) do
      spec[k] = v
    end
    return spec
  end

  local function areas()
    return { workAreas = { { index = 1, numSubSections = 1, subSectionData = { { isValid = true } } } } }
  end

  ---PF's env, with the two statics the substitution goes through. Both mirror the shipped source
  ---rather than approximating it: `getIsVehicleValid` rejects a machine with no ExtendedSprayer spec,
  ---no work areas, or a manure barrel holding an `attachedTool`, and `getValidSprayerToUse` returns
  ---the first machine on the rig that passes. Getting these wrong is what the bug was.
  ---@param rig table[] the rig in hitch order, as PF's rootVehicle.childVehicles
  local function pfEnv(rig)
    local function isValid(vehicle)
      if type(vehicle[VDT.PrecisionFarming.SPRAYER_SPEC]) ~= "table" then
        return false
      end
      local workArea = vehicle.spec_workArea
      if type(workArea) ~= "table" or #(workArea.workAreas or {}) == 0 then
        return false
      end
      local barrel = vehicle.spec_manureBarrel
      return type(barrel) ~= "table" or barrel.attachedTool == nil
    end
    rawset(_G, "FS25_precisionFarming", {
      ExtendedSprayer = {
        getIsVehicleValid = isValid,
        getValidSprayerToUse = function()
          for _, vehicle in ipairs(rig) do
            if isValid(vehicle) then
              return vehicle
            end
          end
          return nil
        end,
      },
    })
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
    rawset(_G, "FS25_precisionFarming", nil)
  end)

  it("reports the tool's rates, which is what the game's own HUD does", function()
    -- The Kaweco Profi II, from examples/json/telemetry/precisionFarming/liquidManure_dribbleBar.json:
    -- a barrel sold WITHOUT a spreading tool, so it declares no work areas at all and its own spec is
    -- a set of zeroes that never move. It fails PF's third check and never reaches the barrel one --
    -- which is why reading `spec_manureBarrel.attachedTool` (nil here, and correctly so) missed it.
    local tool = { [VDT.PrecisionFarming.SPRAYER_SPEC] = specOf(), spec_workArea = areas() }
    local barrel = {
      [VDT.PrecisionFarming.SPRAYER_SPEC] = specOf({ isFertilizing = false, nActualValue = 0, nTargetValue = 0 }),
      spec_manureBarrel = { attachedTool = nil },
    }
    pfEnv({ barrel, tool })

    local pf = VDT.PrecisionFarming.collectSprayer(barrel)
    assert.are.equal("FERTILIZER", pf.mode)
    assert.are.same({ level = 45, target = 90, unit = "kg/ha" }, pf.nitrogen)
  end)

  it("substitutes for a barrel that does declare its tool, the same way", function()
    -- The other half of the family: a barrel with work areas of its own, which the base game silences
    -- while a tool is attached (`ManureBarrel:getIsWorkAreaActive`). PF rejects it on the fourth
    -- check instead of the third -- a different route to the same answer, which is the point of
    -- asking PF rather than testing for one of them.
    local tool = { [VDT.PrecisionFarming.SPRAYER_SPEC] = specOf(), spec_workArea = areas() }
    local barrel = {
      [VDT.PrecisionFarming.SPRAYER_SPEC] = specOf({ isFertilizing = false, nActualValue = 0, nTargetValue = 0 }),
      spec_workArea = areas(),
      spec_manureBarrel = { attachedTool = tool },
    }
    pfEnv({ barrel, tool })

    assert.are.same({ level = 45, target = 90, unit = "kg/ha" }, VDT.PrecisionFarming.collectSprayer(barrel).nitrogen)
  end)

  it("keeps its own reading once the tool is unhitched", function()
    local barrel = {
      [VDT.PrecisionFarming.SPRAYER_SPEC] = specOf({ nActualValue = 6 }),
      spec_workArea = areas(),
      spec_manureBarrel = { attachedTool = nil },
    }
    pfEnv({ barrel })
    assert.are.equal(90, VDT.PrecisionFarming.collectSprayer(barrel).nitrogen.level)
  end)

  it("does not borrow a strip whose indices belong to the tool", function()
    -- `index` joins to the *object's* own workAreas, so a borrowed strip would sit on the barrel and
    -- index the tool's areas: a join that reads fine and is wrong. The readout is what the barrel
    -- needs; the tool still exports the strip against the areas it belongs to.
    local tool = { [VDT.PrecisionFarming.SPRAYER_SPEC] = specOf(), spec_workArea = areas() }
    local barrel = {
      [VDT.PrecisionFarming.SPRAYER_SPEC] = specOf(),
      spec_manureBarrel = { attachedTool = tool },
      spec_workArea = { workAreas = { { index = 1 } } },
    }
    pfEnv({ barrel, tool })
    assert.is_nil(VDT.PrecisionFarming.collectSprayer(barrel).workAreas)
    assert.is_not_nil(VDT.PrecisionFarming.collectSprayer(tool).workAreas)
  end)

  it("keeps its own reading when the rig has nothing better to offer", function()
    -- Not everything hitched to a barrel is an applicator. PF finds no valid sprayer on the rig at
    -- all, and the barrel's own reading is still the best answer available.
    local barrel = {
      [VDT.PrecisionFarming.SPRAYER_SPEC] = specOf({ nActualValue = 6 }),
      spec_manureBarrel = { attachedTool = { name = "a trailer" } },
    }
    pfEnv({ barrel, { name = "a trailer" } })
    assert.are.equal(90, VDT.PrecisionFarming.collectSprayer(barrel).nitrogen.level)
  end)

  it("substitutes nothing when PF's internals cannot be reached", function()
    -- No env, so `getIsVehicleValid` is unreachable and the answer is unknown rather than false. A
    -- PF rename must cost the substitution, never redirect a readout onto a machine we guessed at.
    local barrel = { [VDT.PrecisionFarming.SPRAYER_SPEC] = specOf({ nActualValue = 6 }) }
    assert.are.equal(90, VDT.PrecisionFarming.collectSprayer(barrel).nitrogen.level)
  end)

  it("never gives a machine that is no PF sprayer the rates of the tool behind it", function()
    -- The gate is the object's OWN spec, checked before any substitution: a tractor pulling the rig
    -- would otherwise inherit the applicator's readout and draw a rate panel on the cab.
    local tool = { [VDT.PrecisionFarming.SPRAYER_SPEC] = specOf(), spec_workArea = areas() }
    local tractor = { name = "the tractor in front" }
    pfEnv({ tractor, tool })
    assert.is_nil(VDT.PrecisionFarming.collectSprayer(tractor))
  end)
end)

-- The manual application rate (collectManual, via collectSprayer): PF's step, and what one pass at it
-- does. Pure arithmetic over the value maps and whatever is in the tank -- no server-only state -- so
-- unlike the sub-section strip it is exact on a multiplayer client too.
describe("PrecisionFarming manual rate", function()
  local FERTILIZER, LIME = 7, 8

  -- Steps worth 15 kg N/ha (or 0.1 pH) each, costing 200 l/ha (or 1000 l/ha of lime) each.
  local function sprayer(over)
    local spec = {
      isFertilizing = true,
      isSolidFertilizerSprayer = true,
      sprayAmountAutoMode = false,
      sprayAmountManual = 3,
      sprayAmountManualMin = 1,
      sprayAmountManualMax = 7,
      nActualValue = 3,
      nTargetValue = 6,
      nitrogenMap = {
        maxValue = 10,
        getNitrogenValueFromInternalValue = function(_, internal)
          return ({ [3] = 45, [6] = 90 })[internal] or 0
        end,
        getNitrogenFromChangedStates = function(_, states)
          return states * 15
        end,
        getFertilizerUsageByStateChange = function(_, states)
          -- PF returns liters, mass and the nitrogen proportion; only the first is the per-hectare
          -- figure, and reading the wrong one is a plausible-looking mistake, so it is pinned here.
          return states * 200, states * 0.2, 0.1
        end,
      },
      pHMap = {
        maxValue = 10,
        getPhValueFromInternalValue = function(_, internal)
          return ({ [2] = 6.2, [4] = 6.8 })[internal] or 0
        end,
        getPhValueFromChangedStates = function(_, states)
          return states * 0.1
        end,
        getLimeUsageByStateChange = function(_, states)
          return states * 1000
        end,
      },
    }
    for k, v in pairs(over or {}) do
      spec[k] = v
    end
    return {
      [VDT.PrecisionFarming.SPRAYER_SPEC] = spec,
      getSprayerFillUnitIndex = function()
        return 1
      end,
      getFillUnitFillType = function(_, index)
        return index == 1 and FERTILIZER or 0
      end,
    }
  end

  before_each(function()
    rawset(_G, "g_modIsLoaded", { FS25_precisionFarming = true })
    rawset(_G, "MathUtil", {
      round = function(v, decimals)
        local mult = 10 ^ (decimals or 0)
        return math.floor(v * mult + 0.5) / mult
      end,
    })
    rawset(_G, "FillType", { UNKNOWN = 0 })
    rawset(_G, "FillTypeManager", { MASS_SCALE = 1 })
    rawset(_G, "g_fillTypeManager", {
      getFillTypeByIndex = function(_, index)
        -- Tonnes per liter, as the engine stores it: a kilo of solid fertilizer per liter.
        return ({ [FERTILIZER] = { massPerLiter = 0.001 }, [LIME] = { massPerLiter = 0.0012 } })[index]
      end,
    })
  end)

  after_each(function()
    rawset(_G, "g_modIsLoaded", nil)
    rawset(_G, "MathUtil", nil)
    rawset(_G, "FillType", nil)
    rawset(_G, "FillTypeManager", nil)
    rawset(_G, "g_fillTypeManager", nil)
    rawset(_G, "FS25_precisionFarming", nil)
  end)

  it("reports the step, its bounds, and what one pass at it does", function()
    local pf = VDT.PrecisionFarming.collectSprayer(sprayer())
    assert.is_false(pf.auto)
    assert.are.same({ step = 3, min = 1, max = 7, change = 45, rate = 600, rateUnit = "kg/ha" }, pf.manual)
  end)

  -- Each of PF's four machine kinds is weighed in the unit its own HUD prints, off the same liters
  -- per hectare -- so the terminal and the in-game display agree rather than merely both being right.
  it("weighs the rate in the unit PF's HUD uses for this kind of machine", function()
    local function rateOf(over, object)
      return VDT.PrecisionFarming.collectSprayer(object or sprayer(over)).manual
    end

    local liquid = rateOf({ isSolidFertilizerSprayer = false, isLiquidFertilizerSprayer = true })
    assert.are.equal(600, liquid.rate)
    assert.are.equal("l/ha", liquid.rateUnit)

    local slurry = rateOf({ isSolidFertilizerSprayer = false, isSlurryTanker = true })
    assert.are.equal(0.6, slurry.rate)
    assert.are.equal("m³/ha", slurry.rateUnit)

    local manure = rateOf({ isSolidFertilizerSprayer = false, isManureSpreader = true })
    assert.are.equal(0.6, manure.rate)
    assert.are.equal("t/ha", manure.rateUnit)

    -- Lime is decided by what is in the tank, not by the machine: the same spreader does both, which
    -- is why PF's HUD branches on the loaded fill type before it looks at the machine's kind.
    local limeTool = sprayer({ isFertilizing = false, isLiming = true })
    limeTool.getFillUnitFillType = function()
      return LIME
    end
    local lime = rateOf(nil, limeTool)
    assert.are.equal(3.6, lime.rate)
    assert.are.equal("t/ha", lime.rateUnit)
    assert.are.equal(0.3, lime.change)
  end)

  it("costs the rate against the trailer feeding an empty sprayer, the way PF does", function()
    -- PF walks the supply sources in getFillTypeSourceVehicle -- a static on its spec class, so it is
    -- only reachable through the mod-env global (see pfClass). An empty sprayer pulled behind a full
    -- tank trailer is spreading the trailer's product, and the rate has to be costed against that.
    local trailer = {
      getFillUnitFillType = function()
        return LIME
      end,
    }
    rawset(_G, "FS25_precisionFarming", {
      ExtendedSprayer = {
        getFillTypeSourceVehicle = function()
          return trailer, 2
        end,
      },
    })
    local tool = sprayer({ isFertilizing = false, isLiming = true })
    assert.are.equal(3.6, VDT.PrecisionFarming.collectSprayer(tool).manual.rate)
  end)

  it("falls back to the fill type the tank last held", function()
    -- An empty machine still knows what it last spread, and PF's own mode check falls back the same
    -- way -- so running dry must not blank the rate at the moment you are deciding whether to refill.
    local tool = sprayer()
    tool.getFillUnitFillType = function()
      return 0
    end
    tool.getFillUnitLastValidFillType = function()
      return FERTILIZER
    end
    assert.are.equal(600, VDT.PrecisionFarming.collectSprayer(tool).manual.rate)
  end)

  it("keeps the step when the product cost is unknowable", function()
    -- No fill type anywhere: the step and what it does to the soil are still exact, only the product
    -- it costs is not. Reporting the step without a rate beats reporting neither.
    local tool = sprayer()
    tool.getFillUnitFillType = function()
      return 0
    end
    local manual = VDT.PrecisionFarming.collectSprayer(tool).manual
    assert.are.equal(3, manual.step)
    assert.are.equal(45, manual.change)
    assert.is_nil(manual.rate)
    assert.is_nil(manual.rateUnit)
  end)

  it("omits the block with herbicide, where the step changes nothing", function()
    -- PF keeps no rates in this mode and deactivates its own adjust action, so a terminal that showed
    -- the step would draw a live-looking control the machine ignores.
    assert.is_nil(VDT.PrecisionFarming.collectSprayer(sprayer({ isFertilizing = false })).manual)
  end)

  it("carries PF's own gate on leaving auto", function()
    assert.is_true(VDT.PrecisionFarming.collectSprayer(sprayer()).canToggleAuto)
    local locked = sprayer({ sprayAmountAutoModeChangeAllowed = false })
    assert.is_false(VDT.PrecisionFarming.collectSprayer(locked).canToggleAuto)
  end)

  it("never answers a liming tool with the nitrogen change", function()
    -- The `and`/`or` trap: with the pH converter gone, a fallthrough would put a kg N/ha figure next
    -- to a pH reading. A plausible number for the wrong substance is worse than no number.
    local tool = sprayer({
      isFertilizing = false,
      isLiming = true,
      pHMap = { maxValue = 10, getLimeUsageByStateChange = function() end },
    })
    tool.getFillUnitFillType = function()
      return LIME
    end
    assert.is_nil(VDT.PrecisionFarming.collectSprayer(tool).manual.change)
  end)

  it("survives value-map methods that have moved", function()
    local pf = VDT.PrecisionFarming.collectSprayer(sprayer({
      nitrogenMap = {
        maxValue = 10,
        getNitrogenValueFromInternalValue = function(_, internal)
          return ({ [3] = 45, [6] = 90 })[internal] or 0
        end,
        getFertilizerUsageByStateChange = function()
          error("PF internals moved")
        end,
      },
    }))
    assert.are.equal(3, pf.manual.step)
    assert.is_nil(pf.manual.change)
    assert.is_nil(pf.manual.rate)
  end)

  -- The live rate (liveRate, via collectSprayer): `spec.lastLitersPerHectar`, weighed the same way
  -- the step is. In AUTO this is the only rate there is -- the tool reads the map and picks its own
  -- per square metre, so no step describes it -- and it is what PF's own HUD prints there.
  describe("live rate", function()
    ---A working machine: PF's last computed liters/ha, and a work area the engine calls active.
    local function working(over, areaActive)
      local object = sprayer(over)
      object[VDT.PrecisionFarming.SPRAYER_SPEC].lastLitersPerHectar = 400
      object.spec_workArea = { workAreas = { { index = 1 } } }
      object.getIsWorkAreaActive = function()
        return areaActive ~= false
      end
      return object
    end

    it("reports what is actually leaving the machine, in the same unit as the step", function()
      local pf = VDT.PrecisionFarming.collectSprayer(working())
      -- 400 l/ha of solid fertilizer at a kilo per liter, exactly as PF's HUD weighs it.
      assert.are.equal(400, pf.rate)
      assert.are.equal("kg/ha", pf.rateUnit)
      -- The nominal step cost is a different number and is carried alongside, not replaced: one is
      -- what the machine is doing, the other what the chosen step would cost.
      assert.are.equal(600, pf.manual.rate)
    end)

    it("weighs it in the unit PF's HUD uses for this kind of machine", function()
      local slurry =
        VDT.PrecisionFarming.collectSprayer(working({ isSolidFertilizerSprayer = false, isSlurryTanker = true }))
      assert.are.equal(0.4, slurry.rate)
      assert.are.equal("m³/ha", slurry.rateUnit)
    end)

    it("withholds it the moment the boom comes up", function()
      -- PF never clears the field: it holds whatever the last processed area needed. Reporting that
      -- would put the rate of a finished pass on screen looking live, so absent is the honest answer.
      local pf = VDT.PrecisionFarming.collectSprayer(working(nil, false))
      assert.is_nil(pf.rate)
      assert.is_nil(pf.rateUnit)
      -- The step cost survives, because it never depended on the tool running.
      assert.are.equal(600, pf.manual.rate)
    end)

    it("withholds it on a machine with no work areas to judge by", function()
      -- PF's figure is there to be read; what is missing is any way to know whether the tool is
      -- working, which is the gate under test. Set deliberately, because a machine with no figure
      -- either would pass this whether or not the gate exists.
      local object = sprayer()
      object[VDT.PrecisionFarming.SPRAYER_SPEC].lastLitersPerHectar = 400
      assert.is_nil(VDT.PrecisionFarming.collectSprayer(object).rate)
    end)

    it("drops a zero rather than reporting it", function()
      -- A machine that is down and working but computing nothing per hectare says more by staying
      -- quiet: "0 kg/ha" under a running boom reads as a broken figure rather than an absent one.
      -- This was added expecting multiplayer clients to hit it; they do not (see liveRate), so it
      -- guards only the cases nobody has enumerated -- which is the reason to keep it.
      local object = working()
      object[VDT.PrecisionFarming.SPRAYER_SPEC].lastLitersPerHectar = 0
      assert.is_nil(VDT.PrecisionFarming.collectSprayer(object).rate)
    end)

    it("has none with herbicide in the tank, where PF computes no rates at all", function()
      local pf = VDT.PrecisionFarming.collectSprayer(working({ isFertilizing = false, isLiming = false }))
      assert.are.equal("OTHER", pf.mode)
      assert.is_nil(pf.rate)
    end)
  end)
end)

-- The nozzle bar (collectNozzles, via collectSprayer): PF's own per-nozzle effect states. Unlike the
-- sub-sections these are recomputed on every client, so this is the one per-position signal that
-- survives multiplayer -- and the only one that says anything with herbicide in the tank.
describe("PrecisionFarming nozzles", function()
  local function effects(nozzles, individual)
    return {
      individualNozzleControl = individual == true,
      sprayerEffects = nozzles,
    }
  end

  local function sprayerWith(spec)
    local object = {
      [VDT.PrecisionFarming.SPRAYER_SPEC] = { isFertilizing = true, sprayAmountAutoMode = true },
    }
    object[VDT.PrecisionFarming.EFFECTS_SPEC] = spec
    return object
  end

  before_each(function()
    rawset(_G, "g_modIsLoaded", { FS25_precisionFarming = true })
    rawset(_G, "MathUtil", {
      round = function(v)
        return math.floor(v + 0.5)
      end,
    })
  end)

  after_each(function()
    rawset(_G, "g_modIsLoaded", nil)
    rawset(_G, "MathUtil", nil)
  end)

  it("is absent on a sprayer PF drives no nozzle effects on", function()
    assert.is_nil(VDT.PrecisionFarming.collectSprayer(sprayerWith(nil)).nozzles)
    assert.is_nil(VDT.PrecisionFarming.collectSprayer(sprayerWith(effects({}))).nozzles)
  end)

  it("orders the nozzles left to right across the boom", function()
    -- PF stores a POSITIVE xOffset for the left side (it looks a positive offset up in sectionsLeft),
    -- and the effect list itself comes out of a pairs() walk of its node XML -- so the order has to be
    -- rebuilt from the offsets, descending.
    local pf = VDT.PrecisionFarming.collectSprayer(sprayerWith(effects({
      { xOffset = -3, isActive = false },
      { xOffset = 6, isActive = true },
      { xOffset = -6, isActive = false },
      { xOffset = 3, isActive = true },
    })))

    assert.are.same({ true, true, false, false }, pf.nozzles.active)
    assert.are.equal(4, pf.nozzles.count)
    assert.are.equal(2, pf.nozzles.activeCount)
    assert.is_false(pf.nozzles.individual)
  end)

  it("carries PF's per-nozzle vs per-section control flag", function()
    local pf = VDT.PrecisionFarming.collectSprayer(sprayerWith(effects({ { xOffset = 1 } }, true)))
    assert.is_true(pf.nozzles.individual)
    -- An effect that has never run reads as off rather than as missing.
    assert.are.same({ false }, pf.nozzles.active)
    assert.are.equal(0, pf.nozzles.activeCount)
  end)

  it("still reports the nozzles with herbicide, where there are no rates at all", function()
    local object = sprayerWith(effects({ { xOffset = 2, isActive = true }, { xOffset = -2, isActive = false } }))
    -- Neither liming nor fertilizing: PF computes no levels, so every rate is absent.
    object[VDT.PrecisionFarming.SPRAYER_SPEC].isFertilizing = false
    local pf = VDT.PrecisionFarming.collectSprayer(object)
    assert.are.equal("OTHER", pf.mode)
    assert.is_nil(pf.nitrogen)
    assert.is_nil(pf.ph)
    assert.are.same({ true, false }, pf.nozzles.active)
  end)

  it("survives an effect list that no longer looks like this", function()
    assert.is_nil(VDT.PrecisionFarming.collectSprayer(sprayerWith({ sprayerEffects = "moved" })).nozzles)
    -- A nozzle with no offset sorts as centre rather than dropping out of the bar.
    local pf = VDT.PrecisionFarming.collectSprayer(sprayerWith(effects({ { isActive = true }, { xOffset = 5 } })))
    assert.are.same({ false, true }, pf.nozzles.active)
  end)
end)

-- Pulse-width modulation: PF's per-nozzle rate control, and the reason a PWM boom looks like nozzles
-- are cutting out mid-turn when nothing has switched off.
describe("PrecisionFarming nozzle PWM", function()
  local function pwmSprayer(nozzles, individual)
    local object = {
      [VDT.PrecisionFarming.SPRAYER_SPEC] = { isFertilizing = true },
    }
    object[VDT.PrecisionFarming.EFFECTS_SPEC] = {
      individualNozzleControl = individual ~= false,
      sprayerEffects = nozzles,
    }
    return object
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

  it("carries each nozzle's own output, in the same left-to-right order", function()
    -- Mid-turn: the outside of the boom is travelling at full speed, the inside is barely moving, and
    -- PF pulses each nozzle in proportion. Every one of them is still `active`.
    local pf = VDT.PrecisionFarming.collectSprayer(pwmSprayer({
      { xOffset = -6, isActive = true, amountScale = 0.2 },
      { xOffset = 6, isActive = true, amountScale = 1 },
      { xOffset = 0, isActive = true, amountScale = 0.55 },
    }))

    assert.are.same({ 1, 0.55, 0.2 }, pf.nozzles.amount)
    assert.are.same({ true, true, true }, pf.nozzles.active)
    assert.are.equal(3, pf.nozzles.activeCount)
  end)

  it("omits the amounts when every nozzle is wide open", function()
    -- Every machine without PWM, and a PWM boom at full speed: a run of 1s says nothing the active
    -- flags do not.
    local pf = VDT.PrecisionFarming.collectSprayer(pwmSprayer({
      { xOffset = 1, isActive = true, amountScale = 1 },
      { xOffset = -1, isActive = false },
    }))
    assert.is_nil(pf.nozzles.amount)
    assert.are.same({ true, false }, pf.nozzles.active)
  end)

  it("clamps an out-of-range amount rather than passing it through", function()
    local pf = VDT.PrecisionFarming.collectSprayer(pwmSprayer({
      { xOffset = 1, isActive = true, amountScale = 1.4 },
      { xOffset = -1, isActive = true, amountScale = -0.2 },
    }))
    assert.are.same({ 1, 0 }, pf.nozzles.amount)
  end)
end)
