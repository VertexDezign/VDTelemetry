-- Precision Farming (FS25_precisionFarming): detection, plus its value maps as ground-layer planes.
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
-- LAYERS below is the other half: PF's own value maps, exported as extra planes of the mapLayers
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

---Cell coordinates for a world position on a PF value map, transcribed from the maps' own point reads
---(NitrogenMap:getLevelAtWorldPos and friends, which all repeat this). Only needed for the two maps
---that expose no point read of their own.
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
---@param map table a PF value map
---@return fun(x: number, z: number): number
local function bitVectorReader(map)
  return function(x, z)
    local cellX, cellZ = mapCell(map, x, z)
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
