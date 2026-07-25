-- Aspect collector: the vehicle-schema drawing data. Applies to any object (vehicle or implement).
-- Namespaced under VDT.* (see TurnOn.lua).
--
-- This is what the game's own HUD draws its little rig diagram from (InputHelpDisplay's
-- drawVehicleSchema): every object carries a `schemaOverlay` naming which silhouette represents it,
-- plus, for each of its attacher joints, where a child hangs off it.
--
-- We deliberately export the RAW joint data and do no layout arithmetic here. Composing the offsets,
-- rotations and invertX flags down the implement tree is the consumer's job (see
-- InputHelpDisplay:collectVehicleSchemaDisplayOverlays for the reference algorithm) — keeping it out
-- of the mod means the dashboard can change how it draws without shipping a new mod build.
--
-- The link between a child and its parent's joint list is `jointDescIndex`, which lives on the
-- attacher-joint entry rather than on the object, so VehicleExporter attaches it while walking the
-- tree.

VDT = VDT or {}
VDT.Schema = {}

---@param object table
---@return SchemaModel|nil nil when the object defines no schema overlay
function VDT.Schema.collect(object)
  local overlay = object.schemaOverlay
  if overlay == nil then
    return nil
  end

  local joints
  -- `attacherJoints` stays nil until the object registers one, so a plain implement has none.
  if overlay.attacherJoints ~= nil then
    joints = {}
    for _, joint in ipairs(overlay.attacherJoints) do
      table.insert(joints, {
        x = joint.x,
        y = joint.y,
        rotation = joint.rotation,
        invertX = joint.invertX,
        liftedOffsetX = joint.liftedOffsetX,
        liftedOffsetY = joint.liftedOffsetY,
      })
    end
  end

  return {
    -- One of VehicleSchemaOverlayData.SCHEMA_OVERLAY (VEHICLE / HARVESTER / TRAILER / ...), mod-
    -- prefixed for modded silhouettes. Empty when the XML named none.
    name = overlay.schemaName,
    offsetX = overlay.offsetX,
    offsetY = overlay.offsetY,
    -- How much of the silhouette's width is padding, so a consumer can butt objects up against each
    -- other the way the HUD does.
    borderLeft = overlay.invisibleBorderLeft,
    borderRight = overlay.invisibleBorderRight,
    attacherJoint = joints,
  }
end
