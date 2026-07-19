-- Unit tests for the field-info export channel (src/collect/FieldInfoExporter.lua): the growth-token
-- helper plus the collect() and tick() paths against stubbed game globals (FieldState, the fruit-type
-- manager, the field manager, mission systems) and, for the enrichment case, the FS25_CropRotation
-- env. Whether the real engine objects still look like these stubs is what the in-game smoke test
-- covers.
--
-- Run with `busted` from the vdTelemetry/ directory. FieldInfoExporter.lua self-registers an export
-- channel at load, so we load ExportChannels first (only if not already loaded, so we don't reset a
-- registry another spec populated). CropRotation.lua is loaded too so the enrichment path can resolve
-- VDT.CropRotation.collectField.

if VDT == nil or VDT.ExportChannels == nil then
  dofile("src/export/ExportChannels.lua")
end
dofile("src/integrations/CropRotation.lua")
dofile("src/collect/FieldInfoExporter.lua")

describe("FieldInfoExporter.growthToken", function()
  -- A fruit-type descriptor whose predicates key off a threshold, so we can drive each branch.
  local function desc(threshold)
    return {
      getIsCut = function(_, gs)
        return gs < 0
      end,
      getIsWithered = function(_, gs)
        return gs == 99
      end,
      getIsGrowing = function(_, gs)
        return gs < threshold
      end,
      getIsPreparable = function(_, gs)
        return gs == threshold
      end,
      getIsHarvestable = function(_, gs)
        return gs > threshold
      end,
    }
  end

  it("walks the cut/withered/growing/preparable/harvestable ladder in order", function()
    assert.are.equal("withered", VDT.FieldInfoExporter.growthToken(desc(7), 99))
    assert.are.equal("growing", VDT.FieldInfoExporter.growthToken(desc(7), 5))
    assert.are.equal("readyToPrepare", VDT.FieldInfoExporter.growthToken(desc(7), 7))
    assert.are.equal("readyToHarvest", VDT.FieldInfoExporter.growthToken(desc(7), 8))
  end)

  it("returns nil for a missing descriptor or when no predicate matches", function()
    assert.is_nil(VDT.FieldInfoExporter.growthToken(nil, 5))
    assert.is_nil(VDT.FieldInfoExporter.growthToken({}, 5))
  end)
end)

