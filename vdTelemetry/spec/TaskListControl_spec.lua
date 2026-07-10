-- Unit tests for src/command/TaskListControl.lua (FS25_TaskList write-back commands).
--
-- Run with `busted` from the vdTelemetry/ directory. The control self-registers its command types
-- into VDT.CommandRegistry at load, so we load CommandRegistry first (only if not already loaded, so
-- we don't reset a registry another spec populated). buildStandardTask reads the FS25_TaskList globals
-- Task / TaskListUtils and g_currentMission, which don't exist off-engine, so we stub them.

if VDT == nil or VDT.CommandRegistry == nil then
  dofile("src/command/CommandRegistry.lua")
end
dofile("src/command/TaskListControl.lua")

-- RECUR_MODE mirrors the mod's Task.lua; the real convertMonthNumberToPeriod is a fixed -2 (wrap 12)
-- offset with no game state, so we replicate it exactly.
local function installTaskListGlobals(currentDay)
  rawset(_G, "Task", {
    TASK_TYPE = { Standard = 1 },
    RECUR_MODE = { NONE = 0, MONTHLY = 1, DAILY = 2, EVERY_N_MONTHS = 3, EVERY_N_DAYS = 4 },
    new = function()
      return { id = "generated-id", period = 1 }
    end,
  })
  rawset(_G, "TaskListUtils", {
    convertMonthNumberToPeriod = function(month)
      month = month - 2
      if month <= 0 then
        month = month + 12
      end
      return month
    end,
  })
  rawset(_G, "g_currentMission", { environment = { currentDay = currentDay or 100 } })
end

local build = function(taskId, params)
  return VDT.TaskListControl.buildStandardTask(taskId, params)
end

describe("TaskListControl command registration", function()
  it("registers all four task commands with requiresVehicle = false", function()
    for _, cmdType in ipairs({ "completeTask", "deleteTask", "createTask", "editTask" }) do
      local handler = VDT.CommandRegistry.get(cmdType)
      assert.is_not_nil(handler)
      assert.are.equal(false, handler.requiresVehicle)
    end
  end)
end)

describe("TaskListControl.buildStandardTask", function()
  before_each(function()
    installTaskListGlobals(100)
  end)

  it("carries the common fields and marks a once task non-recurring", function()
    local task = build(nil, { detail = "Fix the fence", priority = 3, effort = 2, recurMode = 0, month = 4 })
    assert.are.equal("generated-id", task.id) -- no taskId => mod-generated
    assert.are.equal(1, task.type) -- Standard
    assert.are.equal("Fix the fence", task.detail)
    assert.are.equal(3, task.priority)
    assert.are.equal(2, task.effort)
    assert.are.equal(false, task.shouldRecur)
    assert.are.equal(2, task.period) -- month 4 -> period 2
    assert.are.equal(0, task.n)
    assert.are.equal(0, task.nextN)
  end)

  it("uses the given id for an edit", function()
    local task = build("existing-task-7", { detail = "x", recurMode = 0, month = 1 })
    assert.are.equal("existing-task-7", task.id)
  end)

  it("sets period (not nextN) for a monthly task", function()
    local task = build(nil, { recurMode = 1, month = 3 })
    assert.is_true(task.shouldRecur)
    assert.are.equal(1, task.period) -- month 3 -> period 1
    assert.are.equal(0, task.nextN)
    assert.are.equal(0, task.n)
  end)

  it("leaves period at the Task default for a daily task", function()
    local task = build(nil, { recurMode = 2, month = 6 })
    assert.is_true(task.shouldRecur)
    assert.are.equal(1, task.period) -- untouched Task.new() default
    assert.are.equal(0, task.n)
    assert.are.equal(0, task.nextN)
  end)

  it("seeds nextN from the start month for every-N-months", function()
    local task = build(nil, { recurMode = 3, n = 4, month = 5 })
    assert.are.equal(4, task.n)
    assert.are.equal(3, task.nextN) -- month 5 -> period 3
  end)

  it("seeds nextN from the current day for every-N-days", function()
    installTaskListGlobals(137)
    local task = build(nil, { recurMode = 4, n = 3, month = 5 })
    assert.are.equal(3, task.n)
    assert.are.equal(137, task.nextN)
  end)
end)
