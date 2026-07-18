-- Optional integration: FS25_CropRotation (ModHub mod_id 347316, by Chissel). An export channel
-- mirroring the mod's saved rotation plans (the planner), like integrations/TaskList.lua mirrors task
-- groups: it self-detects the mod and serializes the local farm's crop-rotation plans into
-- cropRotation.json. **Absence of that file is the app's "not installed" signal** — when the mod isn't
-- present the channel is registered but never writes (and a file left over from a session where it
-- *was* installed is deleted at startup).
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
-- **Written against FS25_CropRotation 1.0.1.0** — everything below reads that mod's *internals*
-- (planner fields, the YieldCalculator), which it is free to rename in any release. So fail soft,
-- never throw: guard every field read and pcall the yield maths, and treat a missing one as "no data"
-- (an empty panel beats a Lua error in the collector, which would take the whole telemetry write down
-- with it). Same contract on the write side (src/command/CropRotationControl.lua).
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

---The mod's planner instance, or nil when it isn't installed. Public because the write side
---(src/command/CropRotationControl.lua) resolves the planner exactly the same way — one definition of
---the mod-environment handle, so the isolation rule above can't drift between read and write.
---@return table|nil
function VDT.CropRotation.planner()
  local e = env()
  return e ~= nil and e.g_cropRotationPlanner or nil
end
local planner = VDT.CropRotation.planner

---The local player's farm, or nil when it can't be resolved (farm 0 is "no farm", not a farm id).
---Shared with the write side, which must not create or mutate plans on farm 0.
---@return number|nil
function VDT.CropRotation.localFarmId()
  if g_localPlayer ~= nil and type(g_localPlayer.farmId) == "number" and g_localPlayer.farmId > 0 then
    return g_localPlayer.farmId
  end
  return nil
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

