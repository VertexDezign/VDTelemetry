-- Executes the FS25_EnhancedLoanSystem write-back (app -> mod): take out an annuity loan, and make a
-- special redemption payment against one. The write half of the ELS block on the finance channel
-- (src/integrations/EnhancedLoanSystem.lua).
--
-- Both drive the mod's OWN entry points -- ELS_loanManager:addLoan and :specialRedemptionPayment --
-- which is exactly what its in-game screen's dialog callbacks do. ELS ships no event wrapper for
-- either, and does not need one: ELS_loan is a replicated Object, so a client creating one sends
-- OBJECT_CREATED to the server (Client:registerObject), whose readStream files it into the SERVER's
-- loan table; and a redemption's field changes reach the server through the client's own dirty-object
-- update stream. So both work from a dedicated-server client, which is where this will mostly be used.
--
-- BOTH ARE ACTIONS, NOT TARGET STATES, and cannot be restated idempotently -- a doubled takeLoan is a
-- second loan. Like createTask and unloadObjectStorage they carry no target state, are never replayed
-- on reconnect, and rely on the command channel's at-most-once id watermark (see Protocol.kt).
--
-- A loan is addressed by the network object id the read side exports, resolved back by WALKING the
-- farm's loans rather than NetworkUtil.getObject(id) -- that would happily hand back any registered
-- object with that id, and a stale id from the app must fail to resolve rather than resolve to a
-- trailer (same rule as MissionControl).
--
-- The clamps below mirror ELS's own dialogs rather than inventing rules, and they are RE-DERIVED here
-- rather than trusted from the app: the borrowing ceiling especially, which the read side publishes
-- from a 30 s cache. addLoan itself clamps nothing at all -- ELS does it in the dialog's text input --
-- so if we did not, an app one write out of date could borrow past the ceiling.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.EnhancedLoanControl = {}

local function num(v)
  return type(v) == "number" and v or 0
end

---Shared preamble: the mod is up, this player may manage its loans, and there is a local farm to act
---on. The farm OBJECT is returned rather than its id alone -- the redemption clamp needs its money too,
---and resolving it a second time would only invite the two halves to disagree.
---@return table|nil manager, table|nil farm
local function resolve(debugger, label)
  local manager = VDT.EnhancedLoanSystem.loanManager()
  if manager == nil then
    debugger:warn("%s: Enhanced Loan System is not installed -- ignoring", label)
    return nil, nil
  end
  -- ELS gates its own buttons on MANAGE_RIGHTS, not the base loan's farmManager right.
  if not VDT.EnhancedLoanSystem.canManage() then
    debugger:warn("%s: this player may not manage the farm's loans -- ignoring", label)
    return nil, nil
  end
  local farm = VDT.FinanceExporter.ownFarm()
  if farm == nil then
    debugger:warn("%s: no local farm resolved, refusing to act on a loan", label)
    return nil, nil
  end
  return manager, farm
end

---Resolve a live loan by the exported network object id, restricted to this farm's own.
---@return table|nil loan
local function resolveLoan(manager, farmId, loanId, debugger, label)
  if loanId == nil then
    debugger:warn("%s: missing loanId", label)
    return nil
  end
  local ok, loans = pcall(manager.currentLoans, manager, farmId)
  if not ok or type(loans) ~= "table" then
    debugger:warn("%s: could not read the farm's loans", label)
    return nil
  end
  for _, loan in ipairs(loans) do
    local okId, id = pcall(NetworkUtil.getObjectId, loan)
    if okId and id == loanId then
      return loan
    end
  end
  -- A loan the app still lists may have been cleared by its final instalment since the last write.
  debugger:warn("%s: no running loan with id %s on this farm", label, tostring(loanId))
  return nil
end

