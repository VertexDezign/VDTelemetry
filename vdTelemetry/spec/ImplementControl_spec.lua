-- Unit tests for src/command/ImplementControl.lua (lower/fold/activate dispatch).
--
-- Run with `busted` from the vdTelemetry/ directory. Every target routes through an
-- FS25_additionalInputs function on the vehicle (vdAI<Action><Position>), so the vehicle stub just
-- records which function was called with which state. No engine globals are needed. The control
-- self-registers into VDT.CommandRegistry at load, so we load CommandRegistry first -- but only if it
-- isn't already loaded, so we don't reset the registry another spec populated.

if VDT == nil or VDT.CommandRegistry == nil then
  dofile("src/command/CommandRegistry.lua")
end
dofile("src/command/ImplementControl.lua")

local debugger = { debug = function() end, warn = function() end }

-- A vehicle stub exposing the given vdAI* function names; each records { name, on } when called.
local function fakeVehicle(funcNames)
  local v = { calls = {} }
  for _, name in ipairs(funcNames) do
    v[name] = function(self, on)
      self.calls[#self.calls + 1] = { name, on }
    end
  end
  return v
end

describe("ImplementControl dispatch", function()
  it("routes front lower to vdAILowerFront", function()
    local v = fakeVehicle({ "vdAILowerFront" })
    VDT.ImplementControl.setLowered(v, "front", true, debugger)
    assert.are.same({ { "vdAILowerFront", true } }, v.calls)
  end)

  it("routes back fold to vdAIFoldBack", function()
    local v = fakeVehicle({ "vdAIFoldBack" })
    VDT.ImplementControl.setFolded(v, "back", false, debugger)
    assert.are.same({ { "vdAIFoldBack", false } }, v.calls)
  end)

  it("routes vehicle activate to vdAIActivateVehicle", function()
    local v = fakeVehicle({ "vdAIActivateVehicle" })
    VDT.ImplementControl.setActivated(v, "vehicle", true, debugger)
    assert.are.same({ { "vdAIActivateVehicle", true } }, v.calls)
  end)

  it("does not crash when the additionalInputs function is missing", function()
    local v = fakeVehicle({}) -- no vdAI* functions present
    VDT.ImplementControl.setLowered(v, "front", true, debugger)
    assert.are.same({}, v.calls)
  end)

  it("ignores unknown targets", function()
    local v = fakeVehicle({ "vdAILowerFront", "vdAILowerBack", "vdAILowerVehicle" })
    VDT.ImplementControl.setLowered(v, "sideways", true, debugger)
    assert.are.same({}, v.calls)
  end)
end)
