-- Husbandry export channel: the LOCAL player's owned animal husbandries (pens), written to
-- husbandry.json on its OWN interval — animal condition/productivity drift over in-game hours, so
-- this is interval-driven like the production channel, not tied to the 100 ms main tick.
--
-- Reads only base-game state (g_currentMission.husbandrySystem), so it lives in collect/, not
-- integrations/. Every engine read is pcall-guarded (fail-soft house rule). Own-farm only: scoped by
-- the mod-wide VDT.Farm.ownFarmId, with the app-selection id from ProductionExporter.placeableId, so
-- both match the other channels. Absence of husbandry.json means "no data yet / export off", same as
-- the others.
--
-- Each pen exposes the game's own aggregated display data: getConditionInfos() (the food/water/straw/
-- output/cleanliness bars, already localized), getGlobalProductionFactor() (productivity), the
-- animal counts, and getClusters() for the per-group breakdown. A cluster's breed/age label comes
-- from animalSystem:getVisualByAge(subTypeIndex, age).store — the animal store item for that age.
--
-- Since v2 a condition bar also carries the FILL TYPE behind it where it has one (fillTypeByTitle),
-- which makes this channel the sole reporter of the farm's manure and slurry: the heap and the tank
-- ARE the barn's store, so the storage channel deliberately leaves them out (StorageExporter v3,
-- feedsHusbandry) rather than have the stock overview count them twice. A pen that keeps its store
-- INSIDE itself -- no heap placeable anywhere -- was never reported at all before this: the barn in
-- examples/json/husbandry/basic.json holds 3834 l of manure and 233 l of slurry that appear nowhere
-- in the storage capture from the same farm (examples/json/storage/mp_modded.json), which is where
-- this channel became the only place they are named.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.HusbandryExporter = {}

VDT.HusbandryExporter.CHANNEL = "husbandry"
VDT.HusbandryExporter.FILE_NAME = "husbandry.json"
-- Own version, evolving independently of VDTelemetry.VERSION and the shared Kotlin HusbandriesData.
-- 2: `type` (fill type name) on the condition bars whose liters are real storage, which makes this
--    channel the one place the farm's manure and slurry can be priced -- see fillTypeByTitle, and
--    the matching StorageExporter v3, which stopped exporting the placeable holding them.
VDT.HusbandryExporter.VERSION = 2
-- Write cadence in ms. Husbandry state changes over in-game hours, so a 5 s refresh is plenty.
VDT.HusbandryExporter.INTERVAL_MS = 5000

local function num(v)
  return type(v) == "number" and v or 0
end

-- Round a [0,1] ratio to 4 decimals (Json.lua prints floats with %.14g; keep the file compact).
local function ratio(value)
  local r = math.max(0, math.min(1, num(value)))
  return math.floor(r * 10000 + 0.5) / 10000
end

-- Localized bar title -> fill type NAME, for the fill types this one pen's storage holds. This is
-- how a condition bar gets a `type`, and the whole reason the storage channel can stop reporting the
-- manure heap: without it the farm's manure is named only as "Mist", in the player's language, and
-- nothing can price it.
--
-- Matched on the TITLE because the info table carries no fill type index and never has: every
-- fill-backed bar is built as `info.title = fillType.title` and nothing else
-- (PlaceableHusbandryStraw / ...LiquidManure / ...Water / ...Milk each do exactly that), so the
-- title is the only handle back. Candidates are the fill types the pen's UNLOADING STATION supports,
-- which does two jobs at once:
--   * it keeps the map to a handful of entries belonging to THIS pen, so two fill types that share a
--     title elsewhere in the game cannot collide here (and a pair that still does is dropped rather
--     than guessed at);
--   * it selects exactly the bars whose liters are real storage. It is the same test the specs
--     themselves apply before they trust the bar (getHusbandryIsFillTypeSupported, the one they warn
--     "Missing filltype in husbandry storage!" about), and it leaves out the pallet outputs on
--     purpose: PlaceableHusbandryPallets keeps its own fillLevels of liters still WAITING to become
--     an egg pallet, and stock that has no object yet must not be priced -- the pallet on the ground
--     is what storage.json's `loosePallets` counts, once it exists.
-- Food goes untyped too, and is passed no map at all: a food bar is a food GROUP ("Grass (30%)")
-- summed over several fill types, and it lives in spec_husbandryFood's own fillLevels rather than in
-- the pen's storage, so no other channel can double-report it either.
---@param husbandry table a PlaceableHusbandry
---@return table<string, string|boolean> title -> fill type name, or false where the title is ambiguous
local function fillTypeByTitle(husbandry)
  local map = {}
  local spec = husbandry.spec_husbandry
  local station = type(spec) == "table" and spec.unloadingStation or nil
  local supported = type(station) == "table" and station.supportedFillTypes or nil
  if type(supported) ~= "table" or g_fillTypeManager == nil then
    return map
  end
  for fillTypeIndex in pairs(supported) do
    local fillType = g_fillTypeManager:getFillTypeByIndex(fillTypeIndex)
    if fillType ~= nil and type(fillType.title) == "string" and fillType.title ~= "" then
      -- `false` marks a title two supported types share, and is sticky: neither can claim it back
      map[fillType.title] = map[fillType.title] == nil and fillType.name or false
    end
  end
  return map
