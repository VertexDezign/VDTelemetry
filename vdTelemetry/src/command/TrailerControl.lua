-- Executes tip-side and unloading commands from the app -> mod back-channel. The inverse of the
-- VDT.Tipping and VDT.Discharge aspect collectors.
--
-- Direct engine calls, for the same reason as PipeControl: additionalInputs has no vdAI* counterpart
-- for either, so calling the setter is the normal path rather than an exception.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.TrailerControl = {}

-- Dischargeable.DISCHARGE_STATE_*. Named here rather than referenced because a command may arrive
-- before any Dischargeable has been loaded in this session.
local DISCHARGE_OFF = 0
local DISCHARGE_OBJECT = 1
local DISCHARGE_GROUND = 2

---Choose which tip side the next tip will use. Absolute: the app sends the index it rendered.
---
---setPreferedTipSide clamps to tipSideCount and owns TrailerToggleTipSideEvent, so this is idempotent
---and multiplayer-safe with no work of ours. It also stops an in-progress tip before switching, which
---is why the game gates its own action on getCanTogglePreferdTipSide (tip state must be CLOSED) -- we
---gate on the same thing rather than yanking a raised trough sideways.
---@param vehicle Vehicle the controlled vehicle
---@param target string vehicle|front|back
---@param side number 1-based tip side index
---@param debugger GrisuDebug
function VDT.TrailerControl.setTipSide(vehicle, target, side, debugger)
  local object = VDT.TargetResolver.resolve(vehicle, target, debugger)
  if object == nil then
    return
  end
  if object.setPreferedTipSide == nil then
    debugger:debug("setTipSide: %s does not tip, ignoring", target)
    return
  end
  if object.getCanTogglePreferdTipSide ~= nil and not object:getCanTogglePreferdTipSide() then
    debugger:debug("setTipSide: %s cannot change side right now (mid-tip?), ignoring", target)
    return
  end
  object:setPreferedTipSide(side)
  debugger:debug("setPreferedTipSide(%s, %s)", target, tostring(side))
end

---Start (on=true) or stop unloading.
---
---Deliberately a BOOLEAN rather than the absolute DischargeState the aspect reports. Which flavour of
---unloading applies -- into an object or onto the ground -- is a fact about the spot the machine is
---standing on, not something the app can know: the game's own actionEventToggleDischarging asks
---getCanDischargeToObject/ToGround and picks. We ask the same questions in the same order, so the
---button does what the player's own keypress would.
---
---Still idempotent, which is what the lossy channel needs: "unloading" and "not unloading" are both
---absolute assertions, and a repeat is dropped by the state check rather than by setDischargeState's
---own guard -- otherwise a resend while already tipping onto the ground could silently switch it to
---an object that has since come into range.
---
---setManualDischargeState, NOT setDischargeState: Trailer OVERWRITES the manual one to clear its
---automatic-redischarge tracking when unloading stops. Going straight to setDischargeState skips
---that, leaving the trailer aimed at an object it has finished with.
---@param vehicle Vehicle the controlled vehicle
---@param target string vehicle|front|back
---@param on boolean
---@param debugger GrisuDebug
function VDT.TrailerControl.setDischarging(vehicle, target, on, debugger)
  local object = VDT.TargetResolver.resolve(vehicle, target, debugger)
  if object == nil then
    return
  end
  if object.setManualDischargeState == nil or object.getDischargeState == nil then
    debugger:debug("setDischarging: %s does not discharge, ignoring", target)
    return
  end

  local current = object:getDischargeState()
  if on == (current ~= DISCHARGE_OFF) then
    debugger:debug("setDischarging: %s already %s", target, on and "unloading" or "stopped")
    return
  end

  if not on then
    object:setManualDischargeState(DISCHARGE_OFF)
    debugger:debug("setManualDischargeState(%s, OFF)", target)
    return
  end

  local node = object.getCurrentDischargeNode ~= nil and object:getCurrentDischargeNode() or nil
  if node == nil then
    debugger:debug("setDischarging: %s has no current discharge node, ignoring", target)
    return
  end

  -- The game's own order: an object under the node wins over the ground under it.
  if object:getCanToggleDischargeToObject() and object:getCanDischargeToObject(node) then
    object:setManualDischargeState(DISCHARGE_OBJECT)
    debugger:debug("setManualDischargeState(%s, OBJECT)", target)
  elseif object:getCanToggleDischargeToGround() and object:getCanDischargeToGround(node) then
    object:setManualDischargeState(DISCHARGE_GROUND)
    debugger:debug("setManualDischargeState(%s, GROUND)", target)
  else
    -- The engine is refusing this spot. It already publishes WHY, on the discharge aspect's `reason`,
    -- and the app shows it -- so there is nothing to report from here that the driver is not already
    -- looking at.
    debugger:debug("setDischarging: %s cannot unload here, ignoring", target)
  end
end

-- Command handlers (see CommandRegistry).
VDT.CommandRegistry.register("setTipSide", {
  parse = function(xml, key)
    return {
      target = xml:getString(key .. "#target"),
      side = xml:getInt(key .. "#side", 1),
    }
  end,
  execute = function(vehicle, params, debugger)
    VDT.TrailerControl.setTipSide(vehicle, params.target, params.side, debugger)
  end,
})

VDT.CommandRegistry.register("setDischarging", {
  parse = function(xml, key)
    return {
      target = xml:getString(key .. "#target"),
      on = xml:getBool(key .. "#on", false),
    }
  end,
  execute = function(vehicle, params, debugger)
    VDT.TrailerControl.setDischarging(vehicle, params.target, params.on, debugger)
  end,
})
