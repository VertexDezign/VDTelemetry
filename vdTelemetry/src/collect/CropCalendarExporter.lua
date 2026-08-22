-- Crop calendar export channel: for every crop the game shows on its map, which of the twelve
-- periods it may be SOWN in and which it may be HARVESTED in, written to cropCalendar.json. This is
-- the game's own Anbaukalender (gui/InGameMenuCalendarFrame), and it reads the same three calls that
-- frame does: getFruitTypes() filtered to shownOnMap, getIsPlantableInPeriod and
-- getIsHarvestableInPeriod against missionInfo.growthMode.
--
-- Base-game state only, so it lives in collect/, not integrations/. NOT farmScoped: growth is world
-- state, and every farm on the server reads the identical calendar.
--
-- Event-driven rather than interval-driven, because almost nothing in here moves. The crop rows are
-- fixed the moment the map is loaded -- a fruit type's growth data comes out of its foliage XML and
-- no gameplay changes it. The only live part is `today`, so the channel subscribes to DAY_CHANGED
-- (the marker steps a day) and PERIOD_LENGTH_CHANGED (the season-length setting is changeable in
-- game, and it changes how far into its period a given day sits). That is a rewrite per in-game day
-- of a file of a couple of kilobytes.
--
-- growthMode rides along because it decides what the periods MEAN: outside GrowthMode.SEASONAL both
-- predicates return true for every period unconditionally, so every crop would draw twelve full bars.
-- Exporting the mode lets the app say why instead of showing data that looks broken.
--
-- Every engine read is pcall-guarded (fail-soft house rule). getIsPlantableInPeriod earns it more
-- than most: it indexes growthDataSeasonal.periods[period] with no nil check of its own, and
-- growthDataSeasonal is only built when the platform supports seasonal growth -- so on a map without
-- it, the call throws rather than returning false.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.CropCalendarExporter = {}

VDT.CropCalendarExporter.CHANNEL = "cropCalendar"
VDT.CropCalendarExporter.FILE_NAME = "cropCalendar.json"
-- Own version, evolving independently of VDTelemetry.VERSION and the shared Kotlin CropCalendarData.
VDT.CropCalendarExporter.VERSION = 1

-- The calendar is always twelve periods; the game hardcodes the same bound in its own frame
-- (`for i = 1, 12`) and in Environment.PERIODS_IN_YEAR.
VDT.CropCalendarExporter.PERIODS = 12

-- How often tick() re-reads the growth mode, in ms. There is NO message to subscribe to for it:
-- GrowthSystem:setGrowthMode writes missionInfo.growthMode, fires its multiplayer SavegameSettingsEvent
-- and logs, and publishes nothing to the message center (MessageType.SETTING_CHANGED covers the
-- client's GameSettings, not the savegame's). So this channel watches the value instead -- and it must
-- watch it, because the mode decides what every period in the file MEANS: outside SEASONAL the game
-- answers "plantable" for all twelve, so a stale file shows the wrong calendar entirely until the next
-- day rolls over. Two seconds is invisible for a menu action and keeps the per-frame path to a
-- counter compare.
VDT.CropCalendarExporter.GROWTH_MODE_POLL_MS = 2000

VDT.CropCalendarExporter.subscribed = false

-- ms accumulated since the last growth-mode check, and the mode the last collect() actually used.
-- Comparing against what was WRITTEN rather than what was last seen makes the watch self-correcting:
-- if a write is skipped or fails, the next poll finds the mismatch still there and queues it again.
local growthModePoll = 0
local lastWrittenGrowthMode = nil

-- GrowthMode ids -> the names we export. The enum lives in the engine's growth/GrowthMode.lua; it is
-- mirrored here rather than read from the global so a missing GrowthMode table degrades to "unknown"
-- instead of throwing.
local GROWTH_MODES = { [1] = "SEASONAL", [2] = "DAILY", [3] = "DISABLED" }

-- SeasonPeriod -> Season, the same three-periods-per-season grouping as the engine's
-- SeasonPeriod.getSeason. Used for the calendar's season band.
local SEASONS = { "SPRING", "SUMMER", "AUTUMN", "WINTER" }

---The season a period belongs to, as an exported name. Periods run 1..12 with three per season.
---@param period number 1..12
---@return string one of SPRING | SUMMER | AUTUMN | WINTER
function VDT.CropCalendarExporter.seasonForPeriod(period)
  return SEASONS[math.floor((period - 1) / 3) + 1] or "SPRING"
end

---The active growth mode's exported name. Reads missionInfo, which is synchronized to multiplayer
---clients; an unreadable or unknown value degrades to "SEASONAL" -- the game's own default, and the
---only mode in which this channel's contents are meaningful, so guessing it keeps the calendar shown
---rather than banner-ing a savegame that is in fact seasonal.
---@return number growthMode the raw engine id, for the period predicates
---@return string name the exported name
function VDT.CropCalendarExporter.growthMode()
  local info = g_currentMission ~= nil and g_currentMission.missionInfo or nil
  local mode = type(info) == "table" and info.growthMode or nil
  if type(mode) ~= "number" or GROWTH_MODES[mode] == nil then
    return 1, "SEASONAL"
  end
  return mode, GROWTH_MODES[mode]
