-- Aspect collectors: how a tool is currently configured. Applies to any object (vehicle or
-- implement). Two related-but-separate collectors in one file, following collect/vehicle/
-- SupportSystems.lua. Namespaced under VDT.* (see TurnOn.lua).
--
--   * work mode  -- the discrete mode a tool is switched to (a mower's transport/work modes, a
--                   cultivator's depth settings). Modes are named in the vehicle XML.
--   * work width -- the live width of a tool with foldable/retractable sections, which changes as
--                   sections are switched off, so it is not a static spec value. It carries the
--                   individual sections too: the on/off shutoff bar, which is the base game's only
--                   answer to "section control" (see issue #43).

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

---Which side of the boom a section sits on. `isCenter` wins: a center section is in neither of the
---engine's two side lists (VariableWorkWidth.lua:111-117), so it is never switched off and the game's
---own HUD brackets it with separators instead.
---@param section table an entry of spec_variableWorkWidth.sections
---@return string LEFT | CENTER | RIGHT
local function sideOf(section)
  if section.isCenter then
    return "CENTER"
  end
  return section.isLeft and "LEFT" or "RIGHT"
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

  ---@type WorkWidthModel
  local model = {
    left = tonumber(ValueMapper.mapFloat(left)),
    leftMax = tonumber(ValueMapper.mapFloat(leftMax)),
    right = tonumber(ValueMapper.mapFloat(right)),
    rightMax = tonumber(ValueMapper.mapFloat(rightMax)),
    total = tonumber(ValueMapper.mapFloat(left + right)),
    unit = "m",
  }

  -- The sections themselves — the shutoff bar a terminal draws across the boom, and the same read one
  -- level deeper. Order is `spec.sections`, i.e. the XML's own declaration order, because that is what
  -- the game's HUD draws left to right (VariableWorkWidthHUDExtension:draw walks 1..#sections).
  -- `sectionsLeft` / `sectionsRight` are deliberately NOT used: they are sorted by width for the
  -- fold-in state machine, so they are not display order.
  local sections, active = {}, 0
  for _, section in ipairs(spec.sections or {}) do
    local isActive = section.isActive ~= false
    if isActive then
      active = active + 1
    end
    sections[#sections + 1] = { active = isActive, side = sideOf(section) }
  end
  if #sections > 0 then
    model.sections = sections
    model.activeCount = active
  end

  return model
end
