-- Unit tests for src/command/PrecisionFarmingControl.lua (application-rate dispatch).
--
-- Run with `busted` from the vdTelemetry/ directory. The control file self-registers into
-- VDT.CommandRegistry at load (dofile runs in the real _G), so we load CommandRegistry first -- but
-- only if it isn't already loaded, so we don't reset the registry another spec populated. We test the
-- VDT.PrecisionFarmingControl functions directly, not the registration.

if VDT == nil or VDT.CommandRegistry == nil then
  dofile("src/command/CommandRegistry.lua")
end
-- The control resolves which machine to drive through the integration, so that loads first -- the
-- same order VDTelemetry.lua sources them in.
if VDT == nil or VDT.PrecisionFarming == nil then
  if ValueMapper == nil then
    dofile("src/mapper/ValueMapper.lua")
  end
  dofile("src/integrations/PrecisionFarming.lua")
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
  return sprayer
end

-- The rig as PF answers for it. `getValidSprayerToUse` is a static on PF's spec class and NOT a
-- registered vehicle function, so it is only reachable through the mod-env global -- which is what
-- this stubs, and what the first version of this control got wrong.
local function fakeRig(sprayer)
  rawset(_G, "FS25_precisionFarming", {
    ExtendedSprayer = {
      getValidSprayerToUse = function()
        return sprayer
      end,
    },
  })
  return { name = "the controlled vehicle" }
end

before_each(function()
  rawset(_G, "g_modIsLoaded", { FS25_precisionFarming = true })
end)

after_each(function()
  rawset(_G, "g_modIsLoaded", nil)
  rawset(_G, "FS25_precisionFarming", nil)
end)

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

  -- The regression this control shipped with: `getValidSprayerToUse` is a static on PF's spec class
  -- and is absent from ExtendedSprayer.registerFunctions, so it is NOT on the vehicle. Reading it off
  -- the vehicle found nothing on every machine there is -- a self-propelled sprayer included, which
  -- is what made it obvious in game. Only the mod-env lookup resolves it.
  it("reaches PF's resolver through the mod env, not through the vehicle", function()
    local sprayer = fakeSprayer()
    local rig = fakeRig(sprayer)
    rawset(_G, "FS25_precisionFarming", nil)
    VDT.PrecisionFarmingControl.setSprayAmountStep(rig, 2, debugger)
    assert.are.same({}, sprayer.calls, "no env, no sprayer -- and no crash")

    fakeRig(sprayer)
    VDT.PrecisionFarmingControl.setSprayAmountStep(rig, 2, debugger)
    assert.are.same({ { "step", 2 } }, sprayer.calls)
  end)

  it("survives a resolver that throws", function()
    rawset(_G, "FS25_precisionFarming", {
      ExtendedSprayer = {
        getValidSprayerToUse = function()
          error("PF internals moved")
        end,
      },
    })
    assert.has_no.errors(function()
      VDT.PrecisionFarmingControl.setSprayAmountStep({}, 2, debugger)
    end)
  end)
end)
