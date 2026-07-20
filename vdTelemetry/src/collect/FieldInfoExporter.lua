-- Field-info export channel: the per-field agronomy the game shows in its FELDINFO panel, written to
-- fieldInfo.json for the app's field-info popup (issue #15). Interval-driven, not event-driven like
-- the map channel: the crop state grows over in-game time and changes on till/harvest/sow, so this
-- resamples every REFRESH_MS rather than only on ownership/placeable events. Reads only base-game
-- state, so it lives in collect/, not integrations/ — but it optionally enriches each field with the
-- FS25_CropRotation rows via that integration when it is installed (guarded, fail-soft).
--
-- Everything here mirrors the game's own PlayerHUDUpdater:showFieldInfo path (see the fs25-modding
-- source): for each field we build a fresh FieldState and update() it at the field's centre — the
-- same density-map reads the HUD does at the player's cursor, so no player position is needed. The
-- field's geometry (label, polygon, owner, area) is NOT repeated here; it stays in the near-static
-- map channel (MapExporter.lua) and the app joins the two by field id.
--
-- Fail-soft house rule (as in the integrations): guard every engine read and pcall the per-field
-- collection so one odd field can't take the whole channel — or the telemetry write — down. Absence
-- of fieldInfo.json means "no data yet / export off", handled the same as map.json.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.FieldInfoExporter = {}

VDT.FieldInfoExporter.CHANNEL = "fieldInfo"
VDT.FieldInfoExporter.FILE_NAME = "fieldInfo.json"
-- Own version, evolving independently of VDTelemetry.VERSION and the shared Kotlin FieldInfoData.
VDT.FieldInfoExporter.VERSION = 1

-- Default resample cadence (ms), handed to ExportChannels as this channel's intervalMs — so it's
-- user-configurable + profile-scaled like the other interval channels. Growth advances on the in-game
-- clock (day/period), far slower than the 100 ms telemetry tick, so a periodic re-collect is plenty
-- and cheap (a handful of density-map reads per field); the write runs on the export flush.
VDT.FieldInfoExporter.REFRESH_MS = 30000

---Round to the nearest integer (percentages are shown whole).
---@param value number
---@return number
local function round(value)
  return math.floor(value + 0.5)
end

---Growth-phase token for a fruit type at a growth state, mirroring PlayerHUDUpdater:fieldAddField's
---text ladder (cut / withered / growing / preparable / harvestable). nil when none applies or the
---descriptor is missing. Each predicate is pcall-guarded so a renamed method degrades to "no token".
---@param desc table? fruit-type descriptor (g_fruitTypeManager:getFruitTypeByIndex)
---@param growthState number
---@return string?
function VDT.FieldInfoExporter.growthToken(desc, growthState)
  if desc == nil then
    return nil
  end
  local function is(predicate)
    if type(predicate) ~= "function" then
      return false
    end
    local ok, value = pcall(predicate, desc, growthState)
    return ok and value == true
  end
  if is(desc.getIsCut) then
    return "cut"
  elseif is(desc.getIsWithered) then
    return "withered"
  elseif is(desc.getIsGrowing) then
    return "growing"
  elseif is(desc.getIsPreparable) then
    return "readyToPrepare"
  elseif is(desc.getIsHarvestable) then
    return "readyToHarvest"
  end
  return nil
end

-- The tokens the game treats as "growing" (yield bonus is shown for these), per fieldAddField.
local GROWING_TOKENS = { growing = true, readyToPrepare = true, readyToHarvest = true }

-- FS25_precisionFarming (the Precision Farming mod), keyed by its mod name in the shared
-- g_modIsLoaded table — the exact gate the game's own HUD uses (PlayerHUDUpdater vs.
-- PrecisionFarming's MOD_NAME check). When it's loaded PF swaps the base FELDINFO box for its soil
-- model and, in FieldInfoDisplayExtension, deactivates three of the vanilla lines: yield bonus
-- (fieldInfo_yieldBonus), fertilized (ui_growthMapFertilized) and needs-lime (ui_growthMapNeedsLime).
-- We mirror the base HUD, so with PF installed those three are stale/superseded and must be omitted
-- to match what the player actually sees. We don't (yet) surface PF's own soil data — this only
-- suppresses what PF removes; the remaining lines (crop, growth, weeds, plowing, rolling) are untouched.
local PRECISION_FARMING_MOD = "FS25_precisionFarming"
local function precisionFarmingActive()
  return type(g_modIsLoaded) == "table" and g_modIsLoaded[PRECISION_FARMING_MOD] == true
