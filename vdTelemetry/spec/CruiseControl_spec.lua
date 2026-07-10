-- Unit tests for src/command/CruiseControl.lua (enable/disable/setSpeed dispatch).
--
-- Run with `busted` from the vdTelemetry/ directory. apply() reads the engine global `Drivable`
-- (CRUISECONTROL_STATE_*), which doesn't exist off-engine, so we install a minimal stub. It's set via
-- rawset(_G, ...) per test because busted's per-block insulation restores _G between blocks (same
-- reason CommandChannel_spec re-installs its XMLFile stub). The control self-registers into
-- VDT.CommandRegistry at load, so we load CommandRegistry first -- but only if it isn't already
-- loaded, so we don't reset the registry another spec populated.

if VDT == nil or VDT.CommandRegistry == nil then
  dofile("src/command/CommandRegistry.lua")
end
dofile("src/command/CruiseControl.lua")

local debugger = { debug = function() end, warn = function() end }

local function installDrivable()
  rawset(_G, "Drivable", {
    CRUISECONTROL_STATE_OFF = 0,
    CRUISECONTROL_STATE_ACTIVE = 1,
    CRUISECONTROL_STATE_FULL = 2,
  })
end

-- A vehicle stub that records cruise setter calls; spec_drivable present only when drivable.
local function fakeVehicle(drivable)
  local v = { calls = {} }
  if drivable then
    v.spec_drivable = {}
  end
  function v:setCruiseControlState(state)
    self.calls[#self.calls + 1] = { "state", state }
  end
  function v:setCruiseControlMaxSpeed(speed)
    self.calls[#self.calls + 1] = { "speed", speed }
  end
  return v
end

describe("CruiseControl.apply", function()
  it("enable -> setCruiseControlState(ACTIVE)", function()
    installDrivable()
    local v = fakeVehicle(true)
    VDT.CruiseControl.apply(v, "enable", nil, debugger)
    assert.are.same({ { "state", Drivable.CRUISECONTROL_STATE_ACTIVE } }, v.calls)
  end)

  it("disable -> setCruiseControlState(OFF)", function()
    installDrivable()
    local v = fakeVehicle(true)
    VDT.CruiseControl.apply(v, "disable", nil, debugger)
    assert.are.same({ { "state", Drivable.CRUISECONTROL_STATE_OFF } }, v.calls)
  end)

  it("setSpeed -> setCruiseControlMaxSpeed(speed)", function()
    installDrivable()
    local v = fakeVehicle(true)
    VDT.CruiseControl.apply(v, "setSpeed", 15.5, debugger)
    assert.are.same({ { "speed", 15.5 } }, v.calls)
  end)

  it("setSpeed with no speed is ignored", function()
    installDrivable()
    local v = fakeVehicle(true)
    VDT.CruiseControl.apply(v, "setSpeed", nil, debugger)
    assert.are.same({}, v.calls)
  end)

  it("ignores non-drivable vehicles", function()
    installDrivable()
    local v = fakeVehicle(false)
    VDT.CruiseControl.apply(v, "enable", nil, debugger)
    assert.are.same({}, v.calls)
  end)

  it("ignores unknown actions", function()
    installDrivable()
    local v = fakeVehicle(true)
    VDT.CruiseControl.apply(v, "bogus", nil, debugger)
    assert.are.same({}, v.calls)
  end)
end)
