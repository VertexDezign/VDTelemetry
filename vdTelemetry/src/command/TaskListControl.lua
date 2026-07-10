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

-- Build a Standard Task from the command params, mirroring ManageTasksFrame's period / nextN rules.
-- Exposed for unit testing (the period / nextN logic is the fiddly part); stub Task / TaskListUtils /
-- g_currentMission to exercise it offline.
---@param taskId string|nil existing id for an edit, or nil to let the mod generate one for a create
---@param params table { detail, priority, effort, recurMode, n, month }
function VDT.TaskListControl.buildStandardTask(taskId, params)
  local task = Task.new()
  if taskId ~= nil then
    task.id = taskId
  end
  task.type = Task.TASK_TYPE.Standard
  task.detail = params.detail or ""
  task.priority = params.priority or 1
  task.effort = params.effort or 1

  local recurMode = params.recurMode or Task.RECUR_MODE.NONE
  task.recurMode = recurMode
  task.shouldRecur = recurMode ~= Task.RECUR_MODE.NONE
  task.n = 0
  task.nextN = 0
  local month = params.month or 1

  if recurMode == Task.RECUR_MODE.NONE or recurMode == Task.RECUR_MODE.MONTHLY then
    task.period = TaskListUtils.convertMonthNumberToPeriod(month)
  elseif recurMode == Task.RECUR_MODE.EVERY_N_MONTHS then
    task.n = params.n or 1
    task.nextN = TaskListUtils.convertMonthNumberToPeriod(month) -- start period
  elseif recurMode == Task.RECUR_MODE.EVERY_N_DAYS then
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
    tl:addTask(params.groupId, VDT.TaskListControl.buildStandardTask(nil, params), false)
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
    tl:addTask(params.groupId, VDT.TaskListControl.buildStandardTask(params.taskId, params), true)
    debugger:debug("editTask %s/%s", tostring(params.groupId), tostring(params.taskId))
  end,
})
