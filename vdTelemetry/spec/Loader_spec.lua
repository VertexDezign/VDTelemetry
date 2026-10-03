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
--   * "up" is the end a positive input drives toward, whatever way the part was modelled -- the
--     rule that read the Kubota SVL's lift upside down was a geometric one.

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
  before_each(function()
    stubEngine(0, {})
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

  -- A rotating cylinder whose positive input drives toward `toward` ("max" or "min"), the two ways the
  -- captured machines are authored: a positive speed, or a negative one / an inverted axis.
  local function rotating(axis, toward, invert)
    local speed = toward == "max" and 0.001 or -0.001
    if invert then
      speed = -speed
    end
    return { axis = axis, rotMin = -1, rotMax = 1, rotSpeed = speed, invertAxis = invert == true }
  end

  it("reports nothing without Cylindered", function()
    assert.is_nil(VDT.LoaderCylinders.collect({}))
  end)

  it("puts every captured fully raised arm at the top, whichever end of its limits that is", function()
    -- skidSteer_fullyRaised / frontLoader_shovel_raisedTipped / telehandler / wheelLoader.json:
    -- three raise toward their min, the Kubota toward its max.
    local kubota = rotating("AXIS_FRONTLOADER_ARM", "max")
    local johnDeere = rotating("AXIS_FRONTLOADER_ARM", "min")
    assert.are.equal(1, VDT.LoaderCylinders.collect(machine({ kubota }, { [kubota] = 1 }))[1].travel)
    assert.are.equal(1, VDT.LoaderCylinders.collect(machine({ johnDeere }, { [johnDeere] = 0 }))[1].travel)
  end)

  it("reads an inverted axis as the author meant it", function()
    -- Same node, same speed, the key flipped in the XML: up is now the other end.
    local arm = rotating("AXIS_FRONTLOADER_ARM", "max", true)
    assert.are.equal(0.25, VDT.LoaderCylinders.collect(machine({ arm }, { [arm] = 0.25 }))[1].travel)
  end)

  it("reads the role off the axis and turns tilt so 1 is curled back", function()
    -- The John Deere's shovel tipped out: the engine reads 1, tipping is a negative input.
    local arm = rotating("AXIS_FRONTLOADER_ARM", "min")
    local tilt = rotating("AXIS_FRONTLOADER_TOOL", "min")
    local boom = { axis = "AXIS_FRONTLOADER_ARM2", transMin = 0, transMax = 2.5, transSpeed = 0.001 }
    local out = VDT.LoaderCylinders.collect(machine({ arm, boom, tilt }, { [arm] = 0, [boom] = 1, [tilt] = 1 }))
    assert.are.same({
      { role = "LIFT", axis = "AXIS_FRONTLOADER_ARM", travel = 1 },
      { role = "TELESCOPE", axis = "AXIS_FRONTLOADER_ARM2", travel = 1 },
      { role = "TILT", axis = "AXIS_FRONTLOADER_TOOL", travel = 0 },
    }, out)
  end)

  it("orients an animated cylinder by its animation speed", function()
    local arm = { axis = "AXIS_FRONTLOADER_ARM", animName = "lift", animSpeed = -0.001 }
    assert.are.equal(0.75, VDT.LoaderCylinders.collect(machine({ arm }, { [arm] = 0.25 }))[1].travel)
  end)

  it("leaves out a lift or tilt with no speed to read a direction from", function()
    local still = { axis = "AXIS_FRONTLOADER_ARM", rotMin = 0, rotMax = 1 }
    assert.is_nil(VDT.LoaderCylinders.collect(machine({ still }, { [still] = 0.5 })))
  end)

  it("keeps an AUX cylinder as the engine reads it", function()
    -- The pallet fork's TOOL2: what it does is the tool's business, so there is no up to orient to.
    local clamp = { axis = "AXIS_FRONTLOADER_TOOL2", animName = "clamp" }
    assert.are.same(
      { { role = "AUX", axis = "AXIS_FRONTLOADER_TOOL2", travel = 0.334 } },
      VDT.LoaderCylinders.collect(machine({ clamp }, { [clamp] = 0.334 }))
    )
  end)

  it("leaves out a moving tool with no limits, whose state would be a raw angle", function()
    -- getMovingToolState returns curRot itself for a rotation with a speed and no limits. 1.9 rad
    -- read as a fraction is a cylinder at 190 percent.
    local spin = { axis = "AXIS_FRONTLOADER_TOOL2", rotSpeed = 0.001 }
    local arm = rotating("AXIS_FRONTLOADER_ARM", "max")
    local out = VDT.LoaderCylinders.collect(machine({ spin, arm }, { [spin] = 1.9, [arm] = 0.4 }))
    assert.are.equal(1, #out)
    assert.are.equal("LIFT", out[1].role)
  end)

  it("ignores cranes, unbound tools and tools the configuration removed", function()
    local crane = rotating("AXIS_CRANE_ARM", "max")
    local unbound = { rotMin = 0, rotMax = 1, rotSpeed = 0.001 }
    local unconfigured = rotating("AXIS_FRONTLOADER_TOOL2", "max")
    unconfigured.hasRequiredConfigurations = false
    assert.is_nil(
      VDT.LoaderCylinders.collect(
        machine({ crane, unbound, unconfigured }, { [crane] = 0.5, [unbound] = 0.5, [unconfigured] = 0.5 })
      )
    )
  end)

  it("clamps a travel outside 0..1 before turning it", function()
    local arm = rotating("AXIS_FRONTLOADER_ARM", "min")
    assert.are.equal(0, VDT.LoaderCylinders.collect(machine({ arm }, { [arm] = 1.2 }))[1].travel)
  end)
end)
