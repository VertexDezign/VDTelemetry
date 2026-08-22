-- Aspect collector: mixer wagon -- the feed recipe it is mixing to, how much of each ingredient is in
-- the tub, and whether the drum is actually turning. Applies to any object (vehicle or implement):
-- the spec sits on towed mixers and on self-propelled ones alike. Namespaced under VDT.* (see
-- TurnOn.lua).
--
-- What the game shows is MixerWagonHUDExtension: one bar per recipe ingredient, the ingredient's
-- allowed [min, max] window drawn as a band, and a marker at the ingredient's CURRENT SHARE OF THE
-- LOAD. Three things about that are worth writing down, because getting any of them wrong makes the
-- bars lie:
--
-- 1. The share is `ingredient / sum(ingredients)` -- a share of what is loaded, NOT of capacity. The
--    HUD sums the ingredient levels itself rather than reading the fill unit, and so must anything
--    downstream. This collector therefore exports per-ingredient LITRES and leaves the division to
--    the panel: the sum is the honest denominator, and it is the same one the game divides by.
-- 2. Whether the mix is good is NOT re-derived here. The engine decides it inside
--    MixerWagon:addFillUnitFillLevel and records the answer as the tub's fill type: the recipe's own
--    fill type when every ingredient sits inside its window, FORAGE_MIXING when one does not, and the
--    ingredient's own fill type when only one thing is loaded. `fillType` below is that answer, read
--    rather than recomputed -- which also keeps us out of a decompiled control-flow tangle.
-- 3. `minPercentage`/`maxPercentage` come from the map's animalFood.xml, so the ingredient count,
--    their names and their windows are MAP DATA. Nothing downstream may assume three bars, a fixed
--    set of materials, or that FORAGE is what comes out.
--
-- A mixer wagon with no `#recipe` in its XML (or one naming a recipe the map does not define) loads
-- with an empty `mixerWagonFillTypes` and behaves as a plain trailer that happens to have a drum. The
-- aspect is still emitted for it -- the tub, the mixing time and the drum are all still true -- just
-- with no ingredients.
--
-- MULTIPLAYER: fine, by an unusual route. The mixer's fill unit is deliberately taken OUT of the
-- normal fill-unit sync (`synchronizeFillLevel = false` in MixerWagon:onLoad); the per-ingredient
-- levels ride MixerWagon's own onReadStream / onReadUpdateStream instead, and the client re-applies
-- them through addFillUnitFillLevel -- which is also what sets `activeTimer`. So both the ingredient
-- levels and the mixing state survive on a client.
--
-- Deliberately NOT collected: `spec.baleTriggers` (built under `if self.isServer` in onLoad, so it is
-- nil on a client) and the bale-not-accepted warning (an event, not state -- a poll cannot see it).

VDT = VDT or {}
VDT.Mixer = {}

-- FillType.UNKNOWN -- an empty tub. Reported as absent rather than as a fill type named "UNKNOWN",
-- matching aspects/FillUnit.lua and aspects/Spraying.lua.
local FILL_TYPE_UNKNOWN = 1

-- Trailer.TIPSTATE_OPENING / TIPSTATE_OPEN -- the two states in which the engine keeps the drum
-- turning to push material out (MixerWagon:onUpdate).
local TIPSTATE_OPENING = 1
local TIPSTATE_OPEN = 2

---@param index number|nil
---@return table|nil the fill type record, or nil when there is no such fill type
local function fillTypeAt(index)
  if type(index) ~= "number" or type(g_fillTypeManager) ~= "table" then
    return nil
  end
  local ok, desc = pcall(g_fillTypeManager.getFillTypeByIndex, g_fillTypeManager, index)
  if not ok or type(desc) ~= "table" then
    return nil
  end
  return desc
end

---Tonnes per litre of `fillTypeIndex`. The engine stores massPerLiter scaled by
---FillTypeManager.MASS_SCALE, so it is unscaled here exactly as integrations/PrecisionFarming.lua
---does before weighing anything.
---@param fillTypeIndex number|nil
---@return number|nil
local function massPerLiter(fillTypeIndex)
  local desc = fillTypeAt(fillTypeIndex)
  local scale = type(FillTypeManager) == "table" and FillTypeManager.MASS_SCALE or nil
  if desc == nil or type(desc.massPerLiter) ~= "number" or type(scale) ~= "number" or scale == 0 then
    return nil
  end
  return desc.massPerLiter / scale
