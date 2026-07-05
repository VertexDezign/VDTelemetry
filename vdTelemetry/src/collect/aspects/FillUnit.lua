-- Aspect collector: fill units (the repeated <fillUnit> form, used by vehicle and implements).
-- Distinct from the motor's fixed fuel/def/air fill units (see collect/vehicle/Motor.lua).
-- Namespaced under VDT.* (see TurnOn.lua).
--
-- Skips a vehicle's propellant (fuel) fill units — those belong to the motor block — and AIR.
-- fillTypeIndex 1 is the "unknown" default fill type: its name/title/unit are blanked to "".

VDT = VDT or {}
VDT.FillUnit = {}

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
    local fillTypeIndex = fillUnit.fillType
    local fillType = g_fillTypeManager:getFillTypeByIndex(fillTypeIndex)
    if not (propellantFillUnitIndices:contains(fillUnitIndex) or fillType.name == "AIR") then
      local capacity = fillUnit.capacity
      local fillLevel = fillUnit.fillLevel
      local unit = fillType.unitShort
      local name = fillType.name
      local title = fillType.title
      if fillTypeIndex == 1 then
        unit = ""
        name = ""
        title = ""
      end
      local fillPercentage = 0
      -- some mods have a capacity of zero; avoid dividing by zero
      if capacity > 0 then
        fillPercentage = fillLevel / capacity
      end
      table.insert(fillUnitList, {
        value = math.floor(fillLevel),
        type = name,
        title = title,
        unit = unit,
        capacity = math.floor(capacity),
        fillLevelPercentage = tonumber(ValueMapper.mapPercentage(fillPercentage, 0)),
      })
    end
  end

  if #fillUnitList == 0 then
    return nil
  end
  return { fillUnit = fillUnitList }
end
