-- Aspect collector: the tool's work areas — the rectangles it actually processes ground with.
-- Applies to any object (vehicle or implement). Namespaced under VDT.* (see TurnOn.lua).
--
-- This is the generic answer to "what is working right now", for the machines that have no sections
-- at all (a tedder, a plough, a mower). Two engine predicates carry it, and both are the game's own:
--
--   * getIsWorkAreaActive   (WorkArea.lua:309) -- ground contact, driving direction and lowered state,
--                             plus the VariableWorkWidth override (:378-386) that switches an area off
--                             with its section. So `active` already accounts for the shutoff bar.
--   * getIsWorkAreaProcessing (WorkArea.lua:337) -- true within 200 ms of the area last having
--                             processed ground. "Active" is a capability, this is the evidence.
--
-- `shape` is the area's footprint in the shared normalized [0,1] map frame (MapExporter's, the same
-- one map.json / mapVehicles.json / the course use), so the app can draw the swath under the vehicle
-- marker without knowing anything about world coordinates. The engine describes an area by three
-- corners of a parallelogram — start, width, height — and so do we; the fourth corner is
-- width + height - start, which the consumer can derive.
--
-- Every engine call here is pcall'd: the nodes belong to an i3d that a mod may have built oddly, and
-- the cost of being wrong has to be a missing overlay rather than a broken telemetry write.

VDT = VDT or {}
VDT.WorkAreas = {}

-- Helper volumes, not work: the engine skips the whole processing setup for them
-- (WorkArea.lua:246), so they have no functionName and never touch ground.
VDT.WorkAreas.SKIPPED_TYPE = "AUXILIARY"

---Wire token for a work-area type index ("SPRAYER", "CULTIVATOR", "COMBINE", ...). The manager holds
---the names uppercased already (WorkAreaTypeManager:addWorkAreaType), so they pass through as-is;
---nil when the enum can't be reached or the index is unknown.
---@param areaType number|nil
---@return string|nil
function VDT.WorkAreas.typeToken(areaType)
  if areaType == nil or g_workAreaTypeManager == nil then
    return nil
  end
  local ok, name = pcall(g_workAreaTypeManager.getWorkAreaTypeNameByIndex, g_workAreaTypeManager, areaType)
  if not ok or type(name) ~= "string" or name == "" then
    return nil
  end
  return name
end

---World position of a work-area node, or nil when it can't be read.
---@param node any
---@return number|nil x, number|nil z
local function worldPos(node)
  if node == nil then
    return nil, nil
  end
  local ok, x, _, z = pcall(getWorldTranslation, node)
  if not ok or type(x) ~= "number" or type(z) ~= "number" then
    return nil, nil
  end
  return x, z
end

---Build one work area's model, or nil when it is a helper volume or its nodes can't be read.
---@param object table
---@param area table an entry of spec_workArea.workAreas
---@param sizeX number|nil world size for normalization; nil skips the footprint
---@param sizeZ number|nil
---@return WorkAreaModel|nil
local function collectArea(object, area, sizeX, sizeZ)
  local token = VDT.WorkAreas.typeToken(area.type)
  if token == VDT.WorkAreas.SKIPPED_TYPE then
    return nil
  end

  ---@type WorkAreaModel
  local model = { index = area.index }
  if token ~= nil then
    model.type = token
  end

  -- Both predicates are the object's own registered functions, present whenever spec_workArea is.
  local okActive, isActive = pcall(object.getIsWorkAreaActive, object, area)
  model.active = okActive and isActive == true
  local okProcessing, isProcessing = pcall(object.getIsWorkAreaProcessing, object, area)
  model.processing = okProcessing and isProcessing == true

  local startX, startZ = worldPos(area.start)
  local widthX, widthZ = worldPos(area.width)
  local heightX, heightZ = worldPos(area.height)
  if startX == nil or widthX == nil or heightX == nil then
    return model
  end

  -- The engine's own workWidth is only recomputed when a section moves (WorkArea.lua:321-331) and
  -- starts at -1, so measure the start->width edge instead: it is the same distance, from positions
  -- already read, and it is never stale.
  local dx, dz = widthX - startX, widthZ - startZ
  model.width = tonumber(ValueMapper.mapFloat(math.sqrt(dx * dx + dz * dz)))
  model.unit = "m"

  if sizeX ~= nil then
    model.shape = {
      VDT.MapExporter.normalizeCoord(startX, sizeX),
      VDT.MapExporter.normalizeCoord(startZ, sizeZ),
      VDT.MapExporter.normalizeCoord(widthX, sizeX),
      VDT.MapExporter.normalizeCoord(widthZ, sizeZ),
      VDT.MapExporter.normalizeCoord(heightX, sizeX),
      VDT.MapExporter.normalizeCoord(heightZ, sizeZ),
    }
  end

  return model
end

---@param object table a vehicle or implement
---@return WorkAreaModel[]|nil nil when the object works no ground
function VDT.WorkAreas.collect(object)
  local spec = object.spec_workArea
  if spec == nil or spec.workAreas == nil then
    return nil
  end

  -- Resolved once per object rather than per area. nil (no HUD map, no terrain yet) is not fatal:
  -- the areas still report what they are doing, only without a footprint to draw it at.
  local sizeX, sizeZ
  if VDT.MapExporter ~= nil and g_currentMission ~= nil then
    sizeX, sizeZ = VDT.MapExporter.resolveWorldSize()
  end

  local areas = {}
  for _, area in ipairs(spec.workAreas) do
    areas[#areas + 1] = collectArea(object, area, sizeX, sizeZ)
  end

  -- Never an empty Lua table: the encoder would write `{}` where the consumer expects `[]`.
  if #areas == 0 then
    return nil
  end
  return areas
end
