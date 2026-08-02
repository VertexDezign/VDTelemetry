-- Unit tests for the sowing aspect collector (src/collect/aspects/Sowing.lua).
--
-- Run with `busted` from the vdTelemetry/ directory. The collector is a field read of its spec table
-- plus one crop lookup, so the objects below are plain tables shaped like the engine's and
-- g_fruitTypeManager is stubbed from a table (as g_fillTypeManager is in FillUnit_spec).
--
-- The behaviour worth pinning down is the indirection: `spec.seeds` holds fruit type indices and
-- `spec.currentSeed` indexes *that list*, not the fruit type table — so a machine on its second seed
-- reports the second entry of its own list, whatever index that happens to be.

if ValueMapper == nil then
  dofile("src/mapper/ValueMapper.lua")
end
if VDT == nil or VDT.Sowing == nil then
  dofile("src/collect/aspects/Sowing.lua")
end

-- Fruit type index -> the fill type its crop is carried as.
local FILL_TYPES = {
  [4] = { name = "WHEAT", title = "Weizen" },
  [6] = { name = "BARLEY", title = "Gerste" },
  [12] = { name = "CANOLA", title = "Raps" },
}
local FRUIT_NAMES = { [4] = "WHEAT", [6] = "BARLEY", [12] = "CANOLA" }

-- A sowing machine with sane engine defaults; `over` replaces any of them.
local function sower(over)
  local spec = {
    seeds = { 4, 6, 12 },
    currentSeed = 1,
    allowsSeedChanging = true,
    useDirectPlanting = false,
    seedUsageScale = 1,
  }
  for k, v in pairs(over or {}) do
    spec[k] = v
  end
  return { spec_sowingMachine = spec }
end

describe("Sowing.collect", function()
  before_each(function()
    -- Only the usage scale formats through ValueMapper.mapFloat, which needs the engine's MathUtil
    -- (stubbed the same way WorkAspects_spec does).
    rawset(_G, "MathUtil", {
      round = function(v, decimals)
        local mult = 10 ^ (decimals or 0)
        return math.floor(v * mult + 0.5) / mult
      end,
    })
    _G.g_fruitTypeManager = {
      getFruitTypeNameByIndex = function(_, index)
        return FRUIT_NAMES[index]
      end,
      getFillTypeByFruitTypeIndex = function(_, index)
        return FILL_TYPES[index]
      end,
    }
  end)

  after_each(function()
    _G.g_fruitTypeManager = nil
    rawset(_G, "MathUtil", nil)
  end)

  it("returns nil when the object does not sow", function()
    assert.is_nil(VDT.Sowing.collect({}))
  end)

  it("resolves the selected crop through the machine's own seed list", function()
    local s = VDT.Sowing.collect(sower({ currentSeed = 2 }))
    assert.are.equal("BARLEY", s.fruitType)
    assert.are.equal("BARLEY", s.fillType)
    assert.are.equal("Gerste", s.title)
    assert.are.equal(2, s.seedIndex)
    assert.are.equal(3, s.seedCount)
  end)

  it("reports the hopper configuration", function()
    local s = VDT.Sowing.collect(sower({ useDirectPlanting = true, allowsSeedChanging = false }))
    assert.is_true(s.directPlanting)
    assert.is_false(s.changeAllowed)
  end)

  it("leaves the usage scale out at the engine default", function()
    assert.is_nil(VDT.Sowing.collect(sower()).usageScale)
    assert.are.equal(1.5, VDT.Sowing.collect(sower({ seedUsageScale = 1.5 })).usageScale)
  end)

  it("reports no crop when the machine declares no seeds", function()
    local s = VDT.Sowing.collect(sower({ seeds = {} }))
    assert.are.equal(0, s.seedCount)
    assert.is_nil(s.fruitType)
    assert.is_nil(s.fillType)
    assert.is_nil(s.title)
  end)

  it("survives a crop the managers do not know", function()
    local s = VDT.Sowing.collect(sower({ seeds = { 99 }, currentSeed = 1 }))
    assert.is_nil(s.fruitType)
    assert.is_nil(s.fillType)
    -- The rest of the aspect is still reported: an unresolvable crop is a missing name, not a
    -- missing hopper.
    assert.are.equal(1, s.seedCount)
    assert.is_true(s.changeAllowed)
  end)
end)
