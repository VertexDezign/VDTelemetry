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
if Json == nil then
  dofile("src/utils/Json.lua")
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

-- A silo placeable (spec_silo) owned by `owner`, with a single storage set of level/cap maps.
local function makeSilo(name, owner, uniqueId, levels, caps)
  local storage = makeStorage(levels, caps)
  storage.ownerFarmId = owner
  return {
    uniqueId = uniqueId,
    spec_silo = { storages = { storage } },
    getOwnerFarmId = function()
      return owner
    end,
    getName = function()
      return name
    end,
  }
end

-- A factory (PlaceableFactory) stub: it IS the placeable, its input storage lives on
-- spec_factory.storage, its lone production has no id/status, and it has neither
-- getIsProductionEnabled nor getOutputDistributionMode.
local function makeFactory(name, owner, uniqueId, opts)
  return {
    uniqueId = uniqueId,
    productions = opts.productions,
    spec_factory = { storage = opts.storage },
    getName = function()
      return name
    end,
    getOwnerFarmId = function()
      return owner
    end,
  }
end

-- An object-storage placeable (spec_objectStorage). `groups` is a list of { title, count } modelled
-- as the game's objectInfos (each with an abstract object exposing getDialogText).
local function makeObjectStorage(name, owner, uniqueId, capacity, numStored, groups, maxUnload)
  local objectInfos = {}
  for _, g in ipairs(groups or {}) do
    objectInfos[#objectInfos + 1] = {
      numObjects = g.count,
      objects = {
        {
          getDialogText = function()
            return g.title
          end,
        },
      },
    }
  end
  return {
    uniqueId = uniqueId,
    spec_objectStorage = {
      capacity = capacity,
      numStoredObjects = numStored,
      objectInfos = objectInfos,
      maxUnloadAmount = maxUnload,
    },
    getName = function()
      return name
    end,
    getOwnerFarmId = function()
      return owner
    end,
  }
end

