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

  -- Every mode's name, index-aligned with `current`, so a terminal can say what a tap switches TO and
  -- not only what the machine is in now. Already localized, like `name`: loadWorkModeFromXML reads
  -- #name with the machine's own customEnvironment. A nameless mode keeps its slot as "" -- dropping
  -- it would relabel every mode after it.
  local names = {}
  for _, entry in ipairs(spec.workModes or {}) do
    names[#names + 1] = type(entry.name) == "string" and entry.name or ""
  end
  if 0 < #names then
    model.names = names
  end

  model.canChange = VDT.Work.canChangeMode(object)
  return model
end

---Whether the game's own work-mode key would switch this object's mode right now. Shared with
---WorkModeControl, which asks again at command time, so the export and the command cannot disagree.
---
---Two gates, both of which the key passes through before it reaches setWorkMode:
---  * getIsWorkModeChangeAllowed -- what WorkMode:onUpdate switches the action on and off by. WorkMode
---    checks the fold limits and, where the XML sets allowChangeOnLowered="false", that the attacher
---    joint is up; TurnOnVehicle overrides it to refuse while running where the XML sets
---    allowChangeWhileTurnedOn="false".
---  * getIsPowered -- the action is registered with addPoweredActionEvent, whose wrapper refuses with
---    "start the motor" unless a motor on the rig is running (Motorized) or the machine is hitched to
---    something that is (Attachable). The setter checks neither, which is how a Krone BiG M switched
---    modes from the terminal with its engine off.
---Everything both read -- fold time, joint moveDown, turned-on, motor state -- is synchronized, so a
---multiplayer client gets the answer the server would.
---@param object table
---@return boolean|nil nil when the object cannot say (no getIsWorkModeChangeAllowed)
function VDT.Work.canChangeMode(object)
  if object.getIsWorkModeChangeAllowed == nil then
    return nil
  end
  if not object:getIsWorkModeChangeAllowed() then
    return false
  end
  -- getIsPowered returns `isPowered, warning`; only the first is ours (see aspects/Mixer.lua).
  if object.getIsPowered ~= nil and object:getIsPowered() ~= true then
    return false
  end
  return true
end

---Which side of the boom a section sits on. `isCenter` wins: a center section is in neither of the
---engine's two side lists (VariableWorkWidth:onPostLoad fills sectionsLeft/sectionsRight and sets
---hasCenter instead), so it is never switched off and the game's
---own HUD brackets it with separators instead.
---@param section table an entry of spec_variableWorkWidth.sections
---@return string LEFT | CENTER | RIGHT
local function sideOf(section)
  if section.isCenter then
    return "CENTER"
  end
  return section.isLeft and "LEFT" or "RIGHT"
end

---One side of the tool as a width in meters, from what `getVariableWorkWidth` hands back.
---
---Two things have to be undone. The engine measures a side by its section node's **local X**
---(VariableWorkWidth:onPostLoad), so the right-hand side arrives negative — summing the two as they
---come gave a total of exactly 0 for every tool that has sections. And a side with no sections at all
---returns the placeholder `1, 1, false` from VariableWorkWidth:getVariableWorkWidth, which is not a
---measurement in any unit; that side contributes nothing.
---@param width number
---@param max number
---@param hasSections boolean|nil
---@return number width, number max both in meters, both zero when the side has no sections
local function sideWidth(width, max, hasSections)
  if hasSections == false then
    return 0, 0
  end
  return math.abs(width), math.abs(max)
end

---@param object table
---@return WorkWidthModel|nil nil when the object has no variable-width sections
function VDT.Work.collectWidth(object)
  local spec = object.spec_variableWorkWidth
  if spec == nil or not spec.hasSections then
    return nil
  end

  -- Each side reports (currentWidth, maxWidth, hasSections); the engine walks its own section list,
  -- which is short. Sides are independent — half-width work on one side is a normal headland
  -- technique — and each is measured from the tool's centre line, so the two add up to the whole.
  local left, leftMax = sideWidth(object:getVariableWorkWidth(true))
  local right, rightMax = sideWidth(object:getVariableWorkWidth(false))

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
