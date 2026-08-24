-- Unit tests for the husbandry export channel (src/collect/HusbandryExporter.lua).
--
-- Run with `busted` from the vdTelemetry/ directory. The collector reuses ProductionExporter's
-- own-farm + id helpers (loaded first; it self-registers a channel, so ExportChannels ahead of it)
-- and reads FS globals (husbandrySystem, animalSystem). We stub just enough to drive collect().

if VDT == nil or VDT.Farm == nil then
  dofile("src/utils/Farm.lua")
end
if VDT.ExportChannels == nil then
  dofile("src/export/ExportChannels.lua")
end
if VDT.ProductionExporter == nil then
  dofile("src/collect/ProductionExporter.lua")
end
if VDT.HusbandryExporter == nil then
  dofile("src/collect/HusbandryExporter.lua")
end
if Json == nil then
  dofile("src/utils/Json.lua")
end

local function makeCluster(subTypeIndex, count, age, health, reproduction, supportsRepro)
  return {
    health = health,
    reproduction = reproduction,
    getNumAnimals = function()
      return count
    end,
    getAge = function()
      return age
    end,
    getSubTypeIndex = function()
      return subTypeIndex
    end,
    getSupportsReproduction = function()
      return supportsRepro == true
    end,
  }
end

-- `opts.supportedFillTypes` is the pen's unloading station's supported set (fill type index -> true),
-- the list a condition bar's `type` is resolved against; a pen without one keeps no storage of its
-- own and every bar stays untyped.
local function makeHusbandry(opts)
  local station = opts.supportedFillTypes ~= nil and { supportedFillTypes = opts.supportedFillTypes } or nil
  return {
    uniqueId = opts.uniqueId,
    spec_husbandry = { unloadingStation = station },
    getName = function()
      return opts.name
    end,
    getNumOfAnimals = function()
      return opts.numAnimals
    end,
    getMaxNumOfAnimals = function()
      return opts.maxNumAnimals
    end,
    getGlobalProductionFactor = function()
      return opts.productivity
    end,
    getFoodInfos = function()
      return opts.food or {}
    end,
    getConditionInfos = function()
      return opts.conditions or {}
    end,
    getClusters = function()
      return opts.clusters or {}
    end,
  }
end

-- `breedNames` maps a subtype index -> the breed title. The stubbed subtype's fillTypeIndex equals
-- its subtype index, and the fill-type-title lookup uses the same key, mirroring the real chain
-- getSubTypeByIndex(idx).fillTypeIndex -> getFillTypeTitleByIndex(...).
--
-- `fillTypes` maps a fill type index -> { name = ..., title = ... }, the pair behind a condition
-- bar's `type`: the game builds such a bar's title from the fill type's localized `title`, and the
-- exporter matches on it to report the language-independent `name`.
local function installWorld(husbandries, farmId, breedNames, fillTypes)
  _G.g_localPlayer = farmId ~= nil and { farmId = farmId } or nil
  _G.g_fillTypeManager = {
    getFillTypeTitleByIndex = function(_, fillTypeIndex)
      return breedNames and breedNames[fillTypeIndex] or nil
    end,
    getFillTypeByIndex = function(_, fillTypeIndex)
      return fillTypes and fillTypes[fillTypeIndex] or nil
    end,
  }
  _G.g_currentMission = {
    husbandrySystem = {
      getPlaceablesByFarm = function()
        return husbandries
      end,
    },
    animalSystem = {
      getSubTypeByIndex = function(_, subTypeIndex)
        return { fillTypeIndex = subTypeIndex }
      end,
    },
  }
end

after_each(function()
  _G.g_currentMission = nil
  _G.g_localPlayer = nil
  _G.g_fillTypeManager = nil
end)

