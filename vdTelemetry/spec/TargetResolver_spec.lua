-- Unit tests for src/command/TargetResolver.lua (the `target` token -> object walk).
--
-- Run with `busted` from the vdTelemetry/ directory. The resolver only ever calls two functions on
-- the vehicle -- `vdAIGetAttacherJointPosition` (FS25_additionalInputs) and `getSelectedVehicle`
-- (the engine) -- so the stubs below are the whole environment; no engine globals are needed.

if VDT == nil or VDT.TargetResolver == nil then
  dofile("src/command/TargetResolver.lua")
end

local debugger = { debug = function() end, warn = function() end }

---A machine with attacher joints, whose implements are labelled by the position the stubbed
---vdAIGetAttacherJointPosition reports for them -- the same call VehicleExporter labels them with.
---@param implements table[] { position = "FRONT"|"BACK", object = table }
local function fakeVehicle(implements)
  local attached = {}
  for i, entry in ipairs(implements) do
    attached[i] = { object = entry.object, position = entry.position }
  end
  return {
    spec_attacherJoints = { attachedImplements = attached },
    vdAIGetAttacherJointPosition = function(_, attachedImplement)
      return attachedImplement.position
    end,
  }
end

describe("TargetResolver.resolve", function()
  it("resolves 'vehicle' to the controlled vehicle itself", function()
    local v = fakeVehicle({})
    assert.are.equal(v, VDT.TargetResolver.resolve(v, "vehicle", debugger))
  end)

  it("resolves 'front' and 'back' by the position additionalInputs reports", function()
    local plough, weight = {}, {}
    local v = fakeVehicle({
      { position = "BACK", object = plough },
      { position = "FRONT", object = weight },
    })
    assert.are.equal(plough, VDT.TargetResolver.resolve(v, "back", debugger))
    assert.are.equal(weight, VDT.TargetResolver.resolve(v, "front", debugger))
  end)

  it("resolves nothing when the named attacher is empty", function()
    local v = fakeVehicle({ { position = "BACK", object = {} } })
    assert.is_nil(VDT.TargetResolver.resolve(v, "front", debugger))
  end)

  it("resolves 'selected' through getSelectedVehicle, at any depth", function()
    -- The whole point of the token (issue #120): the dribble bar is hitched behind the barrel, so it
    -- sits on no attacher of the tractor's and no positional token can name it.
    local dribbleBar = {}
    local v = fakeVehicle({ { position = "BACK", object = {} } })
    v.getSelectedVehicle = function()
      return dribbleBar
    end
    assert.are.equal(dribbleBar, VDT.TargetResolver.resolve(v, "selected", debugger))
  end)

  it("resolves 'selected' to nothing when nothing is selected", function()
    -- Normal, not an error: a rig where nothing can be selected has nothing selected.
    local v = fakeVehicle({})
    v.getSelectedVehicle = function()
      return nil
    end
    assert.is_nil(VDT.TargetResolver.resolve(v, "selected", debugger))
  end)

  it("does not fall back to the vehicle when the selection cannot be read", function()
    -- The failure that would be silent: resolving to the tractor would tip, uncover or unload the
    -- wrong machine rather than doing nothing.
    local v = fakeVehicle({}) -- no getSelectedVehicle at all
    assert.is_nil(VDT.TargetResolver.resolve(v, "selected", debugger))
  end)

  it("resolves nothing for an unknown token or a missing vehicle", function()
    assert.is_nil(VDT.TargetResolver.resolve(fakeVehicle({}), "sideways", debugger))
    assert.is_nil(VDT.TargetResolver.resolve(nil, "vehicle", debugger))
  end)
end)
