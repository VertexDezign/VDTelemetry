-- Vehicle-marker export channel: every vehicle the game itself would show on its map, written to
-- mapVehicles.json on its OWN interval — positions change constantly, so this is neither
-- event-driven like map.json nor tied to the 100 ms main-telemetry tick (a map overview doesn't
-- need 10 Hz, and a busy server can hold dozens of vehicles). INTERVAL_MS is a constant today and
-- the natural place for a settings-XML/GUI value later.
--
-- Which vehicles: root vehicles only (one marker per rig — attached implements share their
-- tractor's marker), filtered by the vehicle's own mapHotspotAvailable flag — the exact opt-out the
-- game's map uses (pallets etc. set it false in their XML). The type token is the vehicle's
-- mapHotspotType (VehicleHotspot.TYPE key, camelCased: "tractor", "harvester", "trailer", ...).
--
-- Coordinates are the same normalized [0,1] map frame as map.json / the player marker
-- (MapExporter.resolveWorldSize + normalizeCoord); heading is compass degrees via
-- ValueMapper.calculateHeading, the same convention as the driven vehicle's gps.heading.
-- isEntered marks the LOCAL player's vehicle (the app hides it — the player marker already shows
-- it); isControlled is true for any human-driven vehicle, also other players' in MP.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.MapVehicles = {}

VDT.MapVehicles.CHANNEL = "mapVehicles"
VDT.MapVehicles.FILE_NAME = "mapVehicles.json"
-- Own version, evolving independently of VDTelemetry.VERSION and the shared Kotlin MapVehiclesData.
VDT.MapVehicles.VERSION = 1
-- Write cadence in ms. Deliberately its own constant (not the main telemetry interval).
VDT.MapVehicles.INTERVAL_MS = 1000

-- Accumulated ms since the last markDirty; on the table so specs can reset it.
VDT.MapVehicles.timerMs = 0

local tokenByType, tokenSource -- lazy reverse map of VehicleHotspot.TYPE, value -> token

---Wire token for a VehicleHotspot.TYPE value ("tractor", "harvester", "toolTrailed", ...);
---"other" when the enum isn't reachable or the value is unknown.
---@param hotspotType any
---@param types table<string, number>|nil enum override for tests; defaults to VehicleHotspot.TYPE
---@return string
function VDT.MapVehicles.typeToken(hotspotType, types)
  types = types or (VehicleHotspot ~= nil and VehicleHotspot.TYPE or nil)
  if types == nil or hotspotType == nil then
    return "other"
  end
  if tokenByType == nil or tokenSource ~= types then
    tokenByType = {}
    tokenSource = types
    for key, value in pairs(types) do
      if type(key) == "string" then
        local lower = string.lower(key)
        tokenByType[value] = (string.gsub(lower, "_(%a)", string.upper))
      end
    end
  end
  return tokenByType[hotspotType] or "other"
end

function VDT.MapVehicles.isAvailable()
  return g_currentMission ~= nil and g_currentMission.vehicleSystem ~= nil
end

---Build one exported vehicle entry, or nil when the position can't be read.
---@param vehicle table
---@param sizeX number
---@param sizeZ number
---@return MapVehicleModel|nil
local function collectVehicle(vehicle, sizeX, sizeZ)
  local okPos, x, _, z = pcall(getWorldTranslation, vehicle.rootNode)
  if not okPos or type(x) ~= "number" or type(z) ~= "number" then
    return nil
  end

  ---@type MapVehicleModel
  local entry = {
    type = VDT.MapVehicles.typeToken(vehicle.mapHotspotType),
    posX = VDT.MapExporter.normalizeCoord(x, sizeX),
    posZ = VDT.MapExporter.normalizeCoord(z, sizeZ),
  }

  local okName, name = pcall(vehicle.getFullName, vehicle)
  if okName and type(name) == "string" and name ~= "" then
    entry.name = name
  end

  local okHeading, heading = pcall(ValueMapper.calculateHeading, vehicle)
  if okHeading and type(heading) == "number" then
    entry.heading = math.floor(heading)
  end

  local okFarm, farmId = pcall(vehicle.getOwnerFarmId, vehicle)
  if okFarm and type(farmId) == "number" and farmId > 0 then
    entry.farmId = farmId
  end

  local okAi, isAi = pcall(vehicle.getIsAIActive, vehicle)
  entry.isAI = okAi and isAi == true

  -- Only enterables carry the controlled/entered flags; for a trailer the Kotlin defaults (false)
  -- say the same thing, so the keys are omitted.
  local spec = vehicle.spec_enterable
  if spec ~= nil then
    entry.isControlled = spec.isControlled == true
    entry.isEntered = spec.isEntered == true
  end

  return entry
end

---Build the vehicles model, or nil when the world size can't be resolved yet (skips the write).
---@return MapVehiclesModel|nil
function VDT.MapVehicles.collect()
  if g_currentMission == nil then
    return nil
  end
  local sizeX, sizeZ = VDT.MapExporter.resolveWorldSize()
  if sizeX == nil then
    return nil
  end

  local vehicles = {}
  local system = g_currentMission.vehicleSystem
  for _, vehicle in ipairs(system ~= nil and system.vehicles or {}) do
    -- one marker per rig, and only vehicles the game's own map would show
    if
      vehicle ~= nil
      and vehicle.rootVehicle == vehicle
      and vehicle.rootNode ~= nil
      and vehicle.mapHotspotAvailable ~= false
    then
      vehicles[#vehicles + 1] = collectVehicle(vehicle, sizeX, sizeZ)
    end
  end

  return {
    version = tostring(VDT.MapVehicles.VERSION),
    -- omit the empty array: the Json encoder emits {} for an empty table (see TaskList.lua)
    vehicles = #vehicles > 0 and vehicles or nil,
  }
end

-- Interval-driven, unlike the event-driven channels: accumulate the frame delta and queue a write
-- every INTERVAL_MS. markDirty on an unavailable channel stays pending (selectDirty skips it
-- without clearing), so ticking before the vehicle system is up costs nothing.
function VDT.MapVehicles.tick(_, dt)
  if type(dt) ~= "number" then
    return
  end
  VDT.MapVehicles.timerMs = VDT.MapVehicles.timerMs + dt
  if VDT.MapVehicles.timerMs >= VDT.MapVehicles.INTERVAL_MS then
    VDT.MapVehicles.timerMs = 0
    VDT.ExportChannels.markDirty(VDT.MapVehicles.CHANNEL)
  end
end

-- Self-register the channel (see ExportChannels).
VDT.ExportChannels.register({
  name = VDT.MapVehicles.CHANNEL,
  fileName = VDT.MapVehicles.FILE_NAME,
  isAvailable = VDT.MapVehicles.isAvailable,
  collect = VDT.MapVehicles.collect,
  tick = VDT.MapVehicles.tick,
})
