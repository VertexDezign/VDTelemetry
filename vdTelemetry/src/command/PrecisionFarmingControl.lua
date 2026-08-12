-- Executes Precision Farming application-rate commands from the app -> mod back-channel. The inverse
-- of the `precisionFarming.auto` / `precisionFarming.manual` half of the integration collector: it
-- maps an absolute target (auto on/off, step = 4) onto PF's own setters.
--
-- Absolute, not "one step up" -- the same rule as every other control here. The file channel is
-- lossy/async, so a dropped or doubled increment would leave the terminal and the machine disagreeing
-- about a rate the player cannot see from the seat; an absolute step is self-correcting, and the app
-- already renders the current one, so a +/- tap computes its own target.
--
-- Both setters are PF's own vehicle functions and each owns its multiplayer event
-- (ExtendedSprayerAmountEvent, sent from inside them), so this calls them directly and adds no event
-- of its own -- the same shape as MotorControl and LightControl. `setSprayAmountManualValue` clamps
-- to the machine's own min/max, so a stale step from the app is corrected rather than rejected.
--
-- Finding *which* machine to call them on goes through src/integrations/PrecisionFarming.lua, which
-- owns every read of PF's internals; this file must be sourced after it.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.PrecisionFarmingControl = {}

---Resolve the sprayer PF's own keybinds would drive and check it carries `setter`, logging why not.
---
---Not the controlled vehicle: the sprayer is usually the implement behind it, and on a slurry rig it
---is the tool behind *that*. VDT.PrecisionFarming.validSprayer delegates the whole question to PF,
---including the manure-barrel exception -- and, the reason this is not inline here, including the
---fact that the function is only reachable through PF's mod env. Asking the vehicle for it, which is
---what this first shipped doing, found nothing on every machine there is.
---@param vehicle Vehicle the controlled vehicle
---@param setter string
---@param debugger GrisuDebug
---@return table|nil
local function sprayerWith(vehicle, setter, debugger)
  local sprayer = VDT.PrecisionFarming.validSprayer(vehicle)
  if sprayer == nil then
    debugger:debug("%s: no Precision Farming sprayer on this rig, ignoring", setter)
    return nil
  end
  if type(sprayer[setter]) ~= "function" then
    -- PF registers both setters on every ExtendedSprayer, so this means its API has moved.
    debugger:warn("%s: sprayer has no such function (Precision Farming changed?)", setter)
    return nil
  end
  return sprayer
end

---Put the rig's sprayer into automatic (auto=true) or manual application-rate mode.
---
---PF forces manual off on a machine whose `sprayAmountAutoModeChangeAllowed` is unset, which the mod
---reports as `precisionFarming.canToggleAuto` so the app can leave the switch out instead of drawing
---one the game undoes.
---@param vehicle Vehicle
---@param auto boolean
---@param debugger GrisuDebug
function VDT.PrecisionFarmingControl.setSprayAmountAuto(vehicle, auto, debugger)
  local sprayer = sprayerWith(vehicle, "setSprayAmountAutoMode", debugger)
  if sprayer == nil then
    return
  end
  -- Explicitly, never nil: PF reads a nil state as "toggle", which is the one thing this channel
  -- must not send.
  sprayer:setSprayAmountAutoMode(auto == true)
  debugger:debug("setSprayAmountAutoMode(%s)", tostring(auto == true))
end

---Set the manual step (PF's `sprayAmountManual`) to an absolute value.
---
---Does not touch the mode. PF stores the step in either mode -- it simply has no effect until manual
---is on -- so the app can pre-set a rate and switch, and a step command never silently drops the
---machine out of auto.
---@param vehicle Vehicle
---@param step number
---@param debugger GrisuDebug
function VDT.PrecisionFarmingControl.setSprayAmountStep(vehicle, step, debugger)
  if type(step) ~= "number" then
    debugger:warn("setSprayAmountStep: step is not a number, ignoring")
    return
  end
  local sprayer = sprayerWith(vehicle, "setSprayAmountManualValue", debugger)
  if sprayer == nil then
    return
  end
  -- Rounded because the value indexes PF's level tables and is written to the wire as an unsigned
  -- int; PF clamps it to the machine's own min/max from there.
  sprayer:setSprayAmountManualValue(math.floor(step + 0.5))
  debugger:debug("setSprayAmountManualValue(%s)", tostring(step))
end

-- Command handlers (see CommandRegistry).
VDT.CommandRegistry.register("setSprayAmountAuto", {
  parse = function(xml, key)
    return { auto = xml:getBool(key .. "#auto", true) }
  end,
  execute = function(vehicle, params, debugger)
    VDT.PrecisionFarmingControl.setSprayAmountAuto(vehicle, params.auto, debugger)
  end,
})

VDT.CommandRegistry.register("setSprayAmountStep", {
  parse = function(xml, key)
    return { step = xml:getInt(key .. "#step") }
  end,
  execute = function(vehicle, params, debugger)
    VDT.PrecisionFarmingControl.setSprayAmountStep(vehicle, params.step, debugger)
  end,
})
