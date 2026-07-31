-- Unit tests for src/collect/vehicle/Motor.lua, specifically the gear subtree.
--
-- Run with `busted` from the vdTelemetry/ directory. The collector needs ValueMapper (numeric fields
-- run through it), and nothing else global as long as the motor reports no fill-type consumers.

if ValueMapper == nil then
  dofile("src/mapper/ValueMapper.lua")
end
dofile("src/collect/vehicle/Motor.lua")

-- A motor whose gear API answers exactly what the engine's would. `getGearGroupToDisplay` is the
-- interesting one: it returns a name *and* whether this transmission has groups at all, and the name
-- is the placeholder "N" whenever it hasn't.
local function fakeMotor(gear, groupName, groupsAvailable)
  return {
    getLastMotorRpm = function()
      return 1450
    end,
    getMaxRpm = function()
      return 2200
    end,
    getSmoothLoadPercentage = function()
      return 0.42
    end,
    getGearToDisplay = function()
      return gear
    end,
    getIsInNeutral = function()
      return false
    end,
    getGearGroupToDisplay = function()
      return groupName, groupsAvailable
    end,
    getDrivingDirection = function()
      return 1
    end,
    getMaximumForwardSpeed = function()
      return nil
    end,
    getMaximumBackwardSpeed = function()
      return nil
    end,
  }
end

local function fakeVehicle(motor)
  return {
    spec_motorized = {
      getMotorState = function()
        return 3
      end,
      motorTemperature = { value = 89, valueMin = 20, valueMax = 120 },
      getMotor = function()
        return motor
      end,
      consumersByFillType = {},
    },
  }
end

describe("Motor.collect gear", function()
  it("leaves the group out when the transmission has no ranges", function()
    -- A combine, a CVT, anything fully automatic: the engine returns "N" here as a placeholder and
    -- says so with the flag beside it. Exporting the name anyway had the terminal print "ND".
    local model = VDT.Motor.collect(fakeVehicle(fakeMotor("D", "N", false)))
    assert.are.equal("D", model.gear.value)
    assert.is_nil(model.gear.group)
  end)

  it("leaves it out for a geared tractor with no ranges either", function()
    -- Same placeholder, and this is the one that read as "N12".
    local model = VDT.Motor.collect(fakeVehicle(fakeMotor("12", "N", false)))
    assert.are.equal("12", model.gear.value)
    assert.is_nil(model.gear.group)
  end)

  it("exports the range a ranged transmission is in", function()
    local model = VDT.Motor.collect(fakeVehicle(fakeMotor("2", "E", true)))
    assert.are.equal("2", model.gear.value)
    assert.are.equal("E", model.gear.group)
  end)

  it("exports N as a range like any other when the transmission has ranges", function()
    -- Groups available and the range lever out: "N" is the answer here, not a placeholder, and the
    -- terminal prints it against the gear the same as any other range.
    local model = VDT.Motor.collect(fakeVehicle(fakeMotor("2", "N", true)))
    assert.are.equal("N", model.gear.group)
  end)
end)
