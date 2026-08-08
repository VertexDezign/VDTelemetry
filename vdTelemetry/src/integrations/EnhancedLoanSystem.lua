-- Optional integration: FS25_EnhancedLoanSystem (ModHub mod_id 314906, by Chissel). ELS **replaces**
-- the base-game loan outright -- its own words: "the standard game credits will be deactivated. If a
-- farm still has a loan, it will be transferred to the new system" (it sweeps existing base loans into
-- its own at mission start and on farm creation, ELS_main.convertIngameLoans).
--
-- This file is currently only the detector. The full integration -- the annuity loans themselves, and
-- taking / specially redeeming them from the terminal -- is issue #47; this is the seat it will grow
-- from, and it exists ahead of that because of the leak below.
--
-- WHY THE DETECTOR ALONE IS ALREADY LOAD-BEARING. ELS deactivates the base loan by overwriting
-- `InGameMenuStatisticsFrame.hasPlayerLoanPermission` to return false. That is a method on the
-- *in-game frame* -- it does not touch `Platform.gameplay.hasLoans`, and it does not touch
-- `g_currentMission:getHasPlayerPermission("farmManager")`, which is what our finance channel reads.
-- So without this check the terminal keeps offering Borrow/Repay, and `setLoan` still drives
-- ChangeLoanEvent: a base-game loan created behind ELS's back, accruing the base 4% while ELS's own
-- screen shows nothing, until the next mission start sweeps it up. Hence: ELS installed => the
-- base-game loan block is suppressed entirely (see FinanceExporter.loansAvailable).
--
-- **Written against FS25_EnhancedLoanSystem 1.0.0.0** -- everything here reads that mod's *internals*,
-- which it is free to rename in any release. So fail soft, never throw: a missing field means "not
-- installed", because a throw in a collector takes the whole telemetry write down with it.
--
-- Mod-environment isolation: ELS's `g_els_loanManager` is a global in *its own* Lua environment, not
-- the shared `_G`, so from our env it is reachable only as
-- `FS25_EnhancedLoanSystem.g_els_loanManager` -- the bare global is nil here (see CropRotation.lua,
-- where the same trap already bit).
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.EnhancedLoanSystem = {}

-- The mod's env global (keyed by the exact mod name); nil when the mod isn't installed.
local function env()
  return type(FS25_EnhancedLoanSystem) == "table" and FS25_EnhancedLoanSystem or nil
end

---The mod's loan manager, or nil when it isn't installed. Public so the #47 read and write sides
---resolve it identically -- one definition of the mod-environment handle, so the isolation rule above
---cannot drift between them.
---@return table|nil
function VDT.EnhancedLoanSystem.loanManager()
  local e = env()
  return e ~= nil and e.g_els_loanManager or nil
end

---Whether ELS is installed and up. When it is, the base-game loan is not the farm's loan any more.
---@return boolean
function VDT.EnhancedLoanSystem.isAvailable()
  return VDT.EnhancedLoanSystem.loanManager() ~= nil
end

---The mod's ELS_loan class, for building a new loan on the write side.
---@return table|nil
function VDT.EnhancedLoanSystem.loanClass()
  local e = env()
  return e ~= nil and e.ELS_loan or nil
end

---The manager's settings object (interest rate, durations, mortgage ratios). Replicated to clients as
---an Object, so a client reads live values.
---@return table|nil
function VDT.EnhancedLoanSystem.properties()
  local manager = VDT.EnhancedLoanSystem.loanManager()
  return manager ~= nil and manager.loanManagerProperties or nil
end

---Whether this player may take or redeem a loan. ELS gates its own buttons on MANAGE_RIGHTS -- NOT
---the `farmManager` right the base-game loan uses -- so this deliberately asks a different question
---from FinanceExporter.canManageLoan. Drives the UI; the mod re-checks at execution time.
---@return boolean
function VDT.EnhancedLoanSystem.canManage()
  if g_currentMission == nil or type(g_currentMission.getHasPlayerPermission) ~= "function" then
    return false
  end
  local permission = (Farm ~= nil and Farm.PERMISSION ~= nil) and Farm.PERMISSION.MANAGE_RIGHTS or "manageRights"
  local ok, allowed = pcall(g_currentMission.getHasPlayerPermission, g_currentMission, permission)
  return ok and allowed == true
end

local function num(v)
  return type(v) == "number" and v or 0
end

-- Whole currency units, matching the finance channel's rounding.
local function money(value)
  return math.floor(num(value) + 0.5)
end

-- What the farm could still borrow. Cached behind its own throttle because it is EXPENSIVE: the
-- manager walks every vehicle on the map calling getSellPrice(), plus every farmland -- the same
-- reason the base-game block reads farm.loanMax instead of recomputing equity. The in-game screen
-- only asks for it when you press "take loan"; the terminal has to publish it continuously, so it
-- asks far less often than the channel writes. A stale ceiling is harmless: it only bounds the app's
-- input, and the write side recomputes it fresh before acting on anything.
local MAX_AMOUNT_INTERVAL_MS = 30000
local maxAmountCache, maxAmountAgeMs = nil, MAX_AMOUNT_INTERVAL_MS

