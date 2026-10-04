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
-- DIRECTION. The engine's 0..1 runs from rotMin to rotMax, and which end is "up" is an accident of how
-- the node was modelled. On every captured loader it ran BACKWARDS: an arm on the ground read 0.99 and
-- a telehandler at full height 0.002, a level shovel 0.29 and a dumped one 0.81. The reason is
-- geometric -- an arm or a tilt cylinder rotates about its node's X axis, and a POSITIVE rotation about
-- X turns the node's +Z (forward, toward the tool) DOWN -- so whether rotMax is the bottom depends only
-- on whether the node's X axis points the same way as the machine's. That is checked per tool against
-- the object's own root node, and `travel` is exported oriented:
--   * LIFT -- 1 is the top of the stroke;
--   * TILT -- 1 is curled back, 0 tipped out;
--   * TELESCOPE -- 1 is fully extended (a translation along the boom's +Z, which with the X axis agreeing
--     points out of the boom);
--   * AUX -- the engine's own 0..1, unoriented: its meaning is the tool's, so there is no "up" to orient to.
-- A LIFT/TILT/TELESCOPE tool whose direction cannot be told -- driven by an animation, rotating about
-- another axis, or with its X axis across the machine's -- is left OUT rather than exported in a
-- direction that might be backwards.
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

-- How closely a tool's X axis has to agree (or disagree) with the machine's before its direction is
-- trusted: cos(60 degrees). A node turned further than that is modelled across the machine, and its
-- rotation says nothing about up.
local AXIS_AGREEMENT = 0.5

---1 when the tool's X axis agrees with the machine's, -1 when it is reversed, nil when it is across.
---@param object table
---@param tool table
---@return number|nil
local function xAxisSense(object, tool)
  if tool.node == nil or object.rootNode == nil then
    return nil
  end
  local tx, ty, tz = localDirectionToWorld(tool.node, 1, 0, 0)
  local rx, ry, rz = localDirectionToWorld(object.rootNode, 1, 0, 0)
  local dot = tx * rx + ty * ry + tz * rz
  if dot > AXIS_AGREEMENT then
    return 1
  elseif dot < -AXIS_AGREEMENT then
    return -1
  end
  return nil
end

---`state` turned so that 1 is raised / curled back / extended, or nil when the direction is unknowable.
---See DIRECTION in the header.
---@param object table
---@param tool table
---@param role string
---@param state number the engine's 0..1, already clamped
---@return number|nil
local function orient(object, tool, role, state)
  if role == "AUX" then
    return state
  end
  local sense = xAxisSense(object, tool)
  if sense == nil then
    return nil
  end
  if role == "TELESCOPE" then
    -- A translation along +Z, the way out of the boom when X agrees.
    if tool.rotMax ~= nil or tool.transMin == nil or (tool.translationAxis or 3) ~= 3 then
      return nil
    end
    return sense > 0 and state or 1 - state
  end
  -- LIFT / TILT: a rotation about X, where positive turns +Z down.
  if tool.rotMax == nil or tool.rotMin == nil or (tool.rotationAxis or 1) ~= 1 then
    return nil
  end
  return sense > 0 and 1 - state or state
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
        travel = orient(object, tool, role, math.max(0, math.min(1, state)))
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
