-- Precision Farming (FS25_precisionFarming): detection, the application rates on a sprayer, and its
-- value maps as ground-layer planes.
--
-- Detection is a shared gate for the channels that must suppress base-game data PF supersedes. It's
-- the internal Precision Farming mod, keyed by its mod name in the shared g_modIsLoaded table; that is
-- the exact gate the game's own code uses (PlayerHUDUpdater / PrecisionFarming's MOD_NAME check), so
-- reading the same table matches the game.
--
-- When PF is installed it replaces the base fertilizer + lime model with its own soil maps and, in
-- FieldInfoDisplayExtension, deactivates the vanilla yield-bonus / fertilized / needs-lime lines. So
-- the channels that mirror the base HUD drop that superseded data when PF is present:
--   * FieldInfoExporter — omits yieldBonus / fertilized / needsLime from the field-info popup.
--   * MapLayersExporter — omits the fertilized + needs-lime soil layers from the ground overlay.
--
-- The application rates are the second part: what the tool in the player's hands is putting on the
-- ground, read off PF's ExtendedSprayer spec. That spec hangs off the vehicle under a plain string
-- key, so it is reachable from here; see collectSprayer for which half of it survives multiplayer.
--
-- LAYERS below is the third: PF's own value maps, exported as extra planes of the mapLayers
-- channel. PF registers seven maps (PrecisionFarming:registerValueMap) but only five are menu-visible
-- — cover and tramline return false from getShowInMenu — and those five are exactly the ones with a
-- legend a player reads: soil type, pH, nitrogen, yield and seed rate.
--
-- Reading them per world position, the way the sweep needs, is what shapes the code below:
--   * soil / pH / nitrogen expose a documented point read (getTypeIndexAtWorldPos /
--     getLevelAtWorldPos) that already resolves world -> map cell and returns 0 for ground the
--     player's cover map hasn't uncovered (unsampled farmland). We call those.
--   * yield and seed rate have no point read at all — PF only ever writes them by area and draws them
--     with the GPU overlay calls. So we do what their own reads do: convert the position with PF's
--     formula and pull the cell out of the bit-vector map (channel 0, as their DensityMapModifiers
--     use). Guarded like everything else here.
--
-- Every read of PF's internals is fail-soft (see the pcall-wrapped resolve below): a PF update is free
-- to rename any of this, and the cost of being wrong must be a missing overlay, not a broken sweep.
--
-- Written against the internal Precision Farming shipped with FS25 (its scripts/maps/*.lua).
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.PrecisionFarming = {}

-- The internal mod's name (its folder / customEnv), the key it registers under in g_modIsLoaded.
VDT.PrecisionFarming.MOD_NAME = "FS25_precisionFarming"

-- Fields this integration adds to the object model, declared here next to the code that sets them
-- (see EnhancedVehicle.lua for why). `level`/`target` are real units -- kg N/ha and pH -- converted
-- from PF's internal levels the same way its own HUD does.
---@class PfValueModel
---@field level number
---@field target number
---@field unit string?

-- One ~2 m slice across the boom (PF's own sub-division), left to right. `valid` is PF's isValid: it
-- has a reading here. An invalid slice is off the field, on unsampled ground, or the tool is doing
-- something that isn't liming or fertilizing.
---@class PfSubSectionModel
---@field valid boolean
---@field n number?
---@field nTarget number?
---@field ph number?
---@field phTarget number?

-- The sub-sections of one work area, joined to WorkAreaModel by `index`.
---@class PfWorkAreaModel
---@field index number
---@field subSections PfSubSectionModel[]

-- The boom's nozzles, left to right. `active` is what is *actually coming out* right now, which is a
-- different question from the shutoff sections: it already folds in the section, the direction and
-- speed, spot spraying's weed detection and the "this ground is already fertilized" skip.
-- `individual` is false on a machine PF switches a whole section at a time.
---@class PfNozzlesModel
---@field count number
---@field activeCount number
---@field individual boolean
---@field active boolean[]

-- `mode` is what the tool is currently doing with what is in its tank. `nitrogen`/`ph` are the
-- averages over the whole boom -- the numbers PF's own HUD shows -- and are network-synced, so they
-- are there for every player. `workAreas` is the per-slice detail, which is not: see the note on
-- collectSprayer.
---@class PrecisionFarmingModel
---@field mode string LIME | FERTILIZER | OTHER
---@field auto boolean
---@field nitrogen PfValueModel? only while fertilizing -- PF stops maintaining it otherwise
---@field ph PfValueModel? only while liming, for the same reason
---@field spotSpray boolean?
---@field workAreas PfWorkAreaModel[]?
---@field nozzles PfNozzlesModel?

---@class VehicleModel
---@field precisionFarming PrecisionFarmingModel?

---@class ImplementModel
---@field precisionFarming PrecisionFarmingModel?

---True when the Precision Farming mod is loaded. g_modIsLoaded is a shared engine global (populated
---in mods.lua), readable from any mod environment — so this matches the game's own gate exactly.
---@return boolean
function VDT.PrecisionFarming.isActive()
  return type(g_modIsLoaded) == "table" and g_modIsLoaded[VDT.PrecisionFarming.MOD_NAME] == true
end

---PF's own singleton. **Mod-environment isolation** (see farm-page-plan.md, and CropRotation.lua which
---resolves the same way): FS25 gives each mod its own Lua env, so PF's `g_precisionFarming` is a global
---in *its* env, not in the shared `_G` — from here the bare global is nil, and it has to be reached
---through the env global named after the mod. Only shared engine tables (`g_currentMission`,
---`g_modIsLoaded`, `MathUtil`) are readable directly.
---
---The bare global is still tried as a fallback, so this keeps working if a future version does put it
---in `_G`.
---@return table|nil
local function pfInstance()
  local env = type(FS25_precisionFarming) == "table" and FS25_precisionFarming or nil
  local instance = (env ~= nil and env.g_precisionFarming) or g_precisionFarming
  return type(instance) == "table" and instance or nil
end

---The live value map PF registered under `name` (it assigns each one onto itself by name), or nil when
---PF isn't loaded / hasn't built it yet.
---@param name string PF's own field name, e.g. "soilMap"
---@return table|nil
local function valueMap(name)
  if not VDT.PrecisionFarming.isActive() then
    return nil
  end
  local instance = pfInstance()
  local map = instance ~= nil and instance[name] or nil
  return type(map) == "table" and map or nil
end

---True when PF is installed but its singleton can't be reached from here — the mod-environment trap
---above. Distinguishes "no PF" from "PF, but we can't see it", which is the difference between the
---layers being correctly absent and being silently broken (see MapLayersExporter's one-shot warning).
---@return boolean
function VDT.PrecisionFarming.isUnreachable()
  return VDT.PrecisionFarming.isActive() and pfInstance() == nil
end

-- ---------------------------------------------------------------------------
-- Application rates on the tool (the section view -- gps-course-plan.md §4)
-- ---------------------------------------------------------------------------

-- PF's ExtendedSprayer spec, under the key it builds for itself:
-- `"spec_" .. g_currentModName .. ".extendedSprayer"` (ExtendedSprayer.lua:3). That is a plain string
-- key on the vehicle table, so unlike PF's globals it is readable from here without the mod-env dance
-- above -- the same reason `subSectionData` is reachable at all.
VDT.PrecisionFarming.SPRAYER_SPEC = "spec_" .. VDT.PrecisionFarming.MOD_NAME .. ".extendedSprayer"

-- PF's per-nozzle effects, on the same kind of key. Only the sprayers PF ships node data for have
-- this spec populated -- and those are exactly the machines where it takes the base game's width
-- controls away (ExtendedSprayerEffects.lua:101-105 removes VariableWorkWidth's onRegisterActionEvents
-- and onDraw), so where the shutoff bar freezes, this is what replaces it.
VDT.PrecisionFarming.EFFECTS_SPEC = "spec_" .. VDT.PrecisionFarming.MOD_NAME .. ".extendedSprayerEffects"

-- Spot spraying, a purchasable configuration (WeedSpotSpray.lua:28). It matters to a reader of the
-- nozzle bar: with it on, a boom running at 40% is covering the whole width and skipping the clean
-- ground -- without it, 40% just means most of the boom is folded away.
VDT.PrecisionFarming.SPOT_SPRAY_SPEC = "spec_" .. VDT.PrecisionFarming.MOD_NAME .. ".weedSpotSpray"

---Convert one of PF's internal levels to the value a player reads, through the map's own converter
---(NitrogenMap:getNitrogenValueFromInternalValue / PHMap:getPhValueFromInternalValue). Levels are
---small integers indexing a value table; the real numbers -- kg N/ha, a pH -- only exist in the map.
---@param map table|nil the tool's own value map (spec.nitrogenMap / spec.pHMap)
---@param converter string the map method to call
---@param internal any PF's stored level
---@param maxValue number|nil clamp, as PF's HUD clamps nitrogen to the map's maximum
---@return number|nil nil when the map, the method or the value is unusable
local function realValue(map, converter, internal, maxValue)
  if type(map) ~= "table" or type(internal) ~= "number" or type(map[converter]) ~= "function" then
    return nil
  end
  local value = math.max(0, internal)
  if type(maxValue) == "number" then
    value = math.min(value, maxValue)
  end
  local ok, real = pcall(map[converter], map, value)
  if not ok or type(real) ~= "number" then
    return nil
  end
  return tonumber(ValueMapper.mapFloat(real))
end

---A level/target pair, or nil when neither says anything (0 is PF's "no reading here").
---@param map table|nil
---@param converter string
---@param level any
---@param target any
---@param unit string|nil
---@return PfValueModel|nil
local function valuePair(map, converter, level, target, unit)
  local maxValue = type(map) == "table" and map.maxValue or nil
  local actual = realValue(map, converter, level, maxValue)
  local wanted = realValue(map, converter, target, maxValue)
  if actual == nil and wanted == nil then
    return nil
  end
  if (actual or 0) <= 0 and (wanted or 0) <= 0 then
    return nil
  end
  return { level = actual or 0, target = wanted or 0, unit = unit }
end

---The per-slice detail for one work area, or nil when PF keeps none for it.
---@param spec table the ExtendedSprayer spec
---@param area table a base-game work area
---@return PfWorkAreaModel|nil
local function collectSubSections(spec, area)
  local data = area.subSectionData
  local count = area.numSubSections
  if type(data) ~= "table" or type(count) ~= "number" or count <= 0 then
    return nil
  end

  local nitrogen, ph = spec.nitrogenMap, spec.pHMap
  local nMax = type(nitrogen) == "table" and nitrogen.maxValue or nil
  local phMax = type(ph) == "table" and ph.maxValue or nil

  local subSections = {}
  for i = 1, count do
    local slice = data[i]
    if type(slice) == "table" then
      subSections[#subSections + 1] = {
        valid = slice.isValid == true,
        n = realValue(nitrogen, "getNitrogenValueFromInternalValue", slice.nitrogenLevel, nMax),
        nTarget = realValue(nitrogen, "getNitrogenValueFromInternalValue", slice.nitrogenTargetLevel, nMax),
        ph = realValue(ph, "getPhValueFromInternalValue", slice.phLevel, phMax),
        phTarget = realValue(ph, "getPhValueFromInternalValue", slice.phTargetLevel, phMax),
      }
    end
  end

  if #subSections == 0 then
    return nil
  end
  return { index = area.index, subSections = subSections }
end

---The boom's nozzles, left to right, or nil when PF drives no per-nozzle effects on this machine.
---
---Unlike the sub-sections above this is **not** server-only: the states are recomputed in
---`ExtendedSprayerEffects:onUpdate` with no `isServer` gate (`:187-203`), because they drive what the
---player sees coming out of the boom. So this is the one per-position signal that survives
---multiplayer -- and the only one that says anything at all with herbicide in the tank, where PF
---computes no rates and every sub-section reads invalid.
---
---Each state already folds in everything that can stop a nozzle: its section being off
---(`:361`), reversing or crawling (`WeedSpotSpray.lua:118-120`), spot spraying finding no weed under
---it (`:123-131`), and liquid fertilizer skipping ground that already has some (`:142-160`).
---@param object table a vehicle or implement
---@return PfNozzlesModel|nil
local function collectNozzles(object)
  local spec = object[VDT.PrecisionFarming.EFFECTS_SPEC]
  if type(spec) ~= "table" or type(spec.sprayerEffects) ~= "table" then
    return nil
  end

  local nozzles = {}
  for _, effect in ipairs(spec.sprayerEffects) do
    if type(effect) == "table" then
      nozzles[#nozzles + 1] = { x = tonumber(effect.xOffset) or 0, active = effect.isActive == true }
    end
  end
  if #nozzles == 0 then
    return nil
  end

  -- Sorted rather than taken in the spec's order, which comes out of a `pairs()` walk of PF's node
  -- XML. `xOffset` is the nozzle's lateral offset, measured once at load
  -- (`ExtendedSprayerEffects.lua:249`), and positive means the LEFT side -- that is how PF itself
  -- reads it, looking a positive offset up in `sectionsLeft` (`:264-271`). So descending x is left to
  -- right across the boom, matching the order the shutoff sections come in.
  table.sort(nozzles, function(a, b)
    return a.x > b.x
  end)

  local active, activeCount = {}, 0
  for index, nozzle in ipairs(nozzles) do
    active[index] = nozzle.active
    if nozzle.active then
      activeCount = activeCount + 1
    end
  end

  return {
    count = #nozzles,
    activeCount = activeCount,
    individual = spec.individualNozzleControl == true,
    active = active,
  }
end

---Whether this machine has PF's spot-spray configuration fitted. Nil (rather than false) when the
---spec is absent entirely, so "no such machine" stays distinct from "fitted, switched off".
---@param object table
---@return boolean|nil
local function spotSprayEnabled(object)
  local spec = object[VDT.PrecisionFarming.SPOT_SPRAY_SPEC]
  if type(spec) ~= "table" then
    return nil
  end
  return spec.isEnabled == true
end

---Application rates for one object, or nil when it is not a PF sprayer/spreader.
---
---**The parts have different reach.** `nitrogen`/`ph` are the boom averages PF streams to every
---client (ExtendedSprayer.lua:180-206, plus its own value event), so they are there for everyone. The
---per-slice `workAreas` are refreshed inside `if self.isServer` (:212-255), so on a multiplayer client
---they are simply absent -- which is why they are optional rather than the primary shape, and why the
---app has to draw a readout from the averages and treat the strip as detail on top. `nozzles` is the
---exception that survives multiplayer; see collectNozzles.
---@param object table a vehicle or implement
---@return PrecisionFarmingModel|nil
function VDT.PrecisionFarming.collectSprayer(object)
  if not VDT.PrecisionFarming.isActive() then
    return nil
  end
  local spec = object[VDT.PrecisionFarming.SPRAYER_SPEC]
  if type(spec) ~= "table" then
    return nil
  end

  -- Both flags come from getCurrentSprayerMode, refreshed in onUpdateTick on client and server alike;
  -- neither is set for a tool spraying herbicide, which is a mode PF has no rates for.
  local mode = "OTHER"
  if spec.isLiming then
    mode = "LIME"
  elseif spec.isFertilizing then
    mode = "FERTILIZER"
  end

  -- Each reading is emitted ONLY in the mode that maintains it, which is the same branch PF's own HUD
  -- picks. It has to be: `nitrogenLevel` is read under `if spec.isFertilizing` and `phLevel` under
  -- `if spec.isLiming` (ExtendedSprayer.lua:714-719), and the aggregates they feed are never reset --
  -- so a sprayer that fertilized this morning and is spraying herbicide now still holds this morning's
  -- nitrogen, possibly from another field. Emitting that would put a stale number next to a live
  -- nozzle bar, which is the one place it would be believed.
  ---@type PrecisionFarmingModel
  local model = {
    mode = mode,
    auto = spec.sprayAmountAutoMode ~= false,
    nitrogen = spec.isFertilizing and valuePair(
      spec.nitrogenMap,
      "getNitrogenValueFromInternalValue",
      spec.nActualValue,
      spec.nTargetValue,
      "kg/ha"
    ) or nil,
    ph = spec.isLiming and valuePair(spec.pHMap, "getPhValueFromInternalValue", spec.phActualValue, spec.phTargetValue)
      or nil,
    spotSpray = spotSprayEnabled(object),
    -- Off a different spec, but the same machine and the same question, so it rides here rather than
    -- becoming a second subtree. ExtendedSprayerEffects requires ExtendedSprayer
    -- (`prerequisitesPresent`), so gating both on this one loses nothing.
    nozzles = collectNozzles(object),
  }

  -- Walked from the base-game work areas rather than PF's own three lists, so the exported `index`
  -- is the one WorkAreaModel carries and the two can be joined.
  local workAreaSpec = object.spec_workArea
  local areas = {}
  for _, area in ipairs(workAreaSpec ~= nil and workAreaSpec.workAreas or {}) do
    areas[#areas + 1] = collectSubSections(spec, area)
  end
  if #areas > 0 then
    model.workAreas = areas
  end

  return model
end

-- Object stage: runs per vehicle/implement during the walk (see registry.lua). Self-propelled
-- sprayers are vehicles and trailed ones implements, so both go through the same hook.
---@param object table a vehicle or implement
---@param model table the object's already core-collected model
function VDT.PrecisionFarming.contributeObject(object, model)
  model.precisionFarming = VDT.PrecisionFarming.collectSprayer(object)
end

-- ---------------------------------------------------------------------------
-- Value maps as ground-layer planes
-- ---------------------------------------------------------------------------

---Cell coordinates for a world position on a PF value map, transcribed from the maps' own point reads
---(NitrogenMap:getLevelAtWorldPos and friends, which all repeat this). Only needed for the two maps
---that expose no point read of their own.
---
---Deliberately `g_currentMission.terrainSize`, NOT the frame the sweep walks (which prefers the HUD
---map's worldSizeX): this has to land on the same cell PF's own reads would, and every one of them
---uses terrainSize. Deriving it from the sweep's frame instead would silently disagree with the
---soil/pH/nitrogen planes right next to these, which go through PF's readers.
---@param map table a PF value map
---@param x number world x
---@param z number world z
---@return number cellX, number cellZ
local function mapCell(map, x, z)
  local size = g_currentMission.terrainSize
  return MathUtil.round((x + size * 0.5) / size * map.sizeX + 0.5) - 1,
    MathUtil.round((z + size * 0.5) / size * map.sizeY + 0.5) - 1
end

---A point read for a map that has none: pull the cell straight out of its bit-vector map. PF's own
---modifiers for these maps are built as DensityMapModifier.new(bitVectorMap, 0, numChannels), so the
---values live in channels 0..numChannels-1.
---
---Out-of-range cells read as 0 ("no data") rather than being clamped to the edge. The sweep walks the
---frame the HUD map reports, which can be larger than mission.terrainSize (they disagree on some
---maps), so a position past the terrain is possible -- and the honest answer for it is "nothing here",
---not a copy of the border cell smeared outwards. It also keeps the engine call off out-of-range
---coordinates, whose behaviour isn't documented.
---@param map table a PF value map
---@return fun(x: number, z: number): number
local function bitVectorReader(map)
  return function(x, z)
    local cellX, cellZ = mapCell(map, x, z)
    if cellX < 0 or cellZ < 0 or cellX >= map.sizeX or cellZ >= map.sizeY then
      return 0
    end
    return getBitVectorMapPoint(map.bitVectorMap, cellX, cellZ, 0, map.numChannels) or 0
  end
end

---Legend lookup from one of PF's value tables: wire value -> { label, color }. `keyField` names the
---field holding the map's internal value; nil means the entry's position in the list is the value
---(the soil map, whose point read returns a 1-based type index).
---@param values table[] PF's per-value table (soilTypes / pHValues / nitrogenValues / ...)
---@param keyField string|nil
---@param label fun(entry: table): string
---@param colorBlind boolean use PF's colorblind palette
---@return table<number, table> value -> { label, color }
local function legendFrom(values, keyField, label, colorBlind)
  local legend = {}
  for index, entry in ipairs(values) do
    local value = keyField ~= nil and entry[keyField] or index
    if type(value) == "number" and value > 0 then
      -- PF carries a colorblind variant next to every color, exactly as the base game does; fall back
      -- to the default when a value has none (PF leaves it nil in places).
      local color = (colorBlind and entry.colorBlind) or entry.color
      legend[value] = { label = label(entry), color = color }
    end
  end
  return legend
end

-- The planes, in the order PF lists them in its own map selector. Each entry:
--   id        wire id, also the file name (mapLayers/<id>.json) and the /api/map-layer path segment
--   mapName   PF's field name for the value map
--   fallback  label when PF's own l10n can't be reached (its keys live in the mod's namespace)
--   build     (map, colorBlind) -> { sample = fun(x, z): number, legend = { [value] = { label, color } } }
--
-- A cell value of 0 means "no data here" in every plane -- ground the cover map hasn't uncovered,
-- never-sampled farmland, a field never harvested -- and renders transparent, like every other plane.
VDT.PrecisionFarming.LAYERS = {
  {
    id = "pfSoilType",
    mapName = "soilMap",
    fallback = "Soil type",
    build = function(map, colorBlind)
      return {
        sample = function(x, z)
          return map:getTypeIndexAtWorldPos(x, z) or 0
        end,
        -- The point read returns a 1-based index into soilTypes, not a stored "value" field.
        legend = legendFrom(map.soilTypes, nil, function(entry)
          return entry.name
        end, colorBlind),
      }
    end,
  },
  {
    id = "pfPh",
    mapName = "pHMap",
    fallback = "pH",
    build = function(map, colorBlind)
      return {
        sample = function(x, z)
          return map:getLevelAtWorldPos(x, z) or 0
        end,
        legend = legendFrom(map.pHValues, "value", function(entry)
          return string.format("%.1f", entry.realValue or 0)
        end, colorBlind),
      }
    end,
  },
  {
    id = "pfNitrogen",
    mapName = "nitrogenMap",
    fallback = "Nitrogen",
    build = function(map, colorBlind)
      return {
        sample = function(x, z)
          return map:getLevelAtWorldPos(x, z) or 0
        end,
        legend = legendFrom(map.nitrogenValues, "value", function(entry)
          return string.format("%d kg/ha", entry.realValue or 0)
        end, colorBlind),
      }
    end,
  },
  {
    id = "pfYield",
    mapName = "yieldMap",
    fallback = "Yield",
    build = function(map, colorBlind)
      return {
        sample = bitVectorReader(map),
        legend = legendFrom(map.yieldValues, "value", function(entry)
          return string.format("%d%%", entry.displayValue or 0)
        end, colorBlind),
      }
    end,
  },
  {
    id = "pfSeedRate",
    mapName = "seedRateMap",
    fallback = "Seed rate",
    build = function(map, colorBlind)
      return {
        sample = bitVectorReader(map),
        legend = legendFrom(map.rateValues, "value", function(entry)
          return entry.text or "?"
        end, colorBlind),
      }
    end,
  },
}

---Whether this plane's value map is there to be read. Checked before the catalogue offers the plane
---and before a sweep tries to sample it, so a PF that isn't installed (or a map it didn't build for
---this save) simply isn't a layer.
---@param layer table an entry of LAYERS
---@return boolean
function VDT.PrecisionFarming.isLayerAvailable(layer)
  return valueMap(layer.mapName) ~= nil
end

---Display name for a plane, from the value map's own overview label — so it reads exactly as PF's map
---selector does, in the player's language. PF's l10n keys live in its mod namespace, which
---g_i18n:getText can't resolve from here, so we ask the map rather than the key.
---@param layer table an entry of LAYERS
---@return string
function VDT.PrecisionFarming.layerLabel(layer)
  local map = valueMap(layer.mapName)
  if map ~= nil and type(map.getOverviewLabel) == "function" then
    local ok, label = pcall(map.getOverviewLabel, map)
    if ok and type(label) == "string" and label ~= "" then
      return label
    end
  end
  return layer.fallback
end

---Resolve one plane against the live PF maps: a sampler and its legend, or nil when the map is
---unavailable or its internals no longer look like this. Called once per sweep, so the cost of the
---pcall + the legend build is paid once, not per cell.
---@param layer table an entry of LAYERS
---@param colorBlind boolean|nil use PF's colorblind palette for the legend
---@return table|nil { sample = fun(x, z): number, legend = table<number, table> }
function VDT.PrecisionFarming.resolveLayer(layer, colorBlind)
  local map = valueMap(layer.mapName)
  if map == nil then
    return nil
  end
  local ok, built = pcall(layer.build, map, colorBlind == true)
  if not ok or type(built) ~= "table" or type(built.sample) ~= "function" then
    return nil
  end
  -- Probe it once, at the map center. The sweep samples ~262k cells with no per-cell guard (the batch
  -- is pcall'd as a whole, like the base-game reads), so a map that throws -- PF holding a nil cover
  -- map, an engine call that has changed -- would abort every sweep this channel ever runs. One read
  -- up front turns that into "this plane is not available", which the rest of the channel handles.
  local readable = pcall(built.sample, 0, 0)
  if not readable then
    return nil
  end
  return built
end