VDT.CommandRegistry.register("takeLoan", {
  requiresVehicle = false,
  parse = function(xml, key)
    return {
      amount = xml:getInt(key .. "#amount"),
      durationYears = xml:getInt(key .. "#durationYears"),
    }
  end,
  execute = function(_, params, debugger)
    local label = "takeLoan"
    local manager, farm = resolve(debugger, label)
    if manager == nil then
      return
    end

    local amount = params.amount
    if type(amount) ~= "number" or amount ~= amount or amount <= 0 then
      debugger:warn("%s: missing or invalid amount (%s)", label, tostring(amount))
      return
    end

    -- Freshly computed, never the read side's cached figure: the ceiling moves with the farm's money.
    local ceiling = VDT.EnhancedLoanSystem.maxAmount(farm.farmId, true)
    if ceiling == nil then
      debugger:warn("%s: could not determine the borrowing ceiling", label)
      return
    end
    if ceiling <= 0 then
      debugger:warn("%s: the bank will not lend this farm anything right now", label)
      return
    end
    if amount > ceiling then
      debugger:debug("%s: %d is above the ceiling, borrowing %d instead", label, amount, ceiling)
      amount = ceiling
    end

    local props = VDT.EnhancedLoanSystem.properties()
    if props == nil then
      debugger:warn("%s: the loan system's settings are not available", label)
      return
    end
    local maxDuration = math.floor(num(props.maxLoanDuration))
    local duration = math.floor(num(params.durationYears))
    if duration < 1 then
      debugger:warn("%s: missing or invalid duration (%s)", label, tostring(params.durationYears))
      return
    end
    if maxDuration > 0 and duration > maxDuration then
      debugger:debug("%s: %d years is longer than the bank offers, using %d", label, duration, maxDuration)
      duration = maxDuration
    end

    -- The rate is the bank's, never the app's -- but it still has to be a usable one. Checked rather
    -- than passed through, because ELS's annuity divides by (1+r)^n - 1: at a zero (or absent) rate
    -- that is a division by zero, and the loan it would create pays NaN instalments forever.
    local interest = props.loanInterest
    if type(interest) ~= "number" or interest ~= interest or interest <= 0 then
      debugger:warn("%s: the bank quotes no usable interest rate (%s)", label, tostring(interest))
      return
    end

    local loanClass = VDT.EnhancedLoanSystem.loanClass()
    if loanClass == nil or type(loanClass.new) ~= "function" then
      debugger:warn("%s: the loan class is not available", label)
      return
    end

    -- Built exactly as ELS's own take-loan callback builds it, isServer/isClient included -- that pair
    -- is what decides whether register() creates the object here or asks the server to.
    local okNew, loan = pcall(loanClass.new, g_currentMission:getIsServer(), g_currentMission:getIsClient())
    if not okNew or type(loan) ~= "table" then
      debugger:warn("%s: could not create the loan", label)
      return
    end
    local okInit = pcall(loan.init, loan, farm.farmId, amount, interest, duration)
    if not okInit then
      debugger:warn(
        "%s: could not initialise the loan (%d over %d years at %s%%)",
        label,
        amount,
        duration,
        tostring(interest)
      )
      return
    end

    local okAdd = pcall(manager.addLoan, manager, loan)
    if not okAdd then
      debugger:error("%s: the loan system refused the loan", label)
      return
    end
    debugger:debug("%s %d over %d years at %s%%", label, amount, duration, tostring(interest))
  end,
})

VDT.CommandRegistry.register("repayLoan", {
  requiresVehicle = false,
  parse = function(xml, key)
    return {
      loanId = xml:getInt(key .. "#loanId"),
      amount = xml:getInt(key .. "#amount"),
    }
  end,
  execute = function(_, params, debugger)
    local label = "repayLoan"
    local manager, farm = resolve(debugger, label)
    if manager == nil then
      return
    end
    local loan = resolveLoan(manager, farm.farmId, params.loanId, debugger, label)
    if loan == nil then
      return
    end

    local props = VDT.EnhancedLoanSystem.properties()
    if props == nil then
      debugger:warn("%s: the loan system's settings are not available", label)
      return
    end
    -- One extra payment per loan per year unless the server allows more; ELS resets the flag at each
    -- year change. Refused rather than clamped -- there is no smaller amount that would be allowed.
    if not props.multipleSpecialRedemptionsAllowed and loan.specialRedemptionDone == true then
      debugger:warn("%s: loan %s has already had its special redemption this year", label, tostring(params.loanId))
      return
    end

    local amount = params.amount
    if type(amount) ~= "number" or amount ~= amount or amount <= 0 then
      debugger:warn("%s: missing or invalid amount (%s)", label, tostring(amount))
      return
    end

    -- ELS's own dialog clamps in this order, and the percentage cap applies ONLY while multiple
    -- redemptions are disallowed. Mirrored exactly, including that asymmetry.
    amount = math.min(amount, math.max(num(farm.money), 0))
    if not props.multipleSpecialRedemptionsAllowed then
      amount = math.min(amount, num(loan.amount) * num(props.specialRedemptionPercentageForAnnuityLoans))
    end
    amount = math.min(amount, num(loan.restAmount))
    amount = math.floor(amount)

    if amount <= 0 then
      debugger:warn("%s: nothing can be paid off right now (money %d)", label, math.floor(num(farm.money)))
      return
    end

    local okPay = pcall(manager.specialRedemptionPayment, manager, loan, amount)
    if not okPay then
      debugger:error("%s: the loan system refused the payment", label)
      return
    end
    debugger:debug("%s %d against loan %s", label, amount, tostring(params.loanId))
  end,
})
