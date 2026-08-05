-- Executes the contract commands (app -> mod): take a contract on, give one up, collect a finished
-- one. The write half of the read-only missions channel (src/collect/MissionExporter.lua).
--
-- All three drive the game's OWN events -- MissionStartEvent / MissionCancelEvent /
-- MissionDismissEvent -- exactly as the in-game contracts screen does
-- (InGameMenuContractsFrame.lua:377-391, :596-607, :579-585): build the event and hand it to
-- `g_client:getServerConnection():sendEvent(...)`. So this mod needs no network event of its own, the
-- server re-checks the player's rights when the event lands, and singleplayer takes the same path
-- (the host's local connection). Calling MissionManager:startMission directly would be wrong twice
-- over: it asserts server-side (MissionManager.lua:317), and it would skip the permission check.
--
-- None of the three touches a vehicle, so all declare requiresVehicle = false (see CommandRegistry /
-- VDTelemetry:onCommand).
--
-- A contract is addressed by the same `id` the read side exports -- the network object id (see the
-- exporter's header for why it is not getUniqueId) -- resolved back to the live mission by walking
-- g_missionManager.missions. Walking, rather than NetworkUtil.getObject(id), because that would
-- happily return any registered object with that id: a stale id from the app must fail to resolve,
-- not resolve to a trailer.
--
-- The guards below mirror the in-game screen's rather than inventing rules: they exist so a refused
-- action leaves a clear log line instead of a silent no-op, since the mod has no way to answer the
-- app yet (see mission-plan.md, "Open questions"). The engine remains the authority -- every one of
-- these is re-checked server-side.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.MissionControl = {}

-- MissionStartState value -> name, for the outcome log. Built lazily off the live enum (which Enum()
-- also hangs functions on, hence the number filter), same shape as the exporter's token maps.
local startStateNames, startStateSource

local function startStateName(value)
  local enum = MissionStartState
  if type(enum) ~= "table" then
    return tostring(value)
  end
  if startStateNames == nil or startStateSource ~= enum then
    startStateNames, startStateSource = {}, enum
    for key, v in pairs(enum) do
      if type(key) == "string" and type(v) == "number" then
        startStateNames[v] = key
      end
    end
  end
  return startStateNames[value] or tostring(value)
end

---Resolve a mission by the exported network object id, or nil (logging why).
---@param missionId number|nil
---@param debugger GrisuDebug
---@param label string the command name, for the log
---@return table|nil mission
local function resolveMission(missionId, debugger, label)
  if missionId == nil then
    debugger:warn("%s: missing missionId", label)
    return nil
  end
  if g_missionManager == nil or type(g_missionManager.missions) ~= "table" then
    debugger:warn("%s: mission manager not available", label)
    return nil
  end
  for _, mission in ipairs(g_missionManager.missions) do
    local ok, id = pcall(NetworkUtil.getObjectId, mission)
    if ok and id == missionId then
      return mission
    end
  end
  -- Contracts come and go on their own (they time out, they are taken by another farm, the game
  -- deletes them): an id the app still shows may simply be gone by the time the click lands.
  debugger:warn("%s: no contract with id %s -- it is gone", label, tostring(missionId))
  return nil
end

---The local farm, or nil when there is none to act for.
local function ownFarmId(debugger, label)
  local farmId = VDT.ProductionExporter.ownFarmId()
  if farmId == nil then
    debugger:warn("%s: no local farm resolved, refusing to act on a contract", label)
  end
  return farmId
end

---Shared preamble: the right to manage contracts, the farm, and the mission itself.
---@return table|nil mission, number|nil farmId
local function resolve(params, debugger, label)
  if not VDT.MissionExporter.canManage() then
    debugger:warn("%s: this player may not manage contracts -- ignoring", label)
    return nil, nil
  end
  local farmId = ownFarmId(debugger, label)
  if farmId == nil then
    return nil, nil
  end
  local mission = resolveMission(params.missionId, debugger, label)
  if mission == nil then
    return nil, nil
  end
  return mission, farmId
end

---Send an event to the server, the way the in-game frame does.
---@return boolean sent
local function send(event, debugger, label)
  if g_client == nil then
    debugger:warn("%s: no client connection", label)
    return false
  end
  local connection = g_client:getServerConnection()
  if connection == nil then
    debugger:warn("%s: no server connection", label)
    return false
  end
  connection:sendEvent(event)
  return true
end

-- The outcomes the engine publishes back. Subscribed once, on the first command: the reply says
-- whether the action was actually carried out, and until there is an app-facing reply path this log
-- is the only place it can go. The channel's next write (event-driven off MISSION_STATUS_CHANGED)
-- shows the *result*, so this covers the "nothing happened, and here is why" case.
function VDT.MissionControl.subscribeOutcomes(debugger)
  if VDT.MissionControl.subscribed or g_messageCenter == nil then
    return
  end
  if MissionStartEvent == nil or MissionCancelEvent == nil or MissionDismissEvent == nil then
    return
  end
  g_messageCenter:subscribe(MissionStartEvent, function(_, startState)
    if MissionStartState ~= nil and startState == MissionStartState.OK then
      debugger:debug("contract accepted")
    else
      debugger:warn("contract not accepted: %s", startStateName(startState))
    end
  end, VDT.MissionControl)
  g_messageCenter:subscribe(MissionCancelEvent, function(_, success)
    debugger:debug("contract cancel %s", success and "accepted" or "refused")
  end, VDT.MissionControl)
  g_messageCenter:subscribe(MissionDismissEvent, function(_, success)
    debugger:debug("contract collect %s", success and "accepted" or "refused")
  end, VDT.MissionControl)
  VDT.MissionControl.subscribed = true
end

VDT.CommandRegistry.register("acceptMission", {
  requiresVehicle = false,
  parse = function(xml, key)
    return {
      missionId = xml:getInt(key .. "#missionId"),
      lease = xml:getBool(key .. "#lease", false),
    }
  end,
  execute = function(_, params, debugger)
    local label = "acceptMission"
    local mission, farmId = resolve(params, debugger, label)
    if mission == nil then
      return
    end

    -- Still on offer? Someone else may have taken it, or it may have timed out, between the app's
    -- last channel write and this click.
    local okReady, ready = pcall(mission.getIsReadyToStart, mission)
    if not okReady or ready ~= true then
      debugger:warn("%s: contract %s is no longer on offer", label, tostring(params.missionId))
      return
    end

    -- The engine's own per-farm cap. The app greys the button at the limit; this is the same rule,
    -- for the case where the app is a write behind.
    local okLimit, reached = pcall(g_missionManager.hasFarmReachedMissionLimit, g_missionManager, farmId)
    if okLimit and reached == true then
      debugger:warn("%s: farm %d is already running its maximum number of contracts", label, farmId)
      return
    end

    -- Leasing spawns the contract's machines at the shop, so it needs somewhere to put them. The
    -- in-game screen refuses with warning_noFreeMissionSpace rather than spawning nothing.
    if params.lease then
      local okSpace, hasSpace = pcall(mission.isSpawnSpaceAvailable, mission)
      if not okSpace or hasSpace ~= true then
        debugger:warn("%s: no free space at the shop for the leased equipment", label)
        return
      end
    end

    VDT.MissionControl.subscribeOutcomes(debugger)
    if send(MissionStartEvent.new(mission, farmId, params.lease == true), debugger, label) then
      debugger:debug("%s %s (lease=%s)", label, tostring(params.missionId), tostring(params.lease == true))
    end
  end,
})

VDT.CommandRegistry.register("cancelMission", {
  requiresVehicle = false,
  parse = function(xml, key)
    return { missionId = xml:getInt(key .. "#missionId") }
  end,
  execute = function(_, params, debugger)
    local label = "cancelMission"
    local mission, farmId = resolve(params, debugger, label)
    if mission == nil then
      return
    end
    -- Only one that is actually running, and only our own: the server enforces both
    -- (MissionCancelEvent.lua:run), this is for the log. Running first, because a contract still on
    -- offer has no farm at all -- checking ownership first would call that "another farm's".
    local okRunning, running = pcall(mission.getIsInProgress, mission)
    if not okRunning or running ~= true then
      debugger:warn("%s: contract %s is not running", label, tostring(params.missionId))
      return
    end
    if mission.farmId ~= farmId then
      debugger:warn("%s: contract %s belongs to another farm -- ignoring", label, tostring(params.missionId))
      return
    end

    VDT.MissionControl.subscribeOutcomes(debugger)
    if send(MissionCancelEvent.new(mission), debugger, label) then
      debugger:debug("%s %s", label, tostring(params.missionId))
    end
  end,
})

VDT.CommandRegistry.register("dismissMission", {
  requiresVehicle = false,
  parse = function(xml, key)
    return { missionId = xml:getInt(key .. "#missionId") }
  end,
  execute = function(_, params, debugger)
    local label = "dismissMission"
    local mission, farmId = resolve(params, debugger, label)
    if mission == nil then
      return
    end
    -- Collecting is for a contract that is over -- however it ended. DISMISSED counts too, matching
    -- the in-game screen, which offers the button for both (InGameMenuContractsFrame.lua:193).
    -- Checked before ownership for the same reason cancel does: an unfinished contract may have no
    -- farm to compare against.
    local status = VDT.MissionExporter.statusToken(mission.status)
    if status ~= "FINISHED" and status ~= "DISMISSED" then
      debugger:warn("%s: contract %s has not finished", label, tostring(params.missionId))
      return
    end
    if mission.farmId ~= farmId then
      debugger:warn("%s: contract %s belongs to another farm -- ignoring", label, tostring(params.missionId))
      return
    end

    VDT.MissionControl.subscribeOutcomes(debugger)
    if send(MissionDismissEvent.new(mission), debugger, label) then
      debugger:debug("%s %s", label, tostring(params.missionId))
    end
  end,
})
