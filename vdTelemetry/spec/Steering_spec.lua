-- Unit tests for src/collect/vehicle/Steering.lua.
--
-- Run with `busted` from the vdTelemetry/ directory. The collector needs `localToLocal`, which is an
-- engine global — the fakes below hand out node ids that index straight into a position table, so a
-- test can lay a machine out along its own axis without a scene graph.

dofile("src/collect/vehicle/Steering.lua")

-- Node id -> position along the machine. `localToLocal(node, root, 0, 0, 0)` returns a point in the
-- root's space, and only the z of it is ever read.
local positions = {}

rawset(_G, "localToLocal", function(node, _root, _x, _y, _z)
  return 0, 0, positions[node] or 0
end)

local nextNode = 0

---A node at `z`, as the fakes hand them out.
local function nodeAt(z)
  nextNode = nextNode + 1
  positions[nextNode] = z
  return nextNode
end

---A wheel: where it sits, how fast it steers (0 = it doesn't), and how far it is held over.
local function wheel(z, rotSpeed, steeringOffset)
  return { repr = nodeAt(z), physics = { rotSpeed = rotSpeed }, steeringOffset = steeringOffset or 0 }
end

---A machine with four wheels: the front pair steering one way, the rear pair however the mode leaves
---them. Front at z = 2, rear at z = -2, which is a tractor seen from above.
local function fourWheeler(rearRotSpeed, rearOffset, steeringNodes)
  return {
    components = { { node = nodeAt(0) } },
    spec_wheels = {
      wheels = {
        wheel(2, 3),
        wheel(2, 3),
        wheel(-2, rearRotSpeed, rearOffset),
        wheel(-2, rearRotSpeed, rearOffset),
      },
      steeringNodes = steeringNodes,
    },
  }
end

---Bolt crab-steering modes onto a machine.
local function withModes(vehicle, name, state, stateMax)
  vehicle.spec_crabSteering = { hasSteeringModes = true, state = state, stateMax = stateMax }
  vehicle.getCrabSteeringMode = function()
    return { name = name, index = state }
  end
  return vehicle
end

describe("Steering.collect", function()
  it("reports nothing on a machine with neither steering modes nor a reversible seat", function()
    assert.is_nil(VDT.Steering.collect(fourWheeler(0)))
    -- The specs being present is not enough: a type can carry them with nothing loaded.
    local bare = fourWheeler(0)
    bare.spec_crabSteering = { hasSteeringModes = false, state = 1, stateMax = 0 }
    bare.spec_reverseDriving = { hasReverseDriving = false, isReverseDriving = false }
    assert.is_nil(VDT.Steering.collect(bare))
  end)

  it("carries the game's own name for the mode, and where it sits in the list", function()
    local model = VDT.Steering.collect(withModes(fourWheeler(0), "Crab steering", 3, 3))
    assert.are.equal("Crab steering", model.mode.name)
    assert.are.equal(3, model.mode.index)
    assert.are.equal(3, model.mode.count)
  end)

  it("leaves an unnamed mode's name out rather than exporting an empty one", function()
    local model = VDT.Steering.collect(withModes(fourWheeler(0), "", 1, 2))
    assert.is_nil(model.mode.name)
  end)
end)

describe("Steering layout", function()
  local function layoutOf(vehicle)
    return VDT.Steering.collect(withModes(vehicle, "mode", 1, 3)).mode.layout
  end

  it("is FRONT when only the front wheels answer the steering wheel", function()
    assert.are.equal("FRONT", layoutOf(fourWheeler(0)))
  end)

  it("is ALL_WHEEL when the rear wheels turn the other way", function()
    -- Which is how the engine builds a counter-steering axle: the rear wheels' resting rotSpeed is
    -- the opposite sign to the front's.
    assert.are.equal("ALL_WHEEL", layoutOf(fourWheeler(-3)))
  end)

  it("is CRAB when both ends turn the same way", function()
    assert.are.equal("CRAB", layoutOf(fourWheeler(3)))
  end)

  it("tells the two dog walks apart when the rear axle is parked over", function()
    -- The other way to build a crab mode: the rear wheels don't steer at all, they are simply parked
    -- at an angle. That bakes the direction into the mode, so a machine that offers it offers two —
    -- and reporting both as one "CRAB" would draw them identically.
    assert.are.equal("CRAB_LEFT", layoutOf(fourWheeler(0, 0.3)))
    assert.are.equal("CRAB_RIGHT", layoutOf(fourWheeler(0, -0.3)))
    -- A hair of offset is modelling noise, not a mode.
    assert.are.equal("FRONT", layoutOf(fourWheeler(0, 0.001)))
  end)

  it("leaves the crab that follows the steering wheel without a side", function()
    -- When both axles steer, the machine walks whichever way the driver turns — there is no left or
    -- right mode to be in, and claiming one would be a picture of a thing that isn't happening.
    assert.are.equal("CRAB", layoutOf(fourWheeler(3, 0.3)))
  end)

  it("is BACK when only the rear axle steers", function()
    local vehicle = fourWheeler(-3)
    vehicle.spec_wheels.wheels[1].physics.rotSpeed = 0
    vehicle.spec_wheels.wheels[2].physics.rotSpeed = 0
    assert.are.equal("BACK", layoutOf(vehicle))
  end)

  it("reads a whole axle flipped by its steering node", function()
    -- The other half of the mechanism: a mode can set a steering node's rotScale negative, which
    -- turns the axle hanging off it the other way without touching any wheel's own rate.
    local counter = { { node = nodeAt(-2), rotScale = -1, rotSpeed = 2, offset = 0 } }
    assert.are.equal("ALL_WHEEL", layoutOf(fourWheeler(0, 0, counter)))
    local together = { { node = nodeAt(-2), rotScale = 1, rotSpeed = 2, offset = 0 } }
    assert.are.equal("CRAB", layoutOf(fourWheeler(0, 0, together)))
    -- Locked: a mode zeroes the scale, and the axle stops steering whatever its node is built to do.
    local locked = { { node = nodeAt(-2), rotScale = 0, rotSpeed = 2, offset = 0 } }
    assert.are.equal("FRONT", layoutOf(fourWheeler(0, 0, locked)))
  end)

  it("does not let a wheel and its own steering node cancel each other out", function()
    -- Both describe the same axle. Adding them up would net to nothing and report an axle that
    -- steers as one that doesn't; the strongest rate in the group is taken instead.
    local node = { { node = nodeAt(-2), rotScale = 1, rotSpeed = -6, offset = 0 } }
    assert.are.equal("ALL_WHEEL", layoutOf(fourWheeler(3, 0, node)))
    -- Even dead even, the axle is still reported as steering — which way round is the only thing in
    -- doubt, and that beats calling a four-wheel-steer machine front-steered.
    local even = { { node = nodeAt(-2), rotScale = 1, rotSpeed = -3, offset = 0 } }
    local layout = layoutOf(fourWheeler(3, 0, even))
    assert.is_true(layout == "CRAB" or layout == "ALL_WHEEL", "a steering rear axle read as " .. tostring(layout))
  end)

  it("says nothing at all when the machine can't be read this way", function()
    -- One axle, so there are no two ends to compare; and a machine with no root component to
    -- measure against. Absent, not a guess: the mode's name is still exported for these.
    local single = fourWheeler(0)
    single.spec_wheels.wheels = { wheel(0, 3), wheel(0, 3) }
    assert.is_nil(layoutOf(single))

    local rootless = fourWheeler(0)
    rootless.components = nil
    assert.is_nil(layoutOf(rootless))

    local wheelless = fourWheeler(0)
    wheelless.spec_wheels = nil
    assert.is_nil(layoutOf(wheelless))
  end)
end)

describe("Steering.collect reverse driving", function()
  local function seat(isReverseDriving, isChangingDirection)
    local vehicle = fourWheeler(0)
    vehicle.spec_reverseDriving = {
      hasReverseDriving = true,
      isReverseDriving = isReverseDriving,
      isChangingDirection = isChangingDirection,
    }
    return vehicle
  end

  it("reports the seat on its own, with no steering modes in sight", function()
    local model = VDT.Steering.collect(seat(true, false))
    assert.is_true(model.reversed)
    assert.is_false(model.changing)
    assert.is_nil(model.mode)
  end)

  it("reports a seat that is the normal way round as false, not as absent", function()
    -- The distinction the dashboard needs: this machine *has* a reversible position and is not using
    -- it, which is a different picture from a machine that has none.
    assert.is_false(VDT.Steering.collect(seat(false, false)).reversed)
  end)

  it("says while the seat is still turning", function()
    assert.is_true(VDT.Steering.collect(seat(true, true)).changing)
  end)

  it("reports both halves on a machine that has both", function()
    local model = VDT.Steering.collect(withModes(seat(true, false), "All wheel steering", 2, 3))
    assert.are.equal("All wheel steering", model.mode.name)
    assert.is_true(model.reversed)
  end)
end)
