-- Unit tests for the finance export channel (src/collect/FinanceExporter.lua).
--
-- Run with `busted` from the vdTelemetry/ directory. The collector reuses ProductionExporter's
-- own-farm helper (loaded first; it self-registers a channel, so ExportChannels ahead of it) and reads
-- FS globals (g_farmManager, FinanceStats, g_i18n). We stub just enough to drive collect().
--
-- What is worth pinning down: the period walk-back (which bucket belongs to which month, and the year
-- rolling when it wraps), the row/column alignment, and the notification log's visibility rule --
-- which is copied from HUD:showMoneyChange and is the difference between mirroring what the player saw
-- and inventing entries they never got.

if VDT == nil or VDT.ExportChannels == nil then
  dofile("src/export/ExportChannels.lua")
end
if VDT.ProductionExporter == nil then
  dofile("src/collect/ProductionExporter.lua")
end
if ValueMapper == nil then
  dofile("src/mapper/ValueMapper.lua")
end
if VDT.EnhancedLoanSystem == nil then
  dofile("src/integrations/EnhancedLoanSystem.lua")
end
if VDT.FinanceExporter == nil then
  dofile("src/collect/FinanceExporter.lua")
end
if Json == nil then
  dofile("src/utils/Json.lua")
end

-- A cut-down FinanceStats: three buckets is enough to prove row/column alignment without 33 of them.
local STAT_NAMES = { "harvestIncome", "purchaseFuel", "loanInterest" }

local function makeBucket(values)
  local bucket = {}
  for _, name in ipairs(STAT_NAMES) do
    bucket[name] = values[name] or 0
  end
  return bucket
end

local function makeFarm(over)
  over = over or {}
  return {
    farmId = over.farmId or 1,
    money = over.money or 100000,
    loan = over.loan or 0,
    loanMax = over.loanMax or 500000,
    stats = over.stats,
    calculateDailyLoanInterest = function(self)
      return math.floor(0.04 / 12 * self.loan)
    end,
  }
end

---Install the globals collect() reads. Assigns through _G explicitly: busted insulates each block's
---writes, so a bare `FinanceStats = ...` would land in the sandbox and stay invisible to the module
---under test (the same reason the other collector specs do it this way).
---Returns the farm so a case can mutate it.
local function stubGame(over)
  over = over or {}
  local farm = over.farm or makeFarm()

  _G.FinanceStats = {
    statNames = STAT_NAMES,
    statNamesI18n = {
      harvestIncome = "Harvest income",
      purchaseFuel = "Fuel",
      loanInterest = "Loan interest",
    },
  }
  _G.Platform = { gameplay = { hasLoans = over.hasLoans ~= false } }
  _G.g_farmManager = {
    getFarmById = function(_, id)
      return id == farm.farmId and farm or nil
    end,
  }
  _G.g_localPlayer = { farmId = over.playerFarmId or farm.farmId }
  _G.g_currentMission = {
    environment = over.environment or {
      currentPeriod = 6,
      currentYear = 3,
      currentDayInPeriod = 12,
      currentHour = 14,
      currentMinute = 32,
    },
    getHasPlayerPermission = function(_, permission)
      return permission == "farmManager" and over.canManage ~= false
    end,
    getIsServer = function()
      return over.isServer ~= false
    end,
  }
  _G.g_i18n = {
    formatPeriod = function(_, period)
      return ("month%d"):format(period)
    end,
    getText = function(_, key)
      return key
    end,
  }
  return farm
end

