-- Missions export channel: the farm's contracts, written to missions.json.
--
-- Reads only base-game state (g_missionManager), so it lives in collect/, not integrations/. Every
-- engine read is pcall-guarded (fail-soft house rule) -- getDetails() especially, which is overridden
-- sixteen ways and reaches into lazily-resolved state (HarvestMission resolves its selling station
-- from inside it), so one bad type must not take the whole channel down.
--
-- Which contracts: MissionManager:getMissionsByFarmId (MissionManager.lua:370) -- everything with no
-- farm (still on offer) plus this farm's -- then the second filter the game's own contracts screen
-- applies (InGameMenuContractsFrame.lua:195): keep it if it was never started, or if it is ours. That
-- drops contracts another farm is running in multiplayer, exactly as the in-game list does.
--
-- Per-type detail is NOT modelled type by type. AbstractMission:getDetails (AbstractMission.lua:556)
-- already returns what the in-game screen prints -- {title, value} pairs, localized by the game and
-- overridden per mission type -- and getFinishedDetails (:563) the reward breakdown for a finished
-- one. Exporting those rows verbatim covers all 16 base-game types, and carries modded types for
-- free. See issue #17.
--
-- TWO CADENCES. Event-driven off MISSION_GENERATED / MISSION_DELETED / MISSION_STATUS_CHANGED
-- (AbstractMission.lua:291,72,365), which is generation, acceptance, completion and deletion -- plus
-- a slow interval, because two values move with no message behind them: minutesLeft (:485, derived
-- from the environment clock) and completion (:213, pushed over the mission's update stream every
-- ~2.5 s while running). Event-driven alone would show a frozen countdown.
--
-- `id` IS THE NETWORK OBJECT ID, NOT getUniqueId(). uniqueId is assigned in MissionManager:addMission
-- and saved to the savegame, but it is NOT in AbstractMission:writeStream -- and a client takes the
-- readStream path (:206) which inserts into `missions` directly rather than going through addMission.
-- So getUniqueId() is nil on every multiplayer client, and a command keyed on it would work in
-- singleplayer and silently fail in MP. NetworkUtil.getObjectId is what the mission events themselves
-- serialize (MissionStartEvent writes the mission as a node object), so it is the handle that exists
-- and agrees on both sides.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.MissionExporter = {}

VDT.MissionExporter.CHANNEL = "missions"
VDT.MissionExporter.FILE_NAME = "missions.json"
-- Own version, evolving independently of VDTelemetry.VERSION and the shared Kotlin MissionsData.
-- 2: added subtitle / fruitType / sellingStation.
VDT.MissionExporter.VERSION = 2
-- Write cadence in ms, on top of the event subscriptions: the countdown and the completion bar.
VDT.MissionExporter.INTERVAL_MS = 10000

local function num(v)
  return type(v) == "number" and v or 0
end

-- Whole currency units: the game's rewards are floats, and no terminal prints cents.
local function money(value)
  return math.floor(num(value) + 0.5)
end

-- Round a [0,1] ratio to 4 decimals (Json.lua prints floats with %.14g; keep the file compact).
local function ratio(value)
  local r = math.max(0, math.min(1, num(value)))
  return math.floor(r * 10000 + 0.5) / 10000
end

-- Reverse map for an engine enum table: value -> the key's name. Built lazily from the live table so
-- it tracks any renumbering, and rebuilt if the table itself is swapped (the specs pass their own).
-- Enum() decorates these tables with functions (getName, writeStream, ...), hence the number filter.
local function tokenLookup(cache, enum)
  if cache.map == nil or cache.source ~= enum then
    cache.map, cache.source = {}, enum
    for key, value in pairs(enum) do
      if type(key) == "string" and type(value) == "number" then
        cache.map[value] = key
      end
    end
  end
  return cache.map
end

local statusCache, finishCache = {}, {}

---MissionStatus value -> token (CREATED, PREPARING, RUNNING, FINISHED, DISMISSED).
---@param value number|nil
---@param enum table|nil enum override for tests; defaults to the global MissionStatus
---@return string|nil nil when the value is unknown or the enum isn't loaded
function VDT.MissionExporter.statusToken(value, enum)
  enum = enum or MissionStatus
  if type(enum) ~= "table" or value == nil then
    return nil
  end
  return tokenLookup(statusCache, enum)[value]
end

