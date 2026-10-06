-- Unit tests for the More Vehicle Controls integration (src/integrations/MoreVehicleControls.lua).
--
-- Run with `busted` from the vdTelemetry/ directory. The integration is a read of another mod's spec
-- tables, so the objects below are shaped like the mod's own (see references/FS25_moreVehicleControls).
--
-- What is worth testing here is everything that is NOT a plain copy:
--   * the lamps follow the differentials the vehicle has, not the three switches the mod gives every
--     motorized vehicle -- and fall back to the switches where the drivetrain is invisible (MP client);
--   * Enhanced Vehicle's answer stands where it gave one.

if VDT == nil or VDT.MoreVehicleControls == nil then
  dofile("src/integrations/MoreVehicleControls.lua")
end

local AXLE = { diffIndex1IsWheel = true, diffIndex2IsWheel = true }
local CENTRE = { diffIndex1IsWheel = false, diffIndex2IsWheel = false }

---A motorized vehicle with the mod's specs on it. `differentials` is what the host loaded (nil or {}
---for a multiplayer client, which loads none).
local function mvcVehicle(differentials, diffs, handbrake)
  return {
    spec_motorized = { differentials = differentials },
    spec_mvcDifferentials = diffs,
    spec_moreVehicleControls = { handbrake = handbrake },
  }
end

local function contribute(vehicle, motor)
  local model = { motor = motor or {} }
  VDT.MoreVehicleControls.contributeObject(vehicle, model)
  return model.motor
end

describe("VDT.MoreVehicleControls.contributeObject", function()
  it("reports both locks, AWD and the handbrake on a four-wheel-drive tractor", function()
    local motor =
      contribute(mvcVehicle({ AXLE, AXLE, CENTRE }, { frontDiff = false, rearDiff = true, driveMode = 1 }, true))
    assert.are.same({ front = false, back = true }, motor.diffLock)
    assert.is_true(motor.awd)
    assert.is_true(motor.parkingBrake)
  end)

  it("reports 2WD as awd = false, not as absent", function()
    local motor = contribute(mvcVehicle({ AXLE, AXLE, CENTRE }, { frontDiff = false, rearDiff = false, driveMode = 0 }))
    assert.is_false(motor.awd)
  end)

  it("lights no AWD lamp on a machine without a centre differential", function()
    -- the mod's automatic mode still flips driveMode to 1 below 28 km/h here
    local motor = contribute(mvcVehicle({ AXLE }, { frontDiff = false, rearDiff = true, driveMode = 1 }))
    assert.is_nil(motor.awd)
    -- one wheel differential: the rear switch is the one the mod leaves applied on it
    assert.are.same({ back = true }, motor.diffLock)
  end)

  it("reports the switches unfiltered where the drivetrain is not loaded (multiplayer client)", function()
    for _, differentials in ipairs({ {}, false }) do
      local motor =
        contribute(mvcVehicle(differentials or nil, { frontDiff = true, rearDiff = false, driveMode = 1 }, false))
      assert.are.same({ front = true, back = false }, motor.diffLock)
      assert.is_true(motor.awd)
      assert.is_false(motor.parkingBrake)
    end
  end)

  it("reports no drivetrain lamps where the server loaded no differentials", function()
    local vehicle = mvcVehicle({}, { frontDiff = true, rearDiff = true, driveMode = 1 }, true)
    vehicle.isServer = true
    local motor = contribute(vehicle)
    assert.is_nil(motor.diffLock)
    assert.is_nil(motor.awd)
    -- the handbrake is the mod's own brake, not a differential
    assert.is_true(motor.parkingBrake)
  end)

  it("leaves what Enhanced Vehicle already reported", function()
    local motor = contribute(
      mvcVehicle({ AXLE, AXLE, CENTRE }, { frontDiff = true, rearDiff = true, driveMode = 0 }, true),
      { diffLock = { back = false }, awd = true }
    )
    assert.are.same({ back = false }, motor.diffLock)
    assert.is_true(motor.awd)
    -- Enhanced Vehicle said nothing about the parking brake, so this mod's handbrake fills it
    assert.is_true(motor.parkingBrake)
  end)

  it("adds nothing without the mod, or to an object without a motor", function()
    local motor = contribute({ spec_motorized = { differentials = { AXLE, AXLE, CENTRE } } })
    assert.are.same({}, motor)

    local model = {}
    VDT.MoreVehicleControls.contributeObject(mvcVehicle(nil, { frontDiff = true }, true), model)
    assert.are.same({}, model)
  end)
end)
