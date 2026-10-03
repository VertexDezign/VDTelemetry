-- Collects where the linkage an implement hangs off sits in its travel: the three-point hitch's
-- height, as the game's own `attacherJoints.bottomArmPosition` dashboard shows it. Reported on the
-- IMPLEMENT (next to jointDescIndex), because the joint is the parent's and an implement is what
-- tells one of a tractor's linkages from the other -- the front linkage reads on the front implement,
-- the rear one on the rear.
-- Namespaced under VDT.* (see aspects/TurnOn.lua).
--
-- The engine's alphas run the other way round from a gauge: 0 is the top of the arm's travel and 1
-- the bottom (they interpolate distance to the ground, see
-- AttacherJoints:calculateAttacherJointMoveUpperLowerAlpha). So everything here is `1 - alpha`,
-- exactly as the dashboard value computes it, and reads 100 % at the top.
--
-- `min`/`max` are where the arm stops for THIS implement when lowered and when raised -- the engine
-- recomputes both from the implement's own attacher joint, so they differ from one implement to the
-- next and are not the arm's mechanical limits. The dashboard exports the same pair
-- (bottomArmPositionMin / Max).
--
-- Only for a joint that actually moves: the engine animates moveAlpha only where both the parent's
-- joint and the implement's own allow lowering, and pins it to one end otherwise (a trailer on a
-- drawbar, say). Nothing is reported there rather than a "position" that cannot change.
--
-- MULTIPLAYER: fine. The lowering itself is synced (setJointMoveDown sends
-- VehicleLowerImplementEvent), and the arm's travel is animated in AttacherJoints:onUpdate on every
-- machine that runs it, not only on the server -- which is also why the engine updates the dashboard
-- from there with an isClient gate.

VDT = VDT or {}
VDT.Hitch = {}

---@param alpha number|nil
---@return number|nil
local function percent(alpha)
  if type(alpha) ~= "number" then
    return nil
  end
  return tonumber(ValueMapper.mapPercentage(1 - alpha, 0))
end

---@param parent table the object the implement is attached to
---@param attachedImplement table the entry in the parent's spec_attacherJoints.attachedImplements
---@return HitchModel|nil nil when the joint does not move
function VDT.Hitch.collect(parent, attachedImplement)
  local ajSpec = parent.spec_attacherJoints
  local object = attachedImplement.object
  if ajSpec == nil or ajSpec.attacherJoints == nil or object == nil then
    return nil
  end
  local jointDesc = ajSpec.attacherJoints[attachedImplement.jointDescIndex]
  if jointDesc == nil or not jointDesc.allowsLowering then
    return nil
  end
  local attachable = object.spec_attachable
  local objectJoint = attachable ~= nil and attachable.attacherJoint or nil
  if objectJoint == nil or not objectJoint.allowsLowering then
    return nil
  end

  local position = percent(jointDesc.moveAlpha)
  if position == nil then
    return nil
  end
  return {
    position = position,
    min = percent(jointDesc.lowerAlpha),
    max = percent(jointDesc.upperAlpha),
  }
end
