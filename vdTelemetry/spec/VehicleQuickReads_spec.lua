-- Unit tests for the five small cab reads of export v27: the rig's working-speed limit and the
-- odometer (src/collect/VehicleExporter.lua), the hitch position (src/collect/vehicle/Hitch.lua), the
-- load straps (src/collect/aspects/TensionBelts.lua) and the drowned flag (src/collect/aspects/Broken.lua).
--
-- Run with `busted` from the vdTelemetry/ directory. Every one is a read of engine state the specs
-- stand up by hand; the shapes are the engine's own (see each collector's header).

if ValueMapper == nil then
  dofile("src/mapper/ValueMapper.lua")
end
if VDT == nil or VDT.Hitch == nil then
  dofile("src/collect/vehicle/Hitch.lua")
end
if VDT.TensionBelts == nil then
  dofile("src/collect/aspects/TensionBelts.lua")
end
if VDT.Broken == nil then
  dofile("src/collect/aspects/Broken.lua")
end
if VDT.VehicleExporter == nil then
  dofile("src/collect/VehicleExporter.lua")
end

-- ValueMapper rounds through the engine's MathUtil. Stood up per test and torn down after, as the
-- other specs that format numbers do, so no spec sees another's stub.
local function withMathUtil()
  before_each(function()
    rawset(_G, "MathUtil", {
      round = function(value, decimals)
        local factor = 10 ^ (decimals or 0)
        return math.floor(value * factor + 0.5) / factor
      end,
    })
  end)
  after_each(function()
    rawset(_G, "MathUtil", nil)
  end)
end

describe("VehicleExporter.collectSpeedLimit", function()
  withMathUtil()

  local function vehicle(limit)
    return {
      getSpeedLimit = function(_, onlyIfWorking)
        assert.is_true(onlyIfWorking)
        return limit, limit ~= math.huge
      end,
    }
  end

  it("reports the cap the working machines set, in km/h", function()
    assert.are.equal(12, VDT.VehicleExporter.collectSpeedLimit(vehicle(12)))
  end)

  it("keeps one decimal, which is what a damaged machine's reduced cap needs", function()
    assert.are.equal(11.4, VDT.VehicleExporter.collectSpeedLimit(vehicle(11.43)))
  end)

  it("is absent while nothing is working, where the engine says math.huge", function()
    assert.is_nil(VDT.VehicleExporter.collectSpeedLimit(vehicle(math.huge)))
  end)

  it("is absent on an object without the getter", function()
    assert.is_nil(VDT.VehicleExporter.collectSpeedLimit({}))
  end)
end)

describe("VehicleExporter.collectOdometer", function()
  withMathUtil()

  it("reports Drivable's km counter", function()
    local odometer = VDT.VehicleExporter.collectOdometer({ spec_drivable = { odometerMilage = 1234.567 } })
    assert.are.same({ value = 1234.6, unit = "km" }, odometer)
  end)

  it("is absent on anything that cannot be driven", function()
    assert.is_nil(VDT.VehicleExporter.collectOdometer({}))
  end)
end)

describe("Hitch.collect", function()
  ---@param jointDesc table the parent's joint
  ---@param objectLowers boolean whether the implement's own attacher joint allows lowering
  local function rig(jointDesc, objectLowers)
    local parent = { spec_attacherJoints = { attacherJoints = { jointDesc } } }
    local implement = {
      jointDescIndex = 1,
      object = { spec_attachable = { attacherJoint = { allowsLowering = objectLowers } } },
    }
    return parent, implement
  end

  it("reads the arm's place in its travel the dashboard's way round, 100 at the top", function()
    local parent, implement = rig({ allowsLowering = true, moveAlpha = 0.25, upperAlpha = 0.1, lowerAlpha = 0.9 }, true)
    local hitch = VDT.Hitch.collect(parent, implement)
    assert.are.equal(75, hitch.position)
    assert.are.equal(90, hitch.max)
    assert.are.equal(10, hitch.min)
  end)

  it("reports nothing where the parent's joint does not lower", function()
    local parent, implement = rig({ allowsLowering = false, moveAlpha = 0 }, true)
    assert.is_nil(VDT.Hitch.collect(parent, implement))
  end)

  it("reports nothing where the implement's own joint does not lower -- the engine pins the arm then", function()
    local parent, implement = rig({ allowsLowering = true, moveAlpha = 0 }, false)
    assert.is_nil(VDT.Hitch.collect(parent, implement))
  end)

  it("reports nothing before the engine has set a position", function()
    local parent, implement = rig({ allowsLowering = true }, true)
    assert.is_nil(VDT.Hitch.collect(parent, implement))
  end)

  it("reports nothing for a parent without attacher joints", function()
    local _, implement = rig({ allowsLowering = true, moveAlpha = 0 }, true)
    assert.is_nil(VDT.Hitch.collect({}, implement))
  end)
end)

describe("TensionBelts.collect", function()
  local function belts(...)
    local sorted = {}
    for i, fastened in ipairs({ ... }) do
      sorted[i] = { id = i, mesh = fastened and 4711 or nil }
    end
    return { spec_tensionBelts = { hasTensionBelts = true, sortedBelts = sorted } }
  end

  it("counts a belt with a mesh as fastened, which is the engine's own test", function()
    assert.are.same({ fastened = 2, count = 3 }, VDT.TensionBelts.collect(belts(true, false, true)))
  end)

  it("reports every belt open as zero of n", function()
    assert.are.same({ fastened = 0, count = 2 }, VDT.TensionBelts.collect(belts(false, false)))
  end)

  it("is absent on a machine whose configuration has no belts", function()
    assert.is_nil(VDT.TensionBelts.collect({ spec_tensionBelts = { hasTensionBelts = false } }))
    assert.is_nil(VDT.TensionBelts.collect({}))
  end)
end)

describe("Broken.collect", function()
  it("is true for a drowned machine", function()
    assert.is_true(VDT.Broken.collect({ isBroken = true }))
  end)

  it("is absent, not false, on a working one", function()
    assert.is_nil(VDT.Broken.collect({ isBroken = false }))
    assert.is_nil(VDT.Broken.collect({}))
  end)
end)
