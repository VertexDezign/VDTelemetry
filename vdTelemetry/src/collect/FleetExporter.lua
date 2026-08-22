-- Fleet export channel: the LOCAL farm's machines and their condition, written to fleet.json on its
-- OWN interval. It is the second-screen answer to "which machine do I take out today, and which one
-- is overdue" -- the question the game answers in ESC -> Statistics -> vehicle overview, i.e. by
-- pausing the game.
--
-- WHICH MACHINES: exactly the game's own gate, off InGameMenuStatisticsFrame:updateVehicles --
-- accessHandler:canPlayerAccess, the vehicle's own getShowInVehiclesOverview(), and the local farm's
-- ownership. That function is the engine's answer and specializations override it (Pallet and
-- Rideable both return false outright), so it is called rather than reimplemented: mirroring the
-- game's list means asking the game's own question, including whatever a patch does to it.
--
-- ONE ROW PER MACHINE, not per rig -- an implement is listed on its own row, as the game lists it,
-- with `attachedTo` naming the rig it is currently part of. That is the opposite of the mapVehicles
-- channel, which is one marker per rig; the two agree on the type token and the coordinate frame so
-- a row can be handed straight to the map.
--
-- CONDITION comes from the vanilla wearable aspect, EXCEPT under Advanced Damage System, which pins
-- `damage` to 0 on any machine it manages and keeps condition in its own inspection record instead
-- (see the ads block, contributed by src/integrations/AdvancedDamageSystem.lua). Readers take
-- `ads.inspected` where the ads block is present and `wearable` otherwise.
--
-- Reads only base-game state, so it lives in collect/ rather than integrations/, and every engine
-- read is pcall-guarded (fail-soft house rule): this walks third-party-modded vehicles, where a
-- getter throwing is a matter of time.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.FleetExporter = {}

VDT.FleetExporter.CHANNEL = "fleet"
VDT.FleetExporter.FILE_NAME = "fleet.json"
-- Own version, evolving independently of VDTelemetry.VERSION and the shared Kotlin FleetData.
VDT.FleetExporter.VERSION = 1
-- Write cadence in ms. Condition, hours and fill levels drift over in-game hours; the two things
-- here that move faster (fuel, who is driving) are the driven machine's business, and that one is on
-- the 100 ms telemetry channel already.
VDT.FleetExporter.INTERVAL_MS = 5000

-- Engine enum (vehicles/VehiclePropertyState.lua), named locally because it is a base-game global
-- the specs do not stand up.
local PROPERTY_STATES = { [1] = "NONE", [2] = "OWNED", [3] = "LEASED", [4] = "MISSION", [5] = "SHOP_CONFIG" }

-- Store category title per config file. The category of a machine never changes, and the lookup is
-- two manager calls deep, so it is resolved once per model rather than once per row per write.
local categoryCache = {}

---Call a method on a game object, containing anything it throws.
---@return any|nil the method's first result, or nil when it is missing or threw
local function call(object, name, ...)
  if type(object) ~= "table" or type(object[name]) ~= "function" then
    return nil
  end
  local ok, result = pcall(object[name], object, ...)
  if not ok then
    return nil
  end
  return result
end

local function num(value)
  return type(value) == "number" and value or nil
end

---The localized store category of a machine ("Tractors", "Ploughs", ...) -- what both the game's
---overview and ADS's fleet menu print beside the name. nil when the store item can't be resolved.
---@param vehicle table
---@return string|nil
function VDT.FleetExporter.category(vehicle)
  local configFile = vehicle.configFileName
  if type(configFile) ~= "string" or g_storeManager == nil then
    return nil
  end
  local cached = categoryCache[configFile]
  if cached ~= nil then
    return cached ~= false and cached or nil
  end

  local title = nil
  local storeItem = call(g_storeManager, "getItemByXMLFilename", configFile)
  local categoryName = type(storeItem) == "table" and storeItem.categoryName or nil
  if type(categoryName) == "string" then
    local category = call(g_storeManager, "getCategoryByName", categoryName)
    if type(category) == "table" and type(category.title) == "string" and category.title ~= "" then
      title = category.title
    else
      title = categoryName
    end
  end

  categoryCache[configFile] = title ~= nil and title or false
  return title
end

