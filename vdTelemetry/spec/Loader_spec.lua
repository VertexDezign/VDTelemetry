-- Unit tests for the loader aspects (issue #169): src/collect/aspects/{LoaderTool,LoaderCylinders}.lua.
--
-- Run with `busted` from the vdTelemetry/ directory. The engine's node maths and the raycast are
-- stubbed: `localDirectionToWorld` answers the tool's Z axis, and `raycastAll` replays a scripted list
-- of hits per direction into the collector's callback.
--
-- What is worth pinning:
--   * the tool is found by the joint it hangs on -- and NOT by the loader's own joint to the tractor;
--   * pitch is positive nose UP (the engine's directionToPitchYaw is the other way round);
--   * the ray ignores every machine on the rig, the loader arm included, and keeps the NEAREST hit;
--   * nothing below casts upward and reports negative;
--   * an unlimited moving tool is left out, because getMovingToolState returns it raw;
--   * the engine's 0..1 runs top-to-bottom on a normally modelled arm, and is turned round so 1 is up.

if ValueMapper == nil then
  dofile("src/mapper/ValueMapper.lua")
end
for name, file in pairs({
  LoaderTool = "src/collect/aspects/LoaderTool.lua",
  LoaderCylinders = "src/collect/aspects/LoaderCylinders.lua",
}) do
  if VDT == nil or VDT[name] == nil then
    dofile(file)
  end
end

local JOINT = { implement = 1, frontloader = 7, attachableFrontloader = 12, telehandler = 6, wheelLoader = 13 }

local function stubEngine(zAxisY, hits)
  rawset(_G, "MathUtil", {
    round = function(v, decimals)
      local mult = 10 ^ (decimals or 0)
      return math.floor(v * mult + 0.5) / mult
    end,
  })
  rawset(_G, "AttacherJoints", { jointTypeNameToInt = JOINT })
  rawset(_G, "CollisionFlag", { TERRAIN = 1, STATIC_OBJECT = 2, VEHICLE = 4, DYNAMIC_OBJECT = 8 })
  rawset(_G, "localDirectionToWorld", function()
    return math.sqrt(1 - zAxisY * zAxisY), zAxisY, 0
  end)
  rawset(_G, "localToWorld", function()
    return 0, 2, 0
  end)
  rawset(_G, "raycastAll", function(_, _, _, _, dirY, _, _, callbackName, target)
    for _, hit in ipairs((dirY < 0 and hits.down or hits.up) or {}) do
      if not target[callbackName](target, hit.node, 0, 0, 0, hit.distance) then
        return
      end
    end
  end)
end

local function unstub()
  for _, name in ipairs({
    "MathUtil",
    "AttacherJoints",
    "CollisionFlag",
    "localDirectionToWorld",
    "localToWorld",
    "raycastAll",
  }) do
    rawset(_G, name, nil)
  end
end

---A tool on `jointName`, on a rig made of `rig` plus itself.
local function tool(jointName, ownNodes, rig)
  local object = {
    rootNode = 100,
    vehicleNodes = ownNodes or { [100] = {} },
    spec_attachable = {},
    getActiveInputAttacherJoint = function()
      return { jointType = JOINT[jointName] }
    end,
  }
  local all = { object }
  for _, v in ipairs(rig or {}) do
    table.insert(all, v)
  end
  object.rootVehicle = {
    getChildVehicles = function()
      return all
    end,
  }
  return object
end

describe("VDT.LoaderTool", function()
  after_each(unstub)

  it("reports nothing for an object that is not attachable", function()
    stubEngine(0, {})
    assert.is_nil(VDT.LoaderTool.collect({ rootNode = 1 }))
  end)

  it("reports nothing for an implement on an ordinary hitch", function()
    stubEngine(0, {})
    assert.is_nil(VDT.LoaderTool.collect(tool("implement")))
  end)

  it("does not mark the loader arm, which hangs on the tractor by a loader joint of its own", function()
    -- attachableFrontloader is the joint between the loader and the tractor. Matching it would put the
    -- tool's readout on the arm.
    stubEngine(0, {})
    assert.is_nil(VDT.LoaderTool.collect(tool("attachableFrontloader")))
  end)

  it("reports a shovel on a front loader with its joint, a level pitch and the ground under it", function()
    stubEngine(0, { down = { { node = 1, distance = 0.42 } } })
    local m = VDT.LoaderTool.collect(tool("frontloader"))
    assert.are.equal("FRONTLOADER", m.joint)
    assert.are.equal(0, m.pitch)
    assert.are.equal(0.42, m.distance)
  end)

  it("reads pitch positive nose up", function()
    -- Z axis pointing up at 30 degrees: dy = sin(30) = 0.5.
    stubEngine(0.5, {})
    assert.are.equal(30, VDT.LoaderTool.collect(tool("wheelLoader")).pitch)
    stubEngine(-0.5, {})
    assert.are.equal(-30, VDT.LoaderTool.collect(tool("telehandler")).pitch)
  end)

  it("ignores every machine on the rig, the loader arm and the tractor included", function()
    local tractor = { vehicleNodes = { [10] = {} } }
    local arm = { vehicleNodes = { [20] = {} } }
    stubEngine(0, {
      down = {
        { node = 100, distance = 0.1 }, -- the tool itself
        { node = 20, distance = 0.3 }, -- the loader arm
        { node = 10, distance = 0.8 }, -- the tractor's bonnet
        { node = 1, distance = 1.25 }, -- a bale
      },
    })
    assert.are.equal(1.25, VDT.LoaderTool.collect(tool("frontloader", nil, { tractor, arm })).distance)
  end)

  it("keeps the nearest hit whatever order the engine reports them in", function()
    stubEngine(0, { down = { { node = 1, distance = 2.0 }, { node = 2, distance = 0.6 } } })
    assert.are.equal(0.6, VDT.LoaderTool.collect(tool("frontloader")).distance)
  end)

  it("casts upward and reports negative when nothing is below", function()
    -- The tool is buried in a heap, or the ray started under the terrain.
    stubEngine(0, { down = {}, up = { { node = 1, distance = 0.15 } } })
    assert.are.equal(-0.15, VDT.LoaderTool.collect(tool("frontloader")).distance)
  end)

  it("leaves the distance out when neither ray hits anything", function()
    stubEngine(0, {})
    local m = VDT.LoaderTool.collect(tool("frontloader"))
    assert.is_nil(m.distance)
    assert.are.equal(0, m.pitch)
  end)
end)

describe("VDT.LoaderCylinders", function()
  -- The engine's Cylindered.getMovingToolState, which is a spec-table function and NOT a method on
  -- the vehicle: the machines below deliberately carry no `getMovingToolState` of their own.
  --
  -- Every node's X axis is scripted per node: the machine's root (node 1) points +X, and a tool node
  -- answers whatever `xAxis` says for it -- the machine's way (1), reversed (-1) or across it (0).
  local xAxis
  before_each(function()
    stubEngine(0, {})
    xAxis = {}
    rawset(_G, "localDirectionToWorld", function(node)
      local x = node == 1 and 1 or (xAxis[node] or 1)
      return x, 0, math.sqrt(1 - x * x)
    end)
    rawset(_G, "Cylindered", {
      getMovingToolState = function(object, t)
        return object.states[t]
      end,
    })
  end)
  after_each(function()
    unstub()
    rawset(_G, "Cylindered", nil)
  end)

  local function machine(tools, states)
    return { rootNode = 1, spec_cylindered = { movingTools = tools }, states = states }
  end

  it("reports nothing without Cylindered", function()
    assert.is_nil(VDT.LoaderCylinders.collect({}))
  end)

  it("reads the role off the axis and turns lift and tilt so 1 is up", function()
    -- The John Deere 623R in frontLoader_shovel_raisedTipped.json: the engine read the raised arm as
    -- 0.101 and the dumped shovel as 0.81, because a positive rotation about X turns the arm DOWN.
    local arm = { node = 10, axis = "AXIS_FRONTLOADER_ARM", rotMin = -0.5, rotMax = 0.6 }
    local tilt = { node = 11, axis = "AXIS_FRONTLOADER_TOOL", rotMin = -1, rotMax = 0.8 }
    local out = VDT.LoaderCylinders.collect(machine({ arm, tilt }, { [arm] = 0.101, [tilt] = 0.81 }))
    assert.are.same({
      { role = "LIFT", axis = "AXIS_FRONTLOADER_ARM", travel = 0.899 },
      { role = "TILT", axis = "AXIS_FRONTLOADER_TOOL", travel = 0.19 },
    }, out)
  end)

  it("keeps a telescope as the engine reads it, which is already 1 at full extension", function()
    -- The Sennebogen 340 G in telehandler.json, fully extended: 1.
    local boom = { node = 10, axis = "AXIS_FRONTLOADER_ARM2", transMin = 0, transMax = 2.5 }
    assert.are.equal(1, VDT.LoaderCylinders.collect(machine({ boom }, { [boom] = 1 }))[1].travel)
  end)

  it("turns the other way on a node modelled back to front", function()
    xAxis[10] = -1
    xAxis[11] = -1
    local arm = { node = 10, axis = "AXIS_FRONTLOADER_ARM", rotMin = 0, rotMax = 1 }
    local boom = { node = 11, axis = "AXIS_FRONTLOADER_ARM2", transMin = 0, transMax = 2 }
    local out = VDT.LoaderCylinders.collect(machine({ arm, boom }, { [arm] = 0.25, [boom] = 0.25 }))
    assert.are.equal(0.25, out[1].travel)
    assert.are.equal(0.75, out[2].travel)
  end)

  it("leaves out a lift or tilt whose direction cannot be told", function()
    -- Across the machine, about another axis, or driven by an animation: any of them could be
    -- backwards, and a backwards arm is worse than none.
    xAxis[10] = 0
    local across = { node = 10, axis = "AXIS_FRONTLOADER_ARM", rotMin = 0, rotMax = 1 }
    local yawing = { node = 11, axis = "AXIS_FRONTLOADER_TOOL", rotMin = 0, rotMax = 1, rotationAxis = 2 }
    local animated = { node = 12, axis = "AXIS_FRONTLOADER_TOOL", animName = "tilt" }
    assert.is_nil(
      VDT.LoaderCylinders.collect(
        machine({ across, yawing, animated }, { [across] = 0.5, [yawing] = 0.5, [animated] = 0.5 })
      )
    )
  end)

  it("keeps an AUX cylinder as the engine reads it, whatever way it is modelled", function()
    -- The pallet fork's TOOL2: what it does is the tool's business, so there is no up to orient to.
    xAxis[10] = 0
    local clamp = { node = 10, axis = "AXIS_FRONTLOADER_TOOL2", animName = "clamp" }
    assert.are.same(
      { { role = "AUX", axis = "AXIS_FRONTLOADER_TOOL2", travel = 0.334 } },
      VDT.LoaderCylinders.collect(machine({ clamp }, { [clamp] = 0.334 }))
    )
  end)

  it("leaves out a moving tool with no limits, whose state would be a raw angle", function()
    -- getMovingToolState returns curRot itself for a rotation with a speed and no limits. 1.9 rad
    -- read as a fraction is a cylinder at 190 percent.
    local spin = { node = 10, axis = "AXIS_FRONTLOADER_TOOL2", rotSpeed = 0.001 }
    local arm = { node = 11, axis = "AXIS_FRONTLOADER_ARM", rotMin = 0, rotMax = 1 }
    local out = VDT.LoaderCylinders.collect(machine({ spin, arm }, { [spin] = 1.9, [arm] = 0.4 }))
    assert.are.equal(1, #out)
    assert.are.equal("LIFT", out[1].role)
  end)

  it("ignores cranes, unbound tools and tools the configuration removed", function()
    local crane = { node = 10, axis = "AXIS_CRANE_ARM", rotMin = 0, rotMax = 1 }
    local unbound = { node = 11, rotMin = 0, rotMax = 1 }
    local unconfigured =
      { node = 12, axis = "AXIS_FRONTLOADER_TOOL2", rotMin = 0, rotMax = 1, hasRequiredConfigurations = false }
    assert.is_nil(
      VDT.LoaderCylinders.collect(
        machine({ crane, unbound, unconfigured }, { [crane] = 0.5, [unbound] = 0.5, [unconfigured] = 0.5 })
      )
    )
  end)

  it("clamps a travel outside 0..1 before turning it", function()
    local arm = { node = 10, axis = "AXIS_FRONTLOADER_ARM", rotMin = 0, rotMax = 1 }
    assert.are.equal(0, VDT.LoaderCylinders.collect(machine({ arm }, { [arm] = 1.2 }))[1].travel)
  end)
end)
