-- Executes the FS25_Invoices write-back (app -> mod): pay, cancel, answer and issue invoices. The
-- write half of the invoices channel (src/integrations/Invoices.lua).
--
-- All five drive the mod's OWN service entry points -- InvoiceService:payInvoice / :deleteInvoice /
-- :validateProposal / :refuseProposal / :createAndSendInvoice -- which is exactly what its in-game
-- page's buttons do. **The mod's server is already the boundary and it is a thorough one**: from a
-- client each of those sends the mod's own event, and the server then re-checks the farmManager right,
-- that the invoice exists, its state, that the caller's farm is the right side of it, and (for a
-- payment) that the payer can afford it -- before any money moves. Everything below is therefore for
-- the UI and the log, never for safety.
--
-- ONE ASYMMETRY DECIDES WHERE OUR ARITHMETIC LIVES. That server-side sanitising -- discounts clamped
-- into [0,1], every line amount recomputed from price x quantity, the totals rebuilt -- lives in
-- InvoiceCreateEvent:run's CLIENT branch. On a host, or in singleplayer, createAndSendInvoice is
-- called directly and nothing recomputes anything. So createInvoice below builds its line amounts with
-- the mod's own Invoice.computeLineAmount and its totals with invoice:populateFromData, exactly as
-- InvoicesWizardState:createInvoice does -- correct on both paths, rather than only on the one that
-- happens to be re-checked.
--
-- THESE ARE ACTIONS, NOT TARGET STATES, and cannot be restated idempotently -- a doubled createInvoice
-- is a second invoice. Like takeLoan and createTask they carry no target state, are never replayed on
-- reconnect, and rely on the command channel's at-most-once id watermark (see Protocol.kt). A doubled
-- payInvoice is harmless only because the mod refuses to pay something already PAID.
--
-- An invoice is addressed by the repository id the read side exports, resolved back through the mod's
-- own repository:getById. Unlike the ELS loans' network object ids that id is persistent and
-- unambiguous, but a stale one must still fail to resolve rather than act on whatever now holds it,
-- and the role checks below are what make that safe.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.InvoiceControl = {}

-- The server's own cap on a single invoice (InvoiceCreateEvent:run). Re-stated here so the host path,
-- which never reaches that check, refuses the same thing.
VDT.InvoiceControl.MAX_LINES = 100

local function num(v)
  return type(v) == "number" and v or 0
end

-- A number the arithmetic below can survive. NaN fails every comparison, so a `<= 0` test alone never
-- sees it, and an infinity passes every range test and then poisons the total it is summed into --
-- both have to be named (the same screen FillUnit applies to a bottomless fill capacity).
local function finite(v)
  return type(v) == "number" and v == v and v ~= math.huge and v ~= -math.huge
end

---Shared preamble: the mod is up, this player may act, and we know which farm we are.
---@return table|nil manager, number|nil farmId, table|nil service
local function resolve(debugger, label)
  if not VDT.Invoices.isAvailable() then
    debugger:warn("%s: the Invoices mod is not installed -- ignoring", label)
    return nil, nil, nil
  end
  -- The same right every one of the mod's events checks; the server checks it again when the event
  -- lands, so this is for the log, not the boundary.
  if not VDT.Invoices.canManage() then
    debugger:warn("%s: this player may not manage the farm's invoices -- ignoring", label)
    return nil, nil, nil
  end
  local farmId = VDT.ProductionExporter.ownFarmId()
  if farmId == nil then
    debugger:warn("%s: no local farm resolved, refusing to act on an invoice", label)
    return nil, nil, nil
  end
  local manager = VDT.Invoices.manager()
  local service = type(manager) == "table" and manager.service or nil
  if type(service) ~= "table" then
    debugger:warn("%s: the invoice service is not available", label)
    return nil, nil, nil
  end
  return manager, farmId, service
end

---Resolve a live invoice by the exported repository id.
---@return table|nil invoice
local function resolveInvoice(manager, invoiceId, debugger, label)
  if type(invoiceId) ~= "number" then
    debugger:warn("%s: missing or invalid invoiceId (%s)", label, tostring(invoiceId))
    return nil
  end
  local repository = manager.repository
  if type(repository) ~= "table" or type(repository.getById) ~= "function" then
    debugger:warn("%s: the invoice repository is not available", label)
    return nil
  end
  local ok, invoice = pcall(repository.getById, repository, invoiceId)
  if not ok or type(invoice) ~= "table" then
    -- One the app still lists may have been paid or withdrawn by the other party since the last write.
    debugger:warn("%s: no invoice with id %s", label, tostring(invoiceId))
    return nil
  end
  return invoice
end