---Whether a machine belongs on the farm's overview: the game's own three-part gate.
---@param vehicle table
---@param farmId number the local farm
---@return boolean
function VDT.FleetExporter.isListed(vehicle, farmId)
  if type(vehicle) ~= "table" then
    return false
  end
  if call(vehicle, "getShowInVehiclesOverview") ~= true then
    return false
  end
  if call(vehicle, "getOwnerFarmId") ~= farmId then
    return false
  end
  -- The access handler is the multiplayer half of the question: a farm's machine can still be out of
  -- reach for this player (another farm's contractor rig). No handler yet -> nothing is listed.
  local handler = g_currentMission ~= nil and g_currentMission.accessHandler or nil
  return call(handler, "canPlayerAccess", vehicle) == true
end

---Operating hours, to a tenth -- the same figure the game prints as "1234.5 h", as a NUMBER: the
---overview sorts on it, and a formatted string cannot be sorted.
---@param operatingTimeMs number|nil
---@return number
function VDT.FleetExporter.hours(operatingTimeMs)
  local ms = num(operatingTimeMs) or 0
  return math.floor(ms / 360000 + 0.5) / 10
end

---Today, as the game counts it: `month` is the period (1..12). Carried on the document so ADS's log
---dates can be read as "3 months ago" without the app needing another channel.
---@return FleetDateModel|nil
local function collectDate()
  local env = g_currentMission ~= nil and g_currentMission.environment or nil
  if env == nil or num(env.currentYear) == nil or num(env.currentPeriod) == nil then
    return nil
  end
  return {
    year = math.floor(env.currentYear),
    month = math.floor(env.currentPeriod),
    day = math.floor(num(env.currentDayInPeriod) or 1),
  }
end

---Build one machine's row, or nil when it has no usable network id (not registered yet).
---
---The id is the NETWORK OBJECT ID, never getUniqueId(): uniqueId is nil on a multiplayer client, the
---trap that already bit the missions channel (see MissionExporter's header) and the one that makes
---ADS's own `ADS_Main.vehicles` table unusable from here.
---@param vehicle table
---@param sizeX number|nil terrain edge lengths for normalization; nil leaves the position out
---@param sizeZ number|nil
---@return FleetVehicleModel|nil
function VDT.FleetExporter.collectVehicle(vehicle, sizeX, sizeZ)
  local okId, id = pcall(NetworkUtil.getObjectId, vehicle)
  if not okId or type(id) ~= "number" then
    return nil
  end

  ---@type FleetVehicleModel
  local row = {
    id = id,
    name = call(vehicle, "getFullName") or "",
    type = VDT.MapVehicles.typeToken(vehicle.mapHotspotType),
    category = VDT.FleetExporter.category(vehicle),
    age = math.floor(num(vehicle.age) or 0),
    hours = VDT.FleetExporter.hours(vehicle.operatingTime),
    propertyState = PROPERTY_STATES[vehicle.propertyState] or "NONE",
  }

  -- The two money columns the game prints, each only where the game prints it: a leased machine has
  -- no sell value, an owned one no leasing rate. The leasing formula is the game's own
  -- (InGameMenuStatisticsFrame:updateVehicles), running cost plus the per-day charge.
  if vehicle.propertyState == 2 then
    local sellPrice = num(call(vehicle, "getSellPrice"))
    if sellPrice ~= nil then
      row.sellPrice = math.floor(sellPrice)
    end
  elseif vehicle.propertyState == 3 then
    local price = num(vehicle.price) or num(call(vehicle, "getPrice"))
    local running = num(EconomyManager ~= nil and EconomyManager.DEFAULT_RUNNING_LEASING_FACTOR or nil)
    local perDay = num(EconomyManager ~= nil and EconomyManager.PER_DAY_LEASING_FACTOR or nil)
    if price ~= nil and running ~= nil and perDay ~= nil then
      row.leasePerDay = math.floor(price * (running + perDay))
    end
  end

  -- Condition and contents, from the same aspect collectors the driven rig uses -- so a machine
  -- reads the same here as it does in the vehicle app.
  local okWear, wearable = pcall(VDT.Wearable.collect, vehicle)
  row.wearable = okWear and wearable or nil
  local okFill, fillUnits = pcall(VDT.FillUnit.collect, vehicle)
  row.fillUnits = okFill and fillUnits or nil
  local okMotor, motorFillUnits = pcall(VDT.Motor.collectFillUnits, vehicle)
  row.motorFillUnits = okMotor and motorFillUnits or nil

  local root = vehicle.rootVehicle
  if type(root) == "table" and root ~= vehicle then
    local okRoot, rootId = pcall(NetworkUtil.getObjectId, root)
    if okRoot and type(rootId) == "number" then
      row.attachedTo = rootId
    end
  end

  -- Who has it, in the same tokens the map markers use (MapVehiclesExporter.collectVehicle). Only
  -- enterables carry the controlled/entered flags; for a trailer the Kotlin defaults say the same.
  row.isAI = call(vehicle, "getIsAIActive") == true or nil
  local enterable = vehicle.spec_enterable
  if enterable ~= nil then
    row.isControlled = enterable.isControlled == true or nil
    row.isEntered = enterable.isEntered == true or nil
    -- Whether the machine is in the tab rotation. It is exported because that flag is how the park
    -- mods mark a machine as put away -- `Enterable:setIsTabbable(false)` -- and the player who parked
    -- it wants to see that on the list. Written for every enterable rather than only when false, so
    -- "not in the rotation" and "has no seat at all" stay distinguishable on the wire.
    --
    -- Asked through the getter rather than read off the spec: a mod may overwrite the function instead
    -- of setting the field, and the engine's own tab walk asks the same way (VehicleSystem).
    local tabbable = call(vehicle, "getIsTabbable")
    if type(tabbable) == "boolean" then
      row.isTabbable = tabbable
    end
  end

  -- Where it is, in the map channels' normalized frame -- what "show on map" hands over. An
  -- unreadable position costs the row its coordinates, not the row.
  if sizeX ~= nil and sizeZ ~= nil and vehicle.rootNode ~= nil then
    local okPos, x, _, z = pcall(getWorldTranslation, vehicle.rootNode)
    if okPos and type(x) == "number" and type(z) == "number" then
      row.posX = VDT.MapExporter.normalizeCoord(x, sizeX)
      row.posZ = VDT.MapExporter.normalizeCoord(z, sizeZ)
    end
  end

  -- Optional third-party mods decorate the row -- today that is Advanced Damage System's maintenance
  -- block, which is the whole point of the channel for anyone running it.
  VDT.Integrations.run("contributeFleetVehicle", vehicle, row)

  return row
end

function VDT.FleetExporter.isAvailable()
  return g_currentMission ~= nil and g_currentMission.vehicleSystem ~= nil
end

---Build the fleet model, or nil when the vehicle system isn't up yet (skips the write).
---@return FleetModel|nil
function VDT.FleetExporter.collect()
  if not VDT.FleetExporter.isAvailable() then
    return nil
  end
  local farmId = VDT.Farm.ownFarmId()
  if farmId == nil then
    -- spectator / no owned farm: keep the channel present but empty
    return { version = tostring(VDT.FleetExporter.VERSION) }
  end

  -- nil, nil while the world size can't be resolved: the rows are still worth writing without their
  -- coordinates (unlike the map channels, which are nothing but coordinates and skip the write).
  local sizeX, sizeZ = VDT.MapExporter.resolveWorldSize()

  local vehicles = {}
  local system = g_currentMission.vehicleSystem
  for _, vehicle in ipairs(system ~= nil and system.vehicles or {}) do
    if VDT.FleetExporter.isListed(vehicle, farmId) then
      vehicles[#vehicles + 1] = VDT.FleetExporter.collectVehicle(vehicle, sizeX, sizeZ)
    end
  end

  return {
    version = tostring(VDT.FleetExporter.VERSION),
    date = collectDate(),
    -- omit the empty array: the Json encoder emits {} for an empty table (see TaskList.lua)
    vehicles = #vehicles > 0 and vehicles or nil,
  }
end

-- Test seam: drop the resolved store categories between spec cases. Never called in game -- a
-- config file's category does not change while the game is running.
function VDT.FleetExporter.reset()
  categoryCache = {}
end

-- Self-register the channel (see ExportChannels). Interval-driven: the registry owns the cadence.
-- No minProfile -- this walks the vehicle list once every 5 s, where the map's vehicle markers
-- already walk the same list every second.
VDT.ExportChannels.register({
  name = VDT.FleetExporter.CHANNEL,
  fileName = VDT.FleetExporter.FILE_NAME,
  isAvailable = VDT.FleetExporter.isAvailable,
  collect = VDT.FleetExporter.collect,
  intervalMs = VDT.FleetExporter.INTERVAL_MS,
  -- Only this farm's machines are listed (ownFarmId + the game's own access gate).
  farmScoped = true,
})
