-- Optional integration: FS25_CropRotation. An export channel mirroring the mod's saved rotation
-- plans (the planner), like integrations/TaskList.lua mirrors task groups: it self-detects the mod
-- and serializes the local farm's crop-rotation plans into cropRotation.json. **Absence of that file
-- is the app's "not installed" signal** — when the mod isn't present the channel is registered but
-- never writes.
--
-- Unlike TaskList this channel is NOT subscribe-driven. FS25_CropRotation only publishes
-- CROP_ROTATIONS_CHANGED from its *multiplayer* event path, so in singleplayer the cases that matter
-- never fire it: addCropRotation / removeCropRotation take the server branch and mutate
-- `cropRotations` directly, and the per-slot edits (updateCropSelection, ...) mutate the slot in
-- place before sending the event. Subscribing would miss all of that (a new plan only appeared after
-- a save+reload). So we diff a cheap per-tick signature of the planner instead (see tick()); the file
-- is still only written when that signature moves. This also sidesteps the CROP_ROTATIONS_CHANGED id
-- landmine (see farm-page-plan.md) since we never rely on that id.
--
-- Mod-environment isolation (see farm-page-plan.md "Mod-environment isolation"): FS25_CropRotation's
-- `g_cropRotationPlanner` / `g_cropRotation` are globals in *its own* Lua environment, not the shared
-- `_G`, so from our env they're reachable only as `FS25_CropRotation.g_cropRotationPlanner` /
-- `.g_cropRotation` — the bare globals are nil here. (This is exactly what made the earlier probe
-- misread the data as server-only.) `g_localPlayer` is a shared engine global, read directly.
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

-- The yield-bonus percentage the game shows under each slot (e.g. 115%). The planner only stores
-- `slot.yieldValue` while its GUI is open (InGameMenuCropRotationPlanner:updateYieldValues), so we
-- recompute it exactly as the GUI does: build the preceding `numHistory` states (wrapping around the
-- cycle) and run the mod's own YieldCalculator. Calling the mod's method means its body resolves
-- CropRotation.* in the mod's own env for free, and it's pure client-side maths (settings + crop
-- tables loaded on every client) — no density maps or server state. pcall so a mod version change
-- can't throw in the collector; nil then omits the field and the app shows no percentage.
local function yieldPercent(calc, numHistory, rotations, rotationIndex, state, catchCropState)
  if calc == nil then
    return nil
  end
  local count = #rotations
  if count == 0 then
    return nil
  end
  local historyStates = {}
  for i = 1, numHistory do
    local moduloIndex = ((rotationIndex - 1) - i) % count
    historyStates[#historyStates + 1] = rotations[moduloIndex + 1].state
  end
  local ok, multiplier = pcall(calc.getYieldMultiplier, calc, historyStates, state, catchCropState)
  if ok and type(multiplier) == "number" then
    return math.floor(multiplier * 100 + 0.5)
  end
  return nil
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
  local calc, numHistory = nil, 2
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
    calc = cr.yieldCalculator
    numHistory = cr.NUM_HISTORY_MAPS or 2
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
      for i, slot in ipairs(cropRotation.rotations or {}) do
        sequence[#sequence + 1] = {
          state = slot.state,
          crop = cropNames[slot.state] or "",
          catchCropState = slot.catchCropState,
          catchCrop = catchNames[slot.catchCropState] or "",
          yieldPercent = yieldPercent(calc, numHistory, cropRotation.rotations, i, slot.state, slot.catchCropState),
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

-- Allocation-free change signature of the planner. FS25's engine Lua has no bitwise operators, so
-- this is a plain arithmetic rolling hash (kept in double-exact integer range) — enough for change
-- detection, where the only failure mode is a hash collision missing one update, and the data is a
-- handful of plans. pairs() order is stable for an unmodified table, so an unchanged planner hashes
-- the same every tick; any add / remove / in-place slot edit moves the hash.
local function signature(pl)
  local h = 0
  local function mix(v)
    h = (h * 1000003 + (v or 0)) % 2147483647
  end
  mix(pl.nextCropRotationIndex)
  for _, cropRotation in pairs(pl.cropRotations or {}) do
    mix(cropRotation.index)
    mix(cropRotation.farmId)
    for _, slot in ipairs(cropRotation.rotations or {}) do
      mix(slot.state)
      mix(slot.catchCropState)
    end
  end
  return h
end

-- Per-tick change poll (see the header note on why this isn't subscribe-driven). Diffs a cheap
-- signature of the planner and marks the channel dirty when it moves; the first tick has a nil
-- baseline, so it always queues the initial write of whatever is already loaded.
function VDT.CropRotation.tick(debugger)
  if not VDT.CropRotation.isAvailable() then
    return
  end
  local sig = signature(planner())
  if sig ~= VDT.CropRotation.signature then
    if VDT.CropRotation.signature == nil then
      debugger:info("CropRotation integration active (polling planner for changes)")
    end
    VDT.CropRotation.signature = sig
    VDT.ExportChannels.markDirty(VDT.CropRotation.CHANNEL)
  end
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
