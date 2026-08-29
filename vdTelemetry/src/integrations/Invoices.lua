-- Optional integration: FS25_Invoices (by Squallqt) -- invoices raised between farms, for contractor
-- play on multiplayer servers. An event-driven export channel: it self-detects the mod and serializes
-- the local farm's invoices, plus the two catalogues the app's builder needs, into invoices.json.
-- **Absence of that file is the app's "not installed" signal** (the TaskList contract) -- when the mod
-- isn't present the channel is registered but never writes, and a file left over from a session where
-- it *was* installed is deleted at startup.
--
-- **Written against FS25_Invoices 1.2.1.0** -- everything here reads that mod's *internals*, which it
-- is free to rename in any release. So fail soft, never throw: a missing field means "no data",
-- because a throw in a collector takes the whole telemetry write down with it. Same contract on the
-- write side (src/command/InvoiceControl.lua).
--
-- 1.2.1.0 renamed nothing, but it did rewrite one RULE we mirror -- when a late-payment penalty first
-- lands (see daysUntilPenalty). A rule is the more dangerous kind of change: a rename takes the field
-- away and the fail-soft path shows a gap, where a rewritten rule keeps answering, plausibly, with
-- the old number.
--
-- REACHING THE MOD. Unusually, the main handle is not in the mod's Lua environment at all:
-- `g_currentMission.invoicesManager` is set on the MISSION, which is shared, so the repository and the
-- service are reachable directly. Only the two class TABLES need the env lookup -- `FS25_Invoices.Invoice`
-- for its STATE/UNIT_* constants and `FS25_Invoices.InvoiceService` for the work-type catalogue and the
-- hook below. The bare globals `Invoice` / `InvoiceService` are nil from our environment (see
-- CropRotation.lua, where the same trap already bit), and betting on the env key is what the ELS and
-- CropRotation integrations already do. Both handles are required: without the classes the state and
-- unit tokens would have to be hardcoded numbers, which is exactly the renumbering this repo refuses
-- to be exposed to.
--
-- WHAT MAKES IT DIRTY. The mod publishes no message-center event of its own, but every mutation
-- already funnels through `InvoiceService:notifyUI` -- creation, payment, deletion, proposal
-- validation, the join-time full sync, and the penalty sync. Appending to that on the class table
-- reaches every instance (dispatch goes through the metatable's __index), the same way the finance
-- channel hooks `HUD:showMoneyChange`. Appended rather than prepended: nothing is being zeroed on the
-- way out, so running after the mod has finished its own refresh is the safer order.
-- The one thing the hook does not cover is `repository:loadFromXML` at mission start, which never
-- calls notifyUI -- so tick() also marks dirty once when it first installs the hook, and a savegame's
-- existing invoices land without waiting for someone to change one.
-- Switching farm moves every farm-scoped field in this document -- which side of an invoice we are on,
-- which farms are billable, every `actions` entry -- and goes through no funnel of the mod's either,
-- because it changes who is asking rather than what is stored. That is not this file's problem any
-- more: the channel registers `farmScoped = true` and the registry's single PLAYER_FARM_CHANGED
-- subscription marks it dirty (see src/export/ExportChannels.lua, issue #78).
--
-- MULTIPLAYER is free here, unlike the finance channel's archived columns: invoice state is fully
-- replicated (InvoiceSyncEvent at join, then every mutation broadcast), so host and client read the
-- same repository and this file has no server-only branch. The mod is however MULTIPLAYER-ONLY in
-- practice -- an invoice needs two different farms and singleplayer has one -- so in SP this correctly
-- exports the settings, the catalogue, no farms and no invoices.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.Invoices = {}

VDT.Invoices.CHANNEL = "invoices"
VDT.Invoices.FILE_NAME = "invoices.json"
-- Own version, evolving independently of VDTelemetry.VERSION and the shared Kotlin InvoicesData.
VDT.Invoices.VERSION = 1

-- The mod's env key, which is its folder/zip name. Used for both the class tables and its i18n
-- namespace, so a rename only has to be corrected in one place.
local MOD_NAME = "FS25_Invoices"

local hooked = false -- InvoiceService.notifyUI appended?

local function num(v)
  return type(v) == "number" and v or 0
end

-- Whole currency units, character for character the finance channel's money(): the two panels show
-- the same kinds of amounts and a half-unit disagreement between them would read as a bug.
local function money(value)
  return math.floor(num(value) + 0.5)
end

-- g_i18n is checked before it is indexed rather than merely pcall'd (same guard as FinanceExporter):
-- `pcall(g_i18n.getText, ...)` evaluates the field access first, so a nil g_i18n would throw outside
-- the pcall's protection.
--
-- The key lives in the MOD's i18n namespace: I18N:addModI18N gives every mod its own `texts` table, so
-- from our environment the only way in is the customEnv argument. A miss returns the literal string
-- "Missing '<key>' in l10n_xx.xml" rather than nil, which must not reach the panel -- hence hasText.
--
-- Public because the write side needs the same lookup: a line's stored name is a work-type text too,
-- and MOD_NAME must not be spelled out a second time (src/command/InvoiceControl.lua).
---@param key string|nil
---@return string|nil
function VDT.Invoices.modText(key)
  if type(key) ~= "string" or key == "" or g_i18n == nil then
    return nil
  end
  if type(g_i18n.hasText) ~= "function" or type(g_i18n.getText) ~= "function" then
    return nil
  end
  local okHas, has = pcall(g_i18n.hasText, g_i18n, key, MOD_NAME)
  if not okHas or has ~= true then
    return nil
  end
  local ok, text = pcall(g_i18n.getText, g_i18n, key, MOD_NAME)
  if not ok or type(text) ~= "string" or text == "" then
    return nil
  end
  return text
end

-- The mod's env table (keyed by the exact mod name); nil when it isn't installed.
local function env()
  return type(_G[MOD_NAME]) == "table" and _G[MOD_NAME] or nil
end

---The mod's manager, or nil when it isn't installed/up. On the mission, so no env lookup.
---@return table|nil
function VDT.Invoices.manager()
  return g_currentMission ~= nil and g_currentMission.invoicesManager or nil
end

---The mod's Invoice class -- STATE and UNIT_* constants, and the pricing helpers the write side uses.
---@return table|nil
function VDT.Invoices.invoiceClass()
  local e = env()
  local class = e ~= nil and e.Invoice or nil
  return (type(class) == "table" and type(class.STATE) == "table") and class or nil
end

---The mod's InvoiceService class -- the work-type catalogue, the penalty constants, and the table the
---hook is installed on.
---@return table|nil
function VDT.Invoices.serviceClass()
  local e = env()
  local class = e ~= nil and e.InvoiceService or nil
  return type(class) == "table" and class or nil
end

---Whether this player may act on an invoice at all. Every one of the mod's events checks the game's
---`farmManager` right -- the same one the base-game loan uses, and NOT the MANAGE_RIGHTS the Enhanced
---Loan System gates on. Drives the UI; the server re-checks it when the event lands.
---@return boolean
function VDT.Invoices.canManage()
  if g_currentMission == nil or type(g_currentMission.getHasPlayerPermission) ~= "function" then
    return false
  end
  local ok, allowed = pcall(g_currentMission.getHasPlayerPermission, g_currentMission, "farmManager")
  return ok and allowed == true
end

---Token for one of the mod's numeric enum values, derived from the enum's KEY rather than its number
---so a renumbering in the mod cannot silently change what we publish (the rule MapExporter set).
---@param enum table map of KEY -> number
---@param prefix string|nil strip this from the key first (e.g. "UNIT_")
---@return table map of number -> lowercase token
local function tokensOf(enum, prefix)
  local tokens = {}
  for key, value in pairs(enum) do
    if type(key) == "string" and type(value) == "number" then
      local name = key
      if prefix ~= nil then
        if string.sub(key, 1, #prefix) ~= prefix then
          name = nil
        else
          name = string.sub(key, #prefix + 1)
        end
      end
      if name ~= nil and name ~= "" then
        tokens[value] = string.lower(name)
      end
    end
  end
  return tokens
end

---The unit tokens (piece/hour/hectare/liter), read off the Invoice class's UNIT_* constants.
---@param invoiceClass table
---@return table map of number -> token
function VDT.Invoices.unitTokens(invoiceClass)
  return tokensOf(invoiceClass, "UNIT_")
end

---The state tokens (new/sent/paid/cancelled/proposed), read off Invoice.STATE.
---@param invoiceClass table
---@return table map of number -> token
function VDT.Invoices.stateTokens(invoiceClass)
  return tokensOf(invoiceClass.STATE, nil)
end

---The in-game creation stamp, as DD.MM.YYYY + HH:MM.
---
---The mod stores `createdAt` already converted to a CALENDAR month and year, and it rolls January and
---February into the following year (a game year starts in March). Our own environment.date does not
---roll -- so the roll is undone here before ValueMapper's 2023 offset is applied, and an invoice's date
---reads the same as every other date in the app. Without that, two months of every year would
---disagree by one between two panels, which reads as a bug whichever of the two is "right".
---@param createdAt table the mod's stamp
---@return string|nil date, string|nil time
function VDT.Invoices.formatStamp(createdAt)
  if type(createdAt) ~= "table" then
    return nil, nil
  end
  local month = math.floor(num(createdAt.period))
  local year = math.floor(num(createdAt.year))
  -- year 0 is an unstamped invoice (the environment was not up when it was created): no date rather
  -- than a wrong one.
  if month < 1 or month > 12 or year < 1 then
    return nil, nil
  end
  if month < 3 then
    year = year - 1
  end
  local date =
    string.format("%02d.%02d.%04d", math.floor(num(createdAt.day)), month, ValueMapper.mapYearToCalendarYear(year))
  local time = string.format("%02d:%02d", math.floor(num(createdAt.hour)), math.floor(num(createdAt.minute)))
  return date, time
end

---What this player may do with one invoice, mirroring the checks in the mod's own InvoiceStateEvent:run.
---Structural only -- state plus which side of the invoice this farm is on. Affordability is left to the
---app (see the model notes), because the balance moves faster than this channel writes.
---@param invoice table
---@param farmId number the local farm
---@param states table Invoice.STATE
---@param canManage boolean
---@return string[]|nil actions
function VDT.Invoices.actionsFor(invoice, farmId, states, canManage)
  if not canManage then
    return nil
  end
  local actions = {}
  local state = invoice.state
  local isSender = invoice.senderFarmId == farmId
  local isRecipient = invoice.recipientFarmId == farmId

  -- Pay: written as the event writes it (anything that is not already settled and is not still a
  -- proposal), not as "state == NEW" -- so a state the mod starts using one day is not silently
  -- treated as unpayable.
  if isRecipient and state ~= states.PAID and state ~= states.PROPOSED then
    actions[#actions + 1] = "pay"
  end
  -- Cancel: the issuer withdraws an unpaid invoice, or the payer withdraws a proposal they raised.
  if (state == states.NEW and isSender) or (state == states.PROPOSED and isRecipient) then
    actions[#actions + 1] = "cancel"
  end
  -- A proposal is answered by the issuer it names.
  if state == states.PROPOSED and isSender then
    actions[#actions + 1] = "validate"
    actions[#actions + 1] = "refuse"
  end

  return #actions > 0 and actions or nil
end

---In-game days until this invoice starts accruing a penalty, or nil when the question does not apply
---(penalties off, already accruing, or a state that never accrues).
---
---The mod's rule (InvoiceService:processPenalties): overdueDays = elapsedDays - gracePeriods *
---daysPerPeriod, and a penalty lands once that is positive. So the first one is due the day AFTER the
---grace window runs out -- grace is counted in whole periods, but the wait is then counted in days.
---1.2.0.0 counted in periods throughout (floor(elapsedDays / daysPerPeriod) - gracePeriods) and only
---ever accrued on the last day of one, which on a multi-day period put the first penalty a whole
---period later than it now lands.
---
---Floored at 0 rather than going negative, for two reasons that both survive that change: the mod
---polls on a timer rather than on the day rollover, so there is a window where the day has passed and
---the penalty has not been written yet; and a penalty that rounds to 0 on a small invoice never makes
---`penaltyAmount` positive at all, so the countdown below would otherwise run negative forever.
---@param invoice table
---@param terms table the PenaltyTermsModel already collected
---@param states table Invoice.STATE
---@param currentDay number
---@return number|nil
function VDT.Invoices.daysUntilPenalty(invoice, terms, states, currentDay)
  if not terms.enabled or num(invoice.penaltyAmount) > 0 then
    return nil
  end
  local state = invoice.state
  if state == states.PAID or state == states.CANCELLED or state == states.PROPOSED then
    return nil
  end
  local createdDay = math.floor(num(invoice.createdDay))
  if createdDay <= 0 or currentDay <= 0 then
    return nil
  end
  local daysPerPeriod = math.max(1, math.floor(num(terms.daysPerPeriod)))
  local due = createdDay + math.floor(num(terms.gracePeriods)) * daysPerPeriod + 1
  return math.max(due - currentDay, 0)
end

---One line item.
---@param item table
---@param units table unit token map
---@return InvoiceLineModel|nil
function VDT.Invoices.collectLine(item, units)
  if type(item) ~= "table" then
    return nil
  end
  local unitType = math.floor(num(item.unitType))

  ---@type InvoiceLineModel
  local line = {
    workTypeId = math.floor(num(item.workTypeId)),
    -- Prices and quantities are genuinely fractional -- a hectare figure has two decimals and a
    -- `liter` row is priced per 1000 l -- so these deliberately skip money()'s floor. Rounding them
    -- would put every per-litre line out by a factor of a hundred.
    quantity = num(item.quantity),
    unit = units[unitType] or "piece",
    price = num(item.price),
    amount = money(item.amount),
  }
  if type(item.name) == "string" and item.name ~= "" then
    line.name = item.name
  end
  if num(item.vatRate) > 0 then
    line.vatRate = num(item.vatRate)
  end
  if num(item.discountRate) > 0 then
    line.discountRate = num(item.discountRate)
  end
  if num(item.fieldId) > 0 then
    line.fieldId = math.floor(num(item.fieldId))
    if num(item.fieldArea) > 0 then
      line.fieldArea = num(item.fieldArea)
    end
  end
  if type(item.note) == "string" and item.note ~= "" then
    line.note = item.note
  end
  return line
end

---One invoice, from the local farm's point of view.
---@param invoice table
---@param direction string incoming | outgoing -- which of the mod's own two lists this came from
---@param ctx table { farmId, states, stateTokens, units, canManage, terms, currentDay, farmNames }
---@return InvoiceModel|nil
function VDT.Invoices.collectInvoice(invoice, direction, ctx)
  if type(invoice) ~= "table" or type(invoice.id) ~= "number" then
    return nil
  end

  local total = num(invoice.totalAmount)
  -- The mod's own fallback (executePayment): a pre-VAT savegame has no totalHT, and then the whole
  -- total is net.
  local net = type(invoice.totalHT) == "number" and invoice.totalHT or total
  local penalty = num(invoice.penaltyAmount)

  ---@type InvoiceModel
  local model = {
    id = invoice.id,
    direction = direction,
    -- A state the mod's own STATE enum does not carry cannot be named, but the field is required:
    -- "unknown" (MissionExporter's fallback for the same situation) keeps the chip labelled and the
    -- app's `else` branch prints it, where an omitted field would decode to an empty chip.
    state = ctx.stateTokens[invoice.state] or "unknown",
    senderFarmId = math.floor(num(invoice.senderFarmId)),
    recipientFarmId = math.floor(num(invoice.recipientFarmId)),
    total = money(total),
    totalNet = money(net),
    -- Both sides of the asymmetry, because paying one invoice moves two different numbers and the VAT
    -- between them goes nowhere (see the file header).
    totalDue = money(total + penalty),
    credit = money(net + penalty),
  }

  if num(invoice.vatAmount) > 0 then
    model.vat = money(invoice.vatAmount)
  end
  if penalty > 0 then
    model.penalty = money(penalty)
    -- The mod's own definition of overdue: a penalty has actually accrued.
    model.overdue = true
  end

  local counterpartyId = (model.senderFarmId == ctx.farmId) and model.recipientFarmId or model.senderFarmId
  if counterpartyId > 0 then
    model.counterpartyId = counterpartyId
    model.counterpartyName = ctx.farmNames[counterpartyId]
  end

  model.daysUntilPenalty = VDT.Invoices.daysUntilPenalty(invoice, ctx.terms, ctx.states, ctx.currentDay)
  model.date, model.time = VDT.Invoices.formatStamp(invoice.createdAt)
  model.actions = VDT.Invoices.actionsFor(invoice, ctx.farmId, ctx.states, ctx.canManage)

  local lines = {}
  for _, item in ipairs(invoice.lineItems or {}) do
    lines[#lines + 1] = VDT.Invoices.collectLine(item, ctx.units)
  end
  -- omit empty arrays (nil, not {}): an empty Lua table encodes as {} which the Kotlin lists reject
  model.lines = #lines > 0 and lines or nil

  return model
end

---The work-type catalogue, priced for THIS server: the difficulty multiplier and the server's own
---vatRates.xml are both baked in, which is the whole reason it is exported rather than hardcoded in
---the app.
---@param service table the live InvoiceService
---@param serviceClass table its class table (WORK_TYPES lives there)
---@param units table unit token map
---@return WorkTypeModel[]
function VDT.Invoices.collectWorkTypes(service, serviceClass, units)
  local rows = type(serviceClass.WORK_TYPES) == "table" and serviceClass.WORK_TYPES or {}
  local vatEnabled = VDT.Invoices.vatEnabled(service)

  local types = {}
  for _, workType in ipairs(rows) do
    if type(workType) == "table" and type(workType.id) == "number" then
      local name = VDT.Invoices.modText(workType.nameKey)
      ---@type WorkTypeModel
      local entry = {
        id = workType.id,
        name = name or tostring(workType.nameKey or workType.id),
        unit = units[math.floor(num(workType.unit))] or "piece",
        price = 0,
      }

      local okPrice, price = pcall(service.getAdjustedPrice, service, workType.id)
      if okPrice and type(price) == "number" and price == price then
        entry.price = price
      end

      if vatEnabled then
        local okVat, rate = pcall(service.getVatRateForWorkType, service, workType.id)
        if okVat and type(rate) == "number" and rate > 0 then
          entry.vatRate = rate
        end
      end

      -- The three rows the in-game wizard builds through a picker we do not have. Flagged rather than
      -- dropped, so the app can say why they are missing instead of silently offering 53 of 56.
      if workType.vehicleDialog == true then
        entry.needsPicker = "vehicle"
      elseif workType.consumableDialog == true then
        entry.needsPicker = "consumable"
      elseif workType.fillTypeDialog == true then
        entry.needsPicker = "fillType"
      end

      types[#types + 1] = entry
    end
  end
  return types
end

---Whether a farm may appear on an invoice at all, mirroring InvoicesMainDashboard:loadFarms's own
---`isValidFarm`: a real farm id, not the spectator, **and a non-empty name**.
---
---That last condition is the load-bearing one. A map or another mod can create a farm the player never
---sees -- one server had a nameless "farm 14" -- and the mod's own recipient picker filters it out on
---exactly this test. Without it the terminal would offer a recipient the mod would then refuse.
---@param farm table
---@return boolean
function VDT.Invoices.isBillableFarm(farm)
  if type(farm) ~= "table" or type(farm.farmId) ~= "number" or farm.farmId <= 0 then
    return false
  end
  local spectator = (FarmManager ~= nil and type(FarmManager.SPECTATOR_FARM_ID) == "number")
      and FarmManager.SPECTATOR_FARM_ID
    or 0
  if farm.farmId == spectator or farm.isSpectator == true then
    return false
  end
  return type(farm.name) == "string" and farm.name ~= ""
end

---The farms this farm could bill: everything isBillableFarm accepts, less our own -- an invoice needs
---two different farms, which the mod refuses to create otherwise.
---
---The returned NAME map is deliberately wider than the list: it covers every non-spectator farm,
---billable or not, so an invoice that somehow names one still gets a label rather than a bare id.
---@param farmId number the local farm
---@return InvoiceFarmModel[] farms, table names map of farmId -> name (including our own, for labels)
function VDT.Invoices.collectFarms(farmId)
  local farms, names = {}, {}
  local manager = g_farmManager
  if manager == nil or type(manager.farms) ~= "table" then
    return farms, names
  end

  for _, farm in ipairs(manager.farms) do
    if type(farm) == "table" and type(farm.farmId) == "number" and farm.farmId > 0 and farm.isSpectator ~= true then
      names[farm.farmId] = (type(farm.name) == "string" and farm.name ~= "") and farm.name or nil
      if farm.farmId ~= farmId and VDT.Invoices.isBillableFarm(farm) then
        ---@type InvoiceFarmModel
        local entry = { id = farm.farmId, name = farm.name }
        farms[#farms + 1] = entry
      end
    end
  end
  return farms, names
end

---Whether the server simulates VAT. Its own function so the collector and the write side ask the
---question identically.
---@param service table
---@return boolean
function VDT.Invoices.vatEnabled(service)
  if type(service.isVatEnabled) ~= "function" then
    return false
  end
  local ok, enabled = pcall(service.isVatEnabled, service)
  return ok and enabled == true
end

---The server's late-payment rules.
---@param service table
---@param serviceClass table
---@return PenaltyTermsModel
function VDT.Invoices.collectPenaltyTerms(service, serviceClass)
  local function ask(name, fallback)
    if type(service[name]) ~= "function" then
      return fallback
    end
    local ok, value = pcall(service[name], service)
    if not ok then
      return fallback
    end
    return value
  end

  ---@type PenaltyTermsModel
  return {
    enabled = ask("isPenaltyEnabled", false) == true,
    ratePercent = num(ask("getPenaltyRate", 0)),
    gracePeriods = num(serviceClass.PENALTY_GRACE_PERIODS),
    capPercent = num(serviceClass.PENALTY_CAP_PERCENT),
    daysPerPeriod = math.max(1, math.floor(num(ask("getDaysPerPeriod", 1)))),
  }
end

function VDT.Invoices.isAvailable()
  return VDT.Invoices.manager() ~= nil and VDT.Invoices.invoiceClass() ~= nil and VDT.Invoices.serviceClass() ~= nil
end

---Build the invoices model, or nil when the mod isn't up (skips the write, so the file's absence keeps
---meaning "not installed").
---@return InvoicesModel|nil
function VDT.Invoices.collect()
  if not VDT.Invoices.isAvailable() then
    return nil
  end
  local manager = VDT.Invoices.manager()
  local service = manager.service
  local invoiceClass = VDT.Invoices.invoiceClass()
  local serviceClass = VDT.Invoices.serviceClass()
  if type(service) ~= "table" then
    return nil
  end

  local farmId = VDT.Farm.ownFarmId()

  ---@type InvoicesModel
  local model = {
    version = tostring(VDT.Invoices.VERSION),
    vatEnabled = VDT.Invoices.vatEnabled(service),
    penaltyTerms = VDT.Invoices.collectPenaltyTerms(service, serviceClass),
  }

  local units = VDT.Invoices.unitTokens(invoiceClass)
  local workTypes = VDT.Invoices.collectWorkTypes(service, serviceClass, units)
  model.workTypes = #workTypes > 0 and workTypes or nil

  -- Spectator / no owned farm: the catalogue above still means something, but there is nobody to bill
  -- and nothing to owe. Same treatment as the finance channel's farmless document.
  if farmId == nil then
    return model
  end

  model.farmId = farmId
  model.canManage = VDT.Invoices.canManage()

  local farms, farmNames = VDT.Invoices.collectFarms(farmId)
  model.farms = #farms > 0 and farms or nil

  local environment = g_currentMission ~= nil and g_currentMission.environment or nil
  local ctx = {
    farmId = farmId,
    -- The mod's enum table itself, read-only -- nothing of ours is ever written into it; the derived
    -- token map is a separate table of ours.
    states = invoiceClass.STATE,
    stateTokens = VDT.Invoices.stateTokens(invoiceClass),
    units = units,
    canManage = model.canManage,
    terms = model.penaltyTerms,
    currentDay = type(environment) == "table" and math.floor(num(environment.currentDay)) or 0,
    farmNames = farmNames,
  }

  -- Both directions from the MOD's own two accessors rather than a walk of the repository: they
  -- already filter to this farm and already apply the proposal inversion (a proposal is outgoing for
  -- the payer who raised it, incoming for the issuer who must answer). One rule, in one place, and it
  -- is theirs.
  local invoices = {}
  for _, entry in ipairs({ { "incoming", manager.getIncomingInvoices }, { "outgoing", manager.getOutgoingInvoices } }) do
    local ok, rows = pcall(entry[2], manager, farmId)
    if ok and type(rows) == "table" then
      for _, invoice in ipairs(rows) do
        invoices[#invoices + 1] = VDT.Invoices.collectInvoice(invoice, entry[1], ctx)
      end
    end
  end
  table.sort(invoices, function(a, b)
    return a.id < b.id
  end)
  model.invoices = #invoices > 0 and invoices or nil

  return model
end

-- MessageCenter-style callback; the hook passes the service instance, which we ignore.
function VDT.Invoices.markDirty()
  VDT.ExportChannels.markDirty(VDT.Invoices.CHANNEL)
end

---The notifyUI hook itself. Contained end to end: this executes inside the mod's own refresh path,
---where a throw of ours would break its in-game list.
function VDT.Invoices.onNotifyUI()
  pcall(VDT.Invoices.markDirty)
end

-- Install the hook once, lazily -- the mod's class table only exists once it has loaded, and a
-- disabled channel never ticks, so "off" really is off.
local function installHook(debugger)
  if hooked then
    return
  end
  local serviceClass = VDT.Invoices.serviceClass()
  if serviceClass == nil or type(serviceClass.notifyUI) ~= "function" or Utils == nil then
    return
  end
  serviceClass.notifyUI = Utils.appendedFunction(serviceClass.notifyUI, VDT.Invoices.onNotifyUI)
  hooked = true
  -- loadFromXML at mission start never calls notifyUI, so without this a savegame's existing invoices
  -- would sit unexported until someone changed one.
  VDT.Invoices.markDirty()
  debugger:info("Invoices integration active (hooked InvoiceService:notifyUI)")
end

---Per-tick hook: install the change hook once the mod is up. The farm switch, which no hook of the
---mod's covers, is the registry's job -- see the header and `farmScoped` below.
function VDT.Invoices.tick(debugger)
  if not VDT.Invoices.isAvailable() then
    return
  end
  installHook(debugger)
end

-- Test seam: drop the lazily-installed state between spec cases.
function VDT.Invoices.reset()
  hooked = false
end

-- Self-register the channel (see ExportChannels). Registered even when the mod isn't installed;
-- isAvailable() then keeps the file from ever being written, so its absence signals "not installed".
VDT.ExportChannels.register({
  name = VDT.Invoices.CHANNEL,
  fileName = VDT.Invoices.FILE_NAME,
  isAvailable = VDT.Invoices.isAvailable,
  collect = VDT.Invoices.collect,
  tick = VDT.Invoices.tick,
  -- Every invoice is read from one side or the other, and `actions` says what THIS farm may do with it.
  farmScoped = true,
})
