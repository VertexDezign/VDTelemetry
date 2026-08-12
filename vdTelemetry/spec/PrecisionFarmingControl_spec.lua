-- Unit tests for src/command/PrecisionFarmingControl.lua (application-rate dispatch).
--
-- Run with `busted` from the vdTelemetry/ directory. The control file self-registers into
-- VDT.CommandRegistry at load (dofile runs in the real _G), so we load CommandRegistry first -- but
-- only if it isn't already loaded, so we don't reset the registry another spec populated. We test the
-- VDT.PrecisionFarmingControl functions directly, not the registration.

if VDT == nil or VDT.CommandRegistry == nil then
  dofile("src/command/CommandRegistry.lua")
end
dofile("src/command/PrecisionFarmingControl.lua")

local debugger = { debug = function() end, warn = function() end }

-- A PF sprayer stub recording what the control asked of it.
local function fakeSprayer()
  local sprayer = { calls = {} }
  function sprayer:setSprayAmountAutoMode(state)
    self.calls[#self.calls + 1] = { "auto", state }
  end
  function sprayer:setSprayAmountManualValue(value)
    self.calls[#self.calls + 1] = { "step", value }
  end
  -- PF registers this on every ExtendedSprayer and it answers for the whole rig, so every child that
  -- has it returns the same machine.
  function sprayer:getValidSprayerToUse()
    return sprayer
  end
  return sprayer
end

-- A tractor with `sprayer` hitched behind it: the rig as childVehicles reports it, tractor first.
local function fakeRig(sprayer)
  local tractor = {}
  tractor.childVehicles = { tractor }
  if sprayer ~= nil then
    tractor.childVehicles[2] = sprayer
  end
  return tractor
end

describe("PrecisionFarmingControl.setSprayAmountAuto", function()
  it("drives the sprayer behind the controlled vehicle, not the vehicle", function()
    local sprayer = fakeSprayer()
    VDT.PrecisionFarmingControl.setSprayAmountAuto(fakeRig(sprayer), false, debugger)
    assert.are.same({ { "auto", false } }, sprayer.calls)
  end)

  -- PF reads a nil state as "toggle the mode", which is the one thing a lossy channel must not send:
  -- a doubled or dropped command would leave the terminal and the machine disagreeing.
  it("always sends an explicit state, never nil", function()
    local sprayer = fakeSprayer()
    VDT.PrecisionFarmingControl.setSprayAmountAuto(fakeRig(sprayer), nil, debugger)
    assert.are.same({ { "auto", false } }, sprayer.calls)

    sprayer.calls = {}
    VDT.PrecisionFarmingControl.setSprayAmountAuto(fakeRig(sprayer), true, debugger)
    assert.are.same({ { "auto", true } }, sprayer.calls)
  end)

  it("ignores a rig with no Precision Farming sprayer on it", function()
    -- Not an error: every command is dispatched against whatever the player happens to be driving.
    assert.has_no.errors(function()
      VDT.PrecisionFarmingControl.setSprayAmountAuto(fakeRig(nil), true, debugger)
    end)
  end)

  it("ignores a sprayer whose setter has moved", function()
    local sprayer = fakeSprayer()
    sprayer.setSprayAmountAutoMode = nil
    assert.has_no.errors(function()
      VDT.PrecisionFarmingControl.setSprayAmountAuto(fakeRig(sprayer), true, debugger)
    end)
    assert.are.same({}, sprayer.calls)
  end)
end)

describe("PrecisionFarmingControl.setSprayAmountStep", function()
  it("sets an absolute step and leaves the mode alone", function()
    local sprayer = fakeSprayer()
    VDT.PrecisionFarmingControl.setSprayAmountStep(fakeRig(sprayer), 4, debugger)
    assert.are.same({ { "step", 4 } }, sprayer.calls)
  end)

  it("rounds to a whole step -- the value indexes PF's level tables", function()
    local sprayer = fakeSprayer()
    VDT.PrecisionFarmingControl.setSprayAmountStep(fakeRig(sprayer), 3.6, debugger)
    assert.are.same({ { "step", 4 } }, sprayer.calls)
  end)

  it("passes an out-of-range step through for PF to clamp", function()
    -- setSprayAmountManualValue clamps to the machine's own min/max, and those move with the fill
    -- type -- so the machine, not a stale copy of its bounds in the app, decides what is reachable.
    local sprayer = fakeSprayer()
    VDT.PrecisionFarmingControl.setSprayAmountStep(fakeRig(sprayer), 99, debugger)
    assert.are.same({ { "step", 99 } }, sprayer.calls)
  end)

  it("ignores a step that is not a number", function()
    local sprayer = fakeSprayer()
    VDT.PrecisionFarmingControl.setSprayAmountStep(fakeRig(sprayer), nil, debugger)
    assert.are.same({}, sprayer.calls)
  end)

  it("survives a vehicle that reports no rig at all", function()
    -- childVehicles is set on every Vehicle (`{ self }` at load), but a stub or a half-built vehicle
    -- may not have it yet; falling back to the vehicle itself keeps a self-propelled sprayer working.
    local sprayer = fakeSprayer()
    sprayer.childVehicles = nil
    VDT.PrecisionFarmingControl.setSprayAmountStep(sprayer, 2, debugger)
    assert.are.same({ { "step", 2 } }, sprayer.calls)
  end)
end)
