-- Executes the FS25_CropRotation write-back commands (app -> mod). The inverse of the read-only
-- cropRotation export channel (src/integrations/CropRotation.lua): edit a rotation plan by driving
-- the planner's own MP-correct wrappers on FS25_CropRotation.g_cropRotationPlanner, which each send
-- the appropriate multiplayer event (CropRotationEntryEvent), so state stays in sync. None of these
-- touch a vehicle, so every handler declares requiresVehicle = false (see CommandRegistry /
-- VDTelemetry:onCommand).
--
-- Commands:
--   setRotationCrop      slot's main crop        -> updateCropSelection(cr, slot, state)
--   setRotationCatchCrop slot's catch crop       -> updateCatchCropSelection(cr, slot, catchCropState)
--   addRotationSlot      append a slot           -> addCropRotationSelection(cr)
--   removeRotationSlot   drop the last slot      -> removeCropRotationSelection(cr)
--   createRotation       new (1-slot) plan       -> addCropRotation(name, farmId)
--   deleteRotation       remove a plan           -> removeCropRotation(cr)
--
-- Mod-environment isolation: the planner instance and its wrappers live in FS25_CropRotation's own
-- Lua env, reachable from ours only as FS25_CropRotation.g_cropRotationPlanner (the bare
-- g_cropRotationPlanner is nil here — see the integration's header). Calling the wrapper *methods*
-- means their bodies resolve CropRotationEntryEvent / g_cropRotation in the mod's env for free.
-- `rotationIndex` is the plan's own `index` (as exported), resolved back to the live plan via the
-- planner's getCropRotationWithIndex; `slot` is the 1-based position within that plan's sequence.
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.CropRotationControl = {}

local function env()
  return type(FS25_CropRotation) == "table" and FS25_CropRotation or nil
end

local function planner()
  local e = env()
  return e ~= nil and e.g_cropRotationPlanner or nil
end

-- Resolve the planner + the target plan for a command, logging the reason on a miss. Returns
-- (planner, plan) or nil so callers can `if pl == nil then return end`.
local function resolve(rotationIndex, debugger, label)
  local pl = planner()
  if pl == nil then
    debugger:warn("%s: CropRotation not available", label)
    return nil
  end
  local cr = pl:getCropRotationWithIndex(rotationIndex)
  if cr == nil then
    debugger:warn("%s: no rotation with index %s", label, tostring(rotationIndex))
    return nil
  end
  return pl, cr
end

-- Parsers (see CommandRegistry). Slot edits carry the plan index, slot position, and target state.
local function parseSlotCrop(xml, key)
  return {
    rotationIndex = xml:getInt(key .. "#rotationIndex"),
    slot = xml:getInt(key .. "#slot"),
    state = xml:getInt(key .. "#state"),
  }
end

local function parseSlotCatchCrop(xml, key)
  return {
    rotationIndex = xml:getInt(key .. "#rotationIndex"),
    slot = xml:getInt(key .. "#slot"),
    catchCropState = xml:getInt(key .. "#catchCropState"),
  }
end

local function parseRotationIndex(xml, key)
  return { rotationIndex = xml:getInt(key .. "#rotationIndex") }
end

VDT.CommandRegistry.register("setRotationCrop", {
  requiresVehicle = false,
  parse = parseSlotCrop,
  execute = function(_, params, debugger)
    local pl, cr = resolve(params.rotationIndex, debugger, "setRotationCrop")
    if pl == nil then
      return
    end
    if params.slot == nil or cr.rotations[params.slot] == nil then
      debugger:warn(
        "setRotationCrop: rotation %s has no slot %s",
        tostring(params.rotationIndex),
        tostring(params.slot)
      )
      return
    end
    pl:updateCropSelection(cr, params.slot, params.state)
    debugger:debug(
      "setRotationCrop %s[%s] = %s",
      tostring(params.rotationIndex),
      tostring(params.slot),
      tostring(params.state)
    )
  end,
})

VDT.CommandRegistry.register("setRotationCatchCrop", {
  requiresVehicle = false,
  parse = parseSlotCatchCrop,
  execute = function(_, params, debugger)
    local pl, cr = resolve(params.rotationIndex, debugger, "setRotationCatchCrop")
    if pl == nil then
      return
    end
    if params.slot == nil or cr.rotations[params.slot] == nil then
      debugger:warn(
        "setRotationCatchCrop: rotation %s has no slot %s",
        tostring(params.rotationIndex),
        tostring(params.slot)
      )
      return
    end
    pl:updateCatchCropSelection(cr, params.slot, params.catchCropState)
    debugger:debug(
      "setRotationCatchCrop %s[%s] = %s",
      tostring(params.rotationIndex),
      tostring(params.slot),
      tostring(params.catchCropState)
    )
  end,
})

VDT.CommandRegistry.register("addRotationSlot", {
  requiresVehicle = false,
  parse = parseRotationIndex,
  execute = function(_, params, debugger)
    local pl, cr = resolve(params.rotationIndex, debugger, "addRotationSlot")
    if pl == nil then
      return
    end
    pl:addCropRotationSelection(cr)
    debugger:debug("addRotationSlot %s", tostring(params.rotationIndex))
  end,
})

VDT.CommandRegistry.register("removeRotationSlot", {
  requiresVehicle = false,
  parse = parseRotationIndex,
  execute = function(_, params, debugger)
    local pl, cr = resolve(params.rotationIndex, debugger, "removeRotationSlot")
    if pl == nil then
      return
    end
    -- The mod's own GUI refuses to drop the last slot (a 0-slot plan breaks its yield modulo); mirror
    -- that guard here, since the wrapper itself doesn't.
    if #cr.rotations <= 1 then
      debugger:debug("removeRotationSlot %s: refusing to remove the last slot", tostring(params.rotationIndex))
      return
    end
    pl:removeCropRotationSelection(cr)
    debugger:debug("removeRotationSlot %s", tostring(params.rotationIndex))
  end,
})

VDT.CommandRegistry.register("createRotation", {
  requiresVehicle = false,
  parse = function(xml, key)
    return { name = xml:getString(key .. "#name") or "" }
  end,
  execute = function(_, params, debugger)
    local pl = planner()
    if pl == nil then
      debugger:warn("createRotation: CropRotation not available")
      return
    end
    -- addCropRotation takes the owning farm; use the local player's, matching the in-game planner
    -- (InGameMenuCropRotationPlanner:addEntryCallback).
    local farmId = g_localPlayer ~= nil and g_localPlayer.farmId or nil
    if type(farmId) ~= "number" then
      debugger:warn("createRotation: no local farm to own the rotation")
      return
    end
    pl:addCropRotation(params.name, farmId)
    debugger:debug("createRotation '%s' on farm %s", tostring(params.name), tostring(farmId))
  end,
})

VDT.CommandRegistry.register("deleteRotation", {
  requiresVehicle = false,
  parse = parseRotationIndex,
  execute = function(_, params, debugger)
    local pl, cr = resolve(params.rotationIndex, debugger, "deleteRotation")
    if pl == nil then
      return
    end
    pl:removeCropRotation(cr)
    debugger:debug("deleteRotation %s", tostring(params.rotationIndex))
  end,
})
