-- Aspect collectors: how a tool is currently configured. Applies to any object (vehicle or
-- implement). Two related-but-separate collectors in one file, following collect/vehicle/
-- SupportSystems.lua. Namespaced under VDT.* (see TurnOn.lua).
--
--   * work mode  -- the discrete mode a tool is switched to (a mower's transport/work modes, a
--                   cultivator's depth settings). Modes are named in the vehicle XML.
--   * work width -- the live width of a tool with foldable/retractable sections, which changes as
--                   sections are switched off, so it is not a static spec value.

VDT = VDT or {}
VDT.Work = {}

---@param object table
---@return WorkModeModel|nil nil when the object has no selectable work modes
function VDT.Work.collectMode(object)
  local spec = object.spec_workMode
  -- stateMax is 0 when the XML declared no modes, i.e. the spec is present but inert.
  if spec == nil or spec.stateMax == nil or spec.stateMax <= 0 then
    return nil
  end

  local model = { current = spec.state, count = spec.stateMax }
  local mode = spec.workModes ~= nil and spec.workModes[spec.state] or nil
  if mode ~= nil then
    -- Resolved from the XML at load; nil when the mode was declared without a name.
    model.name = mode.name
  end
  return model
end

---@param object table
---@return WorkWidthModel|nil nil when the object has no variable-width sections
function VDT.Work.collectWidth(object)
  local spec = object.spec_variableWorkWidth
  if spec == nil or not spec.hasSections then
    return nil
  end

  -- Each side reports (currentWidth, maxWidth); the engine walks its own section list, which is
  -- short. Sides are independent — half-width work on one side is a normal headland technique.
  local left, leftMax = object:getVariableWorkWidth(true)
  local right, rightMax = object:getVariableWorkWidth(false)

  return {
    left = tonumber(ValueMapper.mapFloat(left)),
    leftMax = tonumber(ValueMapper.mapFloat(leftMax)),
    right = tonumber(ValueMapper.mapFloat(right)),
    rightMax = tonumber(ValueMapper.mapFloat(rightMax)),
    total = tonumber(ValueMapper.mapFloat(left + right)),
    unit = "m",
  }
end
