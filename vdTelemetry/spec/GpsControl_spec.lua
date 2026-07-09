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

-- Installs the stubs and returns the settings table, which records every setValue call so we can
-- assert both the setting id written and the value.
local function installGameSettings()
  rawset(_G, "GameSettings", { SETTING = { STEERING_ASSIST_LINES = "steeringAssistLines" } })
  local settings = {
    calls = {},
    setValue = function(self, id, value)
      self.calls[#self.calls + 1] = { id = id, value = value }
    end,
  }
  rawset(_G, "g_gameSettings", settings)
  return settings
end

describe("GpsControl.setLinesVisible", function()
  it("shows the lines when on=true", function()
    local settings = installGameSettings()
    VDT.GpsControl.setLinesVisible(true, debugger)
    assert.are.same({ { id = "steeringAssistLines", value = true } }, settings.calls)
  end)

  it("hides the lines when on=false", function()
    local settings = installGameSettings()
    VDT.GpsControl.setLinesVisible(false, debugger)
    assert.are.same({ { id = "steeringAssistLines", value = false } }, settings.calls)
  end)
end)

describe("setGpsLinesVisible command", function()
  it("ignores the vehicle -- the setting is global, not vehicle state", function()
    local settings = installGameSettings()
    local handler = VDT.CommandRegistry.get("setGpsLinesVisible")
    handler.execute(nil, { on = true }, debugger)
    assert.are.same({ { id = "steeringAssistLines", value = true } }, settings.calls)
  end)
end)
