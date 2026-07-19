-- Husbandry export channel: the LOCAL player's owned animal husbandries (pens), written to
-- husbandry.json on its OWN interval — animal condition/productivity drift over in-game hours, so
-- this is interval-driven like the production channel, not tied to the 100 ms main tick.
--
-- Reads only base-game state (g_currentMission.husbandrySystem), so it lives in collect/, not
-- integrations/. Every engine read is pcall-guarded (fail-soft house rule). Own-farm only: reuses
-- ProductionExporter.ownFarmId / .placeableId so ownership scoping and the app-selection id match the
-- other channels. Absence of husbandry.json means "no data yet / export off", same as the others.
--
-- Each pen exposes the game's own aggregated display data: getConditionInfos() (the food/water/straw/
-- output/cleanliness bars, already localized), getGlobalProductionFactor() (productivity), the
-- animal counts, and getClusters() for the per-group breakdown. A cluster's breed/age label comes
-- from animalSystem:getVisualByAge(subTypeIndex, age).store — the animal store item for that age.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.HusbandryExporter = {}

VDT.HusbandryExporter.CHANNEL = "husbandry"
VDT.HusbandryExporter.FILE_NAME = "husbandry.json"
-- Own version, evolving independently of VDTelemetry.VERSION and the shared Kotlin HusbandriesData.
VDT.HusbandryExporter.VERSION = 1
-- Write cadence in ms. Husbandry state changes over in-game hours, so a 5 s refresh is plenty.
VDT.HusbandryExporter.INTERVAL_MS = 5000

VDT.HusbandryExporter.timerMs = 0

local function num(v)
  return type(v) == "number" and v or 0
end

-- Round a [0,1] ratio to 4 decimals (Json.lua prints floats with %.14g; keep the file compact).
local function ratio(value)
  local r = math.max(0, math.min(1, num(value)))
  return math.floor(r * 10000 + 0.5) / 10000
end

-- Breed/age label for a cluster: the animal store item for its subtype at its age. Falls back to a
-- generic label when the visual/store can't be resolved.
local function clusterName(cluster)
  local system = g_currentMission ~= nil and g_currentMission.animalSystem or nil
  if system == nil then
    return "Animal"
  end
  local okAge, age = pcall(cluster.getAge, cluster)
  local okSub, subTypeIndex = pcall(cluster.getSubTypeIndex, cluster)
  if not okSub then
    return "Animal"
  end
  local okVis, visual = pcall(system.getVisualByAge, system, subTypeIndex, okAge and age or 0)
  if okVis and type(visual) == "table" and type(visual.store) == "table" then
    local store = visual.store
    if type(store.name) == "string" and store.name ~= "" then
      return store.name
    end
    if type(store.description) == "string" and store.description ~= "" then
      return store.description
    end
  end
  return "Animal"
end

---@param cluster table an AnimalCluster
---@return HusbandryAnimalGroupModel
local function collectAnimalGroup(cluster)
  local okCount, count = pcall(cluster.getNumAnimals, cluster)
  local okAge, age = pcall(cluster.getAge, cluster)
  local okRepro, supportsRepro = pcall(cluster.getSupportsReproduction, cluster)
  return {
    name = clusterName(cluster),
    count = math.floor(okCount and num(count) or 0),
    age = math.floor(okAge and num(age) or 0),
    health = math.floor(num(cluster.health)),
    reproduction = math.floor(num(cluster.reproduction)),
    supportsReproduction = (okRepro and supportsRepro == true) or nil,
  }
end

---@param husbandry table a PlaceableHusbandry
---@param fallbackId string
---@return HusbandryModel
local function collectHusbandry(husbandry, fallbackId)
  local okName, name = pcall(husbandry.getName, husbandry)
  local okNum, numAnimals = pcall(husbandry.getNumOfAnimals, husbandry)
  local okMax, maxAnimals = pcall(husbandry.getMaxNumOfAnimals, husbandry)
  local okProd, productivity = pcall(husbandry.getGlobalProductionFactor, husbandry)

  local conditions = {}
  local okCond, infos = pcall(husbandry.getConditionInfos, husbandry)
  if okCond and type(infos) == "table" then
    for _, info in ipairs(infos) do
      if type(info.title) == "string" and info.title ~= "" then
        conditions[#conditions + 1] = {
          title = info.title,
          ratio = ratio(info.ratio),
          inverted = info.invertedBar == true or nil,
        }
      end
    end
  end

  local animals = {}
  local okClusters, clusters = pcall(husbandry.getClusters, husbandry)
  if okClusters and type(clusters) == "table" then
    for _, cluster in ipairs(clusters) do
      animals[#animals + 1] = collectAnimalGroup(cluster)
    end
  end

  return {
    id = VDT.ProductionExporter.placeableId(husbandry.owningPlaceable or husbandry, fallbackId),
    name = (okName and type(name) == "string" and name ~= "") and name or "Husbandry",
    numAnimals = math.floor(okNum and num(numAnimals) or 0),
    maxNumAnimals = math.floor(okMax and num(maxAnimals) or 0),
    productivity = ratio(okProd and productivity or 0),
    -- omit empty arrays (nil, not {}): an empty Lua table encodes as {} which the Kotlin lists reject
    conditions = #conditions > 0 and conditions or nil,
    animals = #animals > 0 and animals or nil,
  }
end

function VDT.HusbandryExporter.isAvailable()
  return g_currentMission ~= nil and g_currentMission.husbandrySystem ~= nil
end

---Build the husbandries model, or nil when the husbandry system isn't up yet (skips the write).
---@return HusbandriesModel|nil
function VDT.HusbandryExporter.collect()
  if not VDT.HusbandryExporter.isAvailable() then
    return nil
  end
  local farmId = VDT.ProductionExporter.ownFarmId()
  if farmId == nil then
    -- spectator / no owned farm: keep the channel present but empty
    return { version = tostring(VDT.HusbandryExporter.VERSION) }
  end

  local system = g_currentMission.husbandrySystem
  local husbandries = {}
  local okList, placeables = pcall(system.getPlaceablesByFarm, system, farmId)
  if okList and type(placeables) == "table" then
    for index, husbandry in ipairs(placeables) do
      husbandries[#husbandries + 1] = collectHusbandry(husbandry, "husbandry" .. index)
    end
  end

  return {
    version = tostring(VDT.HusbandryExporter.VERSION),
    husbandries = #husbandries > 0 and husbandries or nil,
  }
end

-- Interval-driven: accumulate the frame delta and queue a write every INTERVAL_MS.
function VDT.HusbandryExporter.tick(_, dt)
  if type(dt) ~= "number" then
    return
  end
  VDT.HusbandryExporter.timerMs = VDT.HusbandryExporter.timerMs + dt
  if VDT.HusbandryExporter.timerMs >= VDT.HusbandryExporter.INTERVAL_MS then
    VDT.HusbandryExporter.timerMs = 0
    VDT.ExportChannels.markDirty(VDT.HusbandryExporter.CHANNEL)
  end
end

-- Self-register the channel (see ExportChannels).
VDT.ExportChannels.register({
  name = VDT.HusbandryExporter.CHANNEL,
  fileName = VDT.HusbandryExporter.FILE_NAME,
  isAvailable = VDT.HusbandryExporter.isAvailable,
  collect = VDT.HusbandryExporter.collect,
  tick = VDT.HusbandryExporter.tick,
})
