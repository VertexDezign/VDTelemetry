-- Optional integration: AutoDrive (FS25_AutoDrive, github.com/Stephan-S/FS25_AutoDrive). Namespaced under
-- VDT.* (see aspects/TurnOn.lua). Issue #97.
--
-- **Written against AutoDrive 3.0.1.4** (references/FS25_AutoDrive). Two halves:
--
--   * `vehicle.autoDrive` on the machine you are in: what AutoDrive's HUD shows -- whether it is
--     driving, the mode, the destination (and the unload destination for the modes that have one),
--     the fill type it loads, the loop counter, and the task text and drive time it prints while
--     driving. Plus `ai.active` raised while it drives: AutoDrive is a helper, and the cab's AI lamp
--     should say so. (The map and fleet channels already show it -- they ask getIsAIActive, which
--     AutoDrive overrides; the cab's lamp reads the base game's own helper specs and so needed this.)
--   * `autoDrive.json`, the channel of destinations: every map marker of the network, with its folder
--     and where it is on the map. Event-driven off AutoDrive's own destination listener, which it
--     notifies whenever a marker is created, renamed, moved or deleted -- and on a multiplayer client
--     when the network arrives from the server.
--
-- The write side is src/command/AutoDriveControl.lua.
--
-- MOD ENVIRONMENT: AutoDrive's classes and managers (ADGraphManager, ADInputManager, its events) live
-- in its own environment, reachable only as `FS25_AutoDrive.<name>` from ours -- see CropRotation.lua.
-- The per-vehicle state is a plain field, `vehicle.ad.stateModule`, so it needs no such lookup, and
-- its getters resolve their own globals.
--
-- MULTIPLAYER: the state module streams every field read here from the server to every client
-- (ADStateModule:doWriteStream), so a client reports what the host's AutoDrive is doing. Note the
-- direction: its setters only take effect on the server, which is why the commands go through
-- AutoDrive's own events rather than through them.

---@class AutoDriveTargetModel
---@field id number the marker's index in AutoDrive's list -- what the commands send back
---@field name string

---@class AutoDriveFillTypeModel
---@field name string the engine's fill type name ("WHEAT")
---@field title string localized

---@class AutoDriveModel
---@field active boolean AutoDrive is driving this machine
---@field mode string DRIVE_TO | PICKUP_AND_DELIVER | DELIVER_TO | LOAD | UNLOAD | OTHER
---@field modes string[] the modes this machine can be switched to, in AutoDrive's cycle order
---@field target AutoDriveTargetModel? the destination ("first marker")
---@field unloadTarget AutoDriveTargetModel? the unload destination ("second marker")
---@field fillType string? the fill type it loads, by name
---@field fillTypes AutoDriveFillTypeModel[]? what the attached trailers can carry
---@field loops number rounds pickup & deliver runs; 0 is "until stopped"
---@field loopsDone number
---@field task string? what it is doing right now, in AutoDrive's words; only while driving
---@field remainingSeconds number? time to the destination; only while driving off a field
---@field heading AutoDriveTargetModel? the marker it is on its way to right now

---@class VehicleModel
---@field autoDrive AutoDriveModel?

---@class AutoDriveDestinationModel
---@field id number
---@field name string
---@field folder string? the folder it is filed in; omitted for AutoDrive's catch-all "All"
---@field posX number normalized [0,1] map position, the frame of the map channels
---@field posZ number

---@class AutoDriveChannelModel
---@field version string
---@field destinations AutoDriveDestinationModel[]?

VDT = VDT or {}
VDT.AutoDrive = {}

VDT.AutoDrive.CHANNEL = "autoDrive"
VDT.AutoDrive.FILE_NAME = "autoDrive.json"
-- Own version, evolving independently of VDTelemetry.VERSION and the shared Kotlin AutoDriveData.
VDT.AutoDrive.VERSION = 1

-- AutoDrive.MODE_*, in its cycle order. Read from the mod when reachable; the literals are the 3.0.1.4
-- values. MODE_BGA (6) is left out: it is past ADStateModule.HIGHEST_MODE, so the mode button never
-- reaches it, and its code is commented out in this version.
VDT.AutoDrive.MODES = {
  { token = "DRIVE_TO", key = "MODE_DRIVETO", value = 1 },
  { token = "PICKUP_AND_DELIVER", key = "MODE_PICKUPANDDELIVER", value = 2 },
  { token = "DELIVER_TO", key = "MODE_DELIVERTO", value = 3 },
  { token = "LOAD", key = "MODE_LOAD", value = 4 },
  { token = "UNLOAD", key = "MODE_UNLOAD", value = 5 },
}