end

---The twelve column headers: number, the game's own localized short label, and the season it sits in.
---
---The label comes from g_i18n:formatPeriod and is NOT derivable app-side from the period number: that
---function shifts the month by hemisphere (environment.daylight.latitude < 0), so on a southern map
---period 1 is September rather than March. Falls back to the period number as a string when i18n
---cannot answer, so the calendar still has twelve labelled columns.
---@return CropCalendarPeriodModel[]
function VDT.CropCalendarExporter.collectPeriods()
  local periods = {}
  for period = 1, VDT.CropCalendarExporter.PERIODS do
    local label
    if g_i18n ~= nil then
      local ok, text = pcall(g_i18n.formatPeriod, g_i18n, period, true)
      if ok and type(text) == "string" and text ~= "" then
        label = text
      end
    end
    periods[#periods + 1] = {
      period = period,
      label = label or tostring(period),
      season = VDT.CropCalendarExporter.seasonForPeriod(period),
    }
  end
  return periods
end

-- The periods a predicate says yes to, ascending. `predicate` is the fruit desc's own
-- getIsPlantableInPeriod / getIsHarvestableInPeriod; a throwing period counts as "no" rather than
-- taking the whole crop down with it.
local function periodsWhere(fruitDesc, predicate, growthMode)
  local periods = {}
  for period = 1, VDT.CropCalendarExporter.PERIODS do
    local ok, allowed = pcall(predicate, fruitDesc, growthMode, period)
    if ok and allowed then
      periods[#periods + 1] = period
    end
  end
  return periods
end

---One crop row, or nil when the fruit has no usable name (a broken mod fruit).
---@param fruitDesc table a FruitTypeDesc
---@param growthMode number the raw engine GrowthMode id
---@return CropCalendarCropModel|nil
function VDT.CropCalendarExporter.collectCrop(fruitDesc, growthMode)
  -- The display name is the fruit's FILL type title, not the fruit type's own name: that is what the
  -- game's frame shows, and it is the localized one ("Weizen", not "WHEAT").
  local name
  local okFill, fillType = pcall(g_fruitTypeManager.getFillTypeByFruitTypeIndex, g_fruitTypeManager, fruitDesc.index)
  if okFill and type(fillType) == "table" and type(fillType.title) == "string" and fillType.title ~= "" then
    name = fillType.title
  end
  if name == nil then
    return nil
  end

  local okCatch, isCatchCrop = pcall(fruitDesc.getIsCatchCrop, fruitDesc)

  local plant = periodsWhere(fruitDesc, fruitDesc.getIsPlantableInPeriod, growthMode)
  local harvest = periodsWhere(fruitDesc, fruitDesc.getIsHarvestableInPeriod, growthMode)

  return {
    id = type(fruitDesc.name) == "string" and fruitDesc.name or name,
    name = name,
    catchCrop = (okCatch and isCatchCrop == true) or nil,
    -- omit empty arrays (nil, not {}): an empty Lua table encodes as {} which the Kotlin lists reject
    plant = #plant > 0 and plant or nil,
    harvest = #harvest > 0 and harvest or nil,
  }
end

---The crop rows, sorted by display name the way the game's own frame sorts them.
---@param growthMode number the raw engine GrowthMode id
---@return CropCalendarCropModel[]
function VDT.CropCalendarExporter.collectCrops(growthMode)
  local crops = {}
  local okTypes, fruitTypes = pcall(g_fruitTypeManager.getFruitTypes, g_fruitTypeManager)
  if not okTypes or type(fruitTypes) ~= "table" then
    return crops
  end
  -- pairs, not ipairs: getFruitTypes returns the manager's own keyed table, which the game also walks
  -- with pairs. Its iteration order is undefined, hence the sort below.
  for _, fruitDesc in pairs(fruitTypes) do
    -- shownOnMap is the game's own filter for this screen: it drops the fruits that are not really
    -- crops you plan around (decorative foliage, and the fruits a map hides).
    if type(fruitDesc) == "table" and fruitDesc.shownOnMap then
      local crop = VDT.CropCalendarExporter.collectCrop(fruitDesc, growthMode)
      if crop ~= nil then
        crops[#crops + 1] = crop
      end
    end
  end
  table.sort(crops, function(a, b)
    return a.name < b.name
  end)
  return crops
end

---Where the year currently stands, for the app's "today" marker.
---@return CropCalendarTodayModel|nil
function VDT.CropCalendarExporter.collectToday()
  local environment = g_currentMission ~= nil and g_currentMission.environment or nil
  if type(environment) ~= "table" or type(environment.currentPeriod) ~= "number" then
    return nil
  end
  return {
    period = environment.currentPeriod,
    dayInPeriod = type(environment.currentDayInPeriod) == "number" and environment.currentDayInPeriod or 1,
    -- daysPerPeriod is what places the marker WITHIN its period; 1 is the game's own minimum, and
    -- also the value that makes the marker sit at the period's start when we cannot read it.
    daysPerPeriod = type(environment.daysPerPeriod) == "number" and math.max(environment.daysPerPeriod, 1) or 1,
    year = type(environment.currentYear) == "number" and environment.currentYear or 1,
  }
end

---Available once the environment AND the fruit type table are actually populated.
---
---The emptiness test is the point of this function. g_fruitTypeManager exists well before it holds
---any fruit, and this channel writes ONCE -- there is no interval behind it to correct a bad first
---write, only a day rollover. Registering as available too early would publish a calendar with no
---crops in it and leave that on disk for an in-game day. Being unavailable at startup instead just
---means the file is cleaned up and written as soon as the map's fruits are loaded, which is the same
---sequence the map channel goes through.
---@return boolean
function VDT.CropCalendarExporter.isAvailable()
  if g_currentMission == nil or g_currentMission.environment == nil or g_fruitTypeManager == nil then
    return false
  end
  local ok, fruitTypes = pcall(g_fruitTypeManager.getFruitTypes, g_fruitTypeManager)
  -- next(), not #: the manager hands back its own keyed table, which the game itself walks with pairs.
  return ok and type(fruitTypes) == "table" and next(fruitTypes) ~= nil
end

---Build the crop calendar model, or nil when the fruit types aren't up yet (skips the write).
---@return CropCalendarModel|nil
function VDT.CropCalendarExporter.collect()
  if not VDT.CropCalendarExporter.isAvailable() then
    return nil
  end

  local growthMode, growthModeName = VDT.CropCalendarExporter.growthMode()
  local crops = VDT.CropCalendarExporter.collectCrops(growthMode)
  -- Remember what this document was built with, so tick()'s watch has something to compare against.
  lastWrittenGrowthMode = growthMode

  return {
    version = tostring(VDT.CropCalendarExporter.VERSION),
    growthMode = growthModeName,
    today = VDT.CropCalendarExporter.collectToday(),
    periods = VDT.CropCalendarExporter.collectPeriods(),
    crops = #crops > 0 and crops or nil,
  }
end

-- Test seam: drop the tick's state -- the one-shot subscribe guard and the growth-mode watch -- so a
-- spec can drive tick() from a known point. Nothing in the mod calls it; the subscription is
-- deliberately never undone (see ExportChannels.subscribeFarmChanges for the same reasoning).
function VDT.CropCalendarExporter.resetWatch()
  VDT.CropCalendarExporter.subscribed = false
  growthModePoll = 0
  lastWrittenGrowthMode = nil
end

-- MessageCenter invokes callback(target, ...); target is VDT.CropCalendarExporter, extras ignored.
function VDT.CropCalendarExporter.markDirty()
  VDT.ExportChannels.markDirty(VDT.CropCalendarExporter.CHANNEL)
end

-- Lazy subscribe: wait until the fruit types are loaded, then watch the two things that move the
-- today marker. The crop rows themselves never change while the growth mode holds, so nothing is
-- subscribed for their sake -- the initial markDirty() writes them once.
--
-- The growth mode is the exception, and it has no message of its own (see GROWTH_MODE_POLL_MS), so
-- this tick also polls it. Unlike the subscribe, that part runs for the life of the session.
---@param debugger GrisuDebug
---@param dt number? frame delta in ms
function VDT.CropCalendarExporter.tick(debugger, dt)
  if not VDT.CropCalendarExporter.isAvailable() then
    return
  end
  if not VDT.CropCalendarExporter.subscribed then
    if MessageType == nil or g_messageCenter == nil then
      return
    end
    for _, message in ipairs({ "DAY_CHANGED", "PERIOD_LENGTH_CHANGED" }) do
      if MessageType[message] ~= nil then
        g_messageCenter:subscribe(MessageType[message], VDT.CropCalendarExporter.markDirty, VDT.CropCalendarExporter)
      end
    end
    VDT.CropCalendarExporter.subscribed = true
    VDT.CropCalendarExporter.markDirty()
    debugger:info("Crop calendar channel active (subscribed to day/period-length changes)")
  end

  growthModePoll = growthModePoll + (type(dt) == "number" and dt or 0)
  if growthModePoll < VDT.CropCalendarExporter.GROWTH_MODE_POLL_MS then
    return
  end
  growthModePoll = 0
  -- nil until the first document is built: there is nothing to compare against before then, and the
  -- initial markDirty above has already queued that write.
  if lastWrittenGrowthMode ~= nil and VDT.CropCalendarExporter.growthMode() ~= lastWrittenGrowthMode then
    VDT.CropCalendarExporter.markDirty()
  end
end

-- Self-register the channel (see ExportChannels). Event-driven: no interval, the subscriptions above
-- do the marking. Deliberately NOT farmScoped -- the calendar is world state, the same for every farm.
VDT.ExportChannels.register({
  name = VDT.CropCalendarExporter.CHANNEL,
  fileName = VDT.CropCalendarExporter.FILE_NAME,
  isAvailable = VDT.CropCalendarExporter.isAvailable,
  collect = VDT.CropCalendarExporter.collect,
  tick = VDT.CropCalendarExporter.tick,
})
