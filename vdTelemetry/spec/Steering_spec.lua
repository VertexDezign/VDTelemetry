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

---A wheel at `z` whose built-in steering rate is `rotSpeed` (0 = it doesn't steer at all).
local function wheel(z, rotSpeed)
  return { repr = nodeAt(z), physics = { rotSpeed = rotSpeed } }
end

---A four-wheeler and the one steering mode it is in, front axle at z = 2 and rear at z = -2 — a
---tractor seen from above.
---
---`front` and `back` each describe what the mode does to that end: `rate` is the axle's built-in
---steering rate and `offset` the rest angle the mode holds its wheels at, which is how the engine
---builds a dog walk. `locked` takes the axle out of the steering altogether.
---@param back table|nil
---@param front table|nil
local function machine(back, front)
  back = back or {}
  front = front or {}
  local wheels = {
    wheel(2, front.rate or 3),
    wheel(2, front.rate or 3),
    wheel(-2, back.rate or 0),
    wheel(-2, back.rate or 0),
  }
  local modeWheels = {}
  for index, w in ipairs(wheels) do
    local side = index <= 2 and front or back
    table.insert(modeWheels, { wheel = w, offset = side.offset or 0, locked = side.locked or false })
  end

  local steeringNodes, modeNodes = {}, {}
  for _, side in ipairs({ front, back }) do
    if side.node ~= nil then
      local node = { node = nodeAt(side == front and 2 or -2), rotSpeed = side.node.rotSpeed, rotScaleOrig = 1 }
      table.insert(steeringNodes, node)
      table.insert(modeNodes, {
        steeringNode = node,
        rotScale = side.node.rotScale,
        locked = side.node.locked or false,
        offset = side.node.offset or 0,
      })
    end
  end

  return {
    components = { { node = nodeAt(0) } },
    spec_wheels = { wheels = wheels, steeringNodes = steeringNodes },
    -- The mode itself, which is where the collector reads what this mode does — not off the live
    -- wheels, which are still animating towards it.
    mode = { wheels = modeWheels, steeringNodes = modeNodes },
  }
end

---Bolt crab-steering modes onto a machine.
local function withModes(vehicle, name, state, stateMax)
  vehicle.spec_crabSteering = { hasSteeringModes = true, state = state, stateMax = stateMax }
  local mode = vehicle.mode
  mode.name = name
  mode.index = state
  vehicle.getCrabSteeringMode = function()
    return mode
  end
  return vehicle
end

describe("Steering.collect", function()
  it("reports nothing on a machine with neither steering modes nor a reversible seat", function()
    assert.is_nil(VDT.Steering.collect(machine()))
    -- The specs being present is not enough: a type can carry them with nothing loaded.
    local bare = machine()
    bare.spec_crabSteering = { hasSteeringModes = false, state = 1, stateMax = 0 }
    bare.spec_reverseDriving = { hasReverseDriving = false, isReverseDriving = false }
    assert.is_nil(VDT.Steering.collect(bare))
  end)

  it("carries the game's own name for the mode, and where it sits in the list", function()
    local model = VDT.Steering.collect(withModes(machine(), "Crab steering", 3, 3))
    assert.are.equal("Crab steering", model.mode.name)
    assert.are.equal(3, model.mode.index)
    assert.are.equal(3, model.mode.count)
  end)

  it("leaves an unnamed mode's name out rather than exporting an empty one", function()
    local model = VDT.Steering.collect(withModes(machine(), "", 1, 2))
    assert.is_nil(model.mode.name)
  end)
end)

describe("Steering layout", function()
  local function layoutOf(vehicle)
    return VDT.Steering.collect(withModes(vehicle, "mode", 1, 3)).mode.layout
  end

  it("is FRONT when only the front wheels answer the steering wheel", function()
    assert.are.equal("FRONT", layoutOf(machine()))
  end)

  it("is ALL_WHEEL when the rear wheels turn the other way", function()
    -- Which is how the engine builds a counter-steering axle: the rear wheels' resting rotSpeed is
    -- the opposite sign to the front's.
    assert.are.equal("ALL_WHEEL", layoutOf(machine({ rate = -3 })))
  end)

  it("is a dog walk when the wheels are held over, even though both axles still steer", function()
    -- The case that came back wrong from the game. A mode called "Hundeganglenkung links" leaves the
    -- rear axle counter-steering exactly as four-wheel steering does — the difference is entirely in
    -- the rest angle the wheels are held at, which the engine steers *from*. Comparing the senses
    -- alone reported both dog walks as ALL_WHEEL.
    assert.are.equal("CRAB_LEFT", layoutOf(machine({ rate = -3, offset = 0.3 }, { offset = 0.3 })))
    assert.are.equal("CRAB_RIGHT", layoutOf(machine({ rate = -3, offset = -0.3 }, { offset = -0.3 })))
    -- ...and with only the rear axle held over, which is the same walk built more cheaply.
    assert.are.equal("CRAB_LEFT", layoutOf(machine({ rate = -3, offset = 0.3 })))
  end)

  it("is a dog walk when the held axle has stopped steering too", function()
    assert.are.equal("CRAB_LEFT", layoutOf(machine({ locked = true, offset = 0.3 })))
    -- A hair of offset is modelling noise, not a mode.
    assert.are.equal("FRONT", layoutOf(machine({ offset = 0.001 })))
  end)

  it("does not read a symmetric toe-in as a dog walk", function()
    -- Held equally *against* each other, the machine tracks straight ahead — so the offsets cancel
    -- and the senses decide, rather than the larger one winning by accident.
    assert.are.equal("ALL_WHEEL", layoutOf(machine({ rate = -3, offset = -0.3 }, { offset = 0.3 })))
  end)

  it("leaves the crab that follows the steering wheel without a side", function()
    -- Both ends steering the same way with nothing held over: the machine walks whichever way the
    -- driver turns, and claiming a side would be a picture of a thing that isn't happening.
    assert.are.equal("CRAB", layoutOf(machine({ rate = 3 })))
  end)

  it("is BACK when only the rear axle steers", function()
    assert.are.equal("BACK", layoutOf(machine({ rate = -3 }, { rate = 0 })))
  end)

  it("reads a whole axle flipped by its steering node", function()
    -- The other half of the mechanism: a mode can set a steering node's rotScale negative, which
    -- turns the axle hanging off it the other way without touching any wheel's own rate.
    assert.are.equal("ALL_WHEEL", layoutOf(machine({ node = { rotScale = -1, rotSpeed = 2 } })))
    assert.are.equal("CRAB", layoutOf(machine({ node = { rotScale = 1, rotSpeed = 2 } })))
    -- Locked: the axle stops steering whatever its node is built to do.
    assert.are.equal("FRONT", layoutOf(machine({ node = { locked = true, rotSpeed = 2 } })))
    -- And a node held over is a dog walk like any other.
    assert.are.equal("CRAB_RIGHT", layoutOf(machine({ node = { rotScale = -1, rotSpeed = 2, offset = -0.3 } })))
  end)

  it("does not let a wheel and its own steering node cancel each other out", function()
    -- Both describe the same axle. Adding them up would net to nothing and report an axle that
    -- steers as one that doesn't; the strongest rate in the group is taken instead.
    local strong = machine({ rate = 3, node = { rotScale = 1, rotSpeed = -6 } })
    assert.are.equal("ALL_WHEEL", layoutOf(strong))
    -- Even dead even, the axle is still reported as steering — which way round is the only thing in
    -- doubt, and that beats calling a four-wheel-steer machine front-steered.
    local even = machine({ rate = 3, node = { rotScale = 1, rotSpeed = -3 } })
    local layout = layoutOf(even)
    assert.is_true(layout == "CRAB" or layout == "ALL_WHEEL", "a steering rear axle read as " .. tostring(layout))
  end)

  it("takes a locked axle's built-in rate from where the engine parked it", function()
    -- CrabSteering holds a locked wheel's live rotSpeed at 0 and keeps the real one in rotSpeedBackUp.
    -- Reading the live value would report the axle as non-steering in the *next* mode too.
    local released = machine({ rate = 0 })
    for index = 3, 4 do
      released.spec_wheels.wheels[index].rotSpeedBackUp = -3
    end
    assert.are.equal("ALL_WHEEL", layoutOf(released))
  end)

  it("says nothing at all when the machine can't be read this way", function()
    -- One axle, so there are no two ends to compare; and a machine with no root component to
    -- measure against. Absent, not a guess: the mode's name is still exported for these.
    local single = machine()
    single.spec_wheels.wheels = { wheel(0, 3), wheel(0, 3) }
    single.mode.wheels = {}
    assert.is_nil(layoutOf(single))

    local rootless = machine()
    rootless.components = nil
    assert.is_nil(layoutOf(rootless))

    local wheelless = machine()
    wheelless.spec_wheels = nil
    assert.is_nil(layoutOf(wheelless))
  end)
end)

describe("Steering.collect reverse driving", function()
  local function seat(isReverseDriving, isChangingDirection)
    local vehicle = machine()
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
