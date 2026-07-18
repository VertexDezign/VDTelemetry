-- Unit tests for the productions export channel (src/collect/ProductionExporter.lua).
--
-- Run with `busted` from the vdTelemetry/ directory. The collector reads FS globals
-- (g_currentMission / g_fillTypeManager / g_localPlayer / ProductionPoint), so the tests stub just
-- enough of those to drive collect() offline. ExportChannels must exist first — the collector calls
-- register() at load time.

if VDT == nil or VDT.ExportChannels == nil then
  dofile("src/export/ExportChannels.lua")
end
if VDT.ProductionExporter == nil then
  dofile("src/collect/ProductionExporter.lua")
end

-- The game enums the tokens are derived from (keys matter, values are arbitrary here).
local PROD_STATUS = { INACTIVE = 0, RUNNING = 1, MISSING_INPUTS = 2, NO_OUTPUT_SPACE = 3 }
local OUTPUT_MODE = { KEEP = 0, DIRECT_SELL = 1, AUTO_DELIVER = 2 }

-- Fill-type table keyed by index; getFillTypeByIndex mimics g_fillTypeManager.
local FILL_TYPES = {
  [10] = { name = "MANURE", title = "Manure" },
  [11] = { name = "FERMENTERMANURE", title = "Fermenter manure" },
  [12] = { name = "LIQUIDMANURE", title = "Slurry" },
}

local function makeStorage(levels, caps)
  local s = { _levels = levels, _caps = caps }
  function s:getFillLevels()
    return self._levels
  end
  function s:getFillLevel(ft)
    return self._levels[ft] or 0
  end
  function s:getCapacity(ft)
    return self._caps[ft] or 0
  end
  return s
end

local function makePoint(opts)
  local pp = {
    productions = opts.productions or {},
    storage = opts.storage,
    owningPlaceable = opts.owningPlaceable,
    _owner = opts.owner,
    _enabled = opts.enabled or {},
    _modes = opts.modes or {},
    _name = opts.name,
  }
  function pp:getName()
    return self._name
  end
  function pp:getOwnerFarmId()
    return self._owner
  end
  function pp:getIsProductionEnabled(id)
    return self._enabled[id] == true
  end
  function pp:getOutputDistributionMode(ft)
    return self._modes[ft] or OUTPUT_MODE.KEEP
  end
  return pp
end

local function setupWorld(points, storagesSet, farmId)
  _G.ProductionPoint = { PROD_STATUS = PROD_STATUS, OUTPUT_MODE = OUTPUT_MODE }
  _G.g_fillTypeManager = {
    getFillTypeByIndex = function(_, idx)
      return FILL_TYPES[idx]
    end,
  }
  _G.g_localPlayer = farmId ~= nil and { farmId = farmId } or nil
  _G.g_currentMission = {
    productionChainManager = { productionPoints = points },
    storageSystem = {
      getStorages = function()
        return storagesSet or {}
      end,
    },
  }
end

describe("ProductionExporter.statusToken", function()
  it("camelCases the enum key", function()
    assert.are.equal("running", VDT.ProductionExporter.statusToken(PROD_STATUS.RUNNING, PROD_STATUS))
    assert.are.equal("missingInputs", VDT.ProductionExporter.statusToken(PROD_STATUS.MISSING_INPUTS, PROD_STATUS))
    assert.are.equal("noOutputSpace", VDT.ProductionExporter.statusToken(PROD_STATUS.NO_OUTPUT_SPACE, PROD_STATUS))
  end)

  it("falls back to inactive for nil / unknown", function()
    assert.are.equal("inactive", VDT.ProductionExporter.statusToken(nil, PROD_STATUS))
    assert.are.equal("inactive", VDT.ProductionExporter.statusToken(99, PROD_STATUS))
  end)
end)

describe("ProductionExporter.outputModeToken", function()
  it("camelCases the enum key", function()
    assert.are.equal("keep", VDT.ProductionExporter.outputModeToken(OUTPUT_MODE.KEEP, OUTPUT_MODE))
    assert.are.equal("directSell", VDT.ProductionExporter.outputModeToken(OUTPUT_MODE.DIRECT_SELL, OUTPUT_MODE))
    assert.are.equal("autoDeliver", VDT.ProductionExporter.outputModeToken(OUTPUT_MODE.AUTO_DELIVER, OUTPUT_MODE))
  end)

  it("falls back to keep for nil / unknown", function()
    assert.are.equal("keep", VDT.ProductionExporter.outputModeToken(nil, OUTPUT_MODE))
  end)
end)