---The farm's borrowing ceiling, recomputed at most every MAX_AMOUNT_INTERVAL_MS. Pass force = true to
---bypass the cache (the write side does, so a command never clamps against a stale number).
---@param farmId number
---@param force boolean|nil
---@return number|nil
function VDT.EnhancedLoanSystem.maxAmount(farmId, force)
  local manager = VDT.EnhancedLoanSystem.loanManager()
  if manager == nil or type(manager.maxLoanAmountForFarm) ~= "function" or farmId == nil then
    return nil
  end
  if not force and maxAmountCache ~= nil and maxAmountAgeMs < MAX_AMOUNT_INTERVAL_MS then
    return maxAmountCache
  end
  local ok, amount = pcall(manager.maxLoanAmountForFarm, manager, farmId)
  if not ok or type(amount) ~= "number" then
    return maxAmountCache -- keep the last good figure rather than dropping the key
  end
  -- ELS's own dialog floors it at zero; a farm deeper in debt than it is worth gets a negative here.
  local value = money(math.max(amount, 0))
  if not force then
    maxAmountCache, maxAmountAgeMs = value, 0
  end
  return value
end

---Age the ceiling cache. Driven from the finance channel's tick so this file owns no timer of its own.
---@param dt number|nil frame delta in ms
function VDT.EnhancedLoanSystem.tick(dt)
  maxAmountAgeMs = maxAmountAgeMs + num(dt)
end

---One loan. `id` is the network object id -- ELS_loan is a replicated Object, so that handle exists
---and agrees on both sides of the wire (the same reasoning as the missions channel, and here it is
---doubly right: object replication is literally what carries these). A loan the network has not
---registered yet has no id and is skipped rather than exported unaddressable.
---@param loan table an ELS_loan
---@return EnhancedLoanModel|nil
function VDT.EnhancedLoanSystem.collectLoan(loan)
  if type(loan) ~= "table" then
    return nil
  end
  local okId, id = pcall(NetworkUtil.getObjectId, loan)
  if not okId or type(id) ~= "number" then
    return nil
  end

  ---@type EnhancedLoanModel
  local model = {
    id = id,
    amount = money(loan.amount),
    restAmount = money(loan.restAmount),
    -- Percent per year, as the mod stores and displays it (3.5 means 3.5%).
    interest = math.floor(num(loan.interest) * 100 + 0.5) / 100,
    -- The mod keeps the term in YEARS but counts down in MONTHS; both are carried rather than making
    -- the app guess which unit it is looking at.
    durationYears = math.floor(num(loan.duration)),
    restMonths = math.floor(num(loan.restDuration)),
    paidOff = loan.paidOff == true or nil,
    specialRedemptionDone = loan.specialRedemptionDone == true or nil,
  }

  -- The three figures the mod computes rather than stores. Each is pcall'd on its own: the annuity
  -- maths divides by (1+r)^n - 1, which is a division by zero at a zero interest rate, and
  -- calculateTotalAmount loops until the balance clears -- so a malformed loan must cost its own
  -- derived numbers, not the channel.
  local okRate, rate = pcall(loan.calculateAnnuity, loan)
  if okRate and type(rate) == "number" and rate == rate then
    model.monthlyRate = money(rate)
  end
  local okInterest, interestPortion = pcall(loan.calculateInterestPortion, loan)
  if okInterest and type(interestPortion) == "number" and interestPortion == interestPortion then
    model.monthlyInterest = money(interestPortion)
  end
  if not model.paidOff then
    local okTotal, total = pcall(loan.calculateTotalAmount, loan)
    if okTotal and type(total) == "number" and total == total then
      model.totalCost = money(total)
    end
  end

  return model
end

---The whole ELS block, or nil when the mod isn't installed (the key stays out of the JSON, which is
---what tells the app to render the base-game loan instead -- dispatch on presence, not on a type
---field).
---@param farmId number|nil the local farm
---@return EnhancedLoansModel|nil
function VDT.EnhancedLoanSystem.collect(farmId)
  local manager = VDT.EnhancedLoanSystem.loanManager()
  if manager == nil or farmId == nil then
    return nil
  end
  local props = VDT.EnhancedLoanSystem.properties()

  ---@type EnhancedLoansModel
  local model = {
    canManage = VDT.EnhancedLoanSystem.canManage(),
    maxAmount = VDT.EnhancedLoanSystem.maxAmount(farmId),
  }

  if type(props) == "table" then
    model.interest = math.floor(num(props.loanInterest) * 100 + 0.5) / 100
    model.dynamicInterest = props.dynamicLoanInterest == true or nil
    model.maxDurationYears = math.floor(num(props.maxLoanDuration))
    model.multipleRedemptions = props.multipleSpecialRedemptionsAllowed == true or nil
    -- The fraction of a loan's ORIGINAL amount one special redemption may clear. ELS applies this cap
    -- only while multiple redemptions are disallowed (see ELS's own dialog); the app needs both facts
    -- to say what a payment is limited to.
    model.redemptionFraction = math.floor(num(props.specialRedemptionPercentageForAnnuityLoans) * 10000 + 0.5) / 10000
  end

  -- Both lists, in one array flagged by `paidOff`: the in-game screen keeps them apart behind a
  -- toggle, which is a view choice the app can make for itself. Sorted by id -- creation order, and
  -- stable, where the manager's own pairs() walk is not.
  local loans = {}
  for _, list in ipairs({ manager.currentLoans, manager.paidOffLoans }) do
    local okList, rows = pcall(list, manager, farmId)
    if okList and type(rows) == "table" then
      for _, loan in ipairs(rows) do
        loans[#loans + 1] = VDT.EnhancedLoanSystem.collectLoan(loan)
      end
    end
  end
  table.sort(loans, function(a, b)
    return a.id < b.id
  end)

  -- omit empty arrays (nil, not {}): an empty Lua table encodes as {} which the Kotlin lists reject
  model.loans = #loans > 0 and loans or nil
  return model
end
