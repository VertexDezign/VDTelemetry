-- Unit tests for src/command/GpsControl.lua (steering-assist line visibility).
--
-- Run with `busted` from the vdTelemetry/ directory. setLinesVisible reads the engine globals
-- `g_gameSettings` and `GameSettings` (the setting is global client state, not vehicle state), which
-- don't exist off-engine, so we install stubs. They're set via rawset(_G, ...) per test because
-- busted's per-block insulation restores _G between blocks (same reason CruiseControl_spec
-- re-installs its Drivable stub). The control self-registers into VDT.CommandRegistry at load, so we
-- load CommandRegistry first -- but only if it isn't already loaded, so we don't reset the registry
-- another spec populated.

if VDT == nil or VDT.CommandRegistry == nil then
  dofile("src/command/CommandRegistry.lua")
end
dofile("src/command/GpsControl.lua")

local debugger = { debug = function() end, warn = function() end }

-- Installs the stubs and returns the settings table, which holds `current` and records every setValue
-- call so we can assert the setting id, the value, and that doSave is passed through.
local function installGameSettings(current)
  rawset(_G, "GameSettings", { SETTING = { STEERING_ASSIST_LINES = "steeringAssistLines" } })
  local settings = {
    current = current,
    calls = {},
    getValue = function(self)
      return self.current
    end,
    setValue = function(self, id, value, doSave)
      self.calls[#self.calls + 1] = { id = id, value = value, doSave = doSave }
      self.current = value
    end,
  }
  rawset(_G, "g_gameSettings", settings)
  return settings
end

describe("GpsControl.setLinesVisible", function()
  it("shows the lines when on=true", function()
    local settings = installGameSettings(false)
    VDT.GpsControl.setLinesVisible(true, debugger)
    assert.are.same({ { id = "steeringAssistLines", value = true, doSave = true } }, settings.calls)
  end)

  it("hides the lines when on=false", function()
    local settings = installGameSettings(true)
    VDT.GpsControl.setLinesVisible(false, debugger)
    assert.are.same({ { id = "steeringAssistLines", value = false, doSave = true } }, settings.calls)
  end)

  -- setValue always saves + publishes, so a redundant command would rewrite gameSettings.xml.
  it("does not write when the setting already holds the target value", function()
    local settings = installGameSettings(true)
    VDT.GpsControl.setLinesVisible(true, debugger)
    assert.are.same({}, settings.calls)
  end)

  -- A never-written setting reads back nil rather than false; that must still count as "hidden".
  it("treats a nil setting as hidden", function()
    local settings = installGameSettings(nil)
    VDT.GpsControl.setLinesVisible(false, debugger)
    assert.are.same({}, settings.calls)
    VDT.GpsControl.setLinesVisible(true, debugger)
    assert.are.same({ { id = "steeringAssistLines", value = true, doSave = true } }, settings.calls)
  end)
end)

describe("setGpsLinesVisible command", function()
  it("ignores the vehicle -- the setting is global, not vehicle state", function()
    local settings = installGameSettings(false)
    local handler = VDT.CommandRegistry.get("setGpsLinesVisible")
    handler.execute(nil, { on = true }, debugger)
    assert.are.same({ { id = "steeringAssistLines", value = true, doSave = true } }, settings.calls)
  end)
end)
