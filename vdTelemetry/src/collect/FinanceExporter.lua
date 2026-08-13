-- Finance export channel: the LOCAL farm's books, written to finance.json. Three things, all of them
-- what the in-game finances screen (InGameMenuStatisticsFrame, Finances sub-category) shows:
--
--   * the headline balance + loan block (Farm:getBalance, farm.loan / .loanMax);
--   * the month-by-month table -- one row per FinanceStats.statNames bucket, one column per in-game
--     period. `stats[].values[i]` belongs to `periods[i]`, newest first;
--   * the money notifications the HUD popped, as a running log.
--
-- Reads only base-game state (g_farmManager, FinanceStats), so it lives in collect/, not integrations/.
-- Every engine read is pcall-guarded (fail-soft house rule). Own-farm only: reuses
-- ProductionExporter.ownFarmId so the farm scope matches the other channels. Absence of finance.json
-- means "no data yet / export off", same as the others.
--
-- PERIODS ARE MONTHS. FarmStats.finances is the bucket for the period being played;
-- FarmStats:archiveFinances (off MessageType.PERIOD_CHANGED) pushes it onto financesHistory
-- oldest-first and starts a fresh one. The in-game screen shows the current period plus four
-- (InGameMenuStatisticsFrame.FINANCES.PAST_PERIOD_COUNT); we export up to MAX_PERIODS, because
-- history that exists costs nothing to carry and a trend view will want it. Note only five survive a
-- save -- FarmStats:saveToXMLFile writes the current bucket plus at most four -- so a long session
-- accumulates more than five and a reload drops back to five.
--
-- THE NOTIFICATION LOG comes from a hook on HUD:showMoneyChange, which is the single client-side
-- funnel for the money pop-ups: singleplayer reaches it through FSBaseMission:broadcastNotifications,
-- a multiplayer client through MoneyChangeEvent:run -- and both have already checked the change
-- belongs to the local farm, so the hook is farm-scoped for free. It must be PREPENDED: the original
-- zeroes hud.moneyChanges[moneyType.id] on its way out, so an appended hook would see 0. The log is
-- session-scoped and in memory (the mod persists nothing of its own -- see FUTURE.md).
--
-- MULTIPLAYER. FarmStats is server state and is NOT in Farm:writeStream. A client is sent
-- FinanceStatsEvent indices 0..4 at join (FSBaseMission:sendInitialClientState) and then index 0 only,
-- every ~5 s, when the version counter moved (FSBaseMission:update). So the current column stays fresh
-- by itself but the HISTORY columns go stale after a month rolls over. The in-game screen re-requests
-- them (InGameMenuStatisticsFrame:update); so do we -- but against OUR OWN copy of
-- financesHistoryVersionCounter rather than the game's financesHistoryVersionCounterLocal, because
-- writing that field would make the in-game screen think it was already up to date and skip its own
-- refresh. The cost is a few duplicate requests after a month boundary, which the server just answers.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.FinanceExporter = {}

VDT.FinanceExporter.CHANNEL = "finance"
VDT.FinanceExporter.FILE_NAME = "finance.json"
-- Own version, evolving independently of VDTelemetry.VERSION and the shared Kotlin FinanceData.
VDT.FinanceExporter.VERSION = 1
-- Write cadence in ms. Deliberately unhurried: on a multiplayer client the server refreshes the
-- current period every ~5 s anyway, so exporting faster would re-emit the same numbers.
VDT.FinanceExporter.INTERVAL_MS = 5000

-- Most periods (in-game months) the table carries, current one included.
VDT.FinanceExporter.MAX_PERIODS = 12
-- The in-game screen's column count past the current one -- what the app shows by default, and how
-- many history entries we ask a multiplayer server to resend.
VDT.FinanceExporter.PAST_PERIOD_COUNT = 4
-- Notification log cap. The log is a feed, not an archive; the oldest entry falls off the end.
VDT.FinanceExporter.MAX_HISTORY = 100
-- The in-game borrow/repay granularity (InGameMenuStatisticsFrame.FINANCES.LOAN_STEP). Exported so
-- the app's stepper snaps to the same grid farm.loanMax is snapped to.
VDT.FinanceExporter.LOAN_STEP = 5000

-- How often a multiplayer client re-checks whether the archived periods need refetching.
local HISTORY_REFRESH_MS = 5000

local history = {} -- FinanceEventModel[], newest first, capped at MAX_HISTORY
local seq = 0 -- monotonic notification counter, the log's sort key
local hooked = false -- HUD:showMoneyChange prepended?
local subscribed = false -- messageCenter subscriptions in place?
local historyCounter = nil -- our copy of stats.financesHistoryVersionCounter (see the header)
local refreshTimer = 0

