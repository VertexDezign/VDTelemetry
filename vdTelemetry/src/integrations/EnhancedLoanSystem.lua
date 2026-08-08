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