-- The selectable-crop catalog the app needs to render the write-side dropdowns: { state, name }
-- pairs in the mod's own order. For the main crop we drop `ignoreInPlanner` entries exactly like the
-- planner GUI does (InGameMenuCropRotationPlanner:mapPossibleStateIfNeeded); catch crops keep all
-- (including the 0 "without catch crop" option). `state` is the value the write command sends back
-- (updateCropSelection / updateCatchCropSelection take these indices verbatim).
local function cropOptions(list, skipIgnored)
  local options = {}
  for _, state in ipairs(list or {}) do
    if state.cropIndex ~= nil and not (skipIgnored and state.ignoreInPlanner) then
      options[#options + 1] = { state = state.cropIndex, name = state.name }
    end
  end
  return options
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

-- Per-option yield previews for a slot's dropdowns: for each catalog option, the % the slot would
-- yield if that option were picked, holding the *other* axis at the slot's current value. So the crop
-- dropdown varies the main crop with the catch crop fixed, and the catch dropdown varies the catch
-- crop with the main crop fixed — letting the app show the outcome of each choice inline. Returns a
-- list of { state, yieldPercent }, or nil when there's nothing to compute (no calculator/options).
local function previewYields(calc, numHistory, rotations, rotationIndex, options, varyCatch, fixedState)
  if calc == nil or #options == 0 then
    return nil
  end
  local previews = {}
  for _, option in ipairs(options) do
    local state, catchCropState
    if varyCatch then
      state, catchCropState = fixedState, option.state
    else
      state, catchCropState = option.state, fixedState
    end
    previews[#previews + 1] = {
      state = option.state,
      yieldPercent = yieldPercent(calc, numHistory, rotations, rotationIndex, state, catchCropState),
    }
  end
  return previews
end

---Build the cropRotation model, or nil when the mod isn't loaded (skips the write).
---@return table|nil
function VDT.CropRotation.collect()
  local pl = planner()
  if pl == nil then
    return nil
  end

  local cr = env().g_cropRotation
  local cropStates, catchStates = {}, {}
  local calc, numHistory = nil, 2
  -- getPossibleCropStates/getPossibleCatchCropStates just return prebuilt tables (no lazy build, so
  -- unlike TaskList's getHusbandries there's no cache to poison); still pcall-guard against a mod
  -- version that renamed them.
  if cr ~= nil then
    local okCrop, cs = pcall(cr.getPossibleCropStates, cr)
    if okCrop and type(cs) == "table" then
      cropStates = cs
    end
    local okCatch, ccs = pcall(cr.getPossibleCatchCropStates, cr)
    if okCatch and type(ccs) == "table" then
      catchStates = ccs
    end
    calc = cr.yieldCalculator
    numHistory = cr.NUM_HISTORY_MAPS or 2
  end
  local cropNames = stateNames(cropStates)
  local catchNames = stateNames(catchStates)
  -- The write-side crop catalogs (static after load); the per-slot previews below vary over these
  -- exact option sets so the dropdown %s line up with what a pick would produce.
  local crops = cropOptions(cropStates, true)
  local catchCrops = cropOptions(catchStates, false)

  -- Scope to the local player's farm, matching the in-game planner
  -- (InGameMenuCropRotationPlanner:updateFarmCropRotations); fall back to all if it can't be resolved.
  local farmId = VDT.CropRotation.localFarmId()

  local rotations = {}
  for _, cropRotation in pairs(pl.cropRotations or {}) do
    if farmId == nil or cropRotation.farmId == farmId then
      local sequence = {}
      local slots = cropRotation.rotations or {}
      for i, slot in ipairs(slots) do
        sequence[#sequence + 1] = {
          state = slot.state,
          crop = cropNames[slot.state] or "",
          catchCropState = slot.catchCropState,
          catchCrop = catchNames[slot.catchCropState] or "",
          yieldPercent = yieldPercent(calc, numHistory, slots, i, slot.state, slot.catchCropState),
          -- Inline dropdown previews: crop options vary the main crop (catch fixed), catch options
          -- vary the catch (main fixed).
          cropYields = previewYields(calc, numHistory, slots, i, crops, false, slot.catchCropState),
          catchYields = previewYields(calc, numHistory, slots, i, catchCrops, true, slot.state),
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
    crops = #crops > 0 and crops or nil,
    catchCrops = #catchCrops > 0 and catchCrops or nil,
  }
end

---Per-field FS25_CropRotation rows for the field-info popup (src/collect/FieldInfoExporter.lua),
---read from the mod's density maps at a world position — the same reads its
---PlayerHUDUpdaterExtension does for the in-game HUD: the crop history (last / previous crop), the
---rotation yield, and the catch crop. Returns a FieldCropRotationModel-shaped table, or nil when the
---mod isn't loaded or nothing could be read.
---
---Follows the same fail-soft contract as collect(): resolves the mod's globals through the env handle
---(mod-environment isolation, see the header), pcall-guards every read, and treats a missing internal
---as "no data" so a mod version bump can never throw in the collector (which would take the whole
---telemetry write down). The position is world (x, z); the mod's *AtWorldPos reads take exactly that.
---@param x number world x
---@param z number world z
---@param fruitTypeIndex number? the field's current fruit-type index (for the potential-yield read)
---@return FieldCropRotationModel|nil
function VDT.CropRotation.collectField(x, z, fruitTypeIndex)
  local e = env()
  local cr = e ~= nil and e.g_cropRotation or nil
  if cr == nil then
    return nil
  end

  local result = {}
  local any = false

  -- Crop history: each history state's map resolves the crop name at this position. The list is
  -- ordered, so the first is the last crop and the second the one before it (the HUD's row order).
  local historyStateManager = cr.historyStateManager
  local states = historyStateManager ~= nil and historyStateManager.historyStates or nil
  if type(states) == "table" then
    local ordered = {}
    for _, s in ipairs(states) do
      ordered[#ordered + 1] = s
    end
    local function titleAt(historyState)
      local map = historyState ~= nil and historyState.map or nil
      if map == nil or type(map.getStateTitleAtWorldPos) ~= "function" then
        return nil
      end
      local ok, title = pcall(map.getStateTitleAtWorldPos, map, x, z)
      if ok and type(title) == "string" and title ~= "" then
        return title
      end
      return nil
    end
    local last = titleAt(ordered[1])
    if last ~= nil then
      result.lastCrop = last
      any = true
    end
    local prev = titleAt(ordered[2])
    if prev ~= nil then
      result.prevCrop = prev
      any = true
    end
  end

  -- Rotation yield at this position (the "Fruchtfolgen Ertrag" %), only for a real fruit type.
  local unknown = (FruitType ~= nil and FruitType.UNKNOWN) or 0
  local calc = cr.yieldCalculator
  if
    fruitTypeIndex ~= nil
    and fruitTypeIndex ~= unknown
    and fruitTypeIndex ~= 0
    and calc ~= nil
    and type(calc.potentialYieldAtPosition) == "function"
  then
    local ok, yield = pcall(calc.potentialYieldAtPosition, calc, x, z, fruitTypeIndex)
    if ok and type(yield) == "number" then
      result.yieldPercent = math.floor(yield * 100 + 0.5)
      any = true
    end
  end

  -- Catch crop at this position; no title means "no catch crop" (the app shows "None"). Only fills
  -- the field, never the sole reason to emit the block — a bare field shouldn't grow a rotation panel.
  local catchCropManager = cr.catchCropManager
  local catchCropMap = catchCropManager ~= nil and catchCropManager.catchCropMap or nil
  if
    catchCropMap ~= nil
    and type(catchCropMap.getState) == "function"
    and type(cr.fruitTypeByCatchCropIndex) == "function"
  then
    local ok, catchCropIndex =
      pcall(catchCropMap.getState, catchCropMap, x, z, catchCropMap.firstChannel, catchCropMap.numChannels)
    if ok and catchCropIndex ~= nil then
      local okFruit, fruitType = pcall(cr.fruitTypeByCatchCropIndex, cr, catchCropIndex)
      if okFruit and fruitType ~= nil and fruitType.fillType ~= nil then
        local title = fruitType.fillType.title
        if type(title) == "string" and title ~= "" then
          result.catchCrop = title
        end
      end
    end
  end

  if not any then
    return nil
  end
  return result
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
