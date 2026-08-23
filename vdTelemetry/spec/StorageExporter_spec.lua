-- Unit tests for the storage export channel (src/collect/StorageExporter.lua).
--
-- Run with `busted` from the vdTelemetry/ directory. The collector reads FS globals
-- (g_currentMission / g_fillTypeManager / g_localPlayer / g_baleManager, plus the Bale class), so the
-- tests stub just enough of those to drive collect() offline. ExportChannels must exist first (both collectors call register() at load
-- time), and ProductionExporter must be loaded before StorageExporter — StorageExporter reuses its
-- ownFarmId / placeableId / storageRows helpers.

if VDT == nil or VDT.Farm == nil then
  dofile("src/utils/Farm.lua")
end
if VDT.ExportChannels == nil then
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
  -- index 1 is FillType.UNKNOWN, the engine's "holds nothing nameable" default: everything that can
  -- carry a fill type has to skip it rather than group a row under it.
  [1] = { name = "UNKNOWN", title = "Unknown" },
  [10] = { name = "MANURE", title = "Manure" },
  [11] = { name = "FERMENTERMANURE", title = "Fermenter manure" },
  [12] = { name = "LIQUIDMANURE", title = "Slurry" },
  [20] = { name = "STRAW", title = "Straw" },
  [21] = { name = "GRASS_WINDROW", title = "Grass" },
  [22] = { name = "SILAGE", title = "Silage" },
  [23] = { name = "CHAFF", title = "Chaff" },
  [30] = { name = "WOOL", title = "Wool" },
  [31] = { name = "FERTILIZER", title = "Fertilizer" },
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

