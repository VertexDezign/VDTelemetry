-- Unit tests for the FS25_Invoices integration (src/integrations/Invoices.lua).
--
-- Run with `busted` from the vdTelemetry/ directory. Everything here reads that mod's internals
-- through its own env table (FS25_Invoices.*) plus the manager the mod parks on the mission, so the
-- stubs mirror that split rather than planting bare globals -- which is also the trap the integration
-- exists to avoid.
--
-- What is worth pinning down: that paying an invoice really does move two DIFFERENT numbers (the VAT
-- between them is destroyed, and a single "total" would be a lie to one of the two parties); that the
-- state and unit tokens come from the enum KEYS, so the mod renumbering them cannot silently change
-- what we publish; that `actions` mirrors the mod's own server-side rules, since it is what the app's
-- buttons dispatch on; and that a creation date reads the same as every other date in the app despite
-- the mod storing it with a calendar roll ours does not have.

if VDT == nil or VDT.ExportChannels == nil then
  dofile("src/export/ExportChannels.lua")
end
if VDT.ProductionExporter == nil then
  dofile("src/collect/ProductionExporter.lua")
end
if ValueMapper == nil then
  dofile("src/mapper/ValueMapper.lua")
end
dofile("src/integrations/Invoices.lua")

local debugger = {
  info = function() end,
  debug = function() end,
  warn = function() end,
  error = function() end,
}

-- The mod's own constants, as its Invoice.lua declares them.
local STATE = { NEW = 1, SENT = 2, PAID = 3, CANCELLED = 4, PROPOSED = 5 }

local function makeInvoiceClass(over)
  over = over or {}
  return {
    STATE = over.STATE or { NEW = 1, SENT = 2, PAID = 3, CANCELLED = 4, PROPOSED = 5 },
    UNIT_PIECE = over.UNIT_PIECE or 1,
    UNIT_HOUR = over.UNIT_HOUR or 2,
    UNIT_HECTARE = over.UNIT_HECTARE or 3,
    UNIT_LITER = over.UNIT_LITER or 4,
  }
end

local function makeInvoice(over)
  over = over or {}
  -- `x and nil or y` cannot express "deliberately absent" in Lua (it yields y), and the pre-VAT
  -- savegame case below needs exactly that.
  local totalHT = over.totalHT or 11000
  if over.noNetTotal then
    totalHT = nil
  end
  return {
    id = over.id or 1,
    senderFarmId = over.senderFarmId or 2,
    recipientFarmId = over.recipientFarmId or 1,
    state = over.state or STATE.NEW,
    totalAmount = over.totalAmount or 12100,
    totalHT = totalHT,
    vatAmount = over.vatAmount or 1100,
    penaltyAmount = over.penaltyAmount or 0,
    createdDay = over.createdDay or 10,
    createdAt = over.createdAt or { day = 12, hour = 14, minute = 32, period = 8, year = 3 },
    lineItems = over.lineItems or {
      {
        workTypeId = 2,
        name = "Plowing",
        quantity = 3.5,
        unitType = 3,
        price = 3080,
        amount = 10780,
        vatRate = 0.1,
        discountRate = 0,
        fieldId = 12,
        fieldArea = 3.5,
        note = "",
      },
    },
  }
end

local WORK_TYPES = {
  { id = 2, nameKey = "invoice_work_plowing", basePrice = 2800, unit = 3 },
  { id = 44, nameKey = "invoice_work_driving", basePrice = 1200, unit = 2 },
  { id = 53, nameKey = "invoice_work_goods", basePrice = 0.5, unit = 4 },
  { id = 56, nameKey = "invoice_work_vehicleSale", basePrice = 0, unit = 1, vehicleDialog = true },
  { id = 37, nameKey = "invoice_work_consumableSale", basePrice = 0, unit = 1, consumableDialog = true },
  { id = 55, nameKey = "invoice_work_products", basePrice = 0, unit = 4, fillTypeDialog = true },
}

