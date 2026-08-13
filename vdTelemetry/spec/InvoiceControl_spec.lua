-- Unit tests for src/command/InvoiceControl.lua (the FS25_Invoices write-back commands).
--
-- Run with `busted` from the vdTelemetry/ directory. Load order mirrors VDTelemetry.lua: the control
-- takes its farm + permission helpers from the Invoices integration (which registers a channel at
-- load, so ExportChannels first, and reads the farm through ProductionExporter) and self-registers
-- into CommandRegistry.
--
-- What is worth pinning down: that the four id-addressed commands refuse exactly what the read side
-- refuses to offer -- they are checked against the SAME actionsFor() the app's buttons are driven by,
-- so a button that should not have existed cannot act either; and that createInvoice builds the same
-- invoice the mod's own wizard builds. That last one matters because the mod's server-side
-- recomputation of line amounts runs only on the client->server path: on a host nothing re-checks us,
-- so the arithmetic has to be right here rather than merely survivable.

if VDT == nil or VDT.Farm == nil then
  dofile("src/utils/Farm.lua")
end
if VDT.CommandRegistry == nil then
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
if VDT.Invoices == nil then
  dofile("src/integrations/Invoices.lua")
end
dofile("src/command/InvoiceControl.lua")

local warnings, debugMessages, errors
local debugger = {
  warn = function(_, fmt, ...)
    warnings[#warnings + 1] = string.format(fmt, ...)
  end,
  debug = function(_, fmt, ...)
    debugMessages[#debugMessages + 1] = string.format(fmt, ...)
  end,
  error = function(_, fmt, ...)
    errors[#errors + 1] = string.format(fmt, ...)
  end,
}

local STATE = { NEW = 1, SENT = 2, PAID = 3, CANCELLED = 4, PROPOSED = 5 }

-- The mod's own pricing helpers, copied from its Invoice.lua so the expected amounts below are its
-- arithmetic and not a paraphrase of it.
local function makeInvoiceClass(created)
  local class = {
    STATE = { NEW = 1, SENT = 2, PAID = 3, CANCELLED = 4, PROPOSED = 5 },
    UNIT_PIECE = 1,
    UNIT_HOUR = 2,
    UNIT_HECTARE = 3,
    UNIT_LITER = 4,
  }
  function class.sanitizeDiscountRate(rate)
    rate = tonumber(rate)
    if rate == nil or rate ~= rate then
      return 0
    end
    if rate < 0 then
      return 0
    end
    if rate > 1 then
      return 1
    end
    return rate
  end
  function class.computeLineGross(price, quantity, unitType)
    price = price or 0
    quantity = quantity or 0
    if unitType == class.UNIT_LITER then
      return math.floor(price * quantity / 1000 + 0.5)
    end
    return math.floor(price * quantity + 0.5)
  end
  function class.computeLineAmount(price, quantity, unitType, discountRate)
    local gross = class.computeLineGross(price, quantity, unitType)
    return math.floor(gross * (1 - class.sanitizeDiscountRate(discountRate)) + 0.5)
  end
  function class.new()
    local invoice = { id = 0, state = class.STATE.NEW, lineItems = {} }
    function invoice:populateFromData(id, items, recipientFarmId, senderFarmId)
      self.id = id
      self.lineItems = items
      self.recipientFarmId = recipientFarmId
      self.senderFarmId = senderFarmId
      local total = 0
      for _, item in ipairs(items) do
        total = total + (item.amount or 0)
      end
      self.totalAmount = total
      created.populated = true
    end
    created.invoice = invoice
    return invoice
  end
  return class
end

local WORK_TYPES = {
  { id = 2, nameKey = "invoice_work_plowing", basePrice = 2800, unit = 3 },
  { id = 44, nameKey = "invoice_work_driving", basePrice = 1200, unit = 2 },
  { id = 53, nameKey = "invoice_work_goods", basePrice = 0.5, unit = 4 },
  { id = 56, nameKey = "invoice_work_vehicleSale", basePrice = 0, unit = 1, vehicleDialog = true },
}

local calls, created

local function stubMod(over)
  over = over or {}
  VDT.Invoices.reset()
  calls = {}
  created = {}
  warnings, debugMessages, errors = {}, {}, {}

  local invoices = over.invoices or {}

  local service = {
    isVatEnabled = function()
      return over.vatEnabled ~= false
    end,
    isPenaltyEnabled = function()
      return true
    end,
    getPenaltyRate = function()
      return 5
    end,
    getDaysPerPeriod = function()
      return 3
    end,
    getWorkTypeById = function(_, id)
      for _, workType in ipairs(WORK_TYPES) do
        if workType.id == id then
          return workType
        end
      end
      return nil
    end,
    getAdjustedPrice = function(_, id)
      return ({ [2] = 3080, [44] = 1320, [53] = 0.55 })[id] or 0
    end,
    getVatRateForWorkType = function(_, id)
      return ({ [2] = 0.1, [44] = 0.2, [53] = 0.055 })[id] or 0
    end,
    notifyUI = function() end,
  }
  for _, method in ipairs({ "payInvoice", "deleteInvoice", "validateProposal", "refuseProposal" }) do
    service[method] = function(_, id)
      calls[#calls + 1] = { method = method, id = id }
    end
  end
  service.createAndSendInvoice = function(_, invoice)
    calls[#calls + 1] = { method = "createAndSendInvoice", invoice = invoice }
    return true
  end

  local manager = {
    service = service,
    repository = {
      getById = function(_, id)
        return invoices[id]
      end,
    },
    getIncomingInvoices = function()
      return {}
    end,
    getOutgoingInvoices = function()
      return {}
    end,
  }

  _G.FS25_Invoices = {
    Invoice = makeInvoiceClass(created),
    InvoiceService = {
      WORK_TYPES = WORK_TYPES,
      PENALTY_GRACE_PERIODS = 1,
      PENALTY_CAP_PERCENT = 25,
      notifyUI = function() end,
    },
  }
  _G.g_currentMission = {
    invoicesManager = manager,
    environment = { currentDay = 10 },
    getHasPlayerPermission = function(_, permission)
      return permission == "farmManager" and over.canManage ~= false
    end,
  }
  _G.g_localPlayer = { farmId = 1 }
  _G.g_farmManager = {
    farms = { { farmId = 1, name = "Hillside" }, { farmId = 2, name = "Meadow Farm" } },
    getFarmById = function(_, id)
      if id == 0 then
        return { farmId = 0, name = "Spectator", isSpectator = true }
      end
      -- 14 stands in for the nameless map/mod farm the mod's own picker filters out.
      if id == 14 then
        return { farmId = 14, name = "" }
      end
      return (id == 1 or id == 2) and { farmId = id, name = "Farm " .. id } or nil
    end,
  }
  _G.g_i18n = {
    hasText = function()
      return true
    end,
    getText = function(_, key)
      return "L:" .. tostring(key)
    end,
  }
  _G.Utils = {
    appendedFunction = function(original, appended)
      return function(...)
        original(...)
        appended(...)
      end
    end,
  }
end

local function makeInvoice(over)
  over = over or {}
  return {
    id = over.id or 1,
    senderFarmId = over.senderFarmId or 2,
    recipientFarmId = over.recipientFarmId or 1,
    state = over.state or STATE.NEW,
    totalAmount = 12100,
    totalHT = 11000,
    vatAmount = 1100,
    penaltyAmount = 0,
    createdDay = 5,
    createdAt = { day = 1, hour = 0, minute = 0, period = 8, year = 3 },
    lineItems = {},
  }
end

local function run(commandType, params)
  VDT.CommandRegistry.get(commandType).execute(nil, params, debugger)
end

describe("InvoiceControl registration", function()
  it("registers all five commands, none of them needing a vehicle", function()
    for _, cmdType in ipairs({
      "payInvoice",
      "cancelInvoice",
      "validateProposal",
      "refuseProposal",
      "createInvoice",
    }) do
      local handler = VDT.CommandRegistry.get(cmdType)
      assert.is_not_nil(handler)
      assert.equals(false, handler.requiresVehicle)
    end
  end)
end)

describe("InvoiceControl", function()
  after_each(function()
    _G.FS25_Invoices = nil
    _G.g_currentMission = nil
    _G.g_localPlayer = nil
    _G.g_farmManager = nil
    _G.g_i18n = nil
    _G.Utils = nil
    VDT.Invoices.reset()
  end)

  describe("the shared preamble", function()
    it("refuses everything when the mod is not installed", function()
      stubMod()
      _G.FS25_Invoices = nil
      run("payInvoice", { invoiceId = 1 })
      assert.equals(0, #calls)
      assert.matches("not installed", warnings[1])
    end)

    it("refuses everything without the farmManager right", function()
      stubMod({ canManage = false, invoices = { [1] = makeInvoice() } })
      run("payInvoice", { invoiceId = 1 })
      assert.equals(0, #calls)
      assert.matches("may not manage", warnings[1])
    end)

    it("refuses an invoice id that no longer resolves", function()
      stubMod({ invoices = {} })
      run("payInvoice", { invoiceId = 99 })
      assert.equals(0, #calls)
      assert.matches("no invoice with id 99", warnings[1])
    end)

    it("refuses a missing id rather than acting on whatever comes first", function()
      stubMod({ invoices = { [1] = makeInvoice() } })
      run("payInvoice", {})
      assert.equals(0, #calls)
    end)
  end)

  describe("payInvoice", function()
    it("pays an unpaid invoice this farm owes", function()
      stubMod({ invoices = { [1] = makeInvoice() } })
      run("payInvoice", { invoiceId = 1 })
      assert.same({ method = "payInvoice", id = 1 }, calls[1])
    end)

    it("refuses to pay one this farm issued", function()
      stubMod({ invoices = { [1] = makeInvoice({ senderFarmId = 1, recipientFarmId = 2 }) } })
      run("payInvoice", { invoiceId = 1 })
      assert.equals(0, #calls)
      assert.matches("does not allow 'pay'", warnings[1])
    end)

    it("refuses to pay one already settled", function()
      stubMod({ invoices = { [1] = makeInvoice({ state = STATE.PAID }) } })
      run("payInvoice", { invoiceId = 1 })
      assert.equals(0, #calls)
    end)

    it("refuses to pay a proposal -- it is not payable until validated", function()
      stubMod({ invoices = { [1] = makeInvoice({ state = STATE.PROPOSED }) } })
      run("payInvoice", { invoiceId = 1 })
      assert.equals(0, #calls)
    end)
  end)

  describe("cancelInvoice", function()
    it("lets the issuer withdraw an unpaid invoice", function()
      stubMod({ invoices = { [1] = makeInvoice({ senderFarmId = 1, recipientFarmId = 2 }) } })
      run("cancelInvoice", { invoiceId = 1 })
      assert.same({ method = "deleteInvoice", id = 1 }, calls[1])
    end)

    it("lets the payer withdraw a proposal they raised", function()
      stubMod({ invoices = { [1] = makeInvoice({ state = STATE.PROPOSED, senderFarmId = 2, recipientFarmId = 1 }) } })
      run("cancelInvoice", { invoiceId = 1 })
      assert.same({ method = "deleteInvoice", id = 1 }, calls[1])
    end)

    it("refuses to let the payer cancel an invoice billed to them", function()
      stubMod({ invoices = { [1] = makeInvoice() } })
      run("cancelInvoice", { invoiceId = 1 })
      assert.equals(0, #calls)
    end)
  end)

  describe("proposals", function()
    it("lets the named issuer validate one", function()
      stubMod({ invoices = { [1] = makeInvoice({ state = STATE.PROPOSED, senderFarmId = 1, recipientFarmId = 2 }) } })
      run("validateProposal", { invoiceId = 1 })
      assert.same({ method = "validateProposal", id = 1 }, calls[1])
    end)

    it("lets the named issuer refuse one", function()
      stubMod({ invoices = { [1] = makeInvoice({ state = STATE.PROPOSED, senderFarmId = 1, recipientFarmId = 2 }) } })
      run("refuseProposal", { invoiceId = 1 })
      assert.same({ method = "refuseProposal", id = 1 }, calls[1])
    end)

    it("refuses to let the payer who raised it answer it", function()
      stubMod({ invoices = { [1] = makeInvoice({ state = STATE.PROPOSED, senderFarmId = 2, recipientFarmId = 1 }) } })
      run("validateProposal", { invoiceId = 1 })
      run("refuseProposal", { invoiceId = 1 })
      assert.equals(0, #calls)
    end)

    it("refuses to validate an invoice that is not a proposal", function()
      stubMod({ invoices = { [1] = makeInvoice({ senderFarmId = 1, recipientFarmId = 2 }) } })
      run("validateProposal", { invoiceId = 1 })
      assert.equals(0, #calls)
    end)
  end)

  describe("createInvoice", function()
    it("bills another farm, with us as the issuer", function()
      stubMod()
      run("createInvoice", { farmId = 2, lines = { { workTypeId = 2, quantity = 3.5 } } })

      assert.equals("createAndSendInvoice", calls[1].method)
      local invoice = calls[1].invoice
      assert.equals(1, invoice.senderFarmId)
      assert.equals(2, invoice.recipientFarmId)
      -- id 0 asks the repository for the next one, server-side.
      assert.equals(0, invoice.id)
      assert.equals(STATE.NEW, invoice.state)
      assert.is_true(created.populated)
    end)

    it("prices a line with the mod's own arithmetic, from the catalogue when none is given", function()
      stubMod()
      run("createInvoice", { farmId = 2, lines = { { workTypeId = 2, quantity = 3.5 } } })

      local item = calls[1].invoice.lineItems[1]
      -- 3080/ha adjusted price x 3.5 ha, no discount.
      assert.equals(10780, item.amount)
      assert.equals(3080, item.price)
      assert.equals(3.5, item.quantity)
      assert.equals(3, item.unitType)
      assert.equals(0.1, item.vatRate)
    end)

    it("prices a per-1000-litre line the way the mod does", function()
      stubMod()
      run("createInvoice", { farmId = 2, lines = { { workTypeId = 53, quantity = 2500 } } })
      -- 0.55 per 1000 l x 2500 l = 1.375 -> 1, not 1375. The unit rule is the mod's, not ours.
      assert.equals(1, calls[1].invoice.lineItems[1].amount)
    end)

    it("honours a caller's own price over the catalogue's", function()
      stubMod()
      run("createInvoice", { farmId = 2, lines = { { workTypeId = 2, quantity = 2, price = 1000 } } })
      assert.equals(2000, calls[1].invoice.lineItems[1].amount)
    end)

    it("applies a discount before VAT, and clamps a silly one", function()
      stubMod()
      run("createInvoice", { farmId = 2, lines = { { workTypeId = 2, quantity = 1, discount = 0.25 } } })
      assert.equals(2310, calls[1].invoice.lineItems[1].amount)

      stubMod()
      run("createInvoice", { farmId = 2, lines = { { workTypeId = 2, quantity = 1, discount = 5 } } })
      -- Clamped to 1, i.e. free -- the mod's own sanitizeDiscountRate, not a refusal.
      assert.equals(1, calls[1].invoice.lineItems[1].discountRate)
      assert.equals(0, calls[1].invoice.lineItems[1].amount)
    end)

    it("zeroes VAT on every line when the server has simulation off", function()
      stubMod({ vatEnabled = false })
      run("createInvoice", { farmId = 2, lines = { { workTypeId = 2, quantity = 1 } } })
      assert.equals(0, calls[1].invoice.lineItems[1].vatRate)
    end)

    it("inverts the roles for a proposal and marks it PROPOSED", function()
      stubMod()
      run("createInvoice", { farmId = 2, proposal = true, lines = { { workTypeId = 2, quantity = 1 } } })

      local invoice = calls[1].invoice
      -- A proposal is raised by the payer: we are the recipient, and the farm we name is the issuer
      -- who has to validate it.
      assert.equals(2, invoice.senderFarmId)
      assert.equals(1, invoice.recipientFarmId)
      assert.equals(STATE.PROPOSED, invoice.state)
    end)

    it("fills every field the mod's network stream reads", function()
      stubMod()
      run("createInvoice", { farmId = 2, lines = { { workTypeId = 44, quantity = 2, note = "hedge run" } } })
      local item = calls[1].invoice.lineItems[1]
      -- A nil here would be a nil write on the stream when the invoice is broadcast.
      for _, field in ipairs({
        "workTypeId",
        "amount",
        "quantity",
        "unitType",
        "fieldId",
        "fieldArea",
        "note",
        "vatRate",
        "discountRate",
        "name",
        "iconFilename",
        "price",
        "vehicleUniqueId",
        "consumableXmlFilename",
        "consumableFillTypeIndex",
        "consumableFillLevel",
      }) do
        assert.is_not_nil(item[field], field .. " must not be nil")
      end
      assert.equals("hedge run", item.note)
      assert.equals("L:invoice_work_driving", item.name)
    end)

    describe("refusals", function()
      it("drops a line whose work type needs an in-game picker", function()
        stubMod()
        run("createInvoice", {
          farmId = 2,
          lines = { { workTypeId = 56, quantity = 1 }, { workTypeId = 2, quantity = 1 } },
        })
        -- The picker line is dropped, the usable one still goes.
        assert.equals(1, #calls[1].invoice.lineItems)
        assert.equals(2, calls[1].invoice.lineItems[1].workTypeId)
        assert.matches("needs an in%-game picker", warnings[1])
      end)

      it("drops a line with no usable quantity", function()
        stubMod()
        run(
          "createInvoice",
          { farmId = 2, lines = { { workTypeId = 2, quantity = 0 }, { workTypeId = 2, quantity = 1 } } }
        )
        assert.equals(1, #calls[1].invoice.lineItems)
      end)

      it("drops a line naming a work type the mod does not have", function()
        stubMod()
        run(
          "createInvoice",
          { farmId = 2, lines = { { workTypeId = 999, quantity = 1 }, { workTypeId = 2, quantity = 1 } } }
        )
        assert.equals(1, #calls[1].invoice.lineItems)
      end)

      it("sends nothing at all when every line failed", function()
        stubMod()
        run("createInvoice", { farmId = 2, lines = { { workTypeId = 999, quantity = 1 } } })
        -- An invoice with no lines would be accepted by the mod and bill nothing.
        assert.equals(0, #calls)
        assert.matches("no usable lines", warnings[#warnings])
      end)

      it("refuses an invoice with no lines", function()
        stubMod()
        run("createInvoice", { farmId = 2, lines = {} })
        assert.equals(0, #calls)
      end)

      it("refuses to bill our own farm", function()
        stubMod()
        run("createInvoice", { farmId = 1, lines = { { workTypeId = 2, quantity = 1 } } })
        assert.equals(0, #calls)
        assert.matches("cannot invoice itself", warnings[1])
      end)

      it("refuses a farm that does not exist, and the spectator", function()
        stubMod()
        run("createInvoice", { farmId = 7, lines = { { workTypeId = 2, quantity = 1 } } })
        run("createInvoice", { farmId = 0, lines = { { workTypeId = 2, quantity = 1 } } })
        assert.equals(0, #calls)
      end)

      it("refuses a nameless farm, the same rule the mod's own picker applies", function()
        -- The app never offers one (the read side hides it), but a stale or hand-made command must
        -- not get through either.
        stubMod()
        run("createInvoice", { farmId = 14, lines = { { workTypeId = 2, quantity = 1 } } })
        assert.equals(0, #calls)
        assert.matches("cannot be invoiced", warnings[1])
      end)

      it("refuses more lines than the mod's own cap", function()
        stubMod()
        local lines = {}
        for _ = 1, VDT.InvoiceControl.MAX_LINES + 1 do
          lines[#lines + 1] = { workTypeId = 2, quantity = 1 }
        end
        run("createInvoice", { farmId = 2, lines = lines })
        assert.equals(0, #calls)
        assert.matches("past the mod's cap", warnings[1])
      end)
    end)
  end)

  describe("the command payloads", function()
    -- A stub of the engine's XMLFile reader, enough for the two parse shapes.
    local function stubXml(attrs, lines)
      return {
        getInt = function(_, path)
          return attrs[path]
        end,
        getFloat = function(_, path)
          return attrs[path]
        end,
        getString = function(_, path)
          return attrs[path]
        end,
        getBool = function(_, path, default)
          local value = attrs[path]
          if value == nil then
            return default
          end
          return value
        end,
        iterate = function(_, path, fn)
          if path ~= "commands.command(0).line" then
            return
          end
          for index = 1, #lines do
            fn(index, string.format("commands.command(0).line(%d)", index - 1))
          end
        end,
      }
    end

    it("reads an id-addressed command's single attribute", function()
      local xml = stubXml({ ["commands.command(0)#invoiceId"] = 7 }, {})
      local params = VDT.CommandRegistry.get("payInvoice").parse(xml, "commands.command(0)")
      assert.equals(7, params.invoiceId)
    end)

    it("reads createInvoice's child <line> elements", function()
      -- The first command in the channel to carry children; the envelope reader hands us the live
      -- XMLFile and our key, so the nesting is entirely this control's business.
      local xml = stubXml({
        ["commands.command(0)#farmId"] = 2,
        ["commands.command(0)#proposal"] = true,
        ["commands.command(0).line(0)#workTypeId"] = 2,
        ["commands.command(0).line(0)#quantity"] = 3.5,
        ["commands.command(0).line(0)#price"] = 3080,
        ["commands.command(0).line(1)#workTypeId"] = 44,
        ["commands.command(0).line(1)#quantity"] = 2,
        ["commands.command(0).line(1)#note"] = "hedge run",
      }, { 1, 2 })

      local params = VDT.CommandRegistry.get("createInvoice").parse(xml, "commands.command(0)")
      assert.equals(2, params.farmId)
      assert.is_true(params.proposal)
      assert.equals(2, #params.lines)
      assert.equals(2, params.lines[1].workTypeId)
      assert.equals(3.5, params.lines[1].quantity)
      assert.equals(3080, params.lines[1].price)
      assert.equals("hedge run", params.lines[2].note)
    end)

    it("defaults proposal to false when the attribute is absent", function()
      local xml = stubXml({ ["commands.command(0)#farmId"] = 2 }, {})
      local params = VDT.CommandRegistry.get("createInvoice").parse(xml, "commands.command(0)")
      assert.is_false(params.proposal)
    end)
  end)
end)