---Register one of the four id-addressed actions. They differ only in which action token the read side
---publishes for them and which service method they call, so they are built from one description
---rather than four near-identical blocks -- and the action token is checked against the SAME collector
---the app's buttons are driven by, so a button that should not have been offered cannot act either.
---@param commandType string
---@param action string the token from InvoicesModel.actions
---@param method string the InvoiceService method to call
local function registerAction(commandType, action, method)
  VDT.CommandRegistry.register(commandType, {
    requiresVehicle = false,
    parse = function(xml, key)
      return { invoiceId = xml:getInt(key .. "#invoiceId") }
    end,
    execute = function(_, params, debugger)
      local label = commandType
      local manager, farmId, service = resolve(debugger, label)
      if manager == nil then
        return
      end
      local invoice = resolveInvoice(manager, params.invoiceId, debugger, label)
      if invoice == nil then
        return
      end

      local states = VDT.Invoices.invoiceClass().STATE
      local allowed = VDT.Invoices.actionsFor(invoice, farmId, states, true) or {}
      local permitted = false
      for _, candidate in ipairs(allowed) do
        if candidate == action then
          permitted = true
          break
        end
      end
      if not permitted then
        debugger:warn("%s: invoice %d does not allow '%s' from this farm right now", label, invoice.id, action)
        return
      end

      if type(service[method]) ~= "function" then
        debugger:warn("%s: the invoice service has no %s", label, method)
        return
      end
      local okRun = pcall(service[method], service, invoice.id)
      if not okRun then
        debugger:error("%s: the invoice service refused the action on invoice %d", label, invoice.id)
        return
      end
      debugger:debug("%s %d", label, invoice.id)
    end,
  })
end

registerAction("payInvoice", "pay", "payInvoice")
registerAction("cancelInvoice", "cancel", "deleteInvoice")
registerAction("validateProposal", "validate", "validateProposal")
registerAction("refuseProposal", "refuse", "refuseProposal")

---Build one line item from the app's request, priced and taxed by the MOD's own rules.
---
---The app may send a price (the wizard lets a player override one), but never an amount: that is
---computed here with the mod's own Invoice.computeLineAmount so the host path and the client path
---produce the same invoice. An unpriced line falls back to the catalogue price the read side published.
---@param request table { workTypeId, quantity, price?, discount?, note?, fieldId? }
---@param service table the live InvoiceService
---@param invoiceClass table
---@param debugger GrisuDebug
---@param label string
---@return table|nil item a line item in the shape Invoice:writeStream expects
function VDT.InvoiceControl.buildLine(request, service, invoiceClass, debugger, label)
  local workTypeId = request.workTypeId
  if type(workTypeId) ~= "number" then
    debugger:warn("%s: line with missing workTypeId -- dropping", label)
    return nil
  end

  local okType, workType = pcall(service.getWorkTypeById, service, workTypeId)
  if not okType or type(workType) ~= "table" then
    debugger:warn("%s: unknown workTypeId %s -- dropping the line", label, tostring(workTypeId))
    return nil
  end
  -- The three the in-game wizard builds through a picker (vehicle sale, consumable sale, products).
  -- They carry ownership transfers we cannot assemble from a command, so they are refused rather than
  -- half-built -- the read side already flags them so the app should never offer one.
  if workType.vehicleDialog == true or workType.consumableDialog == true or workType.fillTypeDialog == true then
    debugger:warn("%s: work type %d needs an in-game picker -- dropping the line", label, workTypeId)
    return nil
  end

  local quantity = num(request.quantity)
  if not finite(quantity) or quantity <= 0 then
    debugger:warn("%s: line %d has no usable quantity (%s) -- dropping", label, workTypeId, tostring(request.quantity))
    return nil
  end

  local price = request.price
  if not finite(price) or price < 0 then
    local okPrice, adjusted = pcall(service.getAdjustedPrice, service, workTypeId)
    price = (okPrice and type(adjusted) == "number") and adjusted or 0
  end

  local okDiscount, discount = pcall(invoiceClass.sanitizeDiscountRate, request.discount)
  if not okDiscount or not finite(discount) then
    discount = 0
  end

  local unitType = math.floor(num(workType.unit))
  local okAmount, amount = pcall(invoiceClass.computeLineAmount, price, quantity, unitType, discount)
  if not okAmount or not finite(amount) then
    debugger:warn("%s: line %d could not be priced -- dropping", label, workTypeId)
    return nil
  end

  -- VAT follows the server's setting, exactly as the wizard applies it: no simulated VAT means a zero
  -- rate on every line, not the catalogue rate quietly applied anyway.
  local vatRate = 0
  if VDT.Invoices.vatEnabled(service) then
    local okVat, rate = pcall(service.getVatRateForWorkType, service, workTypeId)
    if okVat and type(rate) == "number" then
      vatRate = rate
    end
  end

  -- The read side's lookup, not a second one: it checks hasText first, so a key the mod has no
  -- translation for leaves the name empty instead of storing the engine's literal
  -- "Missing '<key>' in l10n_xx.xml" on the invoice and broadcasting it to every farm.
  local name = VDT.Invoices.modText(workType.nameKey)

  -- Every field Invoice:writeStream reads, defaulted the way the wizard defaults them -- a missing one
  -- would be a nil write on the network stream.
  return {
    workTypeId = workTypeId,
    amount = amount,
    quantity = quantity,
    unitType = unitType,
    fieldId = math.floor(num(request.fieldId)),
    fieldArea = 0,
    note = type(request.note) == "string" and request.note or "",
    vatRate = vatRate,
    discountRate = discount,
    name = name or "",
    iconFilename = "",
    price = price,
    vehicleUniqueId = "",
    consumableXmlFilename = "",
    consumableFillTypeIndex = 0,
    consumableFillLevel = 0,
  }