---Install the mod env plus the engine globals the integration reads.
local function stubMod(over)
  over = over or {}
  VDT.Invoices.reset()

  local incoming = over.incoming or {}
  local outgoing = over.outgoing or {}

  local service = {
    isVatEnabled = function()
      return over.vatEnabled ~= false
    end,
    isPenaltyEnabled = function()
      return over.penaltiesEnabled ~= false
    end,
    getPenaltyRate = function()
      return 5
    end,
    getDaysPerPeriod = function()
      return over.daysPerPeriod or 3
    end,
    getAdjustedPrice = function(_, id)
      return ({ [2] = 3080, [44] = 1320, [53] = 0.55 })[id] or 0
    end,
    getVatRateForWorkType = function(_, id)
      return ({ [2] = 0.1, [44] = 0.2, [53] = 0.055 })[id] or 0
    end,
    notifyUI = function() end,
  }

  local manager = {
    service = service,
    repository = {},
    getIncomingInvoices = function()
      return incoming
    end,
    getOutgoingInvoices = function()
      return outgoing
    end,
  }

  _G.FS25_Invoices = {
    Invoice = over.invoiceClass or makeInvoiceClass(),
    InvoiceService = {
      WORK_TYPES = over.workTypes or WORK_TYPES,
      PENALTY_GRACE_PERIODS = 1,
      PENALTY_CAP_PERCENT = 25,
      notifyUI = function() end,
    },
  }

  if over.noManager then
    manager = nil
  end

  _G.g_currentMission = {
    invoicesManager = manager,
    environment = { currentDay = over.currentDay or 10 },
    getHasPlayerPermission = function(_, permission)
      return permission == "farmManager" and over.canManage ~= false
    end,
  }
  _G.g_localPlayer = over.noFarm and { farmId = 0 } or { farmId = 1 }
  _G.g_farmManager = {
    farms = over.farms or {
      { farmId = 0, name = "Spectator", isSpectator = true },
      { farmId = 1, name = "Hillside" },
      { farmId = 2, name = "Meadow Farm" },
    },
  }
  _G.g_i18n = {
    hasText = function(_, key)
      return key ~= nil and string.sub(key, 1, 13) == "invoice_work_"
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

  return manager, service
end

describe("Invoices integration", function()
  -- Captured before any case can swap it: the hook and farm-change cases count markDirty calls, and a
  -- restore in the test body would be skipped by the failing assertion that made it matter.
  local realMarkDirty = VDT.ExportChannels.markDirty

  after_each(function()
    _G.FS25_Invoices = nil
    _G.g_currentMission = nil
    _G.g_localPlayer = nil
    _G.g_farmManager = nil
    _G.g_i18n = nil
    _G.Utils = nil
    _G.MessageType = nil
    _G.g_messageCenter = nil
    VDT.ExportChannels.markDirty = realMarkDirty
    VDT.Invoices.reset()
  end)

  describe("availability", function()
    it("is unavailable with no mod env, so the file is never written", function()
      stubMod()
      _G.FS25_Invoices = nil
      assert.is_false(VDT.Invoices.isAvailable())
      assert.is_nil(VDT.Invoices.collect())
    end)

    it("is unavailable with no manager on the mission", function()
      stubMod({ noManager = true })
      assert.is_false(VDT.Invoices.isAvailable())
      assert.is_nil(VDT.Invoices.collect())
    end)

    it("is available once both the manager and the classes are up", function()
      stubMod()
      assert.is_true(VDT.Invoices.isAvailable())
    end)
  end)

  describe("tokens", function()
    it("derives state tokens from the enum KEYS, not the numbers", function()
      -- The whole point: the mod renumbering its states must not change what we publish.
      local renumbered = makeInvoiceClass({ STATE = { NEW = 40, SENT = 41, PAID = 42, CANCELLED = 43, PROPOSED = 44 } })
      local tokens = VDT.Invoices.stateTokens(renumbered)
      assert.equals("new", tokens[40])
      assert.equals("paid", tokens[42])
      assert.equals("proposed", tokens[44])
    end)

    it("derives unit tokens from the UNIT_* constant names", function()
      local tokens = VDT.Invoices.unitTokens(makeInvoiceClass())
      assert.equals("piece", tokens[1])
      assert.equals("hour", tokens[2])
      assert.equals("hectare", tokens[3])
      assert.equals("liter", tokens[4])
    end)

    it("ignores non-UNIT fields on the class", function()
      local class = makeInvoiceClass()
      class.SOMETHING_ELSE = 9
      local tokens = VDT.Invoices.unitTokens(class)
      assert.is_nil(tokens[9])
    end)

    it("names a state the enum has no name for rather than omitting the field", function()
      -- `state` is required on the app's side, and the chip prints whatever token it does not know:
      -- omitting it would draw an empty chip, which reads as a rendering bug rather than as data.
      stubMod({ incoming = { makeInvoice({ state = 99 }) } })
      assert.equals("unknown", VDT.Invoices.collect().invoices[1].state)
    end)
  end)

  describe("the creation stamp", function()
    it("reads back as DD.MM.YYYY + HH:MM", function()
      local date, time = VDT.Invoices.formatStamp({ day = 12, hour = 14, minute = 32, period = 8, year = 3 })
      assert.equals("12.08.2026", date)
      assert.equals("14:32", time)
    end)

    it("undoes the mod's January/February year roll, so one game year is one calendar year", function()
      -- The mod stores Jan/Feb as belonging to the NEXT calendar year (a game year starts in March);
      -- our environment.date does not. Both of these are game year 3 and must read as 2026.
      local august = VDT.Invoices.formatStamp({ day = 1, hour = 0, minute = 0, period = 8, year = 3 })
      local january = VDT.Invoices.formatStamp({ day = 1, hour = 0, minute = 0, period = 1, year = 4 })
      assert.equals("01.08.2026", august)
      assert.equals("01.01.2026", january)
    end)

    it("has no date for an unstamped invoice rather than a wrong one", function()
      local date, time = VDT.Invoices.formatStamp({ day = 0, hour = 0, minute = 0, period = 0, year = 0 })
      assert.is_nil(date)
      assert.is_nil(time)
    end)
  end)

  describe("the money split", function()
    it("carries what the payer loses and what the issuer gains as separate figures", function()
      stubMod({ incoming = { makeInvoice({ penaltyAmount = 605 }) } })
      local model = VDT.Invoices.collect()
      local invoice = model.invoices[1]

      assert.equals(12100, invoice.total)
      assert.equals(11000, invoice.totalNet)
      assert.equals(1100, invoice.vat)
      assert.equals(605, invoice.penalty)
      -- The payer parts with total + penalty; the issuer receives net + penalty. The 1100 of VAT
      -- between them is destroyed -- the mod's simulation, and the reason both numbers are exported.
      assert.equals(12705, invoice.totalDue)
      assert.equals(11605, invoice.credit)
    end)

    it("omits VAT and penalty when they are zero, and marks overdue only once one accrued", function()
      stubMod({ incoming = { makeInvoice({ vatAmount = 0, totalHT = 12100, penaltyAmount = 0 }) } })
      local invoice = VDT.Invoices.collect().invoices[1]
      assert.is_nil(invoice.vat)
      assert.is_nil(invoice.penalty)
      assert.is_nil(invoice.overdue)
      assert.equals(invoice.total, invoice.totalDue)
    end)

    it("treats a pre-VAT savegame's missing net total as fully net", function()
      stubMod({ incoming = { makeInvoice({ noNetTotal = true, vatAmount = 0 }) } })
      local invoice = VDT.Invoices.collect().invoices[1]
      assert.equals(12100, invoice.totalNet)
      assert.equals(12100, invoice.credit)
    end)
  end)

  describe("directions and counterparties", function()
    it("tags each invoice with the list the mod itself put it in", function()
      stubMod({
        incoming = { makeInvoice({ id = 1 }) },
        outgoing = { makeInvoice({ id = 2, senderFarmId = 1, recipientFarmId = 2 }) },
      })
      local invoices = VDT.Invoices.collect().invoices
      assert.equals(2, #invoices)
      assert.equals("incoming", invoices[1].direction)
      assert.equals("outgoing", invoices[2].direction)
    end)

    it("names the other farm, whichever side of the invoice we are", function()
      stubMod({
        incoming = { makeInvoice({ id = 1 }) },
        outgoing = { makeInvoice({ id = 2, senderFarmId = 1, recipientFarmId = 2 }) },
      })
      local invoices = VDT.Invoices.collect().invoices
      assert.equals(2, invoices[1].counterpartyId)
      assert.equals("Meadow Farm", invoices[1].counterpartyName)
      assert.equals(2, invoices[2].counterpartyId)
      assert.equals("Meadow Farm", invoices[2].counterpartyName)
    end)

    it("sorts by id so the list order is stable", function()
      stubMod({
        incoming = { makeInvoice({ id = 7 }) },
        outgoing = { makeInvoice({ id = 3, senderFarmId = 1, recipientFarmId = 2 }) },
      })
      local invoices = VDT.Invoices.collect().invoices
      assert.equals(3, invoices[1].id)
      assert.equals(7, invoices[2].id)
    end)
  end)

  describe("actions", function()
    local function actionsFor(invoice, farmId, canManage)
      return VDT.Invoices.actionsFor(invoice, farmId, STATE, canManage ~= false)
    end

    it("offers pay to the recipient of an unpaid invoice", function()
      local actions = actionsFor(makeInvoice(), 1)
      assert.same({ "pay" }, actions)
    end)

    it("does not offer pay to the issuer", function()
      local actions = actionsFor(makeInvoice({ senderFarmId = 1, recipientFarmId = 2 }), 1)
      assert.same({ "cancel" }, actions)
    end)

    it("offers nothing on an invoice already paid", function()
      assert.is_nil(actionsFor(makeInvoice({ state = STATE.PAID }), 1))
    end)

    it("lets the issuer validate or refuse a proposal, and the payer withdraw it", function()
      local proposal = makeInvoice({ state = STATE.PROPOSED, senderFarmId = 2, recipientFarmId = 1 })
      -- We raised it (we are the payer): we may withdraw it, but not answer it.
      assert.same({ "cancel" }, actionsFor(proposal, 1))
      -- The issuer it names answers it.
      assert.same({ "validate", "refuse" }, actionsFor(proposal, 2))
    end)

    it("never offers pay on a proposal -- it is not payable until validated", function()
      local proposal = makeInvoice({ state = STATE.PROPOSED, senderFarmId = 2, recipientFarmId = 1 })
      for _, action in ipairs(actionsFor(proposal, 1) or {}) do
        assert.is_not.equals("pay", action)
      end
    end)

    it("offers nothing at all without the farmManager right", function()
      assert.is_nil(actionsFor(makeInvoice(), 1, false))
    end)

    it("leaves the key out of the document when the player may not manage", function()
      stubMod({ canManage = false, incoming = { makeInvoice() } })
      local model = VDT.Invoices.collect()
      assert.is_false(model.canManage)
      assert.is_nil(model.invoices[1].actions)
    end)
  end)

  describe("penalty countdown", function()
    local terms = { enabled = true, gracePeriods = 1, daysPerPeriod = 3 }

    it("counts down to the first accrual, two whole periods after creation", function()
      -- created day 10, 3-day months, 1 month grace => first penalty at day 16.
      assert.equals(6, VDT.Invoices.daysUntilPenalty(makeInvoice({ createdDay = 10 }), terms, STATE, 10))
      assert.equals(1, VDT.Invoices.daysUntilPenalty(makeInvoice({ createdDay = 10 }), terms, STATE, 15))
    end)

    it("floors at zero rather than going negative", function()
      -- Accrual only runs on the last day of a period, so an invoice can sit due for a few days.
      assert.equals(0, VDT.Invoices.daysUntilPenalty(makeInvoice({ createdDay = 10 }), terms, STATE, 20))
    end)

    it("stops once a penalty has actually accrued", function()
      local overdue = makeInvoice({ createdDay = 10, penaltyAmount = 605 })
      assert.is_nil(VDT.Invoices.daysUntilPenalty(overdue, terms, STATE, 20))
    end)

    it("says nothing when penalties are off, or the invoice can never accrue one", function()
      local off = { enabled = false, gracePeriods = 1, daysPerPeriod = 3 }
      assert.is_nil(VDT.Invoices.daysUntilPenalty(makeInvoice(), off, STATE, 10))
      assert.is_nil(VDT.Invoices.daysUntilPenalty(makeInvoice({ state = STATE.PAID }), terms, STATE, 10))
      assert.is_nil(VDT.Invoices.daysUntilPenalty(makeInvoice({ state = STATE.PROPOSED }), terms, STATE, 10))
    end)
  end)

  describe("line items", function()
    it("keeps prices and quantities fractional -- they are not currency units", function()
      stubMod({
        incoming = {
          makeInvoice({
            lineItems = {
              { workTypeId = 53, quantity = 2500, unitType = 4, price = 0.55, amount = 1375, name = "Goods" },
            },
          }),
        },
      })
      local line = VDT.Invoices.collect().invoices[1].lines[1]
      -- A floored 0.55 would be 0 and put every per-litre line out by a factor of a hundred.
      assert.equals(0.55, line.price)
      assert.equals(2500, line.quantity)
      assert.equals("liter", line.unit)
      assert.equals(1375, line.amount)
    end)

    it("omits the optional fields it has nothing to say about", function()
      stubMod({
        incoming = {
          makeInvoice({
            lineItems = { { workTypeId = 44, quantity = 2, unitType = 2, price = 1320, amount = 2640 } },
          }),
        },
      })
      local line = VDT.Invoices.collect().invoices[1].lines[1]
      assert.is_nil(line.vatRate)
      assert.is_nil(line.discountRate)
      assert.is_nil(line.fieldId)
      assert.is_nil(line.note)
    end)

    it("carries the field a line was billed for", function()
      stubMod({ incoming = { makeInvoice() } })
      local line = VDT.Invoices.collect().invoices[1].lines[1]
      assert.equals(12, line.fieldId)
      assert.equals(3.5, line.fieldArea)
      assert.equals("hectare", line.unit)
    end)
  end)

  describe("the work-type catalogue", function()
    it("prices every row for this server and localizes it out of the mod's own i18n", function()
      stubMod()
      local types = VDT.Invoices.collect().workTypes
      assert.equals(#WORK_TYPES, #types)
      assert.equals(2, types[1].id)
      assert.equals("L:invoice_work_plowing", types[1].name)
      assert.equals("hectare", types[1].unit)
      assert.equals(3080, types[1].price)
      assert.equals(0.1, types[1].vatRate)
    end)

    it("flags the three rows that need an in-game picker rather than dropping them", function()
      stubMod()
      local byId = {}
      for _, entry in ipairs(VDT.Invoices.collect().workTypes) do
        byId[entry.id] = entry
      end
      assert.equals("vehicle", byId[56].needsPicker)
      assert.equals("consumable", byId[37].needsPicker)
      assert.equals("fillType", byId[55].needsPicker)
      assert.is_nil(byId[2].needsPicker)
    end)

    it("publishes no VAT at all when the server has simulation off", function()
      stubMod({ vatEnabled = false })
      local model = VDT.Invoices.collect()
      assert.is_false(model.vatEnabled)
      for _, entry in ipairs(model.workTypes) do
        assert.is_nil(entry.vatRate)
      end
    end)

    it("falls back to the raw key when the mod has no translation", function()
      stubMod({ workTypes = { { id = 99, nameKey = "something_else", basePrice = 1, unit = 1 } } })
      -- A miss must not leak the engine's "Missing '...' in l10n_xx.xml" placeholder into the panel.
      assert.equals("something_else", VDT.Invoices.collect().workTypes[1].name)
    end)
  end)

  describe("the farm list", function()
    it("offers every farm but our own and the spectator", function()
      stubMod()
      local farms = VDT.Invoices.collect().farms
      assert.equals(1, #farms)
      assert.equals(2, farms[1].id)
      assert.equals("Meadow Farm", farms[1].name)
    end)

    it("hides a nameless farm, as the mod's own recipient picker does", function()
      -- A map or another mod can create a farm the player never sees (one server had a nameless
      -- "farm 14"); InvoicesMainDashboard:loadFarms filters it out on exactly this test, so offering
      -- it here would offer a recipient the mod would then refuse.
      stubMod({
        farms = {
          { farmId = 0, name = "Spectator", isSpectator = true },
          { farmId = 1, name = "Hillside" },
          { farmId = 2, name = "Meadow Farm" },
          { farmId = 14, name = "" },
          { farmId = 15 },
        },
      })
      local farms = VDT.Invoices.collect().farms
      assert.equals(1, #farms)
      assert.equals(2, farms[1].id)
    end)

    it("still shows an invoice from a farm it would not offer as a recipient", function()
      -- Hiding farm 14 from the picker must not hide an invoice it somehow raised: the app falls back
      -- to "Farm 14" for the missing name rather than dropping the row.
      stubMod({
        farms = { { farmId = 1, name = "Hillside" }, { farmId = 14, name = "" } },
        incoming = { makeInvoice({ senderFarmId = 14 }) },
      })
      local model = VDT.Invoices.collect()
      assert.is_nil(model.farms)
      assert.equals(1, #model.invoices)
      assert.equals(14, model.invoices[1].counterpartyId)
      assert.is_nil(model.invoices[1].counterpartyName)
    end)

    it("is omitted entirely in singleplayer, where there is nobody to bill", function()
      stubMod({ farms = { { farmId = 0, isSpectator = true }, { farmId = 1, name = "Hillside" } } })
      assert.is_nil(VDT.Invoices.collect().farms)
    end)
  end)

  describe("the document", function()
    it("omits empty arrays rather than encoding them as {}", function()
      stubMod()
      local model = VDT.Invoices.collect()
      -- An empty Lua table encodes as {} in JSON, which the Kotlin lists reject.
      assert.is_nil(model.invoices)
    end)

    it("carries the server's penalty terms so the app can explain a penalty", function()
      stubMod()
      local terms = VDT.Invoices.collect().penaltyTerms
      assert.is_true(terms.enabled)
      assert.equals(5, terms.ratePercent)
      assert.equals(1, terms.gracePeriods)
      assert.equals(25, terms.capPercent)
      assert.equals(3, terms.daysPerPeriod)
    end)

    it("still publishes the catalogue for a spectator, but no farm-scoped data", function()
      stubMod({ noFarm = true })
      local model = VDT.Invoices.collect()
      assert.is_nil(model.farmId)
      assert.is_nil(model.invoices)
      assert.is_nil(model.farms)
      assert.is_not_nil(model.workTypes)
    end)
  end)

  describe("farm changes", function()
    it("marks dirty when the player switches farm", function()
      stubMod()
      local subscriptions = {}
      local dirty = 0
      _G.MessageType = { PLAYER_FARM_CHANGED = "playerFarmChanged" }
      _G.g_messageCenter = {
        subscribe = function(_, message, callback, target)
          subscriptions[#subscriptions + 1] = { message = message, callback = callback, target = target }
        end,
      }
      VDT.ExportChannels.markDirty = function(name)
        if name == VDT.Invoices.CHANNEL then
          dirty = dirty + 1
        end
      end

      VDT.Invoices.tick(debugger)

      -- Nothing about a farm switch goes through the mod's notifyUI funnel -- it changes who is
      -- asking, not what is stored -- and this channel has no interval to fall back on.
      local farmChange = nil
      for _, entry in ipairs(subscriptions) do
        if entry.message == "playerFarmChanged" then
          farmChange = entry
        end
      end
      assert.is_not_nil(farmChange)

      local before = dirty
      farmChange.callback(farmChange.target)
      assert.equals(before + 1, dirty)
    end)
  end)

  describe("the change hook", function()
    it("appends to the mod's own notifyUI funnel and marks dirty once on install", function()
      local _, service = stubMod()
      local dirty = 0
      VDT.ExportChannels.markDirty = function(name)
        if name == VDT.Invoices.CHANNEL then
          dirty = dirty + 1
        end
      end

      -- The savegame's invoices are loaded without ever calling notifyUI, so the install itself has
      -- to queue a write or they would sit unexported until someone changed one.
      VDT.Invoices.tick(debugger)
      assert.equals(1, dirty)

      -- Installed on the CLASS table, so it reaches every instance through the metatable.
      _G.FS25_Invoices.InvoiceService.notifyUI(service)
      assert.equals(2, dirty)

      -- Idempotent: a second tick must not stack a second hook.
      VDT.Invoices.tick(debugger)
      _G.FS25_Invoices.InvoiceService.notifyUI(service)
      assert.equals(3, dirty)
    end)
  end)
end)
