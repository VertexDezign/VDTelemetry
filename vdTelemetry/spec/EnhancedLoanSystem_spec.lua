-- Unit tests for the FS25_EnhancedLoanSystem integration (src/integrations/EnhancedLoanSystem.lua).
--
-- Run with `busted` from the vdTelemetry/ directory. Everything here reads that mod's internals
-- through its own env table (FS25_EnhancedLoanSystem.*), so the stubs mirror that shape rather than
-- planting bare globals -- which is also the trap the integration exists to avoid.
--
-- What is worth pinning down: that the block's PRESENCE is the only signal (there is no discriminator
-- to get wrong), that the expensive borrowing ceiling really is cached, and that a loan with no
-- network id is dropped rather than exported unaddressable.

if VDT == nil or VDT.EnhancedLoanSystem == nil then
  dofile("src/integrations/EnhancedLoanSystem.lua")
end

local function makeLoan(over)
  over = over or {}
  local loan = {
    -- Only a registered loan has one; the nil case is a real state the collector must drop.
    objectId = over.objectId,
    amount = over.amount or 200000,
    restAmount = over.restAmount or 184320,
    interest = over.interest or 3.5,
    duration = over.duration or 20,
    restDuration = over.restDuration or 221,
    paidOff = over.paidOff or false,
    specialRedemptionDone = over.specialRedemptionDone or false,
  }
  function loan:calculateAnnuity()
    return over.annuity or 1159.4
  end
  function loan:calculateInterestPortion()
    return over.interestPortion or 537.6
  end
  function loan:calculateTotalAmount()
    return over.total or 278160.2
  end
  return loan
end

---Install the mod env plus the engine globals the integration reads.
local function stubMod(over)
  over = over or {}
  local current = over.current or {}
  local paid = over.paid or {}

  _G.NetworkUtil = {
    getObjectId = function(loan)
      if loan.objectId == nil then
        error("not registered")
      end
      return loan.objectId
    end,
  }
  _G.Farm = { PERMISSION = { MANAGE_RIGHTS = "manageRights" } }
  _G.g_currentMission = {
    getHasPlayerPermission = function(_, permission)
      return permission == "manageRights" and over.canManage ~= false
    end,
  }

  local manager = {
    loanManagerProperties = over.props ~= false and {
      loanInterest = 3.5,
      dynamicLoanInterest = true,
      maxLoanDuration = 20,
      multipleSpecialRedemptionsAllowed = false,
      specialRedemptionPercentageForAnnuityLoans = 0.05,
    } or nil,
    maxAmountCalls = 0,
  }
  function manager:currentLoans()
    return current
  end
  function manager:paidOffLoans()
    return paid
  end
  function manager:maxLoanAmountForFarm()
    self.maxAmountCalls = self.maxAmountCalls + 1
    return over.maxAmount or 486200.4
  end

  _G.FS25_EnhancedLoanSystem = { g_els_loanManager = manager, ELS_loan = { new = function() end } }
  return manager
end

