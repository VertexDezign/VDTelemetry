-- Unit tests for src/collect/vehicle/Motor.lua: the gear subtree, and the motor state.
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

---@param motorState number|nil the engine's MotorState; a running engine (ON) unless a test says otherwise
local function fakeVehicle(motor, motorState)
  return {
    spec_motorized = {
      getMotorState = function()
        return motorState or 4
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

describe("Motor.collect state", function()
  -- The engine has four motor states and the exporter used to have three, which cost the two in the
  -- middle their meaning: the key merely turned went out as STARTING, and a starter actually cranking
  -- went out as ON. Each of the four is its own name now, and only ON is a running engine.
  local function stateOf(motorState)
    return VDT.Motor.collect(fakeVehicle(fakeMotor("D", "N", false), motorState)).state
  end

  it("names each of the engine's four states", function()
    assert.are.equal("OFF", stateOf(1))
    assert.are.equal("IGNITION", stateOf(2))
    assert.are.equal("STARTING", stateOf(3))
    assert.are.equal("ON", stateOf(4))
  end)

  it("does not report a cranking engine as running", function()
    -- The regression this pair exists for: 3 is the starter turning, and it read as ON for as long as
    -- the mapper folded it in with 4.
    assert.are_not.equal(stateOf(3), stateOf(4))
    assert.are.equal("STARTING", stateOf(3))
  end)
end)

describe("Motor.collect rpm and load", function()
  local function motorAt(motorState)
    return VDT.Motor.collect(fakeVehicle(fakeMotor("D", "N", false), motorState))
  end

  it("reports what the engine is doing while the crankshaft turns", function()
    -- The fake reports 1450 rpm at 42% load whatever the state, which is the point: these two are
    -- the states where that reading is the engine's and gets passed through untouched.
    assert.are.equal(1450, motorAt(4).rpm.value)
    assert.are.equal(42, motorAt(4).load.value)
    -- The starter turning it over is a real speed too, and the one that shows a start happening.
    assert.are.equal(1450, motorAt(3).rpm.value)
    assert.are.equal(42, motorAt(3).load.value)
  end)

  it("zeroes both readings on an engine that has stopped", function()
    -- Issue #94: the engine stops updating these but keeps their last values, and its own one-shot
    -- zeroing at the state change does not survive a multiplayer client applying an rpm update that
    -- was already in flight behind the stop event -- which left a smoothed remnant of idle sitting
    -- under 100 rpm on a machine that had been switched off. A stopped engine turns at zero.
    for _, motorState in ipairs({ 1, 2 }) do
      local model = motorAt(motorState)
      assert.are.equal(0, model.rpm.value)
      assert.are.equal(0, model.load.value)
    end
  end)

  it("keeps the rev counter's own scale whatever the engine is doing", function()
    -- Only the reading is zeroed. `max` is the tachometer's face, and a gauge whose scale collapsed
    -- when the key came out would redraw itself every time the machine was parked.
    assert.are.equal(2200, motorAt(1).rpm.max)
    assert.are.equal(2200, motorAt(4).rpm.max)
  end)
end)