describe("HusbandryExporter.collect", function()
  it("collects own-farm husbandries with conditions and animal groups", function()
    local pen = makeHusbandry({
      uniqueId = "cowbarn-1",
      name = "Cow Barn",
      numAnimals = 12,
      maxNumAnimals = 20,
      productivity = 0.82,
      food = {
        { title = "Grass (30%)", ratio = 0.2, value = 1000, capacity = 5000 },
        { title = "", ratio = 1 }, -- untitled -> skipped
      },
      conditions = {
        { title = "Water", ratio = 0.65, value = 4500, invertedBar = false },
        { title = "Milk", ratio = 0.7, value = 8000, invertedBar = true },
        -- the game's productivity bar (the valueText info) -> extracted as h.productivity, not a
        -- condition; its ratio (0.89), NOT getGlobalProductionFactor (0.82), is the productivity
        { title = "Productivity", ratio = 0.89, value = 0.89, valueText = "89 %" },
        { title = "", ratio = 1 }, -- untitled -> skipped
      },
      clusters = {
        makeCluster(5, 8, 24, 95, 60, true),
        makeCluster(6, 4, 3, 88, 0, false),
      },
    })
    installWorld({ pen }, 1, { [5] = "Angus", [6] = "Holstein" })

    local model = VDT.HusbandryExporter.collect()

    assert.are.equal("2", model.version)
    assert.are.equal(1, #model.husbandries)
    local h = model.husbandries[1]
    assert.are.equal("cowbarn-1", h.id)
    assert.are.equal("Cow Barn", h.name)
    assert.are.equal(12, h.numAnimals)
    assert.are.equal(20, h.maxNumAnimals)
    -- productivity comes from the game's productivity conditionInfo (0.89), not the 0.82 global factor
    assert.are.equal(0.89, h.productivity)

    -- food comes from getFoodInfos (separate from conditions), with liters + capacity; untitled skipped
    assert.are.equal(1, #h.food)
    assert.are.equal("Grass (30%)", h.food[1].title)
    assert.are.equal(0.2, h.food[1].ratio)
    assert.are.equal(1000, h.food[1].value)
    assert.are.equal(5000, h.food[1].capacity)

    -- untitled + the productivity valueText bar skipped; invertedBar -> inverted; value is liters,
    -- condition bars carry no capacity
    assert.are.equal(2, #h.conditions)
    assert.are.equal("Water", h.conditions[1].title)
    assert.are.equal(0.65, h.conditions[1].ratio)
    assert.are.equal(4500, h.conditions[1].value)
    assert.is_nil(h.conditions[1].capacity)
    assert.is_nil(h.conditions[1].inverted)
    assert.is_true(h.conditions[2].inverted)

    -- breed name is the subtype fill-type title, not the visual's "for beef" description
    assert.are.equal(2, #h.animals)
    assert.are.equal("Angus", h.animals[1].name)
    assert.are.equal(8, h.animals[1].count)
    assert.are.equal(24, h.animals[1].age)
    assert.are.equal(95, h.animals[1].health)
    assert.are.equal(60, h.animals[1].reproduction)
    assert.is_true(h.animals[1].supportsReproduction)
    assert.is_nil(h.animals[2].supportsReproduction)
  end)

  it("returns just the version while spectating (no local farm)", function()
    installWorld({ makeHusbandry({ uniqueId = "x", name = "X" }) }, nil, {})
    local model = VDT.HusbandryExporter.collect()
    assert.are.equal("2", model.version)
    assert.is_nil(model.husbandries)
  end)

  it("omits empty arrays (no {} in the encoded JSON)", function()
    local bare =
      makeHusbandry({ uniqueId = "empty-1", name = "Empty", numAnimals = 0, maxNumAnimals = 0, productivity = 0 })
    installWorld({ bare }, 1, {})

    local model = VDT.HusbandryExporter.collect()
    -- no productivity conditionInfo (like a horse pen) -> productivity omitted
    assert.is_nil(model.husbandries[1].productivity)
    assert.is_nil(model.husbandries[1].food)
    assert.is_nil(model.husbandries[1].conditions)
    assert.is_nil(model.husbandries[1].animals)
    assert.is_nil(string.find(Json.encode(model), "{}", 1, true))
  end)

  it("names what a condition bar's liters are, from the pen's own supported fill types", function()
    local pen = makeHusbandry({
      uniqueId = "cowbarn-1",
      name = "Cow Barn",
      numAnimals = 4,
      maxNumAnimals = 10,
      productivity = 1,
      -- a food GROUP, not a fill type: it never gets a `type`, and is passed no map at all
      food = { { title = "Grass (30%)", ratio = 0.2, value = 1000, capacity = 5000 } },
      conditions = {
        { title = "Water", ratio = 0.5, value = 4500 },
        { title = "Straw", ratio = 0.3, value = 3000 },
        { title = "Manure", ratio = 0.9, value = 9000, invertedBar = true },
        -- the pen holds no eggs: PlaceableHusbandryPallets' liters are not in the unloading
        -- station's set, so the output bar waiting to become a pallet stays unpriced
        { title = "Egg", ratio = 0.4, value = 400, invertedBar = true },
      },
      supportedFillTypes = { [1] = true, [2] = true, [3] = true },
    })
    installWorld({ pen }, 1, {}, {
      [1] = { name = "WATER", title = "Water" },
      [2] = { name = "STRAW", title = "Straw" },
      [3] = { name = "MANURE", title = "Manure" },
    })

    local h = VDT.HusbandryExporter.collect().husbandries[1]
    assert.are.equal("WATER", h.conditions[1].type)
    assert.are.equal("STRAW", h.conditions[2].type)
    assert.are.equal("MANURE", h.conditions[3].type)
    assert.is_nil(h.conditions[4].type)
    assert.is_nil(h.food[1].type)
  end)

  it("leaves a bar untyped when its title is not the plain fill-type title", function()
    -- with no manure heap in range the straw spec appends "(no manure heap)" to the bar's title,
    -- and there being nowhere to put the manure, its level is 0 -- untyped is the honest answer
    local pen = makeHusbandry({
      uniqueId = "cowbarn-1",
      name = "Cow Barn",
      numAnimals = 1,
      maxNumAnimals = 10,
      productivity = 1,
      conditions = { { title = "Manure (no manure heap)", ratio = 0, value = 0, invertedBar = true } },
      supportedFillTypes = { [3] = true },
    })
    installWorld({ pen }, 1, {}, { [3] = { name = "MANURE", title = "Manure" } })

    assert.is_nil(VDT.HusbandryExporter.collect().husbandries[1].conditions[1].type)
  end)

  it("drops a title two supported fill types share rather than guessing which one it is", function()
    local pen = makeHusbandry({
      uniqueId = "cowbarn-1",
      name = "Cow Barn",
      numAnimals = 1,
      maxNumAnimals = 10,
      productivity = 1,
      conditions = { { title = "Water", ratio = 0.5, value = 500 } },
      supportedFillTypes = { [1] = true, [7] = true },
    })
    installWorld({ pen }, 1, {}, {
      [1] = { name = "WATER", title = "Water" },
      [7] = { name = "WATER_MODDED", title = "Water" },
    })

    assert.is_nil(VDT.HusbandryExporter.collect().husbandries[1].conditions[1].type)
  end)

  it("leaves every bar untyped when the pen keeps no storage of its own", function()
    local pen = makeHusbandry({
      uniqueId = "horsepen-1",
      name = "Horse Pen",
      numAnimals = 1,
      maxNumAnimals = 4,
      productivity = 1,
      conditions = { { title = "Water", ratio = 0.5, value = 500 } },
    })
    installWorld({ pen }, 1, {}, { [1] = { name = "WATER", title = "Water" } })

    assert.is_nil(VDT.HusbandryExporter.collect().husbandries[1].conditions[1].type)
  end)

  it("falls back to a generic name when the breed fill-type title can't be resolved", function()
    local pen = makeHusbandry({
      uniqueId = "cowbarn-1",
      name = "Cow Barn",
      numAnimals = 1,
      maxNumAnimals = 10,
      productivity = 1,
      clusters = { makeCluster(99, 1, 10, 100, 0, false) },
    })
    installWorld({ pen }, 1, {}) -- no name for subtype 99
    local model = VDT.HusbandryExporter.collect()
    assert.are.equal("Animal", model.husbandries[1].animals[1].name)
  end)
end)
