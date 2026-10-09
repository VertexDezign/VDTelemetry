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
--     is the tool's own. On a grab it is open: a Göweil bale grab and a Magsi log grab disagreed about
--     it in the engine's raw 0..1 (open at 1 and at 0), and both read 1 open once oriented. A tool cylinder with no speed to read a
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
-- WHICH MACHINES. An axis name is only a key binding, and modders borrow the front-loader ones for
-- anything with a lever: the John Deere 3x50 (issue #175) opens its doors, rear window and roof hatch
-- and swings its armrests on AXIS_FRONTLOADER_ARM/ARM2/TOOL2/TOOL5, and read as a loader it showed a
-- fully raised arm on a tractor with no loader on it. So the axis is only trusted on a machine that is
-- part of a loader: one carrying a joint a loader TOOL hangs on (the front loader itself, a wheel
-- loader, telehandler or skid steer -- VDT.LoaderTool.JOINT_TYPES), or a tool hanging on one (a
-- grab's clamp). A tractor's own `attachableFrontloader` joint is the loader's mount, not a tool
-- joint, so the tractor stays out with or without a loader on it -- the arm is the loader's to report.
-- A machine with a bucket built in and no tool joint would be left out too; none has turned up yet.
--
-- TIP. A high-tip bucket (the Paladin on the Kubota SVL, issue #175) turns the bucket on its own
-- frame with a TOOL2 cylinder -- whose control carries TOOL_OPEN_CLOSE, the same icon as a muck grab's
-- clamp, so the icon cannot tell them apart. What can is what the cylinder carries: a high tip moves
-- the bucket, so the shovel's own nodes hang below its moving node; a clamp moves an arm above a
-- bucket that stays put. Such a cylinder is exported as TIP, with `angle`, how far it has turned the
-- bucket from its travel-0 end in degrees nose up -- which the tool's root-node pitch
-- (VDT.LoaderTool) cannot see, the root being the frame the bucket turns on. Travel 0 is the bucket's
-- normal position, as a positive input opens a grab: skidSteer_highTip.json reads -33.3 degrees at
-- 0.37, and the side view drawn from it matched the bucket in game.
--
-- ICON. An AUX cylinder's role is the tool's own, and the axis name does not say it: TOOL2 is the
-- clamp on a bale grab and a muck grab, the tine spread on a pallet fork, the high tip on a skid
-- steer's bucket. The one place the author does say is the icon they gave the control, out of the
-- engine's fixed set (InputHelpElement.AXIS_ICON: GRABBER_OPEN_CLOSE, TOOL_OPEN_CLOSE,
-- WORKING_WIDTH_TRANSLATE_X, ...), so it is exported as `icon` and the panel decides from it what to
-- draw. An icon of the mod's own (Cylindered prefixes it with the mod's environment) names nothing a
-- terminal could know, and is left out.
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

---Whether `node` is `ancestor` or hangs below it.
---@param node number
---@param ancestor number
---@return boolean
local function isBelow(node, ancestor)
  local depth = 0
  while node ~= nil and node ~= 0 and depth < 64 do
    if node == ancestor then
      return true
    end
    node = getParent(node)
    depth = depth + 1
  end
  return false
end

---Whether the tool cylinder moves the bucket itself: one of the shovel's own nodes hangs below it.
---See TIP in the header.
---@param object table
---@param tool table
---@return boolean
local function carriesBucket(object, tool)
  local shovel = object.spec_shovel
  if shovel == nil or shovel.shovelNodes == nil or tool.node == nil then
    return false
  end
  for _, shovelNode in ipairs(shovel.shovelNodes) do
    if shovelNode.node ~= nil and isBelow(shovelNode.node, tool.node) then
      return true
    end
  end
  return false
end

---How far a tip cylinder has turned the bucket from its travel-0 end, in degrees, positive NOSE UP
---against the tool's root node -- or nil where it is not a rotation about the node's X axis. A
---positive rotation about X turns +Z down, and the node's X axis may point either way across the
---tool, so the sign is taken against the root's. See TIP in the header.
---@param object table
---@param tool table
---@return number|nil
local function tipAngle(object, tool)
  local sense = inputSense(tool)
  if
    sense == nil
    or tool.rotMin == nil
    or tool.rotMax == nil
    or (tool.rotationAxis or 1) ~= 1
    or tool.curRot == nil
    or object.rootNode == nil
  then
    return nil
  end
  local rest = sense > 0 and tool.rotMin or tool.rotMax
  local turned = tool.curRot[1] - rest
  local nx, ny, nz = localDirectionToWorld(tool.node, 1, 0, 0)
  local rx, ry, rz = localDirectionToWorld(object.rootNode, 1, 0, 0)
  local across = (nx * rx + ny * ry + nz * rz) >= 0 and 1 or -1
  return -across * math.deg(turned)
end

---Whether `object` is part of a loader: it carries a joint a loader tool hangs on, or hangs on one
---itself. See WHICH MACHINES in the header.
---@param object table
---@return boolean
function VDT.LoaderCylinders.isLoaderPart(object)
  local joints = object.spec_attacherJoints ~= nil and object.spec_attacherJoints.attacherJoints or nil
  for _, joint in ipairs(joints or {}) do
    if VDT.LoaderTool.jointToken(joint.jointType) ~= nil then
      return true
    end
  end
  return VDT.LoaderTool.inputJointOf(object) ~= nil
end

---The engine's name for the control's icon, or nil when the author gave none or drew their own.
---See ICON in the header.
---@param tool table
---@return string|nil
local function engineIcon(tool)
  local icon = tool.axisActionIcon
  if
    type(icon) ~= "string"
    or InputHelpElement == nil
    or InputHelpElement.AXIS_ICON == nil
    or InputHelpElement.AXIS_ICON[icon] == nil
  then
    return nil
  end
  return icon
end

---@param object table a vehicle or implement
---@return LoaderCylinderModel[]|nil nil when no moving tool on it is driven by a front-loader axis, or
---it is not part of a loader at all
function VDT.LoaderCylinders.collect(object)
  local spec = object.spec_cylindered
  if
    spec == nil
    or spec.movingTools == nil
    or Cylindered == nil
    or type(Cylindered.getMovingToolState) ~= "function"
    or not VDT.LoaderCylinders.isLoaderPart(object)
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
        local cylinder = {
          role = role,
          axis = axis,
          travel = tonumber(ValueMapper.mapFloat(travel, 3)),
          icon = engineIcon(tool),
        }
        if role == "AUX" and carriesBucket(object, tool) then
          cylinder.role = "TIP"
          local angle = tipAngle(object, tool)
          if angle ~= nil then
            cylinder.angle = tonumber(ValueMapper.mapFloat(angle, 1))
          end
        end
        table.insert(out, cylinder)
      end
    end
  end

  if #out == 0 then
    return nil
  end
  return out
end
