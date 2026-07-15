-- Map export channel: near-static map data (POIs + fields) written to map.json, event-driven like
-- the integration channels but reading only base-game state, so it lives in collect/, not
-- integrations/. Unlike the integration channels, absence of map.json means "no data yet", not
-- "mod not installed" — the startup stale-file cleanup may briefly delete it on a slow map load
-- (fields not ready by the first update tick), but the initial markDirty() on subscribe rewrites
-- it as soon as the field list is up, so nothing is lost.
--
-- Coordinates are exported in the normalized [0,1] map space the player marker already uses
-- (IngameMap: norm = (world + worldSize/2) / worldSize, world origin at the terrain center), so the
-- app projects everything with the same math. terrainSize rides along for consumers that need to
-- convert back to meters (e.g. a future ground-layer raster grid).
--
-- POIs come from the HUD map's hotspot list filtered to placeable hotspots (placeableType is only
-- set on PlaceableHotspot) — one list covers sellpoints, shops, productions and animal pens, with
-- the game's own type enum. Farms ride along with their in-game map color (linear RGB converted to
-- sRGB hex) so the app can tint ownership the way the game does. Fields come from g_fieldManager;
-- the border polygon nodes are resolved
-- to world coordinates, thinned to MIN_POINT_SPACING_M and capped at MAX_POLYGON_POINTS so a big
-- map can't bloat the file. All engine reads are guarded — a failed polygon degrades the field to
-- its label, a failed hotspot is skipped; writeDirty()'s pcall contains anything that still throws.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.MapExporter = {}

VDT.MapExporter.CHANNEL = "map"
VDT.MapExporter.FILE_NAME = "map.json"
-- Own version, evolving independently of VDTelemetry.VERSION and the shared Kotlin MapData.
VDT.MapExporter.VERSION = 1

-- Polygon thinning: drop border points closer than this (meters) to the last kept one, then cap the
-- total. 5 m is well below anything visible on a dashboard map; 256 points outlines even the most
-- jagged field faithfully.
VDT.MapExporter.MIN_POINT_SPACING_M = 5
VDT.MapExporter.MAX_POLYGON_POINTS = 256

-- Json.lua prints floats with %.14g; 5 decimals of a [0,1] coordinate is ~2 cm on a 2 km map and
-- keeps the polygons compact.
local COORD_FACTOR = 1e5

---Round a normalized coordinate to 5 decimals.
---@param value number
---@return number
function VDT.MapExporter.roundCoord(value)
  return math.floor(value * COORD_FACTOR + 0.5) / COORD_FACTOR
end

---World coordinate -> normalized [0,1] map coordinate (world origin at the terrain center), rounded.
---@param world number world x or z in meters
---@param worldSize number terrain edge length in meters along that axis
---@return number
function VDT.MapExporter.normalizeCoord(world, worldSize)
  local norm = (world + worldSize * 0.5) / worldSize
  return VDT.MapExporter.roundCoord(math.max(0, math.min(1, norm)))
end

