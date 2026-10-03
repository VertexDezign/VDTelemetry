-- Aspect collector: how far along its stroke each loader cylinder is (issue #169). Applies to any
-- object whose Cylindered moving tools are driven by a front-loader axis: the loader on a tractor, the
-- wheel loader / telehandler / skid steer itself, and a tool with a cylinder of its own (a bale grab's
-- clamp, a shovel's top-hold). Namespaced under VDT.* (see TurnOn.lua).
--
-- What a cylinder DOES is read off the input axis that drives it, because that is the only place the
-- engine says: a moving tool is a node, a limit and an axis name. AXIS_FRONTLOADER_ARM lifts,
-- ARM2 telescopes (a telehandler's boom), TOOL tilts the tool, and TOOL2..TOOL5 are whatever that tool
-- uses them for -- a clamp on one, a top-hold on another -- so they are exported as AUX rather than
-- guessed at. Cranes (AXIS_CRANE_*) are not loaders and are left to the forestry issue.
--
-- `travel` starts from the engine's own Cylindered:getMovingToolState, 0 at the min limit and 1 at the
-- max. It is NOT a vehicle function: Cylindered.registerFunctions never registers it, and the engine
-- itself always calls it as `Cylindered.getMovingToolState(self, tool)`. `object:getMovingToolState` is
-- nil on every machine -- the first captures came back with no cylinders at all because of it.
--
-- The function has a trap of its own: a tool with NO limits returns its raw rotation or translation
-- instead, in radians or metres, which a panel would draw as a fraction. Only tools whose state is
-- bounded by its precedence are kept, and the value is clamped (an animation can be given a start time
-- outside its min/max window).
--
-- DIRECTION. The engine's 0..1 runs from the min limit to the max, and which end is "up" is an
-- accident of how the part was modelled -- so `travel` is turned round where it has to be, and is
-- exported oriented:
--   * LIFT -- 1 is the top of the stroke;
--   * TILT -- 1 is curled back, 0 tipped out;
--   * TELESCOPE -- 1 is fully extended;
--   * AUX -- 1 is the end a positive input drives toward, by the same rule below; what that end MEANS
--     is the tool's own (on a grab it is expected to be open -- a bale grab and a log grab disagreed
--     about it in the engine's raw 0..1, open at 1 and at 0). A tool cylinder with no speed to read a
--     sense from keeps the engine's direction rather than vanishing, since nothing is claimed for it.
--
-- "Up" is read off the machine's CONTROLS, not its geometry. Cylindered:onUpdate moves a tool by
-- `move * rotSpeed` (or trans/anim speed), `move` being the axis input flipped when the XML sets
-- invertAxis -- and every author has to make the same key raise an arm, tip a bucket back and run a
-- boom out. So the end a POSITIVE input drives toward is up, on any machine however it was built. Four
-- fully raised arms settled it: a John Deere 623R, a Sennebogen 340 G and a Volvo L90H raise toward
-- their min limit with a negative speed sense, a Kubota SVL 97-2 toward its max with a positive one --
-- and the telescope and a dumped shovel agree.
--
-- The rule it replaced read the node's X axis against the machine's (a positive rotation about X
-- turns +Z down) and assumed the node points from its pivot toward the tool. It held on three of those
-- four and read the Kubota's lift upside down: its geometry check agreed on all four, so nothing about
-- the node could have told it apart. A tool with no speed to read a sense from has no "up" and is left
-- OUT rather than exported in a direction that might be backwards.
--
-- Not a world reading. The tilt cylinder's travel says where the cylinder is, not whether the shovel
-- is level -- the arm under it moves the horizon -- which is what VDT.LoaderTool is for.
--
-- MULTIPLAYER: curRot/curTrans follow the server on a client through Cylindered's network
-- interpolators (onReadUpdateStream -> setTargetAngle/Value, applied in onUpdate), so this holds there.

VDT = VDT or {}
VDT.LoaderCylinders = {}

local AXIS_PREFIX = "AXIS_FRONTLOADER_"

-- Axis suffix -> exported role. Anything else under the prefix is AUX.
VDT.LoaderCylinders.ROLES = {
  ARM = "LIFT",
  ARM2 = "TELESCOPE",
  TOOL = "TILT",
}

---Whether getMovingToolState answers a fraction for `tool` rather than a raw angle or offset. Follows
---the function's own order: rotation limits win, an unlimited rotation is raw, then translation the
---same way, then the animation.
---@param tool table a spec_cylindered.movingTools entry
---@return boolean
local function isBounded(tool)
  if tool.rotMax ~= nil and tool.rotMin ~= nil then
    return true
  end
  if tool.rotSpeed ~= nil then
    return false
  end
  if tool.transMax ~= nil and tool.transMin ~= nil then
    return true
  end
  if tool.transSpeed ~= nil then
    return false
  end
  return tool.animName ~= nil
end

---Which way a POSITIVE input on the cylinder's axis moves its state: 1 toward its max, -1 toward its
---min, nil when it has no speed to tell by. Cylindered:onUpdate turns input into motion as
---`move * rotSpeed` (or trans/anim speed), with `move` the input flipped when the XML sets invertAxis.
---Every author has to make the same key raise an arm, so this is the machine's own statement of which
---end is "up" -- independent of how the node was modelled. See DIRECTION in the header.
---@param tool table
---@return number|nil
local function inputSense(tool)
  local speed
  if tool.rotMax ~= nil and tool.rotMin ~= nil then
    speed = tool.rotSpeed
  elseif tool.transMax ~= nil and tool.transMin ~= nil then
    speed = tool.transSpeed
  else
    speed = tool.animSpeed
  end
  if type(speed) ~= "number" or speed == 0 then
    return nil
  end
  local sense = speed > 0 and 1 or -1
  if tool.invertAxis then
    sense = -sense
  end
  return sense
end

---`state` turned so that 1 is raised / curled back / extended, or nil when the direction is unknowable.
---See DIRECTION in the header.
---@param tool table
---@param role string
---@param state number the engine's 0..1, already clamped
---@return number|nil
local function orient(tool, role, state)
  local sense = inputSense(tool)
  if sense == nil then
    if role == "AUX" then
      return state
    end
    return nil
  end
  return sense > 0 and state or 1 - state
end

---@param object table a vehicle or implement
---@return LoaderCylinderModel[]|nil nil when no moving tool on it is driven by a front-loader axis
function VDT.LoaderCylinders.collect(object)
  local spec = object.spec_cylindered
  if
    spec == nil
    or spec.movingTools == nil
    or Cylindered == nil
    or type(Cylindered.getMovingToolState) ~= "function"
  then
    return nil
  end

  local out = {}
  for _, tool in ipairs(spec.movingTools) do
    local axis = tool.axis
    if
      type(axis) == "string"
      and axis:sub(1, #AXIS_PREFIX) == AXIS_PREFIX
      and tool.hasRequiredConfigurations ~= false
      and isBounded(tool)
    then
      local state = Cylindered.getMovingToolState(object, tool)
      local role = VDT.LoaderCylinders.ROLES[axis:sub(#AXIS_PREFIX + 1)] or "AUX"
      local travel = nil
      if type(state) == "number" and state == state then
        travel = orient(tool, role, math.max(0, math.min(1, state)))
      end
      if travel ~= nil then
        table.insert(out, {
          role = role,
          axis = axis,
          travel = tonumber(ValueMapper.mapFloat(travel, 3)),
        })
      end
    end
  end

  if #out == 0 then
    return nil
  end
  return out
end