end

---The fill types an ingredient accepts, as a list of indices SORTED ascending.
---
---`entry.fillTypes` is a set (fillTypeIndex -> true) and `next()` over a set has no defined order.
---The game's own HUD takes `next(...)` for the ingredient icon and gets away with it, because an icon
---that flickers between two grasses is not a number anybody reads. Ours are numbers and a name, so
---the order is pinned instead: same list, same first entry, every export.
---@param set table|nil
---@return number[]
local function sortedFillTypes(set)
  local indices = {}
  for index in pairs(set or {}) do
    if type(index) == "number" then
      indices[#indices + 1] = index
    end
  end
  table.sort(indices)
  return indices
end

---The map recipe this machine mixes to.
---
---MixerWagon:onLoad copies each ingredient's name, window and ratio out of the recipe but drops two
---things a panel wants: the authored `title` that labels the bar, and the recipe's own fill type,
---which is what "the mix is finished" actually MEANS (it is FORAGE in the base game, but a recipe is
---map data and a mod may define another). Both are found back here by matching the ingredient names
---in order, which is what the copy preserved. Fail-soft: the aspect is worth emitting without it.
---@param entries table[] spec.mixerWagonFillTypes
---@return table|nil
local function findRecipe(entries)
  local system = type(g_currentMission) == "table" and g_currentMission.animalFoodSystem or nil
  local recipes = type(system) == "table" and system.recipes or nil
  if type(recipes) ~= "table" then
    return nil
  end

  for _, recipe in ipairs(recipes) do
    local ingredients = recipe.ingredients
    if type(ingredients) == "table" and #ingredients == #entries then
      local match = true
      for i, ingredient in ipairs(ingredients) do
        if ingredient.name ~= entries[i].name then
          match = false
          break
        end
      end
      if match then
        return recipe
      end
    end
  end
  return nil
end

---One bar's worth of state.
---@param entry table one spec.mixerWagonFillTypes entry
---@param authored table|nil the matching recipe ingredient, when the recipe was found
---@return MixerIngredientModel
local function ingredientOf(entry, authored)
  local fillTypes = sortedFillTypes(entry.fillTypes)

  local names = {}
  for _, index in ipairs(fillTypes) do
    local desc = fillTypeAt(index)
    if desc ~= nil and desc.name ~= nil then
      names[#names + 1] = desc.name
    end
  end

  -- The authored label first; the material's own title is the fallback, and reads fine for the common
  -- single-material ingredient ("Silage").
  local title = authored ~= nil and authored.title or nil
  if title == nil then
    local desc = fillTypeAt(fillTypes[1])
    title = desc ~= nil and desc.title or nil
  end

  ---@type MixerIngredientModel
  local model = {
    name = entry.name,
    title = title,
    fillTypes = names,
    minPercentage = tonumber(ValueMapper.mapPercentage(entry.minPercentage or 0, 0)),
    maxPercentage = tonumber(ValueMapper.mapPercentage(entry.maxPercentage or 1, 0)),
    value = tonumber(ValueMapper.mapFloat(entry.fillLevel or 0, 2)),
  }

  -- Weighed only when the ingredient pools a single material. An ingredient that accepts several
  -- (grass and hay, say) holds ONE pooled litre count with no record of which of them went in, so any
  -- weight for it would be whichever density we picked -- plausible and wrong. Absent instead; the
  -- machine's payload as a whole is exact and lives on the mass aspect.
  if #fillTypes == 1 then
    local perLiter = massPerLiter(fillTypes[1])
    if perLiter ~= nil then
      model.mass = tonumber(ValueMapper.mapFloat((entry.fillLevel or 0) * perLiter, 3))
    end
  end

  return model
end

---@param object table a vehicle or implement
---@return MixerModel|nil nil when the object is not a mixer wagon
function VDT.Mixer.collect(object)
  local spec = object.spec_mixerWagon
  if spec == nil then
    return nil
  end

  local fillUnitIndex = spec.fillUnitIndex
  -- getIsPowered returns `isPowered, warning` -- only the first is ours. (Same arity trap as
  -- getSprayerDoubledAmountActive; see aspects/Spraying.lua.)
  local powered = object:getIsPowered() == true

  -- The engine's own condition for spinning the drum (MixerWagon:onUpdate): a mix cycle still
  -- running, the pickup switched on, or material on its way out. Note `isTurnedOn` on a mixer wagon
  -- is the PICKUP -- it starts the pickup animation nodes and gates bale loading -- so "the drum is
  -- turning" is deliberately not the same question as the isTurnedOn aspect sitting next to it.
  local trailer = object.spec_trailer
  local tipState = trailer ~= nil and trailer.tipState or nil
  local discharging = tipState == TIPSTATE_OPENING or tipState == TIPSTATE_OPEN
  local activeTimer = spec.activeTimer or 0

  ---@type MixerModel
  local model = {
    running = powered and (0 < activeTimer or object:getIsTurnedOn() == true or discharging),
    powered = powered,
    -- `activeTimer` is decremented every tick the drum turns and is never clamped, so on a machine
    -- left switched on it runs far negative. Only its positive part is a remaining time.
    remaining = math.max(0, math.floor(activeTimer)),
    mixingTime = math.floor(spec.activeTimerMax or 0),
    value = tonumber(ValueMapper.mapFloat(object:getFillUnitFillLevel(fillUnitIndex) or 0, 2)),
    capacity = math.floor(object:getFillUnitCapacity(fillUnitIndex) or 0),
  }

  -- What is in the tub right now, which is also the engine's verdict on the mix: the recipe's fill
  -- type once every ingredient is inside its window, FORAGE_MIXING while one is not, the single
  -- ingredient's own type while only one is loaded.
  local loadedIndex = object:getFillUnitFillType(fillUnitIndex)
  if loadedIndex == FILL_TYPE_UNKNOWN then
    -- An empty tub weighs nothing, and saying so is the point: this is the number the panel prints,
    -- and it has to reach zero.
    model.mass = 0
  else
    local loaded = fillTypeAt(loadedIndex)
    if loaded ~= nil then
      model.fillType = loaded.name
      model.title = loaded.title
    end
    -- What the load itself weighs, and the ONLY honest way to get it.
    --
    -- The obvious route -- the mass aspect's `value` minus its `empty` -- is not a payload and never
    -- was. Vehicle:updateMass adds *everything* getAdditionalComponentMass returns: every fill unit
    -- the machine has including the diesel and DEF tanks, a hard-attached implement's whole mass, the
    -- tension belts. On the captured Kuhn SPW that difference carries a constant ~835 kg with an empty
    -- tub, and an empty wagon read 617 kg of "load" in a real game.
    --
    -- So the tub is weighed on its own, with the same arithmetic the engine uses for this very unit
    -- (FillUnit:getAdditionalComponentMass): level x the density of whatever the tub reports. Note
    -- that is the density of the MIX, not of its parts -- it does not equal the ingredients' masses
    -- summed, and it is right where that sum would be incomplete (a pooled ingredient has no weight).
    local perLiter = massPerLiter(loadedIndex)
    if perLiter ~= nil then
      model.mass = tonumber(ValueMapper.mapFloat(model.value * perLiter, 3))
    end
  end

  local entries = spec.mixerWagonFillTypes or {}
  local recipe = findRecipe(entries)
  if recipe ~= nil then
    local desc = fillTypeAt(recipe.fillType)
    model.recipe = desc ~= nil and desc.name or nil
  end

  if 0 < #entries then
    local ingredients = {}
    for i, entry in ipairs(entries) do
      local authored = recipe ~= nil and recipe.ingredients ~= nil and recipe.ingredients[i] or nil
      ingredients[i] = ingredientOf(entry, authored)
    end
    model.ingredients = ingredients
  end

  return model
end