---Thin a flat polygon [x1,z1,x2,z2,...] (world meters): keep the first point, then only points at
---least minDist from the last kept one; if still over maxPoints, keep every ceil(n/maxPoints)-th.
---@param points number[] flat world coordinates
---@param minDist number minimum spacing in meters
---@param maxPoints number hard cap on the number of points
---@return number[] flat world coordinates
function VDT.MapExporter.decimate(points, minDist, maxPoints)
  local minDistSq = minDist * minDist
  local out = {}
  local lastX, lastZ
  for i = 1, #points - 1, 2 do
    local x, z = points[i], points[i + 1]
    if lastX == nil or (x - lastX) * (x - lastX) + (z - lastZ) * (z - lastZ) >= minDistSq then
      out[#out + 1] = x
      out[#out + 1] = z
      lastX, lastZ = x, z
    end
  end
  local n = #out / 2
  if n > maxPoints then
    local step = math.ceil(n / maxPoints)
    local capped = {}
    for i = 0, n - 1, step do
      capped[#capped + 1] = out[i * 2 + 1]
      capped[#capped + 1] = out[i * 2 + 2]
    end
    out = capped
  end
  return out
end

-- One linear-RGB channel (the game's color space) -> sRGB (the dashboard's), IEC 61966-2-1.
local function channelToSrgb(c)
  c = math.max(0, math.min(1, c))
  if c <= 0.0031308 then
    return 12.92 * c
  end
  return 1.055 * c ^ (1 / 2.4) - 0.055
end

---The game's linear-RGB color (Farm:getColor() and friends) as an sRGB "#rrggbb" hex string, so the
---app can render exactly what the engine's gamma pipeline puts on screen.
---@param r number
---@param g number
---@param b number
---@return string
function VDT.MapExporter.linearToSrgbHex(r, g, b)
  return string.format(
    "#%02x%02x%02x",
    math.floor(channelToSrgb(r) * 255 + 0.5),
    math.floor(channelToSrgb(g) * 255 + 0.5),
    math.floor(channelToSrgb(b) * 255 + 0.5)
  )
end

-- "PRODUCTION_POINT" -> "productionPoint": the wire vocabulary is the game's enum key, camelCased,
-- so it stays stable even if Giants renumbers the enum.
local function camelToken(key)
  local lower = string.lower(key)
  return (string.gsub(lower, "_(%a)", string.upper))
end

local tokenByType, tokenSource -- lazy reverse map of PlaceableHotspot.TYPE, value -> token

---Wire token for a PlaceableHotspot.TYPE value ("unloading", "shop", "productionPoint", ...);
---"other" when the enum isn't reachable or the value is unknown.
---@param placeableType any
---@param types table<string, number>|nil enum override for tests; defaults to PlaceableHotspot.TYPE
---@return string
function VDT.MapExporter.poiTypeToken(placeableType, types)
  types = types or (PlaceableHotspot ~= nil and PlaceableHotspot.TYPE or nil)
  if types == nil or placeableType == nil then
    return "other"
  end
  if tokenByType == nil or tokenSource ~= types then
    tokenByType = {}
    tokenSource = types
    for key, value in pairs(types) do
      if type(key) == "string" then
        tokenByType[value] = camelToken(key)
      end
    end
  end
  return tokenByType[placeableType] or "other"
end

---Terrain edge lengths (meters) for normalization: the HUD map's worldSize (the exact values the
---player marker is normalized with), falling back to the mission terrainSize. Shared with the
---vehicle channel (MapVehiclesExporter) so every overlay uses the same frame. Requires
---g_currentMission; returns nil, nil while the size can't be resolved.
---@return number|nil sizeX, number|nil sizeZ
function VDT.MapExporter.resolveWorldSize()
  local hud = g_currentMission.hud
  local map = hud ~= nil and hud.ingameMap or nil
  local sizeX = map ~= nil and map.worldSizeX or nil
  local sizeZ = map ~= nil and map.worldSizeZ or nil
  if type(sizeX) ~= "number" or sizeX <= 0 then
    sizeX = g_currentMission.terrainSize
  end
  if type(sizeZ) ~= "number" or sizeZ <= 0 then
    sizeZ = g_currentMission.terrainSize
  end
  if type(sizeX) ~= "number" or sizeX <= 0 or type(sizeZ) ~= "number" or sizeZ <= 0 then
    return nil, nil
  end
  return sizeX, sizeZ
end

---@param sizeX number
---@param sizeZ number
---@return MapPoiModel[]
local function collectPois(sizeX, sizeZ)
  local pois = {}
  local hud = g_currentMission.hud
  local map = hud ~= nil and hud.ingameMap or nil
  local hotspots = map ~= nil and map.hotspots or nil
  if hotspots == nil then
    return pois
  end

  for _, hotspot in ipairs(hotspots) do
    -- placeableType only exists on PlaceableHotspot -> naturally excludes vehicle/player/field/AI
    -- hotspots (vehicles become their own channel later, fields are exported below with polygons).
    if hotspot ~= nil and hotspot.placeableType ~= nil then
      local okVisible, visible = pcall(hotspot.getIsVisible, hotspot)
      if not okVisible or visible ~= false then
        local okPos, worldX, worldZ = pcall(hotspot.getWorldPosition, hotspot)
        if okPos and type(worldX) == "number" and type(worldZ) == "number" then
          ---@type MapPoiModel
          local poi = {
            type = VDT.MapExporter.poiTypeToken(hotspot.placeableType),
            posX = VDT.MapExporter.normalizeCoord(worldX, sizeX),
            posZ = VDT.MapExporter.normalizeCoord(worldZ, sizeZ),
          }
          local okName, name = pcall(hotspot.getName, hotspot)
          if okName and type(name) == "string" and name ~= "" then
            poi.name = name
          end
          -- AccessHandler.EVERYONE (0) is the "no owner" default -> omit, matching the field rule.
          local owner = hotspot.ownerFarmId
          if type(owner) == "number" and owner ~= (AccessHandler ~= nil and AccessHandler.EVERYONE or 0) then
            poi.ownerFarmId = owner
          end
          pois[#pois + 1] = poi
        end
      end
    end
  end
  return pois
end

-- Border polygon as a flat normalized [x1,z1,...] array, or nil when any node fails to resolve
-- (the field then degrades to its label). getPolygonPoints() returns scene node ids.
local function collectPolygon(field, sizeX, sizeZ)
  local okNodes, nodes = pcall(field.getPolygonPoints, field)
  if not okNodes or type(nodes) ~= "table" or #nodes < 3 then
    return nil
  end

  local worldPoints = {}
  for _, node in ipairs(nodes) do
    local ok, x, _, z = pcall(getWorldTranslation, node)
    if not ok or type(x) ~= "number" or type(z) ~= "number" then
      return nil
    end
    worldPoints[#worldPoints + 1] = x
    worldPoints[#worldPoints + 1] = z
  end

  local thinned =
    VDT.MapExporter.decimate(worldPoints, VDT.MapExporter.MIN_POINT_SPACING_M, VDT.MapExporter.MAX_POLYGON_POINTS)
  if #thinned < 6 then
    return nil
  end

  local polygon = {}
  for i = 1, #thinned - 1, 2 do
    polygon[#polygon + 1] = VDT.MapExporter.normalizeCoord(thinned[i], sizeX)
    polygon[#polygon + 1] = VDT.MapExporter.normalizeCoord(thinned[i + 1], sizeZ)
  end
  return polygon
end

-- The farms with their in-game map colors (Farm:getColor() — per-farm palette color in MP, the
-- fixed singleplayer green in SP, exactly what the game's own farmlands overlay uses). The
-- spectator farm (id 0) is skipped; a farm whose color can't be read exports without one.
---@return MapFarmModel[]
local function collectFarms()
  local farms = {}
  local manager = g_farmManager
  if manager == nil or manager.farms == nil then
    return farms
  end

  for _, farm in ipairs(manager.farms) do
    if type(farm.farmId) == "number" and farm.farmId > 0 and farm.isSpectator ~= true then
      ---@type MapFarmModel
      local entry = { id = farm.farmId }
      if type(farm.name) == "string" and farm.name ~= "" then
        entry.name = farm.name
      end
      local ok, color = pcall(farm.getColor, farm)
      if ok and type(color) == "table" and type(color[1]) == "number" then
        entry.color = VDT.MapExporter.linearToSrgbHex(color[1], color[2] or 0, color[3] or 0)
      end
      farms[#farms + 1] = entry
    end
  end
  return farms
end

---@param sizeX number
---@param sizeZ number
---@return MapFieldModel[]
local function collectFields(sizeX, sizeZ)
  local fields = {}
  if g_fieldManager == nil then
    return fields
  end

  for _, field in ipairs(g_fieldManager.fields or {}) do
    -- label anchor: the game's own field-number node, which already falls back to the polygon center
    local okLabel, labelX, labelZ = pcall(field.getIndicatorPosition, field)
    if okLabel and type(labelX) == "number" and type(labelZ) == "number" then
      ---@type MapFieldModel
      local entry = {
        labelX = VDT.MapExporter.normalizeCoord(labelX, sizeX),
        labelZ = VDT.MapExporter.normalizeCoord(labelZ, sizeZ),
      }

      -- Field:getId() is the farmland id (fields are keyed by farmland in FS25)
      local okId, id = pcall(field.getId, field)
      if okId and type(id) == "number" then
        entry.id = id
        entry.farmlandId = id
      end

      local okName, name = pcall(field.getName, field)
      if okName and type(name) == "string" and name ~= "" then
        entry.name = name
      end

      -- FarmlandManager.NO_OWNER_FARM_ID (0) -> omitted, the app renders it as unowned
      local okOwner, owner = pcall(field.getOwner, field)
      if
        okOwner
        and type(owner) == "number"
        and owner ~= (FarmlandManager ~= nil and FarmlandManager.NO_OWNER_FARM_ID or 0)
      then
        entry.ownerFarmId = owner
      end

      local okArea, areaHa = pcall(field.getAreaHa, field)
      if okArea and type(areaHa) == "number" then
        entry.areaHa = math.floor(areaHa * 100 + 0.5) / 100
      end

      entry.polygon = collectPolygon(field, sizeX, sizeZ)

      fields[#fields + 1] = entry
    end
  end
  return fields
end

-- Fields only exist once the map is fully loaded; isMissionStarted covers the (theoretical)
-- zero-field map so POIs still export there. Until this turns true the dirty flag stays pending
-- (selectDirty skips unavailable channels without clearing dirty).
function VDT.MapExporter.isAvailable()
  if g_currentMission == nil then
    return false
  end
  local fields = g_fieldManager ~= nil and g_fieldManager.fields or nil
  return (fields ~= nil and #fields > 0) or g_currentMission.isMissionStarted == true
end

---Build the map model, or nil when the world size can't be resolved yet (skips the write).
---@return MapModel|nil
function VDT.MapExporter.collect()
  if g_currentMission == nil then
    return nil
  end
  local sizeX, sizeZ = VDT.MapExporter.resolveWorldSize()
  if sizeX == nil then
    return nil
  end

  local pois = collectPois(sizeX, sizeZ)
  local fields = collectFields(sizeX, sizeZ)
  local farms = collectFarms()

  return {
    version = tostring(VDT.MapExporter.VERSION),
    terrainSize = g_currentMission.terrainSize or sizeX,
    -- omit empty arrays: the Json encoder emits {} for an empty table, so a nil keeps the key
    -- absent and the Kotlin model falls back to emptyList() (see TaskList.lua).
    pois = #pois > 0 and pois or nil,
    fields = #fields > 0 and fields or nil,
    farms = #farms > 0 and farms or nil,
  }
end

-- MessageCenter invokes callback(target, ...); target is VDT.MapExporter, the extra args are ignored.
function VDT.MapExporter.markDirty()
  VDT.ExportChannels.markDirty(VDT.MapExporter.CHANNEL)
end

-- Lazy subscribe: wait until the field list is up, then watch the events that change this channel's
-- data — land bought/sold (ownership tint), placeables added/removed (POIs), and farms
-- created/deleted/recolored (the farm color table). All base-game messages, but guarded anyway
-- (fail-soft house rule). The initial markDirty() writes the state that was already present on load.
function VDT.MapExporter.tick(debugger)
  if VDT.MapExporter.subscribed or not VDT.MapExporter.isAvailable() then
    return
  end
  if MessageType == nil or g_messageCenter == nil then
    return
  end
  for _, message in ipairs({
    "FARMLAND_OWNER_CHANGED",
    "PLACEABLE_ADDED",
    "PLACEABLE_REMOVED",
    "FARM_CREATED",
    "FARM_DELETED",
    "FARM_SETTINGS_CHANGED",
    "FARM_PROPERTY_CHANGED",
  }) do
    if MessageType[message] ~= nil then
      g_messageCenter:subscribe(MessageType[message], VDT.MapExporter.markDirty, VDT.MapExporter)
    end
  end
  VDT.MapExporter.subscribed = true
  VDT.MapExporter.markDirty()
  debugger:info("Map channel active (subscribed to farmland/placeable updates)")
end

-- Self-register the channel (see ExportChannels).
VDT.ExportChannels.register({
  name = VDT.MapExporter.CHANNEL,
  fileName = VDT.MapExporter.FILE_NAME,
  isAvailable = VDT.MapExporter.isAvailable,
  collect = VDT.MapExporter.collect,
  tick = VDT.MapExporter.tick,
})
