-- Aspect collector: fill units (the repeated <fillUnit> form, used by vehicle and implements).
-- Distinct from the motor's fixed fuel/def/air fill units (see collect/vehicle/Motor.lua).
-- Namespaced under VDT.* (see TurnOn.lua).
--
-- Skips a vehicle's propellant (fuel) fill units — those belong to the motor block — AIR, and any
-- unit the game hides from the info HUD (showOnInfoHud="false", e.g. a harvester's pass-through
-- output). fillTypeIndex 1 is the "unknown" default fill type: its name/title/unit are blanked to "".
--
-- Level/capacity/type come from the unit's *display* values where the engine provides them, which is
-- what the game's own fill-level HUD reads (FillUnit:getFillLevelInformation). Several specs publish
-- a corrected figure there rather than mutating the raw fill unit:
--   * Consumable (bale net/twine/wrap) measures the unit in SLOTS, and the raw fillLevel counts only
--     the spare rolls in storage. The partially-used roll on the machine lives in consumingFillLevel,
--     and the spec folds the two together into fillLevelToDisplay. Reading the raw level therefore
--     under-reports by up to a whole roll.
--   * Combine folds its buffer unit into the main tank (parentUnitOnHud).
-- Hence the `...ToDisplay or ...` pairs below; the overrides are nil unless a spec sets them.

VDT = VDT or {}
VDT.FillUnit = {}

-- FillType.UNKNOWN — the engine's "no fill type" default index.
local FILL_TYPE_UNKNOWN = 1

-- FillLevelsDisplay.TYPE_BAR / TYPE_STEP. The engine resolves the XML's #uiDisplayType to this id at
-- load, so map the id rather than re-reading the string. STEP means the game draws one segment per
-- unit of capacity and fills the remainder of the current segment fractionally — how consumables are
-- shown. BAR is the default and is left out of the JSON entirely (Model.kt supplies it).
local DISPLAY_TYPES = { [1] = "BAR", [2] = "STEP" }

-- Round without the engine's MathUtil, so this file stays loadable offline for the specs. Fill levels
-- are non-negative in practice; the sign branch is there so the helper isn't quietly wrong if that
-- ever stops holding.
---@param value number
---@param decimals number
---@return number
local function round(value, decimals)
  local mult = 10 ^ decimals
  if value < 0 then
    return -math.floor(-value * mult + 0.5) / mult
  end
  return math.floor(value * mult + 0.5) / mult
end

---@param object table
---@return FillUnitsModel|nil nil when the object has no reportable fill units
function VDT.FillUnit.collect(object)
  local spec = object.spec_fillUnit
  if spec == nil or #spec.fillUnits <= 0 then
    return nil
  end

  local mSpec = object.spec_motorized
  ---@type Set
  local propellantFillUnitIndices
  if mSpec ~= nil then
    propellantFillUnitIndices = Set:new(mSpec.propellantFillUnitIndices)
  else
    propellantFillUnitIndices = Set:new()
  end

  local fillUnitList = {}
  for fillUnitIndex, fillUnit in ipairs(spec.fillUnits) do
    -- A spec may want a different type shown than the one physically loaded (fillTypeToDisplay is
    -- FillType.UNKNOWN when unset, not nil).
    local fillTypeIndex = fillUnit.fillType
    if fillUnit.fillTypeToDisplay ~= nil and fillUnit.fillTypeToDisplay ~= FILL_TYPE_UNKNOWN then
      fillTypeIndex = fillUnit.fillTypeToDisplay
    end
    local fillType = g_fillTypeManager:getFillTypeByIndex(fillTypeIndex)
    -- Skip units the game itself hides from the vehicle info box (showOnInfoHud="false" in the XML,
    -- e.g. a forage/carrot harvester's pass-through output). The engine defaults the flag to true, so
    -- only an explicit false hides it; a nil (unit created outside XML load) is treated as shown.
    local hiddenFromInfoHud = fillUnit.showOnInfoHud == false
    if not (propellantFillUnitIndices:contains(fillUnitIndex) or fillType.name == "AIR" or hiddenFromInfoHud) then
      local capacity = fillUnit.capacityToDisplay or fillUnit.capacity
      -- Pass-through units (e.g. a forage/carrot harvester's output) carry no capacity in their XML,
      -- so the engine reports capacity = math.huge. The JSON encoder turns +inf into null, which the
      -- typed model (capacity: Int) rejects -> normalize any non-finite capacity to 0 (treated like
      -- the zero-capacity mods handled below).
      if capacity ~= capacity or capacity == math.huge or capacity == -math.huge then
        capacity = 0
      end
      local fillLevel = fillUnit.fillLevelToDisplay or fillUnit.fillLevel
      local unit = fillType.unitShort
      local name = fillType.name
      local title = fillType.title
      if fillTypeIndex == FILL_TYPE_UNKNOWN then
        unit = ""
        name = ""
        title = ""
      end
      local fillPercentage = 0
      -- some mods have a capacity of zero; avoid dividing by zero
      if capacity > 0 then
        fillPercentage = fillLevel / capacity
      end

      -- Both of these are display hints the app may ignore; leave them out at their engine defaults
      -- so the common case adds no JSON. uiPrecision is how many decimals the game prints — it is NOT
      -- a rounding instruction for `value`, whose fractional part carries the consumable's part-used
      -- roll even when precision is 0.
      local display = DISPLAY_TYPES[fillUnit.uiDisplayTypeId]
      if display == "BAR" then
        display = nil
      end
      local precision = fillUnit.uiPrecision
      if precision == 0 then
        precision = nil
      end

      table.insert(fillUnitList, {
        -- Fractional: a consumable unit sitting on one spare roll plus a half-used one reads 1.5.
        value = round(fillLevel, 3),
        type = name,
        title = title,
        unit = unit,
        capacity = math.floor(capacity),
        fillLevelPercentage = tonumber(ValueMapper.mapPercentage(fillPercentage, 0)),
        precision = precision,
        display = display,
      })
    end
  end

  if #fillUnitList == 0 then
    return nil
  end
  return { fillUnit = fillUnitList }
end