describe("ProductionExporter.collect", function()
  after_each(function()
    _G.g_currentMission = nil
    _G.g_fillTypeManager = nil
    _G.g_localPlayer = nil
    _G.ProductionPoint = nil
  end)

  it("collects own-farm production points with lines and storage", function()
    local point = makePoint({
      name = "Biogas",
      owner = 1,
      owningPlaceable = { uniqueId = "biogas-1" },
      enabled = { line1 = true },
      modes = { [11] = OUTPUT_MODE.AUTO_DELIVER },
      productions = {
        {
          id = "line1",
          name = "Manure",
          status = PROD_STATUS.MISSING_INPUTS,
          cyclesPerMonth = 360,
          costsPerActiveMonth = 60,
          inputs = { { type = 10, amount = 400 } },
          outputs = { { type = 11, amount = 400, sellDirectly = false } },
        },
      },
      storage = makeStorage({ [10] = 0, [11] = 5000 }, { [10] = 20000, [11] = 15000 }),
    })
    setupWorld({ point }, {}, 1)

    local model = VDT.ProductionExporter.collect()

    assert.are.equal("1", model.version)
    assert.are.equal(1, #model.productionPoints)
    local p = model.productionPoints[1]
    assert.are.equal("biogas-1", p.id)
    assert.are.equal("Biogas", p.name)

    assert.are.equal(1, #p.lines)
    local line = p.lines[1]
    assert.are.equal("line1", line.id)
    assert.are.equal("missingInputs", line.status)
    assert.is_true(line.enabled)
    assert.are.equal(360, line.cyclesPerMonth)
    assert.are.equal(60, line.costsPerMonth)
    assert.are.equal("MANURE", line.inputs[1].type)
    assert.are.equal(400, line.inputs[1].amount)
    assert.is_nil(line.inputs[1].mode)
    assert.are.equal("autoDeliver", line.outputs[1].mode)
    assert.is_false(line.outputs[1].sellDirectly)

    -- storage rows are sorted by type name (FERMENTERMANURE before MANURE)
    assert.are.equal(2, #p.storage)
    assert.are.equal("FERMENTERMANURE", p.storage[1].type)
    assert.are.equal(5000, p.storage[1].level)
    assert.are.equal("MANURE", p.storage[2].type)
    assert.are.equal(20000, p.storage[2].capacity)

    assert.is_nil(model.storages) -- no standalone storages in this world
  end)

  it("excludes other farms' production points", function()
    local mine = makePoint({ name = "Mine", owner = 1, owningPlaceable = { uniqueId = "a" } })
    local theirs = makePoint({ name = "Theirs", owner = 2, owningPlaceable = { uniqueId = "b" } })
    setupWorld({ mine, theirs }, {}, 1)

    local model = VDT.ProductionExporter.collect()
    assert.are.equal(1, #model.productionPoints)
    assert.are.equal("Mine", model.productionPoints[1].name)
  end)

  it("reports standalone storages but not production-point storages", function()
    -- A standalone silo (own farm) and a production point's storage — the latter carries
    -- spec_productionPoint on its placeable and must be excluded from the storages list.
    local siloStorage = makeStorage({ [12] = 145000 }, { [12] = 300000 })
    siloStorage.owningPlaceable = {
      spec_productionPoint = nil,
      uniqueId = "silo-1",
      getOwnerFarmId = function()
        return 1
      end,
      getName = function()
        return "Central slurry store"
      end,
    }
    local prodStorage = makeStorage({ [10] = 0 }, { [10] = 20000 })
    prodStorage.owningPlaceable = {
      spec_productionPoint = {},
      getOwnerFarmId = function()
        return 1
      end,
      getName = function()
        return "Biogas"
      end,
    }
    setupWorld({}, { [siloStorage] = siloStorage, [prodStorage] = prodStorage }, 1)

    local model = VDT.ProductionExporter.collect()
    assert.is_nil(model.productionPoints)
    assert.are.equal(1, #model.storages)
    assert.are.equal("silo-1", model.storages[1].id)
    assert.are.equal("Central slurry store", model.storages[1].name)
    assert.are.equal("LIQUIDMANURE", model.storages[1].fills[1].type)
    assert.are.equal(145000, model.storages[1].fills[1].level)
  end)

  it("returns just the version while spectating (no local farm)", function()
    setupWorld({ makePoint({ name = "X", owner = 1 }) }, {}, nil)

    local model = VDT.ProductionExporter.collect()
    assert.are.equal("1", model.version)
    assert.is_nil(model.productionPoints)
    assert.is_nil(model.storages)
  end)
end)
