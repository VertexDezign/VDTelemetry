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
-- setCruiseControlMaxSpeed mirrors the engine by writing the (here unclamped) value into
-- spec.cruiseControl.speed, which the MP sync event reads back.
local function fakeVehicle(drivable)
  local v = { calls = {} }
  if drivable then
    v.spec_drivable = { cruiseControl = { speed = 0, speedReverse = 0 } }
  end
  function v:setCruiseControlState(state)
    self.calls[#self.calls + 1] = { "state", state }
  end
  function v:setCruiseControlMaxSpeed(speed)
    self.calls[#self.calls + 1] = { "speed", speed }
    if self.spec_drivable ~= nil and self.spec_drivable.cruiseControl ~= nil then
      self.spec_drivable.cruiseControl.speed = speed
    end
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

  it("setSpeed broadcasts SetCruiseControlSpeedEvent on the server", function()
    installDrivable()
    local sent = {}
    rawset(_G, "SetCruiseControlSpeedEvent", {
      new = function(vehicle, speed, speedReverse)
        return { vehicle = vehicle, speed = speed, speedReverse = speedReverse }
      end,
    })
    rawset(_G, "g_server", {
      broadcastEvent = function(_, event)
        sent[#sent + 1] = event
      end,
    })
    rawset(_G, "g_client", nil)

    local v = fakeVehicle(true)
    VDT.CruiseControl.apply(v, "setSpeed", 15.5, debugger)

    assert.are.same({ { "speed", 15.5 } }, v.calls)
    assert.are.equal(1, #sent)
    assert.are.equal(15.5, sent[1].speed)
    assert.are.equal(v, sent[1].vehicle)

    rawset(_G, "SetCruiseControlSpeedEvent", nil)
    rawset(_G, "g_server", nil)
  end)

  it("setSpeed sends SetCruiseControlSpeedEvent to the server from a client", function()
    installDrivable()
    local sent = {}
    rawset(_G, "SetCruiseControlSpeedEvent", {
      new = function(vehicle, speed, speedReverse)
        return { vehicle = vehicle, speed = speed, speedReverse = speedReverse }
      end,
    })
    rawset(_G, "g_server", nil)
    rawset(_G, "g_client", {
      getServerConnection = function()
        return {
          sendEvent = function(_, event)
            sent[#sent + 1] = event
          end,
        }
      end,
    })

    local v = fakeVehicle(true)
    VDT.CruiseControl.apply(v, "setSpeed", 20, debugger)

    assert.are.equal(1, #sent)
    assert.are.equal(20, sent[1].speed)

    rawset(_G, "SetCruiseControlSpeedEvent", nil)
    rawset(_G, "g_client", nil)
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
