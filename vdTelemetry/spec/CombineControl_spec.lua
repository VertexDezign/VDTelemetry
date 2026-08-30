-- Unit tests for src/command/CombineControl.lua (the straw toggle, app -> mod).
--
-- Run with `busted` from the vdTelemetry/ directory. The control resolves its object through
-- TargetResolver and then calls the engine setter directly, so the environment here is that resolver
-- plus the aspect it asks for the verdict -- and the verdict is the whole point of the file, so it is
-- the real VDT.Harvest rather than a stub of it.
--
-- What is worth testing: the command is dropped, not merely ineffective, on every machine and every
-- crop the game's own key would refuse. A resent command landing on a machine whose tank has changed
-- since the export is the ordinary case, not an exotic one.

if VDT == nil or VDT.CommandRegistry == nil then
  dofile("src/command/CommandRegistry.lua")
end
if ValueMapper == nil then
  dofile("src/mapper/ValueMapper.lua")
end
if VDT.TargetResolver == nil then
  dofile("src/command/TargetResolver.lua")
end
if VDT.Harvest == nil then
  dofile("src/collect/aspects/Harvest.lua")
end
dofile("src/command/CombineControl.lua")

local debugger = { debug = function() end, warn = function() end }

-- Only the two lookups `canToggleSwath` makes. Wheat drops a windrow, maize does not.
local FRUIT_TYPES = { [4] = { hasWindrow = true }, [9] = { hasWindrow = false } }
local FRUIT_BY_FILL = { [4] = 4, [9] = 9 }

local function stubManagers()
  _G.g_fruitTypeManager = {
    getFruitTypeByIndex = function(_, index)
      return FRUIT_TYPES[index]
    end,
    getFruitTypeIndexByFillTypeIndex = function(_, fillTypeIndex)
      return FRUIT_BY_FILL[fillTypeIndex]
    end,
  }
end

---A self-propelled combine, which is what `target = "vehicle"` resolves to.
---@param over table|nil replaces any spec_combine field
local function combine(over)
  local spec = {
    isSwathActive = false,
    fillUnitIndex = 1,
    swath = { isAvailable = true },
    chopper = { isAvailable = true },
  }
  for k, v in pairs(over or {}) do
    spec[k] = v
  end
  return {
    spec_combine = spec,
    calls = {},
    getFillUnitFillType = function()
      return spec.tankFillType or 1
    end,
    setIsSwathActive = function(self, on)
      self.calls[#self.calls + 1] = on
    end,
  }
end

describe("CombineControl.setSwath", function()
  before_each(stubManagers)

  it("sets the swath on the vehicle", function()
    local v = combine()
    VDT.CombineControl.setSwath(v, "vehicle", true, debugger)
    assert.are.same({ true }, v.calls)
  end)

  it("takes an absolute state rather than toggling", function()
    -- The property the lossy command channel needs: a doubled command lands on the same state, where
    -- the game's own key would have flipped it back.
    local v = combine()
    VDT.CombineControl.setSwath(v, "vehicle", false, debugger)
    VDT.CombineControl.setSwath(v, "vehicle", false, debugger)
    assert.are.same({ false, false }, v.calls)
  end)

  it("drops the command on a crop that drops no windrow", function()
    -- Maize. The engine would show a blinking warning and change nothing; refusing here keeps the
    -- mod and the game agreeing about what a command does.
    local v = combine({ tankFillType = 9 })
    VDT.CombineControl.setSwath(v, "vehicle", true, debugger)
    assert.are.same({}, v.calls)
  end)

  it("allows the command while the tank holds a crop that does", function()
    local v = combine({ tankFillType = 4 })
    VDT.CombineControl.setSwath(v, "vehicle", true, debugger)
    assert.are.same({ true }, v.calls)
  end)

  it("drops the command on a machine missing either half of the choice", function()
    local noChopper = combine()
    noChopper.spec_combine.chopper = nil
    VDT.CombineControl.setSwath(noChopper, "vehicle", true, debugger)
    assert.are.same({}, noChopper.calls)

    local noSwath = combine({ swath = { isAvailable = false } })
    VDT.CombineControl.setSwath(noSwath, "vehicle", true, debugger)
    assert.are.same({}, noSwath.calls)
  end)

  it("does not crash on a machine that is not a combine", function()
    assert.has_no.errors(function()
      VDT.CombineControl.setSwath({}, "vehicle", true, debugger)
    end)
  end)

  it("registers a setSwath handler that parses target and state", function()
    local handler = VDT.CommandRegistry.get("setSwath")
    assert.is_not_nil(handler)

    local xml = {
      getString = function(_, key)
        assert.are.equal("cmd#target", key)
        return "vehicle"
      end,
      getBool = function(_, key)
        assert.are.equal("cmd#on", key)
        return true
      end,
    }
    local params = handler.parse(xml, "cmd")
    assert.are.equal("vehicle", params.target)
    assert.is_true(params.on)

    local v = combine()
    handler.execute(v, params, debugger)
    assert.are.same({ true }, v.calls)
  end)
end)
