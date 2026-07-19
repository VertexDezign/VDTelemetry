-- Unit tests for the husbandry export channel (src/collect/HusbandryExporter.lua).
--
-- Run with `busted` from the vdTelemetry/ directory. The collector reuses ProductionExporter's
-- own-farm + id helpers (loaded first; it self-registers a channel, so ExportChannels ahead of it)
-- and reads FS globals (husbandrySystem, animalSystem). We stub just enough to drive collect().

if VDT == nil or VDT.ExportChannels == nil then
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

local function makeHusbandry(opts)
  return {
    uniqueId = opts.uniqueId,
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
local function installWorld(husbandries, farmId, breedNames)
  _G.g_localPlayer = farmId ~= nil and { farmId = farmId } or nil
  _G.g_fillTypeManager = {
    getFillTypeTitleByIndex = function(_, fillTypeIndex)
      return breedNames and breedNames[fillTypeIndex] or nil
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
        { title = "Grass (30%)", ratio = 0.2 },
        { title = "", ratio = 1 }, -- untitled -> skipped
      },
      conditions = {
        { title = "Water", ratio = 0.65, invertedBar = false },
        { title = "Cleanliness", ratio = 0.7, invertedBar = true },
        { title = "", ratio = 1 }, -- untitled -> skipped
      },
      clusters = {
        makeCluster(5, 8, 24, 95, 60, true),
        makeCluster(6, 4, 3, 88, 0, false),
      },
    })
    installWorld({ pen }, 1, { [5] = "Angus", [6] = "Holstein" })

    local model = VDT.HusbandryExporter.collect()

    assert.are.equal("1", model.version)
    assert.are.equal(1, #model.husbandries)
    local h = model.husbandries[1]
    assert.are.equal("cowbarn-1", h.id)
    assert.are.equal("Cow Barn", h.name)
    assert.are.equal(12, h.numAnimals)
    assert.are.equal(20, h.maxNumAnimals)
    assert.are.equal(0.82, h.productivity)

    -- food comes from getFoodInfos (separate from conditions); untitled entry skipped
    assert.are.equal(1, #h.food)
    assert.are.equal("Grass (30%)", h.food[1].title)
    assert.are.equal(0.2, h.food[1].ratio)

    -- untitled condition skipped; invertedBar mapped to inverted (true only)
    assert.are.equal(2, #h.conditions)
    assert.are.equal("Water", h.conditions[1].title)
    assert.are.equal(0.65, h.conditions[1].ratio)
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
    assert.are.equal("1", model.version)
    assert.is_nil(model.husbandries)
  end)

  it("omits empty arrays (no {} in the encoded JSON)", function()
    local bare =
      makeHusbandry({ uniqueId = "empty-1", name = "Empty", numAnimals = 0, maxNumAnimals = 0, productivity = 0 })
    installWorld({ bare }, 1, {})

    local model = VDT.HusbandryExporter.collect()
    assert.is_nil(model.husbandries[1].food)
    assert.is_nil(model.husbandries[1].conditions)
    assert.is_nil(model.husbandries[1].animals)
    assert.is_nil(string.find(Json.encode(model), "{}", 1, true))
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
