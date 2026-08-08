-- Executes the loan write-back (app -> mod): set the farm's loan to a target amount. The write half of
-- the read-only finance channel (src/collect/FinanceExporter.lua).
--
-- Drives the game's OWN ChangeLoanEvent, exactly as the in-game finances screen does
-- (InGameMenuStatisticsFrame:onButtonBorrow / :onButtonRepay): build the event and hand it to
-- `g_client:getServerConnection():sendEvent(...)`. So this mod needs no network event of its own, the
-- server re-checks the player's rights when the event lands, and singleplayer takes the same path (the
-- host's local connection).
--
-- THE COMMAND CARRIES A TARGET, THE EVENT TAKES A DELTA. ChangeLoanEvent:run adds `loanValue` to the
-- farm's current loan and clamps the result into [0, max(loanMax, loan)]; the in-game buttons send a
-- fixed +/-5000. We send `target - farm.loan`, computed here at execution time, so the wire value is
-- absolute state and idempotent (see the command rule in Protocol.kt): a redelivered setLoan computes
-- a zero delta and does nothing, where a redelivered +5000 would borrow twice.
--
-- Two guards, and they are deliberately not symmetric:
--   * borrowing above the ceiling is CLAMPED, mirroring what the server would do with the event anyway
--     -- so an app one write out of date still borrows the most it can, rather than silently doing
--     nothing (same treatment as unloadObjectStorage's amount);
--   * repaying more than the balance is REFUSED. The engine does not check this -- Farm:changeBalance
--     will happily push the money negative -- but the in-game screen won't offer the button, so
--     neither do we. Clamping here would spend money the player did not ask to spend.
--
-- Touches no vehicle, so requiresVehicle = false (see CommandRegistry / VDTelemetry:onCommand).
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.FinanceControl = {}

local function num(v)
  return type(v) == "number" and v or 0
end

---Resolve the target loan this command should leave the farm at, or nil (logging why) when the
---command cannot be carried out. Pure arithmetic over the farm's live numbers, so it is unit-testable
---and the execute below stays a thin send.
---@param farm table the local farm
---@param requested number|nil the app's target
---@param debugger GrisuDebug
---@param label string
---@return number|nil target the clamped target, or nil to do nothing
function VDT.FinanceControl.resolveTarget(farm, requested, debugger, label)
  -- NaN fails every comparison, so it is screened by the `< 0` test only if checked first.
  if type(requested) ~= "number" or requested ~= requested or requested < 0 then
    debugger:warn("%s: missing or invalid target amount (%s)", label, tostring(requested))
    return nil
  end

  local current = num(farm.loan)
  -- The server's own ceiling: max(loanMax, loan), so an existing loan above a since-lowered ceiling
  -- can still be held (and repaid) rather than being force-called.
  local ceiling = math.max(num(farm.loanMax), current)
  local target = math.min(requested, ceiling)
  if target < requested then
    debugger:debug("%s: target %d is above the ceiling, borrowing up to %d", label, requested, target)
  end

  local delta = target - current
  if delta == 0 then
    debugger:debug("%s: loan is already %d, nothing to do", label, current)
    return nil
  end
  if delta < 0 and -delta > num(farm.money) then
    debugger:warn(
      "%s: repaying %d needs more than the farm's %d -- ignoring",
      label,
      math.floor(-delta),
      math.floor(num(farm.money))
    )
    return nil
  end
  return target
end

VDT.CommandRegistry.register("setLoan", {
  requiresVehicle = false,
  parse = function(xml, key)
    return { amount = xml:getInt(key .. "#amount") }
  end,
  execute = function(_, params, debugger)
    local label = "setLoan"

    if not VDT.FinanceExporter.loansAvailable() then
      debugger:warn("%s: this platform has no loans -- ignoring", label)
      return
    end
    -- The same right the in-game screen greys its buttons on. The server checks it again when the
    -- event lands (ChangeLoanEvent:run), so this is for the log, not the boundary.
    if not VDT.FinanceExporter.canManageLoan() then
      debugger:warn("%s: this player may not manage the farm's loan -- ignoring", label)
      return
    end

    local farm = VDT.FinanceExporter.ownFarm()
    if farm == nil then
      debugger:warn("%s: no local farm resolved, refusing to change a loan", label)
      return
    end

    local target = VDT.FinanceControl.resolveTarget(farm, params.amount, debugger, label)
    if target == nil then
      return
    end

    if ChangeLoanEvent == nil then
      debugger:warn("%s: ChangeLoanEvent is not available", label)
      return
    end
    if g_client == nil then
      debugger:warn("%s: no client connection", label)
      return
    end
    local connection = g_client:getServerConnection()
    if connection == nil then
      debugger:warn("%s: no server connection", label)
      return
    end

    local current = num(farm.loan)
    connection:sendEvent(ChangeLoanEvent.new(target - current, farm.farmId))
    debugger:debug(
      "%s %d -> %d (delta %d)",
      label,
      math.floor(current),
      math.floor(target),
      math.floor(target - current)
    )
  end,
})