describe("EnhancedLoanSystem", function()
  before_each(function()
    -- Age the ceiling cache past its throttle so each case starts from a cold read.
    VDT.EnhancedLoanSystem.tick(60000)
  end)

  after_each(function()
    _G.FS25_EnhancedLoanSystem = nil
    _G.NetworkUtil = nil
    _G.Farm = nil
    _G.g_currentMission = nil
  end)

  describe("isAvailable", function()
    it("is false when the mod is not installed", function()
      assert.is_false(VDT.EnhancedLoanSystem.isAvailable())
      assert.is_nil(VDT.EnhancedLoanSystem.collect(1))
    end)

    it("reads the manager out of the mod's own env, not the bare global", function()
      stubMod()
      assert.is_true(VDT.EnhancedLoanSystem.isAvailable())

      -- A bare global must not be enough: that is precisely the isolation trap.
      _G.FS25_EnhancedLoanSystem = nil
      _G.g_els_loanManager = { loans = {} }
      assert.is_false(VDT.EnhancedLoanSystem.isAvailable())
      _G.g_els_loanManager = nil
    end)
  end)

  describe("collectLoan", function()
    it("carries the stored fields and the mod's derived figures", function()
      stubMod()
      local model = VDT.EnhancedLoanSystem.collectLoan(makeLoan({ objectId = 42 }))

      assert.equals(42, model.id)
      assert.equals(200000, model.amount)
      assert.equals(184320, model.restAmount)
      assert.equals(3.5, model.interest)
      -- The term is stored in years but counted down in months; both are carried.
      assert.equals(20, model.durationYears)
      assert.equals(221, model.restMonths)
      assert.equals(1159, model.monthlyRate)
      assert.equals(538, model.monthlyInterest)
      assert.equals(278160, model.totalCost)
      assert.is_nil(model.paidOff)
    end)

    it("drops a loan the network has not registered -- it could not be addressed", function()
      stubMod()
      assert.is_nil(VDT.EnhancedLoanSystem.collectLoan(makeLoan({})))
    end)

    it("omits the total cost of a loan that is already paid off", function()
      stubMod()
      local model = VDT.EnhancedLoanSystem.collectLoan(makeLoan({ objectId = 7, paidOff = true }))

      assert.is_true(model.paidOff)
      assert.is_nil(model.totalCost)
    end)

    it("survives a loan whose derived maths throws", function()
      stubMod()
      local loan = makeLoan({ objectId = 9 })
      loan.calculateAnnuity = function()
        error("division by zero")
      end

      local model = VDT.EnhancedLoanSystem.collectLoan(loan)

      assert.equals(9, model.id)
      assert.is_nil(model.monthlyRate)
      -- The figures that did compute are still there.
      assert.equals(538, model.monthlyInterest)
    end)
  end)

  describe("collect", function()
    it("carries the bank's terms and both loan lists, sorted by id", function()
      stubMod({
        current = { makeLoan({ objectId = 8 }), makeLoan({ objectId = 3 }) },
        paid = { makeLoan({ objectId = 1, paidOff = true }) },
      })

      local model = VDT.EnhancedLoanSystem.collect(1)

      assert.equals(3.5, model.interest)
      assert.is_true(model.dynamicInterest)
      assert.equals(20, model.maxDurationYears)
      assert.is_nil(model.multipleRedemptions) -- false is omitted
      assert.equals(0.05, model.redemptionFraction)
      assert.is_true(model.canManage)
      assert.equals(486200, model.maxAmount)

      assert.equals(3, #model.loans)
      assert.same({ 1, 3, 8 }, { model.loans[1].id, model.loans[2].id, model.loans[3].id })
      assert.is_true(model.loans[1].paidOff)
    end)

    it("reports the permission gate ELS actually uses (MANAGE_RIGHTS, not farmManager)", function()
      stubMod({ canManage = false })

      assert.is_false(VDT.EnhancedLoanSystem.collect(1).canManage)
    end)

    it("omits the loan array entirely when the farm has none", function()
      stubMod()
      assert.is_nil(VDT.EnhancedLoanSystem.collect(1).loans)
    end)

    it("survives the mod's settings object being absent", function()
      stubMod({ props = false })
      local model = VDT.EnhancedLoanSystem.collect(1)

      assert.is_nil(model.interest)
      assert.is_nil(model.maxDurationYears)
      assert.equals(486200, model.maxAmount)
    end)
  end)

  describe("maxAmount", function()
    it("caches the expensive ceiling between reads", function()
      local manager = stubMod()

      VDT.EnhancedLoanSystem.maxAmount(1)
      VDT.EnhancedLoanSystem.maxAmount(1)
      VDT.EnhancedLoanSystem.maxAmount(1)

      assert.equals(1, manager.maxAmountCalls)
    end)

    it("recomputes once the throttle has elapsed", function()
      local manager = stubMod()

      VDT.EnhancedLoanSystem.maxAmount(1)
      VDT.EnhancedLoanSystem.tick(60000)
      VDT.EnhancedLoanSystem.maxAmount(1)

      assert.equals(2, manager.maxAmountCalls)
    end)

    it("bypasses the cache when forced, and does not poison it", function()
      local manager = stubMod()

      VDT.EnhancedLoanSystem.maxAmount(1)
      VDT.EnhancedLoanSystem.maxAmount(1, true)
      VDT.EnhancedLoanSystem.maxAmount(1, true)

      assert.equals(3, manager.maxAmountCalls)
    end)

    it("floors a farm that is deeper in debt than it is worth at zero", function()
      stubMod({ maxAmount = -120000 })

      assert.equals(0, VDT.EnhancedLoanSystem.maxAmount(1))
    end)
  end)
end)