-- A manure-heap placeable (spec_manureHeap). Its ManureHeap is not a Storage subclass, but answers the
-- same three questions, which is the whole reason it can come through the silo path.
local function makeManureHeap(name, owner, uniqueId, level, capacity)
  local heap = makeStorage({ [10] = level }, { [10] = capacity })
  heap.ownerFarmId = owner
  return {
    uniqueId = uniqueId,
    spec_manureHeap = { manureHeap = heap },
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
    -- Two shapes of abstract object, both of which the collector has to read: a fermenting bale keeps
    -- a live Bale behind getRealObject(), everything else survives as a flat attributes table.
    local abstract = {
      getDialogText = function()
        return g.title
      end,
      getRealObject = function()
        return g.real
      end,
    }
    if g.real == nil and g.fillType ~= nil then
      if g.pallet then
        abstract.palletAttributes = { fillType = g.fillType, fillLevel = g.level, isBigBag = g.bigBag }
      else
        abstract.baleAttributes = {
          fillType = g.fillType,
          fillLevel = g.level,
          xmlFilename = g.round and "round.xml" or "square.xml",
        }
      end
    end
    objectInfos[#objectInfos + 1] = { numObjects = g.count, objects = { abstract } }
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

-- A live Bale standing in for the one a fermenting stored bale keeps (getRealObject()).
local function makeStoredRealBale(fillType, level)
  return {
    xmlFilename = "round.xml",
    getFillType = function()
      return fillType
    end,
    getFillLevel = function()
      return level
    end,
  }
end

-- A BunkerSilo, as the placeable specs hand it over. Defaults are the engine's: chaff in, silage out.
local function makeSilage(state, fillLevel, opts)
  opts = opts or {}
  return {
    state = state,
    fillLevel = fillLevel,
    inputFillType = opts.input or 23,
    outputFillType = opts.output or 22,
    compactedPercent = opts.compacted or 0,
    fermentingPercent = opts.fermenting or 0,
  }
end

-- A bunker placeable: one silo (spec_bunkerSilo) or a row of bays (spec_multiBunkerSilo).
local function makeBunker(name, owner, uniqueId, silos)
  local placeable = {
    uniqueId = uniqueId,
    getOwnerFarmId = function()
      return owner
    end,
    getName = function()
      return name
    end,
  }
  if #silos == 1 then
    placeable.spec_bunkerSilo = { bunkerSilo = silos[1] }
  else
    placeable.spec_multiBunkerSilo = { bunkerSilos = silos }
  end
  return placeable
end

-- One entry of itemSystem.sortedItemsToSave holding a Bale. `opts.round` picks the bale XML the
-- stubbed bale manager reports as round; `opts.fermenting` sets the wrapped-and-working flag.
local function makeBale(owner, fillType, fillLevel, opts)
  opts = opts or {}
  return {
    className = "Bale",
    item = {
      ownerFarmId = owner,
      fillType = fillType,
      fillLevel = fillLevel,
      xmlFilename = opts.round and "round.xml" or "square.xml",
      isFermenting = opts.fermenting == true,
      isa = function(_, class)
        return class == Bale
      end,
    },
  }
end

-- A non-Bale item sharing the same list (the game keeps forestry logs there too).
local function makeNonBaleItem()
  return {
    className = "ForestryLog",
    item = {
      ownerFarmId = 1,
      isa = function()
        return false
      end,
    },
  }
end

-- A pallet vehicle. `unit` is the pallet spec's fillUnitIndex — the collector must read that one and
-- not simply the first, which is what a two-unit pallet is here to prove.
local function makePallet(owner, fillType, fillLevel, opts)
  opts = opts or {}
  local unit = opts.unit or 1
  return {
    isPallet = true,
    spec_pallet = { fillUnitIndex = unit },
    spec_bigBag = opts.bigBag and {} or nil,
    getOwnerFarmId = function()
      return owner
    end,
    getFillUnitFillType = function(_, index)
      return index == unit and fillType or 1
    end,
    getFillUnitFillLevel = function(_, index)
      return index == unit and fillLevel or 0
    end,
  }
end

local function setupWorld(placeables, farmId, items, vehicles)
  _G.g_fillTypeManager = {
    getFillTypeByIndex = function(_, idx)
      return FILL_TYPES[idx]
    end,
  }
  -- The engine class the bale walk identifies its items by; only its identity matters here.
  _G.Bale = {}
  _G.g_baleManager = {
    getBaleInfoByXMLFilename = function(_, filename)
      return filename == "round.xml"
    end,
  }
  _G.g_localPlayer = farmId ~= nil and { farmId = farmId } or nil
  _G.g_currentMission = {
    placeableSystem = { placeables = placeables or {} },
    itemSystem = { sortedItemsToSave = items or {} },
    vehicleSystem = { vehicles = vehicles or {} },
  }
end

describe("StorageExporter.collect", function()
  after_each(function()
    _G.g_currentMission = nil
    _G.g_fillTypeManager = nil
    _G.g_localPlayer = nil
    _G.g_baleManager = nil
    _G.Bale = nil
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
      { title = "Round bale (Straw)", count = 20, fillType = 20, level = 4000, round = true },
      -- a fermenting bale is kept as a live object rather than an attributes table
      { title = "Round bale (Grass)", count = 12, real = makeStoredRealBale(21, 3800) },
      { title = "Big bag (Fertilizer)", count = 3, fillType = 31, level = 1000, pallet = true, bigBag = true },
      { title = "Pallet (Wool)", count = 1, fillType = 30, level = 900, pallet = true },
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
    assert.are.equal(4, #s.objects)
    assert.are.equal(1, s.objects[1].index)
    assert.are.equal("Round bale (Straw)", s.objects[1].title)
    assert.are.equal(20, s.objects[1].count)
    assert.are.equal(2, s.objects[2].index)
    -- `level` is ONE object's liters (a group is identical objects, fill level included), so the
    -- group holds level * count -- read off the attributes table for the first...
    assert.are.equal("STRAW", s.objects[1].type)
    assert.are.equal(4000, s.objects[1].level)
    -- ...and off the live object for the second.
    assert.are.equal("GRASS_WINDROW", s.objects[2].type)
    assert.are.equal(3800, s.objects[2].level)
    -- What each row IS comes out in the same vocabulary the loose rows use, so a reader can union the
    -- two lists without parsing the localized title. Which attributes table the abstract object has is
    -- the discriminator; a live bale is a bale by construction.
    assert.are.equal("BALE", s.objects[1].kind)
    assert.are.equal("ROUND", s.objects[1].shape)
    assert.are.equal("BALE", s.objects[2].kind)
    assert.are.equal("ROUND", s.objects[2].shape)
    assert.are.equal("BIGBAG", s.objects[3].kind)
    assert.is_nil(s.objects[3].shape)
    assert.are.equal("PALLET", s.objects[4].kind)
    assert.is_nil(s.objects[4].shape)
  end)

  it("leaves an object-storage row's fill type out when it cannot be read", function()
    -- A modded stored object with neither a live object nor an attributes table: the group still
    -- counts (the total is what the unload dialog addresses), it just names no resource.
    local barn = makeObjectStorage("Odd barn", 1, "barn-4", 50, 3, { { title = "Something", count = 3 } })
    setupWorld({ barn }, 1)

    local row = VDT.StorageExporter.collect().storages[1].objects[1]
    assert.are.equal(3, row.count)
    assert.is_nil(row.type)
    assert.is_nil(row.level)
    assert.is_nil(row.shape)
    assert.is_nil(row.kind)
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

  it("reports a manure heap as a liter storage, like the slurry tank it is the solid twin of", function()
    -- A Misthaufen keeps its heap on spec_manureHeap, not spec_silo, which is why it was invisible
    -- until someone went looking for theirs. It reads through the identical row helper.
    local heap = makeManureHeap("Misthaufen", 1, "heap-1", 42000, 200000)
    local theirs = makeManureHeap("Nachbars Misthaufen", 2, "heap-2", 1000, 200000)
    setupWorld({ heap, theirs }, 1)

    local model = VDT.StorageExporter.collect()
    assert.are.equal(1, #model.storages)
    local s = model.storages[1]
    assert.are.equal("heap-1", s.id)
    assert.are.equal("fill", s.kind)
    assert.are.equal("Misthaufen", s.name)
    assert.are.equal(1, #s.fills)
    assert.are.equal("MANURE", s.fills[1].type)
    assert.are.equal(42000, s.fills[1].level)
    assert.are.equal(200000, s.fills[1].capacity)
  end)

  it("still says what a stored object IS when it holds no fill type", function()
    -- A crate or a vegetable pallet has FillType.UNKNOWN -- the game's own dialog text drops the liter
    -- figure for exactly those. It is still a pallet the farm owns, so the row keeps its kind.
    local barn = makeObjectStorage("Lager", 1, "barn-5", 100, 2, {
      { title = "Palette (Gemüsepalette)", count = 2, fillType = 1, pallet = true },
    })
    setupWorld({ barn }, 1)

    local row = VDT.StorageExporter.collect().storages[1].objects[1]
    assert.are.equal(2, row.count)
    assert.are.equal("PALLET", row.kind)
    assert.is_nil(row.type)
    assert.is_nil(row.level)
  end)

  it("reports a filling bunker with its input fill type and compaction", function()
    -- While the heap is open the game names what is being driven in (chaff), and prints compaction.
    local bunker = makeBunker("Fahrsilo", 1, "bunker-1", { makeSilage(0, 84000, { compacted = 62 }) })
    setupWorld({ bunker }, 1)

    local model = VDT.StorageExporter.collect()
    assert.are.equal(1, #model.bunkerSilos)
    local silo = model.bunkerSilos[1]
    assert.are.equal("bunker-1", silo.id)
    assert.are.equal("Fahrsilo", silo.name)
    assert.are.equal("FILL", silo.state)
    assert.are.equal("CHAFF", silo.type)
    assert.are.equal("Chaff", silo.title)
    assert.are.equal(84000, silo.level)
    assert.are.equal(62, silo.compacted)
    -- fermentation means nothing to an open heap, so it is left out rather than reported as zero
    assert.is_nil(silo.fermenting)
  end)

  it("reports a covered bunker as its output fill type, with fermentation rounded up", function()
    -- Covered, the density map holds the TARP fermenting type -- but the game says silage and so do
    -- we. 0.615 -> 62%, the ceiling the game's own readout takes.
    local bunker = makeBunker("Fahrsilo", 1, "bunker-2", { makeSilage(1, 240000, { fermenting = 0.615 }) })
    setupWorld({ bunker }, 1)

    local silo = VDT.StorageExporter.collect().bunkerSilos[1]
    assert.are.equal("CLOSED", silo.state)
    assert.are.equal("SILAGE", silo.type)
    assert.are.equal(62, silo.fermenting)
    assert.is_nil(silo.compacted)
  end)

  it("gives every bay of a multi-bay bunker its own row, and skips other farms", function()
    local mine = makeBunker("Fahrsilo", 1, "bunker-3", {
      makeSilage(3, 120000),
      makeSilage(0, 0, { compacted = 0 }),
    })
    local theirs = makeBunker("Nachbarsilo", 2, "bunker-4", { makeSilage(0, 5000) })
    setupWorld({ mine, theirs }, 1)

    local silos = VDT.StorageExporter.collect().bunkerSilos
    assert.are.equal(2, #silos)
    -- the placeable's name is the same for every bay, so the bay number goes on both id and name
    assert.are.equal("bunker-3_1", silos[1].id)
    assert.are.equal("Fahrsilo 1", silos[1].name)
    assert.are.equal("DRAIN", silos[1].state)
    assert.are.equal("bunker-3_2", silos[2].id)
    assert.are.equal("Fahrsilo 2", silos[2].name)
    -- an empty bay is still a bay: it is reported, at zero
    assert.are.equal(0, silos[2].level)
  end)

  it("groups loose bales by fill type and shape, counting the fermenting ones", function()
    local items = {
      makeBale(1, 20, 4000, { round = true }),
      makeBale(1, 20, 3900, { round = true }),
      makeBale(1, 20, 2200), -- same straw, square: its own group
      makeBale(1, 21, 3600, { round = true, fermenting = true }),
      makeBale(1, 21, 3600, { round = true, fermenting = true }),
      makeBale(1, 21, 3600, { round = true }), -- grass, wrapped or not, is grass until it ferments
      makeBale(2, 20, 4000, { round = true }), -- the neighbour's
      makeBale(0, 20, 4000, { round = true }), -- a contract's: owned by nobody
      makeNonBaleItem(),
    }
    setupWorld({}, 1, items)

    local bales = VDT.StorageExporter.collect().looseBales
    assert.are.equal(3, #bales)
    -- sorted by type, then kind, then shape; every bale row says so in the same word a stored one does
    assert.are.equal("GRASS_WINDROW", bales[1].type)
    assert.are.equal("BALE", bales[1].kind)
    assert.are.equal("ROUND", bales[1].shape)
    assert.are.equal(3, bales[1].count)
    assert.are.equal(10800, bales[1].level)
    assert.are.equal(2, bales[1].fermenting)

    assert.are.equal("STRAW", bales[2].type)
    assert.are.equal("ROUND", bales[2].shape)
    assert.are.equal(2, bales[2].count)
    assert.are.equal(7900, bales[2].level)
    assert.is_nil(bales[2].fermenting)

    assert.are.equal("STRAW", bales[3].type)
    assert.are.equal("SQUARE", bales[3].shape)
    assert.are.equal(1, bales[3].count)
    assert.is_true(bales[2].kind == "BALE" and bales[3].kind == "BALE")
  end)

  it("groups loose pallets by fill type and form, skipping empties with no fill type", function()
    local vehicles = {
      makePallet(1, 30, 1000),
      makePallet(1, 30, 750),
      makePallet(1, 31, 2000, { bigBag = true }), -- a big bag is its own form
      makePallet(1, 31, 500), -- ...and a plain pallet of the same stuff its own row
      makePallet(1, 1, 0), -- FillType.UNKNOWN: no resource to report
      makePallet(2, 30, 1000), -- the neighbour's
      {
        getOwnerFarmId = function() -- a machine, not a pallet
          return 1
        end,
      },
    }
    setupWorld({}, 1, nil, vehicles)

    local pallets = VDT.StorageExporter.collect().loosePallets
    assert.are.equal(3, #pallets)
    assert.are.equal("FERTILIZER", pallets[1].type)
    assert.are.equal("BIGBAG", pallets[1].kind)
    assert.are.equal(1, pallets[1].count)
    assert.are.equal(2000, pallets[1].level)

    assert.are.equal("FERTILIZER", pallets[2].type)
    assert.are.equal("PALLET", pallets[2].kind)

    assert.are.equal("WOOL", pallets[3].type)
    assert.are.equal("PALLET", pallets[3].kind)
    assert.are.equal(2, pallets[3].count)
    assert.are.equal(1750, pallets[3].level)
    -- shape is a bale's business only; a pallet row never carries one
    assert.is_true(pallets[1].shape == nil and pallets[2].shape == nil and pallets[3].shape == nil)
  end)

  it("reads the fill unit the pallet spec names, not the first one", function()
    setupWorld({}, 1, nil, { makePallet(1, 30, 900, { unit = 2 }) })

    local pallets = VDT.StorageExporter.collect().loosePallets
    assert.are.equal(1, #pallets)
    assert.are.equal("WOOL", pallets[1].type)
    assert.are.equal(900, pallets[1].level)
  end)

  it("omits the new blocks entirely when the farm holds none of them", function()
    -- Empty arrays are never encoded ({} would break the Kotlin parse); a farm with one silo and
    -- nothing else must leave all three new keys absent.
    setupWorld({ makeSilo("Slurry", 1, "silo-1", { [12] = 1000 }, { [12] = 5000 }) }, 1)

    local model = VDT.StorageExporter.collect()
    assert.are.equal(1, #model.storages)
    assert.is_nil(model.bunkerSilos)
    assert.is_nil(model.looseBales)
    assert.is_nil(model.loosePallets)
    assert.is_nil(string.find(Json.encode(model), "{}", 1, true))
  end)

  it("returns just the version while spectating (no local farm)", function()
    setupWorld({ makeSilo("X", 1, "silo-x", { [12] = 1 }, { [12] = 10 }) }, nil)

    local model = VDT.StorageExporter.collect()
    assert.are.equal("3", model.version)
    assert.is_nil(model.storages)
  end)
end)
