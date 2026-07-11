-- Optional integration: FS25_CropRotation. An event-driven export channel mirroring the mod's
-- saved rotation plans (the planner), the same way integrations/TaskList.lua mirrors task groups:
-- it self-detects the mod, subscribes to its change message, and serializes the current farm's
-- crop-rotation plans into cropRotation.json. **Absence of that file is the app's "not installed"
-- signal** — when the mod isn't present the channel is registered but never writes.
--
-- Mod-environment isolation (see farm-page-plan.md "Mod-environment isolation"): FS25_CropRotation's
-- `g_cropRotationPlanner` / `g_cropRotation` are globals in *its own* Lua environment, not the shared
-- `_G`, so from our env they're reachable only as `FS25_CropRotation.g_cropRotationPlanner` /
-- `.g_cropRotation` — the bare globals are nil here. (This is exactly what made the earlier probe
-- misread the data as server-only.) `MessageType`, `g_messageCenter` and `g_localPlayer` are shared
-- engine globals, so those are read directly.
--
-- Data shape (from the mod's CropRotationPlanner.lua / CropRotation.lua):
--   planner.cropRotations   list of  { name, farmId, index, rotations = [{ state, catchCropState,
--                                                                          yieldValue }] }
--     .rotations is the ordered crop sequence; `state` / `catchCropState` are fruit-type indices
--     (0 = fallow / no catch crop). `yieldValue` is only recomputed while the in-game planner GUI is
--     open (InGameMenuCropRotationPlanner:updateYieldValues), so it's stale/100 otherwise — omitted.
--   g_cropRotation:getPossibleCropStates()       list of { cropIndex, name, ignoreInPlanner? }
--   g_cropRotation:getPossibleCatchCropStates()  list of { cropIndex, name }
--     built once on the client in onLoadMapFinished; we read them to resolve display names inline so
--     the app doesn't need the fruit-type table. Both include the special 0 states with i18n labels.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.CropRotation = {}

VDT.CropRotation.CHANNEL = "cropRotation"
VDT.CropRotation.FILE_NAME = "cropRotation.json"
-- Own version, evolving independently of VDTelemetry.VERSION and the shared Kotlin CropRotationData.
VDT.CropRotation.VERSION = 1

-- The mod's env global (keyed by the exact mod name); nil when the mod isn't installed.
local function env()
  return type(FS25_CropRotation) == "table" and FS25_CropRotation or nil
end

local function planner()
  local e = env()
  return e ~= nil and e.g_cropRotationPlanner or nil
end

function VDT.CropRotation.isAvailable()
  return planner() ~= nil
end

-- Map cropIndex -> display name from a possible-states list; tolerant of a nil/absent catalog.
local function stateNames(list)
  local byIndex = {}
  for _, state in pairs(list or {}) do
    if state.cropIndex ~= nil then
      byIndex[state.cropIndex] = state.name
    end
  end
  return byIndex
end

---Build the cropRotation model, or nil when the mod isn't loaded (skips the write).
---@return table|nil
function VDT.CropRotation.collect()
  local pl = planner()
  if pl == nil then
    return nil
  end

  local cr = env().g_cropRotation
  local cropNames, catchNames = {}, {}
  -- getPossibleCropStates/getPossibleCatchCropStates just return prebuilt tables (no lazy build, so
  -- unlike TaskList's getHusbandries there's no cache to poison); still pcall-guard against a mod
  -- version that renamed them.
  if cr ~= nil then
    local okCrop, cropStates = pcall(cr.getPossibleCropStates, cr)
    if okCrop then
      cropNames = stateNames(cropStates)
    end
    local okCatch, catchStates = pcall(cr.getPossibleCatchCropStates, cr)
    if okCatch then
      catchNames = stateNames(catchStates)
    end
  end

  -- Scope to the local player's farm, matching the in-game planner
  -- (InGameMenuCropRotationPlanner:updateFarmCropRotations); fall back to all if it can't be resolved.
  local farmId = nil
  if g_localPlayer ~= nil and type(g_localPlayer.farmId) == "number" and g_localPlayer.farmId > 0 then
    farmId = g_localPlayer.farmId
  end

  local rotations = {}
  for _, cropRotation in pairs(pl.cropRotations or {}) do
    if farmId == nil or cropRotation.farmId == farmId then
      local sequence = {}
      for _, slot in ipairs(cropRotation.rotations or {}) do
        sequence[#sequence + 1] = {
          state = slot.state,
          crop = cropNames[slot.state] or "",
          catchCropState = slot.catchCropState,
          catchCrop = catchNames[slot.catchCropState] or "",
        }
      end
      rotations[#rotations + 1] = {
        index = cropRotation.index,
        name = cropRotation.name or "",
        farmId = cropRotation.farmId,
        -- omit empty arrays: the Json encoder emits {} for an empty table, so a nil keeps the key
        -- absent and the Kotlin model falls back to emptyList() (see TaskList's tasks).
        sequence = #sequence > 0 and sequence or nil,
      }
    end
  end

  return {
    version = tostring(VDT.CropRotation.VERSION),
    rotations = #rotations > 0 and rotations or nil,
  }
end

-- MessageCenter invokes callback(target, ...); target is VDT.CropRotation and the extra args are
-- ignored (we re-read the planner rather than reading the message payload).
function VDT.CropRotation.markDirty()
  VDT.ExportChannels.markDirty(VDT.CropRotation.CHANNEL)
end

-- Lazy subscribe: CROP_ROTATIONS_CHANGED only exists once the mod has loaded, so we wait for the
-- planner (also the natural "installed?" gate) before subscribing. Once subscribed we queue an
-- initial write so the file reflects the state already present on connect.
--
-- ⚠️ FS25_CropRotation reserves this MessageType id by *counting* rather than nextMessageTypeId(), so
-- another mod could share the id (see farm-page-plan.md landmine). Harmless here: a spurious wake
-- just rewrites the same file from the planner.
function VDT.CropRotation.tick(debugger)
  if VDT.CropRotation.subscribed or not VDT.CropRotation.isAvailable() then
    return
  end
  if MessageType == nil or MessageType.CROP_ROTATIONS_CHANGED == nil then
    return
  end
  g_messageCenter:subscribe(MessageType.CROP_ROTATIONS_CHANGED, VDT.CropRotation.markDirty, VDT.CropRotation)
  VDT.CropRotation.subscribed = true
  VDT.CropRotation.markDirty()
  debugger:info("CropRotation integration active (subscribed to crop rotation updates)")
end

-- Self-register the channel (see ExportChannels). Registered even when the mod isn't installed;
-- isAvailable() then keeps the file from ever being written, so its absence signals "not installed".
VDT.ExportChannels.register({
  name = VDT.CropRotation.CHANNEL,
  fileName = VDT.CropRotation.FILE_NAME,
  isAvailable = VDT.CropRotation.isAvailable,
  collect = VDT.CropRotation.collect,
  tick = VDT.CropRotation.tick,
})
