-- Unit tests for src/command/EnhancedLoanControl.lua (take / specially redeem an ELS annuity loan).
--
-- Run with `busted` from the vdTelemetry/ directory. Load order mirrors VDTelemetry.lua: the control
-- takes its mod handle from the EnhancedLoanSystem integration and its farm from FinanceExporter, and
-- self-registers into CommandRegistry. We stub the mod's manager and capture what it was asked to do.
--
-- What is worth pinning down is the clamping, because ELS's own addLoan clamps NOTHING -- the mod does
-- it in its dialog's text input, which a terminal never goes through. Everything the dialog would have
-- prevented has to be prevented here instead.

if VDT == nil or VDT.CommandRegistry == nil then
  dofile("src/command/CommandRegistry.lua")
end
if VDT.ExportChannels == nil then
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
dofile("src/command/EnhancedLoanControl.lua")

local warnings, debugMessages
local debugger = {
  warn = function(_, fmt, ...)
    warnings[#warnings + 1] = string.format(fmt, ...)
  end,
  error = function(_, fmt, ...)
    warnings[#warnings + 1] = string.format(fmt, ...)
  end,
  debug = function(_, fmt, ...)
    debugMessages[#debugMessages + 1] = string.format(fmt, ...)
  end,
}

local added -- the loan handed to addLoan
local redeemed -- { loan, amount } handed to specialRedemptionPayment

local function makeLoan(over)
  over = over or {}
  return {
    objectId = over.objectId or 42,
    amount = over.amount or 200000,
    restAmount = over.restAmount or 184320,
    specialRedemptionDone = over.specialRedemptionDone or false,
  }
end

local function stubGame(over)
  over = over or {}
  local farm = { farmId = 1, money = over.money or 100000, loan = 0, loanMax = 0 }

  _G.NetworkUtil = {
    getObjectId = function(loan)
      return loan.objectId
    end,
  }
  _G.Farm = { PERMISSION = { MANAGE_RIGHTS = "manageRights" } }
  _G.g_farmManager = {
    getFarmById = function(_, id)
      return id == farm.farmId and farm or nil
    end,
  }
  _G.g_localPlayer = { farmId = over.spectator and 0 or 1 }
  _G.g_currentMission = {
    getHasPlayerPermission = function(_, permission)
      return permission == "manageRights" and over.canManage ~= false
    end,
    getIsServer = function()
      return true
    end,
    getIsClient = function()
      return true
    end,
  }

  local manager = {
    loanManagerProperties = {
      loanInterest = 3.5,
      maxLoanDuration = 20,
      multipleSpecialRedemptionsAllowed = over.multipleRedemptions or false,
      specialRedemptionPercentageForAnnuityLoans = 0.05,
    },
  }
  function manager:currentLoans()
    return over.loans or {}
  end
  function manager:paidOffLoans()
    return {}
  end
  function manager:maxLoanAmountForFarm()
    return over.maxAmount or 500000
  end
  function manager:addLoan(loan)
    added = loan
  end
  function manager:specialRedemptionPayment(loan, amount)
    redeemed = { loan = loan, amount = amount }
  end

  local loanClass = {
    new = function()
      local loan = {}
      function loan:init(farmId, amount, interest, duration)
        self.farmId, self.amount, self.interest, self.duration = farmId, amount, interest, duration
      end
      return loan
    end,
  }

  _G.FS25_EnhancedLoanSystem = { g_els_loanManager = manager, ELS_loan = loanClass }
  return farm
end

local function run(type_, params)
  VDT.CommandRegistry.get(type_).execute(nil, params, debugger)
end

describe("EnhancedLoanControl", function()
  before_each(function()
    warnings, debugMessages, added, redeemed = {}, {}, nil, nil
    VDT.EnhancedLoanSystem.tick(60000) -- age the ceiling cache
  end)

  after_each(function()
    _G.FS25_EnhancedLoanSystem = nil
    _G.NetworkUtil = nil
    _G.Farm = nil
    _G.g_farmManager = nil
    _G.g_localPlayer = nil
    _G.g_currentMission = nil
  end)

  describe("takeLoan", function()
    it("is registered and needs no vehicle", function()
      assert.is_false(VDT.CommandRegistry.get("takeLoan").requiresVehicle)
    end)

    it("builds the loan with the bank's current rate and the farm's id", function()
      stubGame()

      run("takeLoan", { amount = 150000, durationYears = 10 })

      assert.is_table(added)
      assert.equals(1, added.farmId)
      assert.equals(150000, added.amount)
      assert.equals(3.5, added.interest)
      assert.equals(10, added.duration)
    end)

    it("clamps to the ceiling rather than refusing -- ELS's addLoan clamps nothing itself", function()
      stubGame({ maxAmount = 80000 })

      run("takeLoan", { amount = 500000, durationYears = 5 })

      assert.equals(80000, added.amount)
    end)

    it("clamps the term to the longest the bank offers", function()
      stubGame()

      run("takeLoan", { amount = 10000, durationYears = 99 })

      assert.equals(20, added.duration)
    end)

    it("refuses when the bank will not lend anything", function()
      stubGame({ maxAmount = 0 })

      run("takeLoan", { amount = 10000, durationYears = 5 })

      assert.is_nil(added)
      assert.is_truthy(warnings[1]:find("will not lend", 1, true))
    end)

    it("refuses a missing, zero or negative amount, and a missing term", function()
      for _, params in ipairs({
        { amount = nil, durationYears = 5 },
        { amount = 0, durationYears = 5 },
        { amount = -5000, durationYears = 5 },
        { amount = 10000, durationYears = nil },
        { amount = 10000, durationYears = 0 },
      }) do
        stubGame()
        warnings, added = {}, nil

        run("takeLoan", params)

        assert.is_nil(added)
        assert.equals(1, #warnings)
      end
    end)

    it("refuses without MANAGE_RIGHTS", function()
      stubGame({ canManage = false })

      run("takeLoan", { amount = 10000, durationYears = 5 })

      assert.is_nil(added)
      assert.is_truthy(warnings[1]:find("may not manage", 1, true))
    end)

    it("refuses when the mod is not installed", function()
      stubGame()
      _G.FS25_EnhancedLoanSystem = nil

      run("takeLoan", { amount = 10000, durationYears = 5 })

      assert.is_nil(added)
      assert.is_truthy(warnings[1]:find("not installed", 1, true))
    end)

    it("refuses for a spectator with no farm", function()
      stubGame({ spectator = true })

      run("takeLoan", { amount = 10000, durationYears = 5 })

      assert.is_nil(added)
      assert.is_truthy(warnings[1]:find("no local farm", 1, true))
    end)
  end)

  describe("repayLoan", function()
    it("pays the requested amount against the addressed loan", function()
      local loan = makeLoan({ objectId = 7, amount = 200000, restAmount = 180000 })
      stubGame({ loans = { loan }, money = 100000, multipleRedemptions = true })

      run("repayLoan", { loanId = 7, amount = 25000 })

      assert.equals(loan, redeemed.loan)
      assert.equals(25000, redeemed.amount)
    end)

    it("clamps to the farm's money", function()
      stubGame({ loans = { makeLoan() }, money = 4200, multipleRedemptions = true })

      run("repayLoan", { loanId = 42, amount = 25000 })

      assert.equals(4200, redeemed.amount)
    end)

    it("applies the percentage cap only while multiple redemptions are disallowed", function()
      -- 5% of the ORIGINAL 200000 = 10000, even though the farm could afford far more.
      stubGame({ loans = { makeLoan() }, money = 100000 })
      run("repayLoan", { loanId = 42, amount = 90000 })
      assert.equals(10000, redeemed.amount)

      redeemed = nil
      stubGame({ loans = { makeLoan() }, money = 100000, multipleRedemptions = true })
      run("repayLoan", { loanId = 42, amount = 90000 })
      assert.equals(90000, redeemed.amount)
    end)

    it("never pays more than is outstanding", function()
      stubGame({ loans = { makeLoan({ restAmount = 3000 }) }, money = 100000, multipleRedemptions = true })

      run("repayLoan", { loanId = 42, amount = 90000 })

      assert.equals(3000, redeemed.amount)
    end)

    it("refuses a second redemption in a year the server allows only one", function()
      stubGame({ loans = { makeLoan({ specialRedemptionDone = true }) }, money = 100000 })

      run("repayLoan", { loanId = 42, amount = 5000 })

      assert.is_nil(redeemed)
      assert.is_truthy(warnings[1]:find("already had its special redemption", 1, true))
    end)

    it("allows one when the server permits several", function()
      stubGame({
        loans = { makeLoan({ specialRedemptionDone = true }) },
        money = 100000,
        multipleRedemptions = true,
      })

      run("repayLoan", { loanId = 42, amount = 5000 })

      assert.equals(5000, redeemed.amount)
    end)

    it("refuses a loan id that is gone -- it may have cleared since the last write", function()
      stubGame({ loans = { makeLoan({ objectId = 7 }) } })

      run("repayLoan", { loanId = 999, amount = 5000 })

      assert.is_nil(redeemed)
      assert.is_truthy(warnings[1]:find("no running loan", 1, true))
    end)

    it("refuses when the farm has no money at all", function()
      stubGame({ loans = { makeLoan() }, money = 0, multipleRedemptions = true })

      run("repayLoan", { loanId = 42, amount = 5000 })

      assert.is_nil(redeemed)
      assert.is_truthy(warnings[1]:find("nothing can be paid off", 1, true))
    end)
  end)
end)