describe("FinanceExporter", function()
  before_each(function()
    VDT.FinanceExporter.reset()
  end)

  after_each(function()
    _G.FinanceStats = nil
    _G.Platform = nil
    _G.g_farmManager = nil
    _G.g_localPlayer = nil
    _G.g_currentMission = nil
    _G.g_i18n = nil
    _G.FS25_EnhancedLoanSystem = nil
  end)

  describe("collectBuckets", function()
    it("puts the period being played first and walks history backwards", function()
      local current = makeBucket({ harvestIncome = 1 })
      local stats = {
        finances = current,
        -- financesHistory is oldest-first, so the LAST entry is the most recent past period
        financesHistory = {
          makeBucket({ harvestIncome = 40 }), -- four back
          makeBucket({ harvestIncome = 30 }),
          makeBucket({ harvestIncome = 20 }),
          makeBucket({ harvestIncome = 10 }), -- one back
        },
      }

      local buckets = VDT.FinanceExporter.collectBuckets(stats)

      assert.equals(5, #buckets)
      assert.equals(current, buckets[1])
      assert.equals(10, buckets[2].harvestIncome)
      assert.equals(40, buckets[5].harvestIncome)
    end)

    it("caps at MAX_PERIODS", function()
      local past = {}
      for i = 1, 30 do
        past[i] = makeBucket({ harvestIncome = i })
      end
      local buckets = VDT.FinanceExporter.collectBuckets({ finances = makeBucket({}), financesHistory = past })

      assert.equals(VDT.FinanceExporter.MAX_PERIODS, #buckets)
    end)

    it("is empty without a stats object", function()
      assert.equals(0, #VDT.FinanceExporter.collectBuckets(nil))
      assert.equals(0, #VDT.FinanceExporter.collectBuckets({}))
    end)
  end)

  describe("collectPeriods", function()
    it("walks the calendar back from the period being played", function()
      stubGame()
      local periods = VDT.FinanceExporter.collectPeriods(3, g_currentMission.environment)

      assert.equals(0, periods[1].index)
      assert.equals(6, periods[1].period)
      assert.is_true(periods[1].current)
      assert.equals("month6", periods[1].label)
      assert.equals(5, periods[2].period)
      assert.is_nil(periods[2].current)
      assert.equals(4, periods[3].period)
    end)

    it("rolls the year when the period wraps below 1", function()
      stubGame({ environment = { currentPeriod = 2, currentYear = 3, currentDayInPeriod = 1 } })
      local periods = VDT.FinanceExporter.collectPeriods(4, g_currentMission.environment)

      local thisYear = ValueMapper.mapYearToCalendarYear(3)
      assert.equals(2, periods[1].period)
      assert.equals(thisYear, periods[1].year)
      assert.equals(1, periods[2].period)
      assert.equals(thisYear, periods[2].year)
      -- wrapped: period 12 of the previous year
      assert.equals(12, periods[3].period)
      assert.equals(thisYear - 1, periods[3].year)
      assert.equals(11, periods[4].period)
      assert.equals(thisYear - 1, periods[4].year)
    end)
  end)

  describe("collectStats", function()
    it("emits one row per stat name, aligned with the buckets", function()
      stubGame()
      local buckets = {
        makeBucket({ harvestIncome = 12000, purchaseFuel = -1250 }),
        makeBucket({ harvestIncome = 45000 }),
      }

      local rows = VDT.FinanceExporter.collectStats(buckets)

      assert.equals(#STAT_NAMES, #rows)
      assert.equals("harvestIncome", rows[1].name)
      assert.equals("Harvest income", rows[1].title)
      assert.same({ 12000, 45000 }, rows[1].values)
      assert.same({ -1250, 0 }, rows[2].values)
    end)

    it("keeps all-zero rows -- hiding them is the app's choice, not the mod's", function()
      stubGame()
      local rows = VDT.FinanceExporter.collectStats({ makeBucket({}) })

      assert.equals(#STAT_NAMES, #rows)
      assert.same({ 0 }, rows[3].values)
    end)

    it("rounds to whole currency units, to nearest on both signs", function()
      stubGame()
      local rows = VDT.FinanceExporter.collectStats({
        makeBucket({ harvestIncome = 1250.6, purchaseFuel = -1250.6 }),
      })

      assert.same({ 1251 }, rows[1].values)
      -- A bare floor would give -1250 here; the +0.5 is what makes the negative side round to nearest.
      assert.same({ -1251 }, rows[2].values)
    end)

    it("breaks an exact half upwards, matching MissionExporter rather than rounding away from zero", function()
      stubGame()
      local rows = VDT.FinanceExporter.collectStats({
        makeBucket({ harvestIncome = 1250.5, purchaseFuel = -1250.5 }),
      })

      assert.same({ 1251 }, rows[1].values)
      -- Not -1251: floor(x + 0.5) ties towards +inf. Asserted so the two channels' money() cannot
      -- drift apart unnoticed -- see the comment on FinanceExporter's money().
      assert.same({ -1250 }, rows[2].values)
    end)
  end)

  describe("record", function()
    local environment =
      { currentPeriod = 6, currentYear = 3, currentDayInPeriod = 12, currentHour = 14, currentMinute = 32 }

    it("stamps an entry with the money type and the in-game clock", function()
      stubGame()
      VDT.FinanceExporter.record(
        -1250,
        { id = 3, statistic = "purchaseFuel", title = "finance_purchaseFuel" },
        nil,
        environment
      )

      local model = VDT.FinanceExporter.collect()
      assert.equals(1, #model.history)
      local entry = model.history[1]
      assert.equals(1, entry.seq)
      assert.equals(-1250, entry.amount)
      assert.equals("purchaseFuel", entry.type)
      assert.equals("finance_purchaseFuel", entry.title) -- our stub g_i18n echoes the key
      assert.equals("12.08.2026", entry.date)
      assert.equals("14:32", entry.time)
    end)

    it("prefers a caller-supplied label, which the game has already localized", function()
      stubGame()
      VDT.FinanceExporter.record(
        500,
        { id = 1, statistic = "other", title = "finance_other" },
        "Sold something",
        environment
      )

      assert.equals("Sold something", VDT.FinanceExporter.collect().history[1].title)
    end)

    it("mirrors the HUD's visibility rule: nothing between -1 and 0", function()
      stubGame()
      local moneyType = { id = 1, statistic = "other" }
      VDT.FinanceExporter.record(-0.5, moneyType, nil, environment)
      VDT.FinanceExporter.record(0, moneyType, nil, environment)
      assert.is_nil(VDT.FinanceExporter.collect().history)

      VDT.FinanceExporter.record(-1, moneyType, nil, environment)
      VDT.FinanceExporter.record(0.4, moneyType, nil, environment)
      assert.equals(2, #VDT.FinanceExporter.collect().history)
    end)

    it("keeps the transaction when the clock isn't up yet, and loses only the stamp", function()
      stubGame()
      VDT.FinanceExporter.record(-1250, { id = 3, statistic = "purchaseFuel" }, nil, nil)
      VDT.FinanceExporter.record(-980, { id = 3, statistic = "purchaseFuel" }, nil, { currentPeriod = 6 })

      local log = VDT.FinanceExporter.collect().history
      assert.equals(2, #log)
      for _, entry in ipairs(log) do
        assert.is_nil(entry.date)
        assert.is_nil(entry.time)
        assert.equals("purchaseFuel", entry.type)
      end
    end)

    it("keeps the newest first and drops the oldest past the cap", function()
      stubGame()
      local moneyType = { id = 1, statistic = "other" }
      for i = 1, VDT.FinanceExporter.MAX_HISTORY + 5 do
        VDT.FinanceExporter.record(i, moneyType, nil, environment)
      end

      local log = VDT.FinanceExporter.collect().history
      assert.equals(VDT.FinanceExporter.MAX_HISTORY, #log)
      assert.equals(VDT.FinanceExporter.MAX_HISTORY + 5, log[1].seq)
      assert.equals(6, log[#log].seq)
    end)
  end)

  describe("onMoneyChange", function()
    it("reads the amount the HUD is about to print and zero", function()
      stubGame()
      local moneyType = { id = 7, statistic = "harvestIncome", title = "finance_harvestIncome" }
      local hud = { moneyChanges = { [7] = 12000 } }

      VDT.FinanceExporter.onMoneyChange(hud, moneyType, nil)

      assert.equals(12000, VDT.FinanceExporter.collect().history[1].amount)
    end)

    it("never throws, whatever it is handed -- it runs inside the game's notification path", function()
      stubGame()
      assert.has_no.errors(function()
        VDT.FinanceExporter.onMoneyChange(nil, nil, nil)
        VDT.FinanceExporter.onMoneyChange({}, { id = 1 }, nil)
        VDT.FinanceExporter.onMoneyChange({ moneyChanges = "not a table" }, { id = 1 }, nil)
      end)
      assert.is_nil(VDT.FinanceExporter.collect().history)
    end)
  end)

  describe("collect", function()
    it("carries the loan block from the farm, without recomputing the ceiling", function()
      stubGame({ farm = makeFarm({ money = 1284310, loan = 300000, loanMax = 750000 }) })

      local model = VDT.FinanceExporter.collect()

      assert.equals("1", model.version)
      assert.equals(1284310, model.balance)
      assert.equals(300000, model.loan)
      assert.equals(750000, model.loanMax)
      assert.equals(5000, model.loanStep)
      assert.equals(1000, model.loanInterestPerDay)
      assert.is_true(model.loansAvailable)
      assert.is_true(model.canManageLoan)
    end)

    it("totals each column from the rounded row values", function()
      stubGame({
        farm = makeFarm({
          stats = {
            finances = makeBucket({ harvestIncome = 12000, purchaseFuel = -1250, loanInterest = -100 }),
            financesHistory = { makeBucket({ harvestIncome = 45000, purchaseFuel = -3100 }) },
          },
        }),
      })

      local model = VDT.FinanceExporter.collect()

      assert.equals(2, #model.periods)
      assert.equals(10650, model.periods[1].total)
      assert.equals(41900, model.periods[2].total)
      assert.equals(#STAT_NAMES, #model.stats)
    end)

    it("reports platform and permission gates", function()
      stubGame({ hasLoans = false, canManage = false })

      local model = VDT.FinanceExporter.collect()

      assert.is_false(model.loansAvailable)
      assert.is_false(model.canManageLoan)
      -- The loan figures go with the block they describe.
      assert.is_nil(model.loan)
      assert.is_nil(model.loanMax)
      assert.is_nil(model.loanStep)
      assert.is_nil(model.loanInterestPerDay)
    end)

    it("drops the base-game loan block when Enhanced Loan System has replaced it", function()
      -- ELS deactivates the base loan by overwriting the in-game frame's permission check, so both the
      -- platform flag and getHasPlayerPermission still say yes -- reading only those would leave the
      -- terminal offering Borrow/Repay for a system the player no longer has.
      stubGame({
        farm = makeFarm({
          loan = 0,
          loanMax = 800000,
          stats = { finances = makeBucket({ harvestIncome = 12000 }), financesHistory = {} },
        }),
      })
      _G.FS25_EnhancedLoanSystem = { g_els_loanManager = { loans = {} } }

      local model = VDT.FinanceExporter.collect()

      assert.is_false(model.loansAvailable)
      assert.is_nil(model.loan)
      assert.is_nil(model.loanMax)
      -- ...and the replacement's own block takes its place -- present, which is what tells the app
      -- which system to render, and empty, because this stub's manager keeps no loans.
      assert.is_table(model.enhancedLoans)
      assert.is_nil(model.enhancedLoans.loans)
      -- The rest of the books are untouched: only the loan block is the replacement's business.
      assert.equals(100000, model.balance)
      assert.is_table(model.stats)
    end)

    it("keeps the channel present but empty for a spectator", function()
      stubGame()
      _G.g_localPlayer.farmId = 0

      local model = VDT.FinanceExporter.collect()

      assert.equals("1", model.version)
      assert.is_nil(model.balance)
      assert.is_nil(model.periods)
    end)

    it("omits the table when the farm has no stats yet", function()
      stubGame({ farm = makeFarm({ stats = nil }) })

      local model = VDT.FinanceExporter.collect()

      assert.is_nil(model.periods)
      assert.is_nil(model.stats)
    end)

    it("returns nil when the farm manager isn't up (skips the write)", function()
      stubGame()
      _G.g_farmManager = nil

      assert.is_nil(VDT.FinanceExporter.collect())
    end)

    it("encodes to json", function()
      stubGame({
        farm = makeFarm({
          stats = { finances = makeBucket({ harvestIncome = 12000 }), financesHistory = {} },
        }),
      })
      VDT.FinanceExporter.record(
        12000,
        { id = 1, statistic = "harvestIncome" },
        "Harvest income",
        g_currentMission.environment
      )

      local encoded = Json.encode(VDT.FinanceExporter.collect(), false)

      assert.is_string(encoded)
      assert.is_truthy(encoded:find('"harvestIncome"', 1, true))
      assert.is_truthy(encoded:find('"loanStep"', 1, true))
    end)
  end)
end)
