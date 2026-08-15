-- Unit tests for the fill-unit aspect collector (src/collect/aspects/FillUnit.lua).
--
-- Run with `busted` from the vdTelemetry/ directory. The collector needs Set, ValueMapper and the
-- g_fillTypeManager global; the first two are loaded for real, the manager is stubbed from a table.
--
-- The interesting behaviour under test is the *display* values. Several game specs publish a
-- corrected figure on the fill unit rather than mutating the raw one — most importantly Consumable
-- (bale net/twine/wrap), which measures the unit in slots and folds "spare rolls + the part-used one
-- on the machine" into fillLevelToDisplay. Reading the raw fillLevel under-reports those.

if Set == nil then
  dofile("src/utils/Set.lua")
end
if ValueMapper == nil then
  dofile("src/mapper/ValueMapper.lua")
end
if VDT == nil or VDT.FillUnit == nil then
  dofile("src/collect/aspects/FillUnit.lua")
end

-- Fill-type table keyed by index; index 1 is the engine's FillType.UNKNOWN.
local FILL_TYPES = {
  [1] = { name = "UNKNOWN", title = "Unknown", unitShort = "l" },
  [4] = { name = "WHEAT", title = "Weizen", unitShort = "l" },
  [7] = { name = "STRAW", title = "Stroh", unitShort = "l" },
  [9] = { name = "AIR", title = "Luft", unitShort = "l" },
  [21] = { name = "BALE_NET", title = "Netz", unitShort = "" },
}

-- A fill unit with sane engine defaults; `over` replaces any of them.
local function unit(over)
  local u = {
    fillType = 4,
    fillLevel = 0,
    capacity = 1000,
    showOnInfoHud = true,
    uiPrecision = 0,
  }
  for k, v in pairs(over or {}) do
    u[k] = v
  end
  return u
end

local function object(units, motorized)
  return { spec_fillUnit = { fillUnits = units }, spec_motorized = motorized }
end

local function collectOne(u)
  local result = VDT.FillUnit.collect(object({ u }))
  assert.is_not_nil(result)
  return result.fillUnit[1]
end

describe("FillUnit.collect", function()
  before_each(function()
    _G.g_fillTypeManager = {
      getFillTypeByIndex = function(_, idx)
        return FILL_TYPES[idx] or FILL_TYPES[1]
      end,
    }
  end)

  after_each(function()
    _G.g_fillTypeManager = nil
  end)

  it("returns nil when the object has no fill unit spec or no units", function()
    assert.is_nil(VDT.FillUnit.collect({}))
    assert.is_nil(VDT.FillUnit.collect(object({})))
  end)

  it("reports the raw level when the engine sets no display override", function()
    local fu = collectOne(unit({ fillLevel = 250, capacity = 1000 }))
    assert.are.equal(250, fu.value)
    assert.are.equal(1000, fu.capacity)
    assert.are.equal(25, fu.fillLevelPercentage)
    assert.are.equal("WHEAT", fu.type)
  end)

  it("prefers fillLevelToDisplay over the raw level", function()
    -- The Consumable case: capacity is 4 slots, the raw level counts only the 2 spare rolls, and the
    -- roll on the machine is 40% used -> the engine publishes 2.4.
    local fu = collectOne(unit({
      fillType = 21,
      fillLevel = 2,
      fillLevelToDisplay = 2.4,
      capacity = 4,
    }))
    assert.are.equal(2.4, fu.value)
    assert.are.equal(60, fu.fillLevelPercentage)
  end)

  it("keeps the fractional part rather than flooring it", function()
    -- Flooring here is exactly what used to hide the part-used roll.
    local fu = collectOne(unit({ fillLevel = 1.5, capacity = 2 }))
    assert.are.equal(1.5, fu.value)
  end)

  it("rounds the level to 3 decimals so the JSON stays stable", function()
    local fu = collectOne(unit({ fillLevel = 1.0 / 3.0, capacity = 1 }))
    assert.are.equal(0.333, fu.value)
  end)

  it("prefers capacityToDisplay over the raw capacity", function()
    local fu = collectOne(unit({ fillLevel = 2, capacity = 999, capacityToDisplay = 4 }))
    assert.are.equal(4, fu.capacity)
    assert.are.equal(50, fu.fillLevelPercentage)
  end)

  it("prefers fillTypeToDisplay unless it is UNKNOWN", function()
    local shown = collectOne(unit({ fillType = 4, fillTypeToDisplay = 7, fillLevel = 10 }))
    assert.are.equal("STRAW", shown.type)
    assert.are.equal("Stroh", shown.title)

    -- UNKNOWN (index 1) is the engine's "unset", not a request to display the unknown type.
    local unset = collectOne(unit({ fillType = 4, fillTypeToDisplay = 1, fillLevel = 10 }))
    assert.are.equal("WHEAT", unset.type)
  end)

  it("blanks the descriptive fields for the UNKNOWN fill type", function()
    local fu = collectOne(unit({ fillType = 1, fillLevel = 5 }))
    assert.are.equal("", fu.type)
    assert.are.equal("", fu.title)
    assert.are.equal("", fu.unit)
  end)

  it("emits precision and display only when they differ from the engine defaults", function()
    local plain = collectOne(unit({ fillLevel = 1 }))
    assert.is_nil(plain.precision)
    assert.is_nil(plain.display)

    local stepped = collectOne(unit({ fillLevel = 1, uiPrecision = 2, uiDisplayTypeId = 2 }))
    assert.are.equal(2, stepped.precision)
    assert.are.equal("STEP", stepped.display)

    -- BAR is the default and is left to the Kotlin model.
    local bar = collectOne(unit({ fillLevel = 1, uiDisplayTypeId = 1 }))
    assert.is_nil(bar.display)
  end)

  it("skips AIR, propellant and info-hud-hidden units", function()
    local result = VDT.FillUnit.collect(object({
      unit({ fillType = 4, fillLevel = 10 }), -- kept
      unit({ fillType = 9, fillLevel = 10 }), -- AIR
      unit({ fillType = 7, fillLevel = 10, showOnInfoHud = false }), -- hidden
      unit({ fillType = 7, fillLevel = 10 }), -- propellant (index 4)
    }, { propellantFillUnitIndices = { 4 } }))
    assert.are.equal(1, #result.fillUnit)
    assert.are.equal("WHEAT", result.fillUnit[1].type)
  end)

  it("returns nil when every unit was skipped", function()
    assert.is_nil(VDT.FillUnit.collect(object({ unit({ fillType = 9 }) })))
  end)

  it("normalizes a non-finite capacity to 0 without dividing by it", function()
    local fu = collectOne(unit({ fillLevel = 10, capacity = math.huge }))
    assert.are.equal(0, fu.capacity)
    assert.are.equal(0, fu.fillLevelPercentage)
  end)
end)
