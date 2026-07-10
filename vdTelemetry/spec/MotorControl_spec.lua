-- Unit tests for src/command/MotorControl.lua (engine start/stop dispatch).
--
-- Run with `busted` from the vdTelemetry/ directory. The control file self-registers into
-- VDT.CommandRegistry at load (dofile runs in the real _G), so we load CommandRegistry first -- but
-- only if it isn't already loaded, so we don't reset the registry another spec populated. We test the
-- VDT.MotorControl function directly, not the registration.

if VDT == nil or VDT.CommandRegistry == nil then
  dofile("src/command/CommandRegistry.lua")
end
dofile("src/command/MotorControl.lua")

local debugger = { debug = function() end, warn = function() end }

-- A vehicle stub that records start/stop calls; spec_motorized present only when hasMotor.
local function fakeVehicle(hasMotor)
  local v = { calls = {} }
  if hasMotor then
    v.spec_motorized = {}
  end
  function v:startMotor()
    self.calls[#self.calls + 1] = "start"
  end
  function v:stopMotor()
    self.calls[#self.calls + 1] = "stop"
  end
  return v
end

describe("MotorControl.setMotorState", function()
  it("starts the motor when on=true", function()
    local v = fakeVehicle(true)
    VDT.MotorControl.setMotorState(v, true, debugger)
    assert.are.same({ "start" }, v.calls)
  end)

  it("stops the motor when on=false", function()
    local v = fakeVehicle(true)
    VDT.MotorControl.setMotorState(v, false, debugger)
    assert.are.same({ "stop" }, v.calls)
  end)

  it("ignores vehicles without a motor spec", function()
    local v = fakeVehicle(false)
    VDT.MotorControl.setMotorState(v, true, debugger)
    assert.are.same({}, v.calls)
  end)
end)
