-- Unit tests for src/command/FinanceControl.lua (set the farm's loan to a target).
--
-- Run with `busted` from the vdTelemetry/ directory. Load order mirrors VDTelemetry.lua: the control
-- takes its farm + permission helpers from FinanceExporter (which registers a channel at load, so
-- ExportChannels first, and reads the farm through ProductionExporter) and self-registers into
-- CommandRegistry. We stub the farm manager, ChangeLoanEvent and a client connection, and capture
-- what was sent.
--
-- What is worth pinning down is the arithmetic and the refusals. The command carries an absolute
-- TARGET but the engine event takes a DELTA, so the conversion is the whole correctness story; and
-- every refusal is the difference between a clear log line and a silent no-op, because the mod has no
-- way to answer the app yet.

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
if VDT.FinanceExporter == nil then
  dofile("src/collect/FinanceExporter.lua")
end
dofile("src/command/FinanceControl.lua")

local warnings, debugMessages
local debugger = {
  warn = function(_, fmt, ...)
    warnings[#warnings + 1] = string.format(fmt, ...)
  end,
  debug = function(_, fmt, ...)
    debugMessages[#debugMessages + 1] = string.format(fmt, ...)
  end,
}

local sent -- the last event handed to the server connection

local function makeFarm(over)
  over = over or {}
  return {
    farmId = 1,
    money = over.money or 100000,
    loan = over.loan or 0,
    loanMax = over.loanMax or 500000,
  }
end

---Install the globals the control reads. See FinanceExporter_spec for why these go through _G.
local function stubGame(over)
  over = over or {}
  local farm = over.farm or makeFarm()

  _G.Platform = { gameplay = { hasLoans = over.hasLoans ~= false } }
  _G.g_farmManager = {
    getFarmById = function(_, id)
      return id == farm.farmId and farm or nil
    end,
  }
  _G.g_localPlayer = { farmId = over.playerFarmId or farm.farmId }
  _G.g_currentMission = {
    getHasPlayerPermission = function(_, permission)
      return permission == "farmManager" and over.canManage ~= false
    end,
  }
  _G.ChangeLoanEvent = {
    new = function(loanValue, farmId)
      return { loanValue = loanValue, farmId = farmId }
    end,
  }
  _G.g_client = {
    getServerConnection = function()
      if over.noConnection then
        return nil
      end
      return {
        sendEvent = function(_, event)
          sent = event
        end,
      }
    end,
  }
  return farm
end

local function run(amount)
  local handler = VDT.CommandRegistry.get("setLoan")
  handler.execute(nil, { amount = amount }, debugger)
end

describe("FinanceControl setLoan", function()
  before_each(function()
    warnings, debugMessages, sent = {}, {}, nil
  end)

  after_each(function()
    _G.Platform = nil
    _G.g_farmManager = nil
    _G.g_localPlayer = nil
    _G.g_currentMission = nil
    _G.ChangeLoanEvent = nil
    _G.g_client = nil
  end)

  it("is registered and needs no vehicle", function()
    local handler = VDT.CommandRegistry.get("setLoan")

    assert.is_table(handler)
    assert.is_false(handler.requiresVehicle)
  end)

  it("sends the delta between the target and the current loan", function()
    stubGame({ farm = makeFarm({ loan = 100000 }) })

    run(250000)

    assert.is_table(sent)
    assert.equals(150000, sent.loanValue)
    assert.equals(1, sent.farmId)
  end)

  it("sends a negative delta to repay", function()
    stubGame({ farm = makeFarm({ loan = 100000, money = 100000 }) })

    run(60000)

    assert.equals(-40000, sent.loanValue)
  end)

  it("is idempotent: a target already reached sends nothing", function()
    stubGame({ farm = makeFarm({ loan = 250000 }) })

    run(250000)

    assert.is_nil(sent)
    assert.equals(0, #warnings)
  end)

  describe("borrowing above the ceiling", function()
    it("clamps to loanMax rather than refusing", function()
      stubGame({ farm = makeFarm({ loan = 480000, loanMax = 500000 }) })

      run(600000)

      assert.equals(20000, sent.loanValue)
    end)

    it("uses the current loan as the ceiling when it already exceeds loanMax", function()
      -- Farmland sold off: the ceiling dropped below what is already borrowed. The engine's own
      -- max(loanMax, loan) means the loan is held, not force-called.
      stubGame({ farm = makeFarm({ loan = 600000, loanMax = 500000, money = 700000 }) })

      run(600000)
      assert.is_nil(sent) -- already there

      run(700000)
      assert.is_nil(sent) -- clamped back down to 600000, so still nothing to do
    end)
  end)

  describe("repaying more than the farm can afford", function()
    it("refuses rather than clamping -- it would spend money the player didn't ask to spend", function()
      stubGame({ farm = makeFarm({ loan = 300000, money = 5000 }) })

      run(0)

      assert.is_nil(sent)
      assert.equals(1, #warnings)
      assert.is_truthy(warnings[1]:find("repaying 300000", 1, true))
    end)

    it("allows a repayment the balance exactly covers", function()
      stubGame({ farm = makeFarm({ loan = 300000, money = 300000 }) })

      run(0)

      assert.equals(-300000, sent.loanValue)
    end)
  end)

  describe("refusals", function()
    it("refuses without the farmManager right", function()
      stubGame({ canManage = false })

      run(50000)

      assert.is_nil(sent)
      assert.is_truthy(warnings[1]:find("may not manage", 1, true))
    end)

    it("refuses where the platform has no loans", function()
      stubGame({ hasLoans = false })

      run(50000)

      assert.is_nil(sent)
      assert.is_truthy(warnings[1]:find("no loans", 1, true))
    end)

    it("refuses for a spectator with no farm", function()
      stubGame()
      _G.g_localPlayer.farmId = 0

      run(50000)

      assert.is_nil(sent)
      assert.is_truthy(warnings[1]:find("no local farm", 1, true))
    end)

    it("refuses a missing, negative or non-numeric target", function()
      local farm = makeFarm({ loan = 100000 })
      for _, bad in ipairs({ "nil", -1, "50000", 0 / 0 }) do
        stubGame({ farm = farm })
        warnings, sent = {}, nil

        run(bad ~= "nil" and bad or nil)

        assert.is_nil(sent)
        assert.equals(1, #warnings)
        assert.is_truthy(warnings[1]:find("invalid target amount", 1, true))
      end
    end)

    it("refuses when there is no server connection", function()
      stubGame({ noConnection = true })

      run(50000)

      assert.is_nil(sent)
      assert.is_truthy(warnings[1]:find("no server connection", 1, true))
    end)
  end)
end)