local function num(v)
  return type(v) == "number" and v or 0
end

-- Whole currency units: the game's amounts are floats and no terminal prints cents. floor(x + 0.5)
-- rounds to nearest on both signs (-1250.6 -> -1251, not the -1250 a bare floor would give). An exact
-- half goes UP rather than away from zero (-1250.5 -> -1250) -- deliberately, because this is
-- character-for-character MissionExporter's money() and the two channels round the same kinds of
-- amounts: a half-unit disagreement between two panels would read as a bug, where the half unit a tie
-- costs here does not. Pinned by the spec so it cannot drift silently.
local function money(value)
  return math.floor(num(value) + 0.5)
end

-- g_i18n is checked before it is indexed rather than merely pcall'd: `pcall(g_i18n.getText, ...)`
-- evaluates the field access first, so a nil g_i18n would throw outside the pcall's protection and
-- take the whole channel write with it (same guard as MissionExporter's collectBaleForm).
local function i18n(getter, ...)
  if g_i18n == nil or type(g_i18n[getter]) ~= "function" then
    return nil
  end
  local ok, text = pcall(g_i18n[getter], g_i18n, ...)
  if not ok or type(text) ~= "string" or text == "" then
    return nil
  end
  return text
end

---The local farm object, or nil when there is none (spectator, or the farm manager isn't up).
---@return table|nil farm
function VDT.FinanceExporter.ownFarm()
  local farmId = VDT.ProductionExporter.ownFarmId()
  if farmId == nil or g_farmManager == nil then
    return nil
  end
  local ok, farm = pcall(g_farmManager.getFarmById, g_farmManager, farmId)
  if not ok or type(farm) ~= "table" then
    return nil
  end
  return farm
end

---The stat buckets to export, newest first: the current period followed by financesHistory walked
---backwards (it is stored oldest-first, so the LAST entry is the most recent past period).
---@param stats table a FarmStats
---@return table[] buckets at most MAX_PERIODS of them; buckets[1] is the period being played
function VDT.FinanceExporter.collectBuckets(stats)
  local buckets = {}
  if type(stats) ~= "table" or type(stats.finances) ~= "table" then
    return buckets
  end
  buckets[1] = stats.finances

  local past = type(stats.financesHistory) == "table" and stats.financesHistory or {}
  local count = #past
  for back = 1, VDT.FinanceExporter.MAX_PERIODS - 1 do
    local entry = past[count - back + 1]
    if type(entry) ~= "table" then
      break -- contiguous by construction; a gap means we have run out of history
    end
    buckets[back + 1] = entry
  end
  return buckets
end

---The column headers for those buckets, walking the calendar backwards from the period being played.
---Wrapping below period 1 rolls the year, so a twelve-period export never shows two ambiguous
---"August"s.
---@param count number how many columns
---@param environment table g_currentMission.environment
---@return FinancePeriodModel[] rows without their `total`, which collect() fills in from the stat rows
function VDT.FinanceExporter.collectPeriods(count, environment)
  local periodsInYear = (Environment ~= nil and type(Environment.PERIODS_IN_YEAR) == "number")
      and Environment.PERIODS_IN_YEAR
    or 12
  local period = math.floor(num(environment.currentPeriod))
  local year = ValueMapper.mapYearToCalendarYear(math.floor(num(environment.currentYear)))

  local periods = {}
  for index = 0, count - 1 do
    periods[index + 1] = {
      index = index,
      period = period,
      label = i18n("formatPeriod", period, false) or tostring(period),
      year = year,
      current = index == 0 or nil,
      total = 0,
    }
    period = period - 1
    if period < 1 then
      period = periodsInYear
      year = year - 1
    end
  end
  return periods
end

---One row per FinanceStats bucket name, with a value per column. Every row is exported, including the
---all-zero ones: the mod stays a faithful mirror of statNames, and hiding empty rows is a view choice
---the app can make without a mod round-trip.
---@param buckets table[] from collectBuckets
---@return FinanceStatModel[]
function VDT.FinanceExporter.collectStats(buckets)
  local names = (FinanceStats ~= nil and type(FinanceStats.statNames) == "table") and FinanceStats.statNames or nil
  if names == nil then
    return {}
  end
  local titles = type(FinanceStats.statNamesI18n) == "table" and FinanceStats.statNamesI18n or {}

  local rows = {}
  for _, name in ipairs(names) do
    local title = titles[name]
    if type(title) ~= "string" or title == "" then
      -- statNamesI18n is filled in FinanceStats.new, so this only bites before any farm exists.
      title = i18n("getText", "finance_" .. name) or name
    end
    local values = {}
    for i, bucket in ipairs(buckets) do
      values[i] = money(bucket[name])
    end
    rows[#rows + 1] = { name = name, title = title, values = values }
  end
  return rows
end

---Record one money notification. Called from the HUD hook with the values the pop-up is about to
---show, so the log holds exactly what the player saw -- including the game's own visibility rule,
---which suppresses a change in (-1, 0).
---@param amount number the accumulated change for this money type
---@param moneyType table the MoneyType
---@param text string|nil the label the caller passed; already localized when present (both
---  MoneyChangeEvent and broadcastNotifications run it through g_i18n before the HUD sees it)
---@param environment table|nil g_currentMission.environment, for the timestamp
function VDT.FinanceExporter.record(amount, moneyType, text, environment)
  if type(amount) ~= "number" then
    return
  end
  -- HUD:showMoneyChange's own rule: a positive change shows, a negative one only from -1 down.
  if not (amount > 0 or amount <= -1) then
    return
  end

  -- nil means "the caller had no label of its own"; an empty string means "deliberately unlabelled",
  -- which is why this distinguishes them rather than treating "" as absent.
  local title = text
  if title == nil and type(moneyType) == "table" and type(moneyType.title) == "string" then
    title = i18n("getText", moneyType.title, moneyType.customEnv)
  end

  -- The timestamp is nice to have, the entry is not: string.format throws on a nil field, so an
  -- environment that is not fully up must cost the stamp rather than the transaction.
  local okDate, date = pcall(ValueMapper.formatGameDate, environment)
  local okTime, time = pcall(ValueMapper.formatGameTime, environment)

  seq = seq + 1
  ---@type FinanceEventModel
  local entry = {
    seq = seq,
    amount = money(amount),
    type = (type(moneyType) == "table" and type(moneyType.statistic) == "string") and moneyType.statistic or nil,
    title = (type(title) == "string" and title ~= "") and title or nil,
    date = okDate and date or nil,
    time = okTime and time or nil,
  }

  table.insert(history, 1, entry)
  while #history > VDT.FinanceExporter.MAX_HISTORY do
    table.remove(history)
  end
  VDT.ExportChannels.markDirty(VDT.FinanceExporter.CHANNEL)
end

---The HUD hook itself. Prepended to HUD:showMoneyChange, so it runs while hud.moneyChanges still
---holds the amount the original is about to print and zero. Contained end to end: this executes
---inside the game's notification path, where a throw of ours would swallow the player's pop-up.
---@param hud table the HUD instance (the hooked method's `self`)
---@param moneyType table
---@param text string|nil
function VDT.FinanceExporter.onMoneyChange(hud, moneyType, text)
  pcall(function()
    if type(hud) ~= "table" or type(moneyType) ~= "table" or moneyType.id == nil then
      return
    end
    local changes = hud.moneyChanges
    if type(changes) ~= "table" then
      return
    end
    local environment = g_currentMission ~= nil and g_currentMission.environment or nil
    VDT.FinanceExporter.record(changes[moneyType.id], moneyType, text, environment)
  end)
end

-- Install the HUD hook once, lazily: HUD is a bare global class table, and replacing the method on it
-- reaches every instance (dispatch goes through the class), so this works whenever it runs.
local function installHook(debugger)
  if hooked or HUD == nil or type(HUD.showMoneyChange) ~= "function" or Utils == nil then
    return
  end
  HUD.showMoneyChange = Utils.prependedFunction(HUD.showMoneyChange, VDT.FinanceExporter.onMoneyChange)
  hooked = true
  debugger:debug("Finance channel hooked HUD:showMoneyChange for the notification log")
end

-- Lazy subscribe, the same shape as the missions channel: the MessageType ids exist once the game has
-- loaded, so we wait for them. Deliberately NOT MONEY_CHANGED -- Farm:changeBalance publishes that on
-- any move of 1 unit or more, which includes vehicle running costs ticking while you drive, and
-- marking dirty on it would drag this channel to frame rate through writeDirty's drain. The interval
-- covers the balance; these two are the rare structural changes.
local function subscribe(debugger)
  if subscribed or g_messageCenter == nil then
    return
  end
  if MessageType == nil or MessageType.PERIOD_CHANGED == nil or ChangeLoanEvent == nil then
    return
  end
  -- A new period archives the current bucket, so the whole table shifts a column.
  g_messageCenter:subscribe(MessageType.PERIOD_CHANGED, VDT.FinanceExporter.markDirty, VDT.FinanceExporter)
  -- ChangeLoanEvent:run publishes itself after farm.loan moves, on both sides of the wire -- so a
  -- borrow or repay lands in the panel without waiting out the interval.
  g_messageCenter:subscribe(ChangeLoanEvent, VDT.FinanceExporter.markDirty, VDT.FinanceExporter)
  subscribed = true
  VDT.FinanceExporter.markDirty()
  debugger:debug("Finance channel subscribed to period changes and loan changes")
end

-- Multiplayer only: re-request the archived periods when the server says they moved. See the header
-- for why this keeps its own counter instead of the game's financesHistoryVersionCounterLocal.
local function refreshHistory(farm, debugger, dt)
  if g_currentMission == nil or type(g_currentMission.getIsServer) ~= "function" then
    return
  end
  local okServer, isServer = pcall(g_currentMission.getIsServer, g_currentMission)
  if not okServer or isServer then
    return -- the host reads FarmStats directly; there is nobody to ask
  end

  refreshTimer = refreshTimer + num(dt)
  if refreshTimer < HISTORY_REFRESH_MS then
    return
  end
  refreshTimer = 0

  local stats = farm.stats
  if type(stats) ~= "table" then
    return
  end
  local counter = stats.financesHistoryVersionCounter
  if type(counter) ~= "number" or counter == historyCounter then
    return
  end

  if FinanceStatsEvent == nil or g_client == nil then
    return
  end
  local connection = g_client:getServerConnection()
  if connection == nil then
    return
  end
  -- Claim the counter before sending: a failed send should not spin every 5 s forever, and the next
  -- archive bumps it again anyway.
  historyCounter = counter
  for index = 1, VDT.FinanceExporter.PAST_PERIOD_COUNT do
    pcall(function()
      connection:sendEvent(FinanceStatsEvent.new(index, farm.farmId))
    end)
  end
  debugger:debug("Finance channel re-requested %d archived periods", VDT.FinanceExporter.PAST_PERIOD_COUNT)
end

---Whether this player may borrow/repay: the game's own farmManager right, which is what greys the
---in-game buttons (InGameMenuStatisticsFrame:hasPlayerLoanPermission). The server re-checks it when
---ChangeLoanEvent lands, so this drives the UI, it is not the boundary.
---@return boolean
function VDT.FinanceExporter.canManageLoan()
  if g_currentMission == nil or type(g_currentMission.getHasPlayerPermission) ~= "function" then
    return false
  end
  local ok, allowed = pcall(g_currentMission.getHasPlayerPermission, g_currentMission, "farmManager")
  return ok and allowed == true
end

---Whether the BASE-GAME loan is in play -- which is the question the app actually has, and it has two
---answers to combine.
---
---`Platform.gameplay.hasLoans` is the flag the in-game screen gates its whole loan block on; absent (a
---stub, an older build) reads as yes, which is the PC truth.
---
---And a mod may have replaced the loan system outright. FS25_EnhancedLoanSystem does exactly that, but
---it deactivates the base loan by overwriting the in-game *frame's* permission check -- leaving both
---the platform flag and `getHasPlayerPermission("farmManager")` saying yes. Reading only those would
---leave the terminal offering Borrow/Repay for a system the player no longer has, and `setLoan` would
---quietly create a base-game loan behind the replacement's back (see integrations/EnhancedLoanSystem).
---So the replacement's presence is part of this answer, and the whole loan block goes with it.
---@return boolean
function VDT.FinanceExporter.loansAvailable()
  if VDT.EnhancedLoanSystem ~= nil and VDT.EnhancedLoanSystem.isAvailable() then
    return false
  end
  if Platform == nil or type(Platform.gameplay) ~= "table" or type(Platform.gameplay.hasLoans) ~= "boolean" then
    return true
  end
  return Platform.gameplay.hasLoans
end

function VDT.FinanceExporter.isAvailable()
  return g_currentMission ~= nil and g_farmManager ~= nil and FinanceStats ~= nil
end

---Build the finance model, or nil when the farm manager isn't up yet (skips the write).
---@return FinanceModel|nil
function VDT.FinanceExporter.collect()
  if not VDT.FinanceExporter.isAvailable() then
    return nil
  end
  local farm = VDT.FinanceExporter.ownFarm()
  if farm == nil then
    -- spectator / no owned farm: no books to show, but keep the channel present
    return { version = tostring(VDT.FinanceExporter.VERSION) }
  end

  local loansAvailable = VDT.FinanceExporter.loansAvailable()

  ---@type FinanceModel
  local model = {
    version = tostring(VDT.FinanceExporter.VERSION),
    balance = money(farm.money),
    loansAvailable = loansAvailable,
    canManageLoan = VDT.FinanceExporter.canManageLoan(),
  }

  -- The whole block goes when the base-game loan is not the farm's loan: `farm.loan` reads 0 under a
  -- replacement that has swept it into its own system, and `farm.loanMax` is then an equity figure
  -- that means nothing. Omitting beats publishing numbers whose subject no longer exists.
  if loansAvailable then
    model.loan = money(farm.loan)
    -- Read, never recomputed: Farm:getEquity walks every farmland and placeable on the map, and the
    -- in-game screen reads this cached field too -- so we are exactly as accurate as it is.
    model.loanMax = money(farm.loanMax)
    model.loanStep = VDT.FinanceExporter.LOAN_STEP
    local okInterest, interest = pcall(farm.calculateDailyLoanInterest, farm)
    if okInterest and type(interest) == "number" then
      model.loanInterestPerDay = money(interest)
    end
  end

  -- The replacement loan system, when one is installed. Its presence is what tells the app to render
  -- it instead of the base-game block above -- which is why that block is omitted rather than zeroed
  -- (loansAvailable), and why this carries no "which system" discriminator.
  if VDT.EnhancedLoanSystem ~= nil then
    model.enhancedLoans = VDT.EnhancedLoanSystem.collect(farm.farmId)
  end

  local environment = g_currentMission.environment
  local buckets = VDT.FinanceExporter.collectBuckets(farm.stats)
  if #buckets > 0 and type(environment) == "table" then
    local periods = VDT.FinanceExporter.collectPeriods(#buckets, environment)
    local stats = VDT.FinanceExporter.collectStats(buckets)
    -- Totals from the ROUNDED row values, not from the raw floats, so a column adds up on screen.
    for _, row in ipairs(stats) do
      for i, value in ipairs(row.values) do
        periods[i].total = periods[i].total + value
      end
    end
    model.periods = periods
    -- omit empty arrays (nil, not {}): an empty Lua table encodes as {} which the Kotlin lists reject
    model.stats = #stats > 0 and stats or nil
  end

  model.history = #history > 0 and history or nil
  return model
end

-- MessageCenter invokes callback(target, ...); target is VDT.FinanceExporter, extra args ignored.
function VDT.FinanceExporter.markDirty()
  VDT.ExportChannels.markDirty(VDT.FinanceExporter.CHANNEL)
end

---Per-tick hook: install the HUD hook and the subscriptions once the game is up, and keep a
---multiplayer client's archived periods fresh. Not called at all while the channel is disabled, which
---is why the hook is installed here rather than at source time -- "off" means off.
function VDT.FinanceExporter.tick(debugger, dt)
  if not VDT.FinanceExporter.isAvailable() then
    return
  end
  installHook(debugger)
  subscribe(debugger)
  -- The replacement loan system's borrowing ceiling is expensive enough to cache; it ages here rather
  -- than owning a timer of its own.
  if VDT.EnhancedLoanSystem ~= nil then
    VDT.EnhancedLoanSystem.tick(dt)
  end
  local farm = VDT.FinanceExporter.ownFarm()
  if farm ~= nil then
    refreshHistory(farm, debugger, dt)
  end
end

-- Test seam: drop the session log and the lazily-installed state between spec cases.
function VDT.FinanceExporter.reset()
  history = {}
  seq = 0
  hooked = false
  subscribed = false
  historyCounter = nil
  refreshTimer = 0
end

-- Self-register the channel (see ExportChannels). Both cadences: the interval for the balance and the
-- current period's running totals, the tick for the hook and the subscriptions.
VDT.ExportChannels.register({
  name = VDT.FinanceExporter.CHANNEL,
  fileName = VDT.FinanceExporter.FILE_NAME,
  isAvailable = VDT.FinanceExporter.isAvailable,
  collect = VDT.FinanceExporter.collect,
  intervalMs = VDT.FinanceExporter.INTERVAL_MS,
  tick = VDT.FinanceExporter.tick,
  -- Balance, loans and the whole monthly table are this farm's books (ownFarmId), and this is the
  -- slowest interval of the lot -- a farm switch would show the previous farm's money for a while.
  farmScoped = true,
})