end

---Fertilized percentage from a field state, or nil when it can't be resolved (spray level / max
---value unreadable). Matches PlayerHUDUpdater:fieldAddField's `sprayLevel / maxSprayLevel`.
---@param state table FieldState
---@return number?
local function sprayLevelPercent(state)
  if type(state.sprayLevel) ~= "number" or state.sprayLevel < 0 then
    return nil
  end
  local fgs = g_currentMission ~= nil and g_currentMission.fieldGroundSystem or nil
  if fgs == nil or type(fgs.getMaxValue) ~= "function" or FieldDensityMap == nil then
    return nil
  end
  local ok, max = pcall(fgs.getMaxValue, fgs, FieldDensityMap.SPRAY_LEVEL)
  if not ok or type(max) ~= "number" or max <= 0 then
    return nil
  end
  return round(state.sprayLevel / max * 100)
end

---Resolved weed-state title, or nil when weeds are off / the field is weed-free / it can't be read.
---Mirrors PlayerHUDUpdater:fieldAddWeed's `weedSystem:getFieldInfoStates()[weedState]`.
---@param state table FieldState
---@return string?
local function weedTitle(state)
  local missionInfo = g_currentMission ~= nil and g_currentMission.missionInfo or nil
  if missionInfo == nil or missionInfo.weedsEnabled ~= true then
    return nil
  end
  local weedState = state.weedState
  if type(weedState) ~= "number" or weedState == 0 then
    return nil
  end
  local weedSystem = g_currentMission.weedSystem
  if weedSystem == nil or type(weedSystem.getFieldInfoStates) ~= "function" then
    return nil
  end
  local ok, states = pcall(weedSystem.getFieldInfoStates, weedSystem)
  if not ok or type(states) ~= "table" then
    return nil
  end
  local title = states[weedState]
  if type(title) == "string" and title ~= "" then
    return title
  end
  return nil
end

---World sample point for a field: its centre (getCenterOfFieldWorldPosition), falling back to the
---posX/posZ fields the engine keeps on it. nil, nil when neither resolves.
---@param field table
---@return number? x, number? z
local function samplePosition(field)
  if type(field.getCenterOfFieldWorldPosition) == "function" then
    local ok, x, z = pcall(field.getCenterOfFieldWorldPosition, field)
    if ok and type(x) == "number" and type(z) == "number" then
      return x, z
    end
  end
  if type(field.posX) == "number" and type(field.posZ) == "number" then
    return field.posX, field.posZ
  end
  return nil, nil
end

---Build one field's entry, or nil to skip it (no id, unresolvable position, or non-field ground).
---@param field table
---@return FieldInfoEntryModel?
local function collectField(field)
  local okId, id = pcall(field.getId, field)
  if not okId or type(id) ~= "number" then
    return nil
  end
  local x, z = samplePosition(field)
  if x == nil then
    return nil
  end

  local state = FieldState.new()
  local okUpdate = pcall(state.update, state, x, z)
  if not okUpdate then
    return nil
  end
  -- Skip anything that isn't farmable ground (the HUD returns early on FieldGroundType.NONE).
  if FieldGroundType ~= nil and state.groundType == FieldGroundType.NONE then
    return nil
  end

  ---@type FieldInfoEntryModel
  local entry = { id = id }

  -- Precision Farming, when installed, hides yield bonus / fertilized / needs-lime from the game's own
  -- panel and shows its soil model instead — so we omit those same three to match (see the note above).
  local pfActive = precisionFarmingActive()

  local fruitIndex = state.fruitTypeIndex
  local unknown = (FruitType ~= nil and FruitType.UNKNOWN) or 0
  local desc = nil
  if fruitIndex ~= nil and fruitIndex ~= unknown and fruitIndex ~= 0 and g_fruitTypeManager ~= nil then
    local okDesc, d = pcall(g_fruitTypeManager.getFruitTypeByIndex, g_fruitTypeManager, fruitIndex)
    if okDesc then
      desc = d
    end
  end
  if desc ~= nil then
    local title = desc.fillType ~= nil and desc.fillType.title or nil
    if type(title) == "string" and title ~= "" then
      entry.crop = title
    end
    entry.growthState = state.growthState
    if type(desc.numGrowthStates) == "number" then
      entry.maxGrowthState = desc.numGrowthStates
    end
    local token = VDT.FieldInfoExporter.growthToken(desc, state.growthState)
    if token ~= nil then
      entry.growth = token
    end
    if
      not pfActive
      and token ~= nil
      and GROWING_TOKENS[token]
      and type(state.getHarvestScaleMultiplier) == "function"
    then
      local okMul, multiplier = pcall(state.getHarvestScaleMultiplier, state)
      if okMul and type(multiplier) == "number" then
        entry.yieldBonusPercent = round((multiplier - 1) * 100)
      end
    end
  end

  if not pfActive then
    entry.sprayLevelPercent = sprayLevelPercent(state)
  end
  entry.weed = weedTitle(state)

  local missionInfo = g_currentMission ~= nil and g_currentMission.missionInfo or nil
  if missionInfo ~= nil then
    if state.plowLevel == 0 and missionInfo.plowingRequiredEnabled == true then
      entry.needsPlowing = true
    end
    if state.limeLevel == 0 and missionInfo.limeRequired == true and not pfActive then
      entry.needsLime = true
    end
  end
  if type(state.rollerLevel) == "number" and state.rollerLevel > 0 then
    entry.needsRolling = true
  end

  -- Optional FS25_CropRotation per-field rows, guarded exactly like the planner channel: only when
  -- the mod is present, and a nil result (mod version drift) just omits the block.
  if VDT.CropRotation ~= nil and VDT.CropRotation.isAvailable ~= nil and VDT.CropRotation.isAvailable() then
    local cropRotation = VDT.CropRotation.collectField(x, z, fruitIndex)
    if cropRotation ~= nil then
      entry.cropRotation = cropRotation
    end
  end

  return entry