end

-- One bar row from a condition/food info: the localized title, its [0,1] fill ratio (for the bar),
-- the current liters (info.value) and the capacity when the info carries one (food does; the
-- condition bars don't). invertedBar rides along for the output bars (high = needs emptying).
--
-- `types` (fillTypeByTitle, condition bars only) names what the liters ARE. A title it does not know
-- leaves the row untyped, which is the honest answer in the one case it happens to a fill-backed
-- bar: with no manure heap in range the straw spec appends "(no manure heap)" to the manure bar's
-- title, and there being nowhere to put manure, its level is 0.
local function barRow(info, types)
  local fillTypeName = types ~= nil and types[info.title] or nil
  local row = {
    title = info.title,
    type = type(fillTypeName) == "string" and fillTypeName or nil,
    ratio = ratio(info.ratio),
    value = math.floor(num(info.value)),
    inverted = info.invertedBar == true or nil,
  }
  if type(info.capacity) == "number" and info.capacity > 0 then
    row.capacity = math.floor(info.capacity)
  end
  return row
end

-- Breed label for a cluster: the title of the subtype's fill type. Each breed is its own fill type,
-- so its title is the breed name ("Angus", "Holstein", ...) -- exactly what the game's AnimalScreen,
-- shop and cluster info box use. (The animal visual's store carries only a description like "for
-- beef", which is why that isn't the name.) Falls back to a generic label when unresolvable.
local function clusterName(cluster)
  local system = g_currentMission ~= nil and g_currentMission.animalSystem or nil
  local ftManager = g_fillTypeManager
  if system == nil or ftManager == nil then
    return "Animal"
  end
  local okSub, subTypeIndex = pcall(cluster.getSubTypeIndex, cluster)
  if not okSub then
    return "Animal"
  end
  local okType, subType = pcall(system.getSubTypeByIndex, system, subTypeIndex)
  if okType and type(subType) == "table" and subType.fillTypeIndex ~= nil then
    local okTitle, title = pcall(ftManager.getFillTypeTitleByIndex, ftManager, subType.fillTypeIndex)
    if okTitle and type(title) == "string" and title ~= "" then
      return title
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

  -- Food is a SEPARATE method from getConditionInfos (which covers water/straw/outputs/cleanliness);
  -- getFoodInfos returns one bar per food group ("Grass (30%)", "Total Mixed Ration (100%)", ...).
  local food = {}
  local okFood, foodInfos = pcall(husbandry.getFoodInfos, husbandry)
  if okFood and type(foodInfos) == "table" then
    for _, info in ipairs(foodInfos) do
      if type(info.title) == "string" and info.title ~= "" then
        food[#food + 1] = barRow(info)
      end
    end
  end

  -- The productivity bar is the game's real productivity (globalProductionFactor * productionFactor):
  -- it's the one conditionInfo carrying a valueText, and a 0..1 fraction rather than liters. Extract
  -- it as the headline `productivity` (getGlobalProductionFactor alone reads too high). It's absent
  -- for animals the game doesn't show it for (horses/pigs) -> productivity stays nil -> no top bar.
  local productivity = nil
  local conditions = {}
  local okCond, infos = pcall(husbandry.getConditionInfos, husbandry)
  if okCond and type(infos) == "table" then
    local types = fillTypeByTitle(husbandry)
    for _, info in ipairs(infos) do
      if type(info.valueText) == "string" then
        productivity = ratio(info.ratio)
      elseif type(info.title) == "string" and info.title ~= "" then
        conditions[#conditions + 1] = barRow(info, types)
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
    productivity = productivity, -- nil (omitted) for animals with no productivity bar, e.g. horses
    -- omit empty arrays (nil, not {}): an empty Lua table encodes as {} which the Kotlin lists reject
    food = #food > 0 and food or nil,
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
  local farmId = VDT.Farm.ownFarmId()
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

-- Self-register the channel (see ExportChannels). Interval-driven: the registry owns the cadence.
VDT.ExportChannels.register({
  name = VDT.HusbandryExporter.CHANNEL,
  fileName = VDT.HusbandryExporter.FILE_NAME,
  isAvailable = VDT.HusbandryExporter.isAvailable,
  collect = VDT.HusbandryExporter.collect,
  intervalMs = VDT.HusbandryExporter.INTERVAL_MS,
  -- Only this farm's pens are exported (ownFarmId).
  farmScoped = true,
})
