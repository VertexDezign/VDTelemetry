-- Unit tests for the storage export channel (src/collect/StorageExporter.lua).
--
-- Run with `busted` from the vdTelemetry/ directory. The collector reads FS globals
-- (g_currentMission / g_fillTypeManager / g_localPlayer), so the tests stub just enough of those to
-- drive collect() offline. ExportChannels must exist first (both collectors call register() at load
-- time), and ProductionExporter must be loaded before StorageExporter — StorageExporter reuses its
-- ownFarmId / placeableId / storageRows helpers.

if VDT == nil or VDT.ExportChannels == nil then
  dofile("src/export/ExportChannels.lua")
end
if VDT.ProductionExporter == nil then
  dofile("src/collect/ProductionExporter.lua")
end
if VDT.StorageExporter == nil then
  dofile("src/collect/StorageExporter.lua")
end
if Json == nil then
  dofile("src/utils/Json.lua")
end

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

local function setupWorld(placeables, farmId)
  _G.g_fillTypeManager = {
    getFillTypeByIndex = function(_, idx)
      return FILL_TYPES[idx]
    end,
  }
  _G.g_localPlayer = farmId ~= nil and { farmId = farmId } or nil
  _G.g_currentMission = {
    placeableSystem = { placeables = placeables or {} },
  }
end

describe("StorageExporter.collect", function()
  after_each(function()
    _G.g_currentMission = nil
    _G.g_fillTypeManager = nil
    _G.g_localPlayer = nil
  end)

  it("reports owned silo placeables as standalone storages, skipping other farms and non-silos", function()
    local mine = makeSilo("Central slurry store", 1, "silo-1", { [12] = 145000 }, { [12] = 300000 })
    local theirs = makeSilo("Neighbour silo", 2, "silo-2", { [12] = 5000 }, { [12] = 100000 })
    -- A production placeable is not a silo (no spec_silo) and its storage is reported by the
    -- production channel — it must never appear in the standalone storages list.
    local prod = {
      spec_productionPoint = {},
      getOwnerFarmId = function()
        return 1
      end,
    }
    setupWorld({ mine, theirs, prod }, 1)

    local model = VDT.StorageExporter.collect()
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
    setupWorld({ mine, theirs }, 1)

    local model = VDT.StorageExporter.collect()
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
    setupWorld({ empty }, 1)

    local model = VDT.StorageExporter.collect()
    assert.are.equal(1, #model.storages)
    assert.are.equal("object", model.storages[1].kind)
    assert.are.equal(0, model.storages[1].count)
    -- empty arrays are omitted (nil), never {} — the encoder would emit {} which Kotlin rejects
    assert.is_nil(model.storages[1].objects)
  end)

  it("never encodes an empty array as {} (would break the Kotlin parse)", function()
    -- Regression for the reported crash: the Json encoder writes {} for an empty Lua table, which
    -- Kotlin rejects ("expected ["). An empty object storage exercises the would-be-empty objects
    -- field while the top-level storages stays a list.
    local empty = makeObjectStorage("Empty barn", 1, "barn-3", 100, 0, {})
    setupWorld({ empty }, 1)

    local encoded = Json.encode(VDT.StorageExporter.collect())
    assert.is_nil(string.find(encoded, "{}", 1, true))
  end)

  it("returns just the version while spectating (no local farm)", function()
    setupWorld({ makeSilo("X", 1, "silo-x", { [12] = 1 }, { [12] = 10 }) }, nil)

    local model = VDT.StorageExporter.collect()
    assert.are.equal("1", model.version)
    assert.is_nil(model.storages)
  end)
end)