---AutoDrive's environment, or nil when it is not installed.
---@return table|nil
function VDT.AutoDrive.env()
  return type(FS25_AutoDrive) == "table" and FS25_AutoDrive or nil
end

---The value of AutoDrive.<key> (a MODE_* constant), falling back to the pinned literal.
local function modeValue(mode)
  local env = VDT.AutoDrive.env()
  local ad = env ~= nil and env.AutoDrive or nil
  local value = ad ~= nil and ad[mode.key] or nil
  return type(value) == "number" and value or mode.value
end

---@param value number an AutoDrive mode number
---@return string
function VDT.AutoDrive.modeToken(value)
  for _, mode in ipairs(VDT.AutoDrive.MODES) do
    if modeValue(mode) == value then
      return mode.token
    end
  end
  return "OTHER"
end

---@param token string
---@return number|nil
function VDT.AutoDrive.modeValue(token)
  for _, mode in ipairs(VDT.AutoDrive.MODES) do
    if mode.token == token then
      return modeValue(mode)
    end
  end
  return nil
end

---The modes the mode button cycles through on this machine. A locomotive skips UNLOAD, as
---ADStateModule:nextMode does -- a train cannot chase a combine.
---@param vehicle table
---@return string[]
function VDT.AutoDrive.modesFor(vehicle)
  local modes = {}
  for _, mode in ipairs(VDT.AutoDrive.MODES) do
    if not (mode.token == "UNLOAD" and vehicle.spec_locomotive ~= nil) then
      modes[#modes + 1] = mode.token
    end
  end
  return modes
end

---The machine's AutoDrive state module, or nil when AutoDrive does not drive it.
---@param vehicle table
---@return table|nil
function VDT.AutoDrive.stateOf(vehicle)
  local ad = vehicle ~= nil and vehicle.ad or nil
  local state = type(ad) == "table" and ad.stateModule or nil
  return type(state) == "table" and state or nil
end

---Whether the second marker means anything in this mode -- the modes AutoDrive's HUD shows its
---second destination list in (ADPullDownList:updateVisibility).
---@param token string
---@return boolean
function VDT.AutoDrive.hasUnloadTarget(token)
  return token == "PICKUP_AND_DELIVER" or token == "LOAD" or token == "UNLOAD"
end

---Whether the fill type means anything in this mode (the HUD's third list, same function).
---@param token string
---@return boolean
function VDT.AutoDrive.hasFillType(token)
  return token == "PICKUP_AND_DELIVER" or token == "LOAD"
end

---@param marker table|nil an AutoDrive map marker ({ markerIndex, name, ... })
---@return AutoDriveTargetModel|nil
local function target(marker)
  if type(marker) ~= "table" or type(marker.markerIndex) ~= "number" or marker.markerIndex < 1 then
    return nil
  end
  return { id = marker.markerIndex, name = tostring(marker.name or "") }
end

---Fill type name + title for an engine fill type index, or nil for one the manager doesn't know.
local function fillTypeEntry(index)
  if g_fillTypeManager == nil or type(index) ~= "number" then
    return nil
  end
  local fillType = g_fillTypeManager:getFillTypeByIndex(index)
  if fillType == nil or fillType.name == nil then
    return nil
  end
  return { name = fillType.name, title = fillType.title or fillType.name }
end

---The fill types AutoDrive offers in its list for this rig -- AutoDrive's own answer, which puts
---UniversalAutoload's list first where a trailer has it. UAL's "all materials" entry (index 1, the
---engine's UNKNOWN) is not a fill type and is left out.
---@param vehicle table
---@return AutoDriveFillTypeModel[]|nil
function VDT.AutoDrive.fillTypesFor(vehicle)
  local env = VDT.AutoDrive.env()
  local ad = env ~= nil and env.AutoDrive or nil
  if ad == nil or type(ad.getValidSupportedFillTypesALfirst) ~= "function" then
    return nil
  end
  local indices = ad.getValidSupportedFillTypesALfirst(vehicle)
  local list = {}
  for _, index in ipairs(type(indices) == "table" and indices or {}) do
    local entry = index ~= ad.UAL_FILLTYPE_ALL and fillTypeEntry(index) or nil
    if entry ~= nil then
      list[#list + 1] = entry
    end
  end
  table.sort(list, function(a, b)
    return a.title < b.title
  end)
  return #list > 0 and list or nil
end

-- Object stage: runs per vehicle/implement during the walk (see registry.lua). Only a machine
-- AutoDrive registered itself on carries `ad.stateModule`, so it no-ops for implements.
---@param object table a vehicle or implement
---@param model table the object's already core-collected model
function VDT.AutoDrive.contributeObject(object, model)
  local state = VDT.AutoDrive.stateOf(object)
  if state == nil then
    return
  end

  local active = state:isActive() == true
  local mode = VDT.AutoDrive.modeToken(state:getMode())
  ---@type AutoDriveModel
  local entry = {
    active = active,
    mode = mode,
    modes = VDT.AutoDrive.modesFor(object),
    target = target(state:getFirstMarker()),
    loops = tonumber(state:getLoopCounter()) or 0,
    loopsDone = tonumber(state:getLoopsDone()) or 0,
  }
  if VDT.AutoDrive.hasUnloadTarget(mode) then
    entry.unloadTarget = target(state:getSecondMarker())
  end
  if VDT.AutoDrive.hasFillType(mode) then
    local fillType = fillTypeEntry(state:getFillType())
    entry.fillType = fillType ~= nil and fillType.name or nil
    entry.fillTypes = VDT.AutoDrive.fillTypesFor(object)
  end

  if active then
    local task = state:getCurrentLocalizedTaskInfo()
    if type(task) == "string" and task ~= "" then
      entry.task = task
    end
    -- AutoDrive computes this only off the field and only while someone sits in the machine, and
    -- writes 0 when it hasn't; 0 is "no estimate", not "arrived".
    local seconds = tonumber(state:getRemainingDriveTime())
    if seconds ~= nil and seconds > 0 then
      entry.remainingSeconds = math.floor(seconds)
    end
    entry.heading = target(state:getCurrentDestination())
  end
  model.autoDrive = entry

  -- The cab's AI lamp. Raised, never lowered: a base-game helper that is also running stays reported.
  if active and model.ai ~= nil then
    model.ai.active = true
  end
end

-- ---------------------------------------------------------------------------------------------------
-- The destinations channel
-- ---------------------------------------------------------------------------------------------------

local listening = false

function VDT.AutoDrive.isAvailable()
  local env = VDT.AutoDrive.env()
  return env ~= nil and env.ADGraphManager ~= nil
end

---Subscribe to AutoDrive's destination listener once it is there. Every notification marks the
---channel dirty; the first subscription does too, so the file is written as soon as the mod is up.
function VDT.AutoDrive.tick()
  if listening then
    return
  end
  local env = VDT.AutoDrive.env()
  local ad = env ~= nil and env.AutoDrive or nil
  if ad == nil or type(ad.registerDestinationListener) ~= "function" then
    return
  end
  ad:registerDestinationListener(VDT.AutoDrive, function()
    VDT.ExportChannels.markDirty(VDT.AutoDrive.CHANNEL)
  end)
  listening = true
  VDT.ExportChannels.markDirty(VDT.AutoDrive.CHANNEL)
end

---@return AutoDriveChannelModel|nil nil until the world size is known (skips the write)
function VDT.AutoDrive.collect()
  local env = VDT.AutoDrive.env()
  local graph = env ~= nil and env.ADGraphManager or nil
  if graph == nil then
    return nil
  end
  local sizeX, sizeZ = VDT.MapExporter.resolveWorldSize()
  if sizeX == nil then
    return nil
  end

  local destinations = {}
  for index, marker in pairs(graph:getMapMarkers() or {}) do
    local point = type(marker) == "table" and graph:getWayPointById(marker.id) or nil
    if type(index) == "number" and point ~= nil and type(point.x) == "number" and type(point.z) == "number" then
      ---@type AutoDriveDestinationModel
      local entry = {
        id = index,
        name = tostring(marker.name or ""),
        posX = VDT.MapExporter.normalizeCoord(point.x, sizeX),
        posZ = VDT.MapExporter.normalizeCoord(point.z, sizeZ),
      }
      if type(marker.group) == "string" and marker.group ~= "" and marker.group ~= "All" then
        entry.folder = marker.group
      end
      destinations[#destinations + 1] = entry
    end
  end
  table.sort(destinations, function(a, b)
    return a.id < b.id
  end)

  return {
    version = tostring(VDT.AutoDrive.VERSION),
    -- omit the empty array: the Json encoder emits {} for an empty table (see TaskList.lua)
    destinations = #destinations > 0 and destinations or nil,
  }
end

VDT.ExportChannels.register({
  name = VDT.AutoDrive.CHANNEL,
  fileName = VDT.AutoDrive.FILE_NAME,
  isAvailable = VDT.AutoDrive.isAvailable,
  collect = VDT.AutoDrive.collect,
  tick = VDT.AutoDrive.tick,
})

-- Test seam: forget the subscription.
function VDT.AutoDrive.resetListening()
  listening = false
end