end

VDT.CommandRegistry.register("createInvoice", {
  requiresVehicle = false,
  parse = function(xml, key)
    -- The first command to carry children. CommandChannel hands each control the live XMLFile and the
    -- element's key, so the lines are read here and the channel stays free of the schema.
    local lines = {}
    xml:iterate(key .. ".line", function(_, lineKey)
      lines[#lines + 1] = {
        workTypeId = xml:getInt(lineKey .. "#workTypeId"),
        quantity = xml:getFloat(lineKey .. "#quantity"),
        price = xml:getFloat(lineKey .. "#price"),
        discount = xml:getFloat(lineKey .. "#discount"),
        fieldId = xml:getInt(lineKey .. "#fieldId"),
        note = xml:getString(lineKey .. "#note"),
      }
    end)
    return {
      farmId = xml:getInt(key .. "#farmId"),
      proposal = xml:getBool(key .. "#proposal", false),
      lines = lines,
    }
  end,
  execute = function(_, params, debugger)
    local label = "createInvoice"
    local manager, farmId, service = resolve(debugger, label)
    if manager == nil then
      return
    end
    local invoiceClass = VDT.Invoices.invoiceClass()

    local otherFarmId = params.farmId
    if type(otherFarmId) ~= "number" or otherFarmId <= 0 then
      debugger:warn("%s: missing or invalid farmId (%s)", label, tostring(otherFarmId))
      return
    end
    if otherFarmId == farmId then
      debugger:warn("%s: a farm cannot invoice itself -- ignoring", label)
      return
    end
    if g_farmManager == nil then
      debugger:warn("%s: no farm manager", label)
      return
    end
    -- The mod's own recipient rule (isBillableFarm), which includes having a name: a map or another
    -- mod can create a farm the player never sees, and the mod's picker filters it out on exactly
    -- that test -- so naming one here has to be refused rather than sent and silently dropped.
    local okFarm, otherFarm = pcall(g_farmManager.getFarmById, g_farmManager, otherFarmId)
    if not okFarm or not VDT.Invoices.isBillableFarm(otherFarm) then
      debugger:warn("%s: farm %d cannot be invoiced", label, otherFarmId)
      return
    end

    local requested = params.lines or {}
    if #requested == 0 then
      debugger:warn("%s: no lines -- nothing to invoice", label)
      return
    end
    if #requested > VDT.InvoiceControl.MAX_LINES then
      debugger:warn(
        "%s: %d lines is past the mod's cap of %d -- ignoring",
        label,
        #requested,
        VDT.InvoiceControl.MAX_LINES
      )
      return
    end

    local items = {}
    for _, request in ipairs(requested) do
      items[#items + 1] = VDT.InvoiceControl.buildLine(request, service, invoiceClass, debugger, label)
    end
    -- A line that could not be built is dropped with a log line, but an invoice whose lines ALL failed
    -- must not be sent as an empty one -- the mod would take it and bill nothing.
    if #items == 0 then
      debugger:warn("%s: no usable lines -- ignoring", label)
      return
    end

    -- Roles follow the wizard's own two modes: a normal invoice makes us the issuer, a proposal makes
    -- us the payer asking to be billed (and the named farm the issuer who must then validate it).
    local isProposal = params.proposal == true
    local senderFarmId = isProposal and otherFarmId or farmId
    local recipientFarmId = isProposal and farmId or otherFarmId

    local okNew, invoice = pcall(invoiceClass.new)
    if not okNew or type(invoice) ~= "table" then
      debugger:warn("%s: could not create the invoice", label)
      return
    end
    -- id 0 asks the repository (server-side) for the next one; populateFromData also computes the
    -- totals and stamps the creation date.
    local okPopulate = pcall(invoice.populateFromData, invoice, 0, items, recipientFarmId, senderFarmId)
    if not okPopulate then
      debugger:warn("%s: could not populate the invoice", label)
      return
    end
    if isProposal then
      invoice.state = invoiceClass.STATE.PROPOSED
    end

    local okSend = pcall(service.createAndSendInvoice, service, invoice)
    if not okSend then
      debugger:error("%s: the invoice service refused the invoice", label)
      return
    end
    debugger:debug(
      "%s: %s of %d line(s) totalling %d to farm %d",
      label,
      isProposal and "proposal" or "invoice",
      #items,
      math.floor(num(invoice.totalAmount)),
      otherFarmId
    )
  end,
})