local function setupWorld(points, placeables, farmId, factories)
  _G.ProductionPoint = { PROD_STATUS = PROD_STATUS, OUTPUT_MODE = OUTPUT_MODE }
  _G.g_fillTypeManager = {
    getFillTypeByIndex = function(_, idx)
      return FILL_TYPES[idx]
    end,
  }
  _G.g_localPlayer = farmId ~= nil and { farmId = farmId } or nil
  _G.g_currentMission = {
    productionChainManager = { productionPoints = points, factories = factories or {} },
    placeableSystem = { placeables = placeables or {} },
    storageSystem = {
      getStorages = function()
        return {}
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

  it("collects own-farm factories as read-only points", function()
    local factory = makeFactory("Sawmill", 1, "mill-1", {
      productions = {
        {
          name = "Boards",
          cyclesPerMonth = 30,
          costsPerActiveMonth = 0,
          inputs = { { type = 10, amount = 5 } },
          outputs = { { type = 11, amount = 1, isFactory = true } },
        },
      },
      storage = makeStorage({ [10] = 1000 }, { [10] = 5000 }),
    })
    local otherFarm = makeFactory("Neighbour mill", 2, "mill-2", { productions = {} })
    setupWorld({}, {}, 1, { factory, otherFarm })

    local model = VDT.ProductionExporter.collect()
    assert.are.equal(1, #model.productionPoints)
    local p = model.productionPoints[1]
    assert.are.equal("mill-1", p.id)
    assert.are.equal("Sawmill", p.name)
    assert.is_true(p.isFactory)
    assert.are.equal(1, #p.lines)
    local line = p.lines[1]
    -- factory productions carry no id/status and no on/off surface
    assert.are.equal("line1", line.id)
    assert.is_false(line.enabled)
    -- factory output has no distribution mode
    assert.is_nil(line.outputs[1].mode)
    -- input storage came from spec_factory.storage
    assert.are.equal(1, #p.storage)
    assert.are.equal("MANURE", p.storage[1].type)
    assert.are.equal(1000, p.storage[1].level)
  end)

  it("merges production points and factories into one list", function()
    local point = makePoint({ name = "Bakery", owner = 1, owningPlaceable = { uniqueId = "p" } })
    local factory = makeFactory("Mill", 1, "f", { productions = {} })
    setupWorld({ point }, {}, 1, { factory })

    local model = VDT.ProductionExporter.collect()
    assert.are.equal(2, #model.productionPoints)
  end)

  it("reports owned silo placeables as standalone storages, skipping other farms and non-silos", function()
    local mine = makeSilo("Central slurry store", 1, "silo-1", { [12] = 145000 }, { [12] = 300000 })
    local theirs = makeSilo("Neighbour silo", 2, "silo-2", { [12] = 5000 }, { [12] = 100000 })
    -- A production placeable is not a silo (no spec_silo) and its storage is reported under
    -- productionPoints — it must never appear in the standalone storages list.
    local prod = {
      spec_productionPoint = {},
      getOwnerFarmId = function()
        return 1
      end,
    }
    setupWorld({}, { mine, theirs, prod }, 1)

    local model = VDT.ProductionExporter.collect()
    assert.is_nil(model.productionPoints)
    assert.are.equal(1, #model.storages)
    assert.are.equal("silo-1", model.storages[1].id)
    assert.are.equal("fill", model.storages[1].kind)
    assert.are.equal("Central slurry store", model.storages[1].name)
    assert.are.equal("LIQUIDMANURE", model.storages[1].fills[1].type)
    assert.are.equal(145000, model.storages[1].fills[1].level)
  end)

  it("reports object storages with a per-type breakdown, skipping other farms", function()
    local mine = makeObjectStorage("Bale barn", 1, "barn-1", 250, 32, {
      { title = "Round bale (Straw)", count = 20 },
      { title = "Square bale (Hay)", count = 12 },
    }, 30)
    local theirs = makeObjectStorage("Neighbour barn", 2, "barn-2", 250, 5, { { title = "Pallet", count = 5 } })
    setupWorld({}, { mine, theirs }, 1)

    local model = VDT.ProductionExporter.collect()
    assert.are.equal(1, #model.storages)
    local s = model.storages[1]
    assert.are.equal("barn-1", s.id)
    assert.are.equal("object", s.kind)
    assert.are.equal(32, s.count)
    assert.are.equal(250, s.capacity)
    assert.are.equal(30, s.maxUnloadAmount)
    assert.are.equal(2, #s.objects)
    assert.are.equal(1, s.objects[1].index)
    assert.are.equal("Round bale (Straw)", s.objects[1].title)
    assert.are.equal(20, s.objects[1].count)
    assert.are.equal(2, s.objects[2].index)
  end)

  it("shows an empty object storage with no breakdown rows", function()
    local empty = makeObjectStorage("Empty barn", 1, "barn-3", 100, 0, {})
    setupWorld({}, { empty }, 1)

    local model = VDT.ProductionExporter.collect()
    assert.are.equal(1, #model.storages)
    assert.are.equal("object", model.storages[1].kind)
    assert.are.equal(0, model.storages[1].count)
    -- empty arrays are omitted (nil), never {} — the encoder would emit {} which Kotlin rejects
    assert.is_nil(model.storages[1].objects)
  end)

  it("never encodes an empty array as {} (would break the Kotlin parse)", function()
    -- Regression for the reported crash: the Json encoder writes {} for an empty Lua table, which
    -- Kotlin rejects ("expected ["). Every array field must be omitted when empty, so the encoded
    -- model must contain no {} at all. Exercises the widest set of would-be-empty fields at once: an
    -- empty object storage (objects), a production point with no lines (lines/storage).
    local empty = makeObjectStorage("Empty barn", 1, "barn-3", 100, 0, {})
    local point = makePoint({ name = "P", owner = 1, owningPlaceable = { uniqueId = "p" }, productions = {} })
    setupWorld({ point }, { empty }, 1)

    local encoded = Json.encode(VDT.ProductionExporter.collect())
    assert.is_nil(string.find(encoded, "{}", 1, true))
  end)

  it("returns just the version while spectating (no local farm)", function()
    setupWorld({ makePoint({ name = "X", owner = 1 }) }, {}, nil)

    local model = VDT.ProductionExporter.collect()
    assert.are.equal("1", model.version)
    assert.is_nil(model.productionPoints)
    assert.is_nil(model.storages)
  end)
end)