end

---Build the field-info model, or nil when the world isn't ready (skips the write).
---@return FieldInfoModel|nil
function VDT.FieldInfoExporter.collect()
  if g_currentMission == nil or g_fieldManager == nil then
    return nil
  end
  if FieldState == nil or type(FieldState.new) ~= "function" then
    return nil
  end

  local fields = {}
  for _, field in ipairs(g_fieldManager.fields or {}) do
    -- Contain each field: FieldState:update reads engine density maps and CropRotation walks a
    -- third-party mod's internals, either of which a game/mod update could break for one field.
    local ok, entry = pcall(collectField, field)
    if ok and entry ~= nil then
      fields[#fields + 1] = entry
    end
  end

  return {
    version = tostring(VDT.FieldInfoExporter.VERSION),
    -- omit an empty array: the Json encoder emits {} for an empty table, so a nil keeps the key
    -- absent and the Kotlin model falls back to emptyList() (see MapExporter.lua).
    fields = #fields > 0 and fields or nil,
  }
end

-- Fields (and FieldState) only exist once the map is loaded; same gate as MapExporter, plus the
-- FieldState class being present (it isn't in a headless/edge context).
function VDT.FieldInfoExporter.isAvailable()
  if g_currentMission == nil then
    return false
  end
  if FieldState == nil or type(FieldState.new) ~= "function" then
    return false
  end
  local fields = g_fieldManager ~= nil and g_fieldManager.fields or nil
  return (fields ~= nil and #fields > 0) or g_currentMission.isMissionStarted == true
end

-- Prompt initial populate. The registry's intervalMs (below) owns the periodic resample now — but its
-- first fire is a whole interval away, too long to wait at 30 s — so this one-shot markDirty fills the
-- channel the moment the field manager is up. Fires once; the registry handles every refresh after.
function VDT.FieldInfoExporter.tick(debugger, _)
  if VDT.FieldInfoExporter.started == true or not VDT.FieldInfoExporter.isAvailable() then
    return
  end
  VDT.FieldInfoExporter.started = true
  debugger:info("FieldInfo channel active")
  VDT.ExportChannels.markDirty(VDT.FieldInfoExporter.CHANNEL)
end

-- Self-register the channel (see ExportChannels). Interval-driven: the registry owns the cadence
-- (REFRESH_MS as the default, then user-configurable + profile-scaled like the other interval
-- channels); the tick above only does the one-shot initial populate.
VDT.ExportChannels.register({
  name = VDT.FieldInfoExporter.CHANNEL,
  fileName = VDT.FieldInfoExporter.FILE_NAME,
  isAvailable = VDT.FieldInfoExporter.isAvailable,
  collect = VDT.FieldInfoExporter.collect,
  intervalMs = VDT.FieldInfoExporter.REFRESH_MS,
  tick = VDT.FieldInfoExporter.tick,
})