---MissionFinishState value -> token (SUCCESS, FAILED, TIMED_OUT, CANCELED). NONE reads as "no
---outcome yet" and is deliberately mapped to nil, so the key stays out of the JSON.
---@param value number|nil
---@param enum table|nil enum override for tests; defaults to the global MissionFinishState
---@return string|nil
function VDT.MissionExporter.finishStateToken(value, enum)
  enum = enum or MissionFinishState
  if type(enum) ~= "table" or value == nil then
    return nil
  end
  local token = tokenLookup(finishCache, enum)[value]
  if token == nil or token == "NONE" then
    return nil
  end
  return token
end

-- One detail row. The game's values are already localized and formatted (g_i18n:formatMoney /
-- formatArea), but a few types put a raw count in there (tree and rock numbers), so anything that
-- isn't a string is stringified rather than dropped.
local function detailRow(detail)
  if type(detail) ~= "table" or detail.title == nil or detail.value == nil then
    return nil
  end
  return { title = tostring(detail.title), value = tostring(detail.value) }
end

---The game's own detail rows for a mission: the reward breakdown once it is finished, the contract
---terms before that. Both are pcall'd as a whole -- an override that trips on unresolved state costs
---us this mission's rows, not the channel.
---@param mission table
---@param isFinished boolean
---@return MissionDetailModel[]|nil
local function collectDetails(mission, isFinished)
  local getter = isFinished and mission.getFinishedDetails or mission.getDetails
  if type(getter) ~= "function" then
    return nil
  end
  local ok, details = pcall(getter, mission)
  if not ok or type(details) ~= "table" then
    return nil
  end
  local rows = {}
  for _, detail in ipairs(details) do
    rows[#rows + 1] = detailRow(detail)
  end
  return #rows > 0 and rows or nil
end

-- The crop a contract names, when it names one. Set by every mission that works a specific fruit
-- (harvest, sow, bale), and read by FIELD PRESENCE rather than by mission type -- same rule the
-- aspect layer follows, so a modded mission that sets the same field is carried too.
---@param mission table
---@return string|nil token the engine's fruit type name (WHEAT, OAT, ...)
---@return string|nil title the localized crop name
local function collectCrop(mission)
  local index = mission.fruitTypeIndex
  if type(index) ~= "number" or index == 0 or g_fruitTypeManager == nil then
    return nil, nil
  end
  local okName, name = pcall(g_fruitTypeManager.getFruitTypeNameByIndex, g_fruitTypeManager, index)
  -- BaleMission resolves the title itself at setFruitType; everything else goes through the fill
  -- type, because that is what the crop is carried and named as (see aspects/Sowing.lua).
  local title = type(mission.fruitTypeTitle) == "string" and mission.fruitTypeTitle or nil
  if title == nil then
    local okFill, fillType = pcall(g_fruitTypeManager.getFillTypeByFruitTypeIndex, g_fruitTypeManager, index)
    if okFill and type(fillType) == "table" then
      title = fillType.title
    end
  end
  return (okName and type(name) == "string") and name or nil, title
end

-- The bale form a contract asks for, when it asks for one. Two different fields say it: a baling
-- contract carries `needRoundbaler` outright, a wrapping one carries a bale type index the bale
-- manager resolves (BaleMission.lua:34, BaleWrapMission.lua:32). Both are stream-synced.
---@param mission table
---@return string|nil token ROUND | SQUARE
---@return string|nil title the localized form ("Round bale")
local function collectBaleForm(mission)
  local round
  if type(mission.needRoundbaler) == "boolean" then
    round = mission.needRoundbaler
  elseif type(mission.baleTypeIndex) == "number" and g_baleManager ~= nil then
    local ok, isRound = pcall(g_baleManager.getIsRoundBale, g_baleManager, mission.baleTypeIndex)
    if not ok or type(isRound) ~= "boolean" then
      return nil, nil
    end
    round = isRound
  else
    return nil, nil
  end
  -- g_i18n is checked before it is indexed, not merely pcall'd: `pcall(g_i18n.getText, ...)`
  -- evaluates the field access first, so a nil g_i18n throws outside the pcall's protection and
  -- takes the whole channel write with it. Same guard as MapLayersExporter's localized().
  local token = round and "ROUND" or "SQUARE"
  if g_i18n == nil then
    return token, nil
  end
  local key = round and "fillType_roundBale" or "fillType_squareBale"
  local okText, title = pcall(g_i18n.getText, g_i18n, key)
  return token, (okText and type(title) == "string") and title or nil
end

-- Where the load has to be delivered, for the contracts that sell something (harvest, tree
-- transport). Taken from the station placeable's OWN map hotspot -- the very position the game puts
-- its selling-station marker at (HarvestMission.lua:217-222) -- so the app never has to match a
-- station by name.
---@param mission table
---@param sizeX number|nil
---@param sizeZ number|nil
---@return MissionStationModel|nil
local function collectSellingStation(mission, sizeX, sizeZ)
  -- A client receives the station as a pending network id and resolves it lazily; the engine's own
  -- getDetails does this too, so a station that has not been looked up yet still reports.
  if mission.pendingSellingStationId ~= nil and type(mission.tryToResolveSellingStation) == "function" then
    pcall(mission.tryToResolveSellingStation, mission)
  end
  local station = mission.sellingStation
  if type(station) ~= "table" then
    return nil
  end

  local model = {}
  local okName, name = pcall(station.getName, station)
  if okName and type(name) == "string" and name ~= "" then
    model.name = name
  end

  local placeable = station.owningPlaceable
  -- Both edge lengths, not just one: normalizeCoord does arithmetic on the size, so a nil sizeZ
  -- would throw here rather than simply skip the position. Same guard as collectPosition.
  if sizeX ~= nil and sizeZ ~= nil and type(placeable) == "table" and type(placeable.getHotspot) == "function" then
    local okHotspot, hotspot = pcall(placeable.getHotspot, placeable)
    if okHotspot and type(hotspot) == "table" and type(hotspot.getWorldPosition) == "function" then
      local okPos, worldX, worldZ = pcall(hotspot.getWorldPosition, hotspot)
      if okPos and type(worldX) == "number" and type(worldZ) == "number" then
        model.posX = VDT.MapExporter.normalizeCoord(worldX, sizeX)
        model.posZ = VDT.MapExporter.normalizeCoord(worldZ, sizeZ)
      end
    end
  end

  -- A station we can neither name nor place is not worth a key.
  if model.name == nil and model.posX == nil then
    return nil
  end
  return model
end

---The farmer offering the contract (AbstractMission:getNPC, :574).
---@param mission table
---@return MissionNpcModel|nil
local function collectNpc(mission)
  if type(mission.getNPC) ~= "function" then
    return nil
  end
  local ok, npc = pcall(mission.getNPC, mission)
  if not ok or type(npc) ~= "table" then
    return nil
  end
  local name = npc.title
  if type(name) ~= "string" or name == "" then
    return nil
  end
  return {
    name = name,
    image = (type(npc.imageFilename) == "string" and npc.imageFilename ~= "") and npc.imageFilename or nil,
  }
end

-- Call an engine getter that returns one value, or nil when it is absent or throws.
local function get(object, getter)
  if type(getter) ~= "function" then
    return nil
  end
  local ok, value = pcall(getter, object)
  if not ok then
    return nil
  end
  return value
end

---Where the contract is, in the normalized frame the map channel and the player marker use.
---AbstractMission:getWorldPosition (:658) returns 0,0 on the base class; every base-game type
---overrides it (field missions via the field indicator, the point ones via their spot), so a literal
---0,0 means "this type doesn't say" -- and normalizing it would drop a marker dead centre of the map.
---@param mission table
---@param sizeX number|nil
---@param sizeZ number|nil
---@return number|nil posX, number|nil posZ
local function collectPosition(mission, sizeX, sizeZ)
  if sizeX == nil or sizeZ == nil or type(mission.getWorldPosition) ~= "function" then
    return nil, nil
  end
  local ok, worldX, worldZ = pcall(mission.getWorldPosition, mission)
  if not ok or type(worldX) ~= "number" or type(worldZ) ~= "number" then
    return nil, nil
  end
  if worldX == 0 and worldZ == 0 then
    return nil, nil
  end
  return VDT.MapExporter.normalizeCoord(worldX, sizeX), VDT.MapExporter.normalizeCoord(worldZ, sizeZ)
end

---Build one mission's model.
---@param mission table an AbstractMission
---@param ownFarmId number|nil the local farm
---@param sizeX number|nil terrain edge lengths for normalization
---@param sizeZ number|nil
---@return MissionModel|nil nil when the mission has no usable network id (not registered yet)
function VDT.MissionExporter.collectMission(mission, ownFarmId, sizeX, sizeZ)
  if type(mission) ~= "table" then
    return nil
  end
  local okId, id = pcall(NetworkUtil.getObjectId, mission)
  if not okId or type(id) ~= "number" then
    return nil
  end

  local status = VDT.MissionExporter.statusToken(mission.status)
  local isFinished = status == "FINISHED" or status == "DISMISSED"
  local started = status ~= nil and status ~= "CREATED"

  ---@type MissionModel
  local model = {
    id = id,
    type = (mission.type ~= nil and type(mission.type.name) == "string") and mission.type.name or "unknown",
    title = tostring(get(mission, mission.getTitle) or ""),
    status = status or "CREATED",
    reward = money(get(mission, mission.getReward)),
    own = (ownFarmId ~= nil and mission.farmId == ownFarmId) or nil,
  }

  local description = get(mission, mission.getDescription)
  if type(description) == "string" and description ~= "" then
    model.description = description
  end
  local location = get(mission, mission.getLocation)
  if type(location) == "string" and location ~= "" then
    model.location = location
  end

  model.finishState = VDT.MissionExporter.finishStateToken(mission.finishState)
  model.npc = collectNpc(mission)

  -- Leasing: the equipment offer only exists while the contract is on offer, but the cost stays
  -- meaningful afterwards (it is subtracted from the payout), so both are carried whenever there is
  -- a vehicle group at all.
  if get(mission, mission.hasLeasableVehicles) == true then
    model.leasable = true
    local costs = get(mission, mission.getVehicleCosts)
    if type(costs) == "number" and costs > 0 then
      model.vehicleCosts = money(costs)
    end
  end

  if isFinished then
    model.totalReward = money(get(mission, mission.getTotalReward))
  end

  -- completion is the synced field (AbstractMission:readUpdateStream), never getCompletion() -- that
  -- one recomputes from the density map and is server-only work.
  if started and type(mission.completion) == "number" then
    model.completion = ratio(mission.completion)
  end

  local minutesLeft = get(mission, mission.getMinutesLeft)
  if type(minutesLeft) == "number" then
    model.minutesLeft = math.floor(minutesLeft)
  end

  if status == "RUNNING" then
    local extra = get(mission, mission.getExtraProgressText)
    if type(extra) == "string" and extra ~= "" then
      model.extraProgress = extra
    end
  end

  -- Field missions only: the farmland id joins this contract to the polygon the map channel already
  -- exports (MapExporter's fields[].id is the same farmland id), so the app tints rather than
  -- redrawing geometry. getFarmlandId is absent on the point-located types -- MissionManager guards
  -- the same way (:381).
  local farmlandId = get(mission, mission.getFarmlandId)
  if type(farmlandId) == "number" then
    model.fieldId = farmlandId
  end
  if type(mission.field) == "table" then
    local areaHa = get(mission.field, mission.field.getAreaHa)
    if type(areaHa) == "number" and areaHa > 0 then
      model.areaHa = math.floor(areaHa * 100 + 0.5) / 100
    end
  end

  model.posX, model.posZ = collectPosition(mission, sizeX, sizeZ)
  model.details = collectDetails(mission, isFinished)
  model.sellingStation = collectSellingStation(mission, sizeX, sizeZ)

  -- What this contract is about beyond its type: the crop for a harvest or a sowing job, the bale
  -- form for a baling one -- the line a list shows under the title. Assembled here rather than in the
  -- app because the parts are localized by the game, and joined so a contract that names both (a
  -- baling job on a named crop) says both.
  local fruitType, fruitTitle = collectCrop(mission)
  local baleType, baleTitle = collectBaleForm(mission)
  model.fruitType = fruitType
  model.baleType = baleType
  local parts = {}
  if baleTitle ~= nil then
    parts[#parts + 1] = baleTitle
  end
  if fruitTitle ~= nil then
    parts[#parts + 1] = fruitTitle
  end
  if #parts > 0 then
    -- U+00B7 MIDDLE DOT as raw UTF-8 bytes: \u{} is Luau/5.3 syntax and the specs run on 5.1.
    model.subtitle = table.concat(parts, " \194\183 ")
  end

  return model
end

---The contracts the in-game screen would list for this farm (see the header for the two filters).
---@param manager table g_missionManager
---@param ownFarmId number
---@return table[] missions
local function visibleMissions(manager, ownFarmId)
  local list = nil
  if type(manager.getMissionsByFarmId) == "function" then
    local ok, filtered = pcall(manager.getMissionsByFarmId, manager, ownFarmId)
    if ok and type(filtered) == "table" then
      list = filtered
    end
  end
  list = list or manager.missions or {}

  local visible = {}
  for _, mission in ipairs(list) do
    -- "Never started" is on offer to everyone; anything started is only ours to show.
    local started = get(mission, mission.getWasStarted) == true
    if not started or mission.farmId == ownFarmId then
      visible[#visible + 1] = mission
    end
  end
  return visible
end

---How many contracts this farm is running, and the engine's cap -- the same count
---MissionManager:hasFarmReachedMissionLimit walks (:416). The app greys "accept" at the cap rather
---than firing a command the server answers with LIMIT_REACHED.
---@param manager table
---@param ownFarmId number
---@return MissionLimitModel
local function collectLimit(manager, ownFarmId)
  local active = 0
  for _, mission in ipairs(manager.missions or {}) do
    if mission.farmId == ownFarmId and get(mission, mission.getWasStarted) == true then
      active = active + 1
    end
  end
  local max = (MissionManager ~= nil and type(MissionManager.MAX_MISSIONS_PER_FARM) == "number")
      and MissionManager.MAX_MISSIONS_PER_FARM
    or 3
  return { active = active, max = max }
end

---Whether this player may accept/cancel/collect: the game's own manageContracts right, which is
---what greys the in-game buttons (InGameMenuContractsFrame.lua:140). The server re-checks it when
---the event lands, so this drives the UI, it is not the boundary.
---@return boolean
function VDT.MissionExporter.canManage()
  if g_currentMission == nil or type(g_currentMission.getHasPlayerPermission) ~= "function" then
    return false
  end
  local permission = (Farm ~= nil and Farm.PERMISSION ~= nil) and Farm.PERMISSION.MANAGE_CONTRACTS or "manageContracts"
  local ok, allowed = pcall(g_currentMission.getHasPlayerPermission, g_currentMission, permission)
  return ok and allowed == true
end

function VDT.MissionExporter.isAvailable()
  return g_currentMission ~= nil and g_missionManager ~= nil and type(g_missionManager.missions) == "table"
end

---Build the missions model, or nil when the mission manager isn't up yet (skips the write).
---@return MissionsModel|nil
function VDT.MissionExporter.collect()
  if not VDT.MissionExporter.isAvailable() then
    return nil
  end
  local farmId = VDT.ProductionExporter.ownFarmId()
  if farmId == nil then
    -- spectator / no owned farm: nothing to accept and nothing running, but keep the channel present
    return { version = tostring(VDT.MissionExporter.VERSION) }
  end

  local manager = g_missionManager
  local sizeX, sizeZ = VDT.MapExporter.resolveWorldSize()
  local missions = {}
  for _, mission in ipairs(visibleMissions(manager, farmId)) do
    missions[#missions + 1] = VDT.MissionExporter.collectMission(mission, farmId, sizeX, sizeZ)
  end

  return {
    version = tostring(VDT.MissionExporter.VERSION),
    limit = collectLimit(manager, farmId),
    canManage = VDT.MissionExporter.canManage(),
    -- omit empty arrays (nil, not {}): an empty Lua table encodes as {} which the Kotlin lists reject
    missions = #missions > 0 and missions or nil,
  }
end

-- MessageCenter invokes callback(target, ...); target is VDT.MissionExporter, extra args ignored.
function VDT.MissionExporter.markDirty()
  VDT.ExportChannels.markDirty(VDT.MissionExporter.CHANNEL)
end

-- Lazy subscribe, the same shape as the TaskList channel: the MessageType ids exist once the game has
-- loaded, so we wait for them rather than subscribing at source time. The interval keeps the file
-- fresh regardless; these three just make acceptance and completion land immediately.
function VDT.MissionExporter.tick(debugger)
  if VDT.MissionExporter.subscribed or not VDT.MissionExporter.isAvailable() then
    return
  end
  if MessageType == nil or MessageType.MISSION_GENERATED == nil or MessageType.MISSION_STATUS_CHANGED == nil then
    return
  end
  g_messageCenter:subscribe(MessageType.MISSION_GENERATED, VDT.MissionExporter.markDirty, VDT.MissionExporter)
  g_messageCenter:subscribe(MessageType.MISSION_DELETED, VDT.MissionExporter.markDirty, VDT.MissionExporter)
  g_messageCenter:subscribe(MessageType.MISSION_STATUS_CHANGED, VDT.MissionExporter.markDirty, VDT.MissionExporter)
  VDT.MissionExporter.subscribed = true
  VDT.MissionExporter.markDirty()
  debugger:debug("Missions channel subscribed to mission generation/status changes")
end

-- Self-register the channel (see ExportChannels). Both cadences: the interval for the countdown, the
-- tick for the event subscriptions.
VDT.ExportChannels.register({
  name = VDT.MissionExporter.CHANNEL,
  fileName = VDT.MissionExporter.FILE_NAME,
  isAvailable = VDT.MissionExporter.isAvailable,
  collect = VDT.MissionExporter.collect,
  intervalMs = VDT.MissionExporter.INTERVAL_MS,
  tick = VDT.MissionExporter.tick,
})
