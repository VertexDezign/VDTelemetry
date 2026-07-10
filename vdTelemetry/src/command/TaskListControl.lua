-- Executes the FS25_TaskList write-back commands (app -> mod). The inverse of the read-only taskList
-- export channel (src/integrations/TaskList.lua): complete / delete / create / edit a task by driving
-- the mod's own MP-correct wrappers (TaskList:completeTask / :deleteTask / :addTask), which each send
-- the appropriate multiplayer event, so state stays in sync. None of these touch a vehicle, so every
-- handler declares requiresVehicle = false (see CommandRegistry / VDTelemetry:onCommand).
--
-- create/edit build a **Standard** task exactly the way the mod's ManageTasksFrame wizard does: the
-- app sends user-facing values (detail, priority, effort, recurMode, n, month) and we resolve the
-- internal period / nextN here, where the game state (current day) lives. Non-Standard
-- (husbandry/production) tasks are not creatable/editable from the app.
--
-- Reads the FS25_TaskList globals Task / TaskListUtils at execute time (they exist only when that mod
-- is installed); every handler guards on g_currentMission.taskList first, so it no-ops when the mod
-- is absent. Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.TaskListControl = {}

local function taskList()
  return g_currentMission ~= nil and g_currentMission.taskList or nil
end

-- The mod's convertMonthNumberToPeriod, inlined: it lives in FS25_TaskList's Lua environment (the
-- TaskListUtils global), which the engine does not share with ours. It's a fixed -2 (wrap 12) offset
-- with no game state (period 1 = March), so we replicate it rather than reach for the unreachable.
local function monthToPeriod(month)
  month = month - 2
  if month <= 0 then
    month = month + 12
  end
  return month
end

-- Recover FS25_TaskList's `Task` class. Its `Task = {}` global lives in *that mod's* Lua environment,
-- not shared with ours, so `Task.new()` isn't reachable directly. FS25 exposes each mod's environment
-- as a global named after the mod, though, so FS25_TaskList.Task reaches it — and works even before
-- any task exists. As a fallback (mod installed under a non-standard global name) we borrow the class
-- off an existing task instance's metatable (Class(Task) => getmetatable(task).__index == Task).
local function isTaskClass(class)
  return type(class) == "table" and type(class.new) == "function" and type(class.TASK_TYPE) == "table"
end

local function resolveTaskClass()
  if type(FS25_TaskList) == "table" and isTaskClass(FS25_TaskList.Task) then
    return FS25_TaskList.Task
  end
  local tl = taskList()
  if tl == nil then
    return nil
  end
  for _, group in pairs(tl.taskGroups or {}) do
    for _, task in pairs(group.tasks or {}) do
      local mt = getmetatable(task)
      if type(mt) == "table" then
        local class = mt.__index or mt
        if isTaskClass(class) then
          return class
        end
      end
    end
  end
  return nil
end

-- Build a Standard Task from the command params, mirroring ManageTasksFrame's period / nextN rules.
-- Returns nil only when the Task class can't be recovered at all (see resolveTaskClass). Exposed for
-- unit testing: set FS25_TaskList.Task, or stub g_currentMission.taskList with a task whose
-- metatable.__index is a Task-like table (TASK_TYPE / RECUR_MODE / new), to exercise it offline.
---@param taskId string|nil existing id for an edit, or nil to let the mod generate one for a create
---@param params table { detail, priority, effort, recurMode, n, month }
---@return table|nil
function VDT.TaskListControl.buildStandardTask(taskId, params)
  local TaskClass = resolveTaskClass()
  if TaskClass == nil then
    return nil
  end
  local recur = TaskClass.RECUR_MODE

  local task = TaskClass.new()
  if taskId ~= nil then
    task.id = taskId
  end
  task.type = TaskClass.TASK_TYPE.Standard
  task.detail = params.detail or ""
  task.priority = params.priority or 1
  task.effort = params.effort or 1

  local recurMode = params.recurMode or recur.NONE
  task.recurMode = recurMode
  task.shouldRecur = recurMode ~= recur.NONE
  task.n = 0
  task.nextN = 0
  local month = params.month or 1

  if recurMode == recur.NONE or recurMode == recur.MONTHLY then
    task.period = monthToPeriod(month)
  elseif recurMode == recur.EVERY_N_MONTHS then
    task.n = params.n or 1
    task.nextN = monthToPeriod(month) -- start period
  elseif recurMode == recur.EVERY_N_DAYS then
    task.n = params.n or 1
    task.nextN = g_currentMission.environment.currentDay
  end
  -- DAILY leaves period at its Task.new default; n / nextN stay 0.
  return task
end

-- Envelope parsers (see CommandRegistry). completeTask / deleteTask carry only ids.
local function parseIds(xml, key)
  return { groupId = xml:getString(key .. "#groupId"), taskId = xml:getString(key .. "#taskId") }
end

-- createTask / editTask carry the Standard-task fields; taskId is present only for editTask.
local function parseTask(xml, key)
  return {
    groupId = xml:getString(key .. "#groupId"),
    taskId = xml:getString(key .. "#taskId"),
    detail = xml:getString(key .. "#detail") or "",
    priority = xml:getInt(key .. "#priority") or 1,
    effort = xml:getInt(key .. "#effort") or 1,
    recurMode = xml:getInt(key .. "#recurMode") or 0,
    n = xml:getInt(key .. "#n") or 1,
    month = xml:getInt(key .. "#month") or 1,
  }
end

VDT.CommandRegistry.register("completeTask", {
  requiresVehicle = false,
  parse = parseIds,
  execute = function(_, params, debugger)
    local tl = taskList()
    if tl == nil then
      debugger:warn("completeTask: TaskList not available")
      return
    end
    tl:completeTask(params.groupId, params.taskId)
    debugger:debug("completeTask %s/%s", tostring(params.groupId), tostring(params.taskId))
  end,
})

VDT.CommandRegistry.register("deleteTask", {
  requiresVehicle = false,
  parse = parseIds,
  execute = function(_, params, debugger)
    local tl = taskList()
    if tl == nil then
      debugger:warn("deleteTask: TaskList not available")
      return
    end
    tl:deleteTask(params.groupId, params.taskId)
    debugger:debug("deleteTask %s/%s", tostring(params.groupId), tostring(params.taskId))
  end,
})

VDT.CommandRegistry.register("createTask", {
  requiresVehicle = false,
  parse = parseTask,
  execute = function(_, params, debugger)
    local tl = taskList()
    if tl == nil then
      debugger:warn("createTask: TaskList not available")
      return
    end
    local task = VDT.TaskListControl.buildStandardTask(nil, params)
    if task == nil then
      debugger:warn("createTask: could not resolve the FS25_TaskList Task class")
      return
    end
    tl:addTask(params.groupId, task, false)
    debugger:debug("createTask in %s", tostring(params.groupId))
  end,
})

VDT.CommandRegistry.register("editTask", {
  requiresVehicle = false,
  parse = parseTask,
  execute = function(_, params, debugger)
    local tl = taskList()
    if tl == nil then
      debugger:warn("editTask: TaskList not available")
      return
    end
    local task = VDT.TaskListControl.buildStandardTask(params.taskId, params)
    if task == nil then
      debugger:warn("editTask: could not resolve the FS25_TaskList Task class")
      return
    end
    tl:addTask(params.groupId, task, true)
    debugger:debug("editTask %s/%s", tostring(params.groupId), tostring(params.taskId))
  end,
})
