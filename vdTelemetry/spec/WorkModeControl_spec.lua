-- Unit tests for src/command/WorkModeControl.lua (the work mode, app -> mod).
--
-- Run with `busted` from the vdTelemetry/ directory. The control resolves its object through
-- TargetResolver and calls the engine setter directly; target "vehicle" resolves to the stub itself,
-- so nothing else needs stubbing.
--
-- What is worth testing: the command is dropped, not merely ineffective, whenever the game's own key
-- would be switched off -- the setter itself does not ask.

if VDT == nil or VDT.CommandRegistry == nil then
  dofile("src/command/CommandRegistry.lua")
end
if VDT.Work == nil then
  dofile("src/collect/aspects/Work.lua")
end
if VDT.TargetResolver == nil then
  dofile("src/command/TargetResolver.lua")
end
dofile("src/command/WorkModeControl.lua")

local debugger = { debug = function() end, warn = function() end }

---A tool with `count` work modes, currently in `state`.
local function tool(state, count, allowed, powered)
  return {
    spec_workMode = { state = state, stateMax = count },
    calls = {},
    getIsWorkModeChangeAllowed = function()
      return allowed ~= false
    end,
    getIsPowered = function()
      if powered == false then
        return false, "Start the motor"
      end
      return true
    end,
    setWorkMode = function(self, mode)
      self.calls[#self.calls + 1] = mode
      self.spec_workMode.state = mode
    end,
  }
end

describe("WorkModeControl.setWorkMode", function()
  it("switches to the mode it is given", function()
    local t = tool(1, 4)
    VDT.WorkModeControl.setWorkMode(t, "vehicle", 3, debugger)
    assert.are.same({ 3 }, t.calls)
  end)

  it("takes an absolute mode, so a doubled command lands once", function()
    local t = tool(1, 4)
    VDT.WorkModeControl.setWorkMode(t, "vehicle", 2, debugger)
    VDT.WorkModeControl.setWorkMode(t, "vehicle", 2, debugger)
    assert.are.same({ 2 }, t.calls)
  end)

  it("drops the command when the engine would refuse the change", function()
    local t = tool(1, 4, false)
    VDT.WorkModeControl.setWorkMode(t, "vehicle", 2, debugger)
    assert.are.same({}, t.calls)
  end)

  it("drops the command with the motor off, as the game's powered key does", function()
    -- A Krone BiG M switched modes from the terminal with its engine off, where the key blinks
    -- "start the motor": the setter never asks getIsPowered.
    local t = tool(1, 4, true, false)
    VDT.WorkModeControl.setWorkMode(t, "vehicle", 2, debugger)
    assert.are.same({}, t.calls)
  end)

  it("drops a mode the machine does not declare", function()
    local t = tool(1, 4)
    VDT.WorkModeControl.setWorkMode(t, "vehicle", 5, debugger)
    VDT.WorkModeControl.setWorkMode(t, "vehicle", 0, debugger)
    VDT.WorkModeControl.setWorkMode(t, "vehicle", nil, debugger)
    assert.are.same({}, t.calls)
  end)

  it("does not crash on a machine without work modes", function()
    assert.has_no.errors(function()
      VDT.WorkModeControl.setWorkMode({}, "vehicle", 1, debugger)
      VDT.WorkModeControl.setWorkMode({ spec_workMode = { state = 1, stateMax = 0 } }, "vehicle", 1, debugger)
    end)
  end)

  it("registers a setWorkMode handler that parses target and mode", function()
    local handler = VDT.CommandRegistry.get("setWorkMode")
    assert.is_not_nil(handler)

    local xml = {
      getString = function(_, key)
        assert.are.equal("cmd#target", key)
        return "selected"
      end,
      getInt = function(_, key)
        assert.are.equal("cmd#mode", key)
        return 2
      end,
    }
    local params = handler.parse(xml, "cmd")
    assert.are.equal("selected", params.target)
    assert.are.equal(2, params.mode)
  end)
end)
