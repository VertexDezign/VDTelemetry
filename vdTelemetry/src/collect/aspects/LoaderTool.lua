-- Aspect collector: where a loader tool is -- its angle against the horizon and how far it is above
-- whatever is under it (issue #169). Applies to any object hitched to a loader's tool joint: the
-- shovel, fork or grab on a front loader, wheel loader, telehandler or skid steer.
-- Namespaced under VDT.* (see TurnOn.lua).
--
-- The method is Tool Inclination Helper's (timmeey86, Apache-2.0), which the user named as the target:
-- the tool's ROOT NODE pitch, and a downward ray that ignores the rig. Both are raw. Neither is "level":
-- no node on a tool is guaranteed to lie parallel to the shovel floor or the fork tines -- the engine's
-- own Shovel measures its tip angle off a separate dischargeInfo node, and a fork has no such node at
-- all -- so a zero has to be SET by the player, per tool model (VDT.LoaderReferences, written by
-- command/LoaderControl.lua). The reference is carried beside the raw values rather than subtracted
-- from them, so a panel can say whose level it is reading off. Without one the app takes the root
-- node as level: on every captured tool it lies within a degree or so of the floor, and the
-- reference is the correction for the tool where it does not.
--
-- Which objects: decided by the joint the tool hangs on, not by `storeData.category` as Tool
-- Inclination Helper does. The category is a shop shelf; a modder who files a shovel under the wrong one
-- has not changed how it hitches. `attachableFrontloader` is excluded on purpose -- that is the joint
-- between the loader and the TRACTOR, so it would mark the loader arm rather than its tool.
--
-- FORKLIFTS (issue #174). A forklift's forks are part of the machine -- on the Hubtex MAXX 45 a
-- physics component of their own, hung off the mast by a component joint -- so there is no tool and no
-- joint to find them by. What every forklift does have is the engine's DynamicMountAttacher on the
-- forks, since that is how a pallet rides on them, and its node lies on the forks: it is measured
-- instead of a root node, under joint FORKLIFT. A machine counts as one when it carries that
-- attacher itself, has an AXIS_FRONTLOADER_ARM cylinder to lift with, and has no loader joint either way
-- (a telehandler or a pallet fork is read the joint way). Tool Inclination Helper string-matches
-- shape names in the vehicle's i3d instead; the mount node is the engine's own word for "the forks".
--
-- Sign: `pitch` is positive NOSE UP. MathUtil.directionToPitchYaw returns asin(-dy), the opposite
-- sense, so it is not used here.
--
-- The ray (`distance`) starts half a metre along the root's Z axis -- forward on a GIANTS vehicle,
-- toward the tool's working edge -- so a fork reads the pallet under its tips rather than the ground
-- under its frame. It ignores every node belonging to any machine on the rig (rootVehicle's
-- childVehicles): checking only the tool and its tractor, as Tool Inclination Helper does, would leave
-- a front loader's own arm able to answer it. It takes the NEAREST hit rather than trusting the engine
-- to report hits in order. Nothing below within reach means the tool is buried or under an overhang,
-- so it casts upward and reports the distance negative; nothing either way (30 m) leaves it absent.
--
-- MULTIPLAYER: node transforms follow the server on a client (Cylindered interpolates its moving tools
-- there), and collision geometry is client-side, so both readings are expected to hold on a client.
-- Argued from the source; still to be confirmed against a client capture.
--
-- Cost: one or two synchronous raycasts per loader tool per export, and only on a rig that has one.

VDT = VDT or {}
VDT.LoaderTool = {}

-- Joint type names (AttacherJoints.registerJointType) that a loader's TOOL hangs on, mapped to the
-- token exported as `joint`.
VDT.LoaderTool.JOINT_TYPES = {
  frontloader = "FRONTLOADER",
  telehandler = "TELEHANDLER",
  wheelLoader = "WHEEL_LOADER",
  skidSteer = "SKID_STEER",
  loaderFork = "LOADER_FORK",
}

-- The token for a forklift, whose forks hang on no joint at all (see FORKLIFTS in the header).
VDT.LoaderTool.FORKLIFT = "FORKLIFT"

-- How far the ray reaches, in metres. Tool Inclination Helper's 10 m is too short: a Sennebogen 340 G
-- at full lift and full extension had its fork past it and came back with no distance at all.
VDT.LoaderTool.MAX_DISTANCE = 30
-- How far forward of the root node the ray starts, in metres (Tool Inclination Helper's value).
VDT.LoaderTool.RAY_FORWARD_OFFSET = 0.5

---The exported token for an engine joint type, or nil when it is not one a loader's tool hangs on.
---@param jointType number|nil an AttacherJoints.jointTypeNameToInt value
---@return string|nil
function VDT.LoaderTool.jointToken(jointType)
  if jointType == nil or AttacherJoints == nil then
    return nil
  end
  for name, token in pairs(VDT.LoaderTool.JOINT_TYPES) do
    if AttacherJoints.jointTypeNameToInt[name] == jointType then
      return token
    end
  end
  return nil
end

---The exported joint token for `object`'s active input joint, or nil when it is not on a loader.
---@param object table
---@return string|nil
function VDT.LoaderTool.inputJointOf(object)
  if object.spec_attachable == nil or type(object.getActiveInputAttacherJoint) ~= "function" then
    return nil
  end
  local joint = object:getActiveInputAttacherJoint()
  if joint == nil then
    return nil
  end
  return VDT.LoaderTool.jointToken(joint.jointType)
end

---Whether `object` carries a joint a loader tool hangs on: it is the loader, not the tool.
---@param object table
---@return boolean
function VDT.LoaderTool.carriesToolJoint(object)
  local joints = object.spec_attacherJoints ~= nil and object.spec_attacherJoints.attacherJoints or nil
  for _, joint in ipairs(joints or {}) do
    if VDT.LoaderTool.jointToken(joint.jointType) ~= nil then
      return true
    end
  end
  return false
end

---The node a forklift's forks are measured off -- its own DynamicMountAttacher node -- or nil when
---`object` is not a forklift. See FORKLIFTS in the header.
---@param object table
---@return number|nil
function VDT.LoaderTool.forkNodeOf(object)
  local mount = object.spec_dynamicMountAttacher
  if mount == nil or mount.dynamicMountAttacherNode == nil then
    return nil
  end
  if VDT.LoaderTool.inputJointOf(object) ~= nil or VDT.LoaderTool.carriesToolJoint(object) then
    return nil
  end
  -- The lift itself, not just any front-loader axis: a pallet trailer may swing a ramp on TOOL2.
  local cylindered = object.spec_cylindered
  for _, tool in ipairs(cylindered ~= nil and cylindered.movingTools or {}) do
    if tool.axis == "AXIS_FRONTLOADER_ARM" and tool.hasRequiredConfigurations ~= false then
      return mount.dynamicMountAttacherNode
    end
  end
  return nil
end

---What kind of tool `object` is, by which tool specialization it carries -- never by its `type`
---string, which is the modder's own word (the captured pallet fork's is implementDynamicMountAttacher).
---The panel draws a different profile per kind.
---
---Checked most specific first: a grab is often ALSO a DynamicMountAttacher, and a muck grab is a
---shovel with a clamp (the clamp is its own AUX cylinder, which the panel draws on top). A spec counts
---only where it is configured: Shovel keeps its table on a type with no shovel nodes, and LogGrab
---with no grabs. BaleGrab is the exception -- its whole onLoad is under `if self.isServer`, so on a
---multiplayer client the table is there and empty, and its presence is all there is to go by.
---@param object table
---@return string SHOVEL | FORK | BALE_GRAB | LOG_GRAB | OTHER
function VDT.LoaderTool.kindOf(object)
  if object.spec_baleGrab ~= nil then
    return "BALE_GRAB"
  end
  local logGrab = object.spec_logGrab
  if logGrab ~= nil and logGrab.grabs ~= nil and #logGrab.grabs > 0 then
    return "LOG_GRAB"
  end
  local shovel = object.spec_shovel
  if shovel ~= nil and shovel.shovelNodes ~= nil and #shovel.shovelNodes > 0 then
    return "SHOVEL"
  end
  if object.spec_dynamicMountAttacher ~= nil then
    return "FORK"
  end
  return "OTHER"
end

---Whether a hit node belongs to any machine on the rig.
---@param rig table[] the rig's vehicles
---@param node number
---@return boolean
local function isRigNode(rig, node)
  for _, vehicle in ipairs(rig) do
    if vehicle.vehicleNodes ~= nil and vehicle.vehicleNodes[node] ~= nil then
      return true
    end
  end
  return false
end

---Every machine on `object`'s rig, `object` included even when the chain is not built yet.
---@param object table
---@return table[]
local function rigOf(object)
  local root = object.rootVehicle
  if root ~= nil and type(root.getChildVehicles) == "function" then
    local children = root:getChildVehicles()
    if children ~= nil and #children > 0 then
      return children
    end
  end
  return { object }
end

---Nearest non-rig hit along a vertical ray from (x, y, z), or nil.
---@param rig table[]
---@param dirY number -1 down, 1 up
---@return number|nil
local function castFrom(rig, x, y, z, dirY)
  local result = { nearest = nil }
  function result.raycastCallback(self, hitObjectId, _, _, _, distance)
    if hitObjectId ~= nil and not isRigNode(rig, hitObjectId) then
      if self.nearest == nil or distance < self.nearest then
        self.nearest = distance
      end
    end
    return true -- keep going: the nearest hit is kept, whatever order the engine reports them in
  end
  local mask = CollisionFlag.TERRAIN
    + CollisionFlag.STATIC_OBJECT
    + CollisionFlag.VEHICLE
    + CollisionFlag.DYNAMIC_OBJECT
  raycastAll(x, y, z, 0, dirY, 0, VDT.LoaderTool.MAX_DISTANCE, "raycastCallback", result, mask)
  return result.nearest
end

---@param object table a vehicle or implement
---@return LoaderToolModel|nil nil when the object is not hitched to a loader's tool joint
function VDT.LoaderTool.collect(object)
  local joint = VDT.LoaderTool.inputJointOf(object)
  local node = object.rootNode
  if joint == nil then
    node = VDT.LoaderTool.forkNodeOf(object)
    joint = node ~= nil and VDT.LoaderTool.FORKLIFT or nil
  end
  if joint == nil or node == nil then
    return nil
  end

  local _, dy, _ = localDirectionToWorld(node, 0, 0, 1)
  ---@type LoaderToolModel
  local model = {
    joint = joint,
    kind = VDT.LoaderTool.kindOf(object),
    pitch = tonumber(ValueMapper.mapFloat(math.deg(math.asin(math.max(-1, math.min(1, dy)))), 2)),
  }

  local rig = rigOf(object)
  local x, y, z = localToWorld(node, 0, 0, VDT.LoaderTool.RAY_FORWARD_OFFSET)
  local distance = castFrom(rig, x, y, z, -1)
  if distance == nil then
    distance = castFrom(rig, x, y, z, 1)
    if distance ~= nil then
      distance = -distance
    end
  end
  if distance ~= nil then
    model.distance = tonumber(ValueMapper.mapFloat(distance, 3))
  end

  -- The player's "this is level" for this tool model, carried beside the raw values rather than
  -- subtracted from them (see the header, and src/store/LoaderReferences.lua).
  if VDT.LoaderReferences ~= nil then
    local reference = VDT.LoaderReferences.get(VDT.LoaderReferences.keyOf(object))
    if reference ~= nil then
      model.reference = { pitch = reference.pitch, distance = reference.distance }
    end
  end

  return model
end