describe("FieldInfoExporter.collect", function()
  -- Per-position density-map data the stubbed FieldState:update copies onto itself.
  local stateByPos

  local function installFieldState()
    rawset(_G, "FieldState", {
      new = function()
        local self = {
          getHarvestScaleMultiplier = function(s)
            return s._mult or 1
          end,
        }
        self.update = function(s, x, _z)
          for k, v in pairs(stateByPos[x] or {}) do
            s[k] = v
          end
        end
        return self
      end,
    })
  end

  local function field(id, x, z)
    return {
      getId = function()
        return id
      end,
      getCenterOfFieldWorldPosition = function()
        return x, z
      end,
    }
  end

  before_each(function()
    stateByPos = {}
    installFieldState()
    rawset(_G, "FruitType", { UNKNOWN = 0 })
    rawset(_G, "FieldGroundType", { NONE = 0 })
    rawset(_G, "FieldDensityMap", { SPRAY_LEVEL = 1 })
    rawset(_G, "g_fruitTypeManager", {
      getFruitTypeByIndex = function(_, idx)
        if idx ~= 3 then
          return nil
        end
        return {
          fillType = { title = "Wheat" },
          numGrowthStates = 7,
          getIsCut = function()
            return false
          end,
          getIsWithered = function()
            return false
          end,
          getIsGrowing = function(_, gs)
            return gs < 7
          end,
          getIsPreparable = function()
            return false
          end,
          getIsHarvestable = function(_, gs)
            return gs >= 7
          end,
        }
      end,
    })
    rawset(_G, "g_currentMission", {
      isMissionStarted = true,
      missionInfo = { plowingRequiredEnabled = true, limeRequired = false, weedsEnabled = true },
      fieldGroundSystem = {
        getMaxValue = function()
          return 4
        end,
      },
      weedSystem = {
        getFieldInfoStates = function()
          return { [1] = "Weeds (light)" }
        end,
      },
    })
    rawset(_G, "g_fieldManager", { fields = {} })
    VDT.FieldInfoExporter.started = nil
  end)

  after_each(function()
    for _, name in ipairs({
      "FieldState",
      "FruitType",
      "FieldGroundType",
      "FieldDensityMap",
      "g_fruitTypeManager",
      "g_currentMission",
      "g_fieldManager",
    }) do
      rawset(_G, name, nil)
    end
  end)

  it("returns nil without a mission / field manager / FieldState", function()
    rawset(_G, "g_currentMission", nil)
    assert.is_nil(VDT.FieldInfoExporter.collect())
  end)

  it("omits an empty fields array (Json can't distinguish [] from {})", function()
    local model = VDT.FieldInfoExporter.collect()
    assert.are.equal("1", model.version)
    assert.is_nil(model.fields)
  end)

  it("maps a growing crop to crop/growth/stage/yield/fertilized/warning rows", function()
    stateByPos = {
      [100] = {
        groundType = 1,
        fruitTypeIndex = 3,
        growthState = 5,
        sprayLevel = 2,
        plowLevel = 0,
        weedState = 1,
        rollerLevel = 0,
        limeLevel = 5,
        _mult = 1.12,
      },
    }
    g_fieldManager.fields = { field(49, 100, 200) }

    local fields = VDT.FieldInfoExporter.collect().fields
    assert.are.equal(1, #fields)
    local f = fields[1]
    assert.are.equal(49, f.id)
    assert.are.equal("Wheat", f.crop)
    assert.are.equal(5, f.growthState)
    assert.are.equal(7, f.maxGrowthState)
    assert.are.equal("growing", f.growth)
    assert.are.equal(12, f.yieldBonusPercent)
    assert.are.equal(50, f.sprayLevelPercent)
    assert.are.equal("Weeds (light)", f.weed)
    assert.is_true(f.needsPlowing)
    assert.is_nil(f.needsLime)
    assert.is_nil(f.needsRolling)
    assert.is_nil(f.cropRotation)
  end)

  it("skips fields on non-field ground (groundType NONE)", function()
    stateByPos = { [100] = { groundType = 0, fruitTypeIndex = 3 } }
    g_fieldManager.fields = { field(1, 100, 100) }
    assert.is_nil(VDT.FieldInfoExporter.collect().fields)
  end)

  it("attaches CropRotation rows when the mod is installed", function()
    rawset(_G, "FS25_CropRotation", {
      g_cropRotationPlanner = { cropRotations = {} },
      g_cropRotation = {
        historyStateManager = {
          historyStates = {
            {
              map = {
                getStateTitleAtWorldPos = function()
                  return "Beetroot"
                end,
              },
            },
            {
              map = {
                getStateTitleAtWorldPos = function()
                  return "Potato"
                end,
              },
            },
          },
        },
        yieldCalculator = {
          potentialYieldAtPosition = function()
            return 1.15
          end,
        },
        catchCropManager = {
          catchCropMap = {
            firstChannel = 0,
            numChannels = 2,
            getState = function()
              return 0
            end,
          },
        },
        fruitTypeByCatchCropIndex = function()
          return nil
        end,
      },
    })
    stateByPos = { [100] = { groundType = 1, fruitTypeIndex = 3, growthState = 5, _mult = 1 } }
    g_fieldManager.fields = { field(49, 100, 200) }

    local cr = VDT.FieldInfoExporter.collect().fields[1].cropRotation
    assert.is_not_nil(cr)
    assert.are.equal("Beetroot", cr.lastCrop)
    assert.are.equal("Potato", cr.prevCrop)
    assert.are.equal(115, cr.yieldPercent)
    assert.is_nil(cr.catchCrop)

    rawset(_G, "FS25_CropRotation", nil)
  end)
end)

describe("FieldInfoExporter.tick", function()
  local marks

  before_each(function()
    marks = 0
    VDT.FieldInfoExporter._origMarkDirty = VDT.ExportChannels.markDirty
    VDT.ExportChannels.markDirty = function(name)
      if name == "fieldInfo" then
        marks = marks + 1
      end
    end
    rawset(_G, "FieldState", { new = function() end })
    rawset(_G, "g_currentMission", { isMissionStarted = true })
    rawset(_G, "g_fieldManager", { fields = { {} } })
    VDT.FieldInfoExporter.started = nil
  end)

  after_each(function()
    VDT.ExportChannels.markDirty = VDT.FieldInfoExporter._origMarkDirty
    rawset(_G, "FieldState", nil)
    rawset(_G, "g_currentMission", nil)
    rawset(_G, "g_fieldManager", nil)
  end)

  local debugger = {
    info = function() end,
  }

  it("populates once on first availability, then leaves the periodic resample to the registry", function()
    VDT.FieldInfoExporter.tick(debugger, 100) -- first tick: immediate one-shot populate
    assert.are.equal(1, marks)
    -- Subsequent ticks do nothing: the registry's intervalMs owns the periodic resample now, not tick.
    VDT.FieldInfoExporter.tick(debugger, 100)
    VDT.FieldInfoExporter.tick(debugger, VDT.FieldInfoExporter.REFRESH_MS)
    assert.are.equal(1, marks)
  end)

  it("does nothing while the channel is unavailable", function()
    rawset(_G, "g_currentMission", nil)
    VDT.FieldInfoExporter.tick(debugger, 100)
    assert.are.equal(0, marks)
  end)
end)
