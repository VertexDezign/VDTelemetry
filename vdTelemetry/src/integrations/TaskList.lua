-- Optional integration: FS25_TaskList (ModHub mod_id 312938, source github.com/Ozz-Modding/FS25_TaskList).
-- An event-driven export channel: it self-detects the mod, subscribes to the two change messages, and
-- serializes the current farm's task groups + tasks into taskList.json. **Absence of that file is the
-- app's "not installed" signal** — when the mod isn't present the channel is registered but never
-- writes (and a file left over from a session where it *was* installed is deleted at startup).
--
-- Verified (2026-07-10): on a dedicated-server client both ACTIVE_TASKS_UPDATED and
-- TASK_GROUPS_UPDATED fire and `g_currentMission.taskList` is fully readable (see farm-page-plan.md).
--
-- **Written against FS25_TaskList 1.2.0.1** — everything below reads that mod's *internals*, which it
-- is free to rename in any release. So fail soft, never throw: guard every field read and treat a
-- missing one as "no data" (an empty panel beats a Lua error in the collector, which would take the
-- whole telemetry write down with it). Same contract on the write side (src/command/TaskListControl.lua).
--
-- Data shape (from the mod's Task.lua / TaskGroup.lua / main.lua):
--   g_currentMission.taskList.taskGroups  map  groupId -> { id, farmId, name, effortMultiplier,
--                                                            type, templateGroupId, tasks }
--     .tasks                              map  taskId  -> Task { id, detail, type, priority, period,
--                                                            effort, shouldRecur, recurMode, n, nextN,
--                                                            husbandry/production fields, ... }
--   g_currentMission.taskList.activeTasks map  "groupId_taskId" -> { id, groupId, createdMarker }
-- Task:getTaskDescription() resolves a human label (Standard tasks => detail; husbandry/production
-- tasks scan getHusbandries()/getProductions()). Those scans populate a lazy cache on the mod, and
-- triggering that build too early poisons it — see describe() below; we only read it once the mod
-- has built it itself.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.TaskList = {}

VDT.TaskList.CHANNEL = "taskList"
VDT.TaskList.FILE_NAME = "taskList.json"
-- Own version, evolving independently of VDTelemetry.VERSION and the shared Kotlin TaskListData.
VDT.TaskList.VERSION = 1

-- Task.TASK_TYPE.Standard. A Standard task's label is just its `detail`; only the auto types
-- (husbandry/production) need the mod's caches to resolve a label.
--
-- Read from the mod's own enum when it's reachable (its Task class lives in the mod's env, not _G --
-- the isolation rule again) so a future release that reorders TASK_TYPE can't turn every standard task
-- into an "auto" one here. The literal stays as the fallback for the version we pinned above.
local TASK_TYPE_STANDARD_FALLBACK = 1
local function standardTaskType()
  local mod = type(FS25_TaskList) == "table" and FS25_TaskList or nil
  local taskClass = mod ~= nil and mod.Task or nil
  local types = taskClass ~= nil and taskClass.TASK_TYPE or nil
  local standard = types ~= nil and types.Standard or nil
  return type(standard) == "number" and standard or TASK_TYPE_STANDARD_FALLBACK
end

local function taskList()
  return g_currentMission ~= nil and g_currentMission.taskList or nil
end

function VDT.TaskList.isAvailable()
  return taskList() ~= nil
end

-- Human-readable label, resolved WITHOUT side-effecting the mod.
--
-- For an auto (husbandry/production) task, getTaskDescription reads TaskList's lazy `husbandries` /
-- `productions` caches. Crucially, we must never be the one to trigger their *initial* build:
-- getHusbandries()/getProductions() populate the cache from the current placeables, and if we call
-- it at collect time — during mission load, before the placeables and farm id are ready — the mod is
-- left holding an empty cache (it only rebuilds when the field is nil). Its own taskCleanup() then
-- reads that empty cache and DROPS every husbandry/production task ("missing husbandry"), and the
-- in-game menu can't create new ones. So only resolve once the mod has built the caches itself;
-- otherwise fall back to the raw detail (empty for auto tasks, which the app renders as untitled).
local function describe(task)
  if task.type == standardTaskType() then
    return task.detail or ""
  end
  local tl = taskList()
  if tl == nil or tl.husbandries == nil or tl.productions == nil then
    return task.detail or ""
  end
  local ok, desc = pcall(task.getTaskDescription, task)
  if ok and type(desc) == "string" and desc ~= "" then
    return desc
  end
  return task.detail or ""
end

---Build the taskList model, or nil when the mod isn't loaded (skips the write).
---@return table|nil
function VDT.TaskList.collect()
  local tl = taskList()
  if tl == nil then
    return nil
  end

  local activeTasks = tl.activeTasks or {}
  -- Scope to the player's current farm, matching the in-game menu; fall back to all groups in
  -- singleplayer or if the farm can't be resolved.
  local isMp = g_currentMission.missionDynamicInfo ~= nil and g_currentMission.missionDynamicInfo.isMultiplayer == true
  local farmId = nil
  local ok, id = pcall(tl.getCurrentFarmId, tl)
  -- getCurrentFarmId returns -1 when the player isn't in a farm; treat that as "unresolved" and show
  -- everything rather than filtering to a farm id nothing matches.
  if ok and type(id) == "number" and id > 0 then
    farmId = id
  end

  local groups = {}
  for _, group in pairs(tl.taskGroups or {}) do
    if (not isMp) or farmId == nil or group.farmId == farmId then
      local tasks = {}
      for _, task in pairs(group.tasks or {}) do
        -- activeTasks is keyed groupId_taskId (see main.lua); presence == due right now.
        local activeKey = tostring(group.id) .. "_" .. tostring(task.id)
        tasks[#tasks + 1] = {
          id = task.id,
          detail = task.detail or "",
          description = describe(task),
          type = task.type,
          priority = task.priority,
          period = task.period,
          effort = task.effort,
          shouldRecur = task.shouldRecur,
          recurMode = task.recurMode,
          n = task.n,
          nextN = task.nextN,
          active = activeTasks[activeKey] ~= nil,
        }
      end
      groups[#groups + 1] = {
        id = group.id,
        name = group.name or "",
        type = group.type,
        farmId = group.farmId,
        effortMultiplier = group.effortMultiplier,
        -- omit empty arrays: the Json encoder emits {} for an empty table, so a nil keeps the key
        -- absent and the Kotlin model falls back to emptyList() (see VehicleExporter's implements).
        tasks = #tasks > 0 and tasks or nil,
      }
    end
  end

  return {
    version = tostring(VDT.TaskList.VERSION),
    groups = #groups > 0 and groups or nil,
  }
end

-- MessageCenter invokes callback(target, ...); target is VDT.TaskList and the extra args are ignored.
function VDT.TaskList.markDirty()
  VDT.ExportChannels.markDirty(VDT.TaskList.CHANNEL)
end

-- Lazy subscribe: the mod's MessageType ids only exist once it has loaded, so we wait for the
-- taskList global (which is also the natural "installed?" gate) before subscribing. Once subscribed
-- we queue an initial write so the file reflects the state that was already present on connect.
function VDT.TaskList.tick(debugger)
  if VDT.TaskList.subscribed or not VDT.TaskList.isAvailable() then
    return
  end
  if MessageType == nil or MessageType.ACTIVE_TASKS_UPDATED == nil or MessageType.TASK_GROUPS_UPDATED == nil then
    return
  end
  g_messageCenter:subscribe(MessageType.ACTIVE_TASKS_UPDATED, VDT.TaskList.markDirty, VDT.TaskList)
  g_messageCenter:subscribe(MessageType.TASK_GROUPS_UPDATED, VDT.TaskList.markDirty, VDT.TaskList)
  VDT.TaskList.subscribed = true
  VDT.TaskList.markDirty()
  debugger:info("TaskList integration active (subscribed to task updates)")
end

-- Self-register the channel (see ExportChannels). Registered even when the mod isn't installed;
-- isAvailable() then keeps the file from ever being written, so its absence signals "not installed".
VDT.ExportChannels.register({
  name = VDT.TaskList.CHANNEL,
  fileName = VDT.TaskList.FILE_NAME,
  isAvailable = VDT.TaskList.isAvailable,
  collect = VDT.TaskList.collect,
  tick = VDT.TaskList.tick,
})
