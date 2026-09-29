-- Executes the baler and bale-wrapper commands from the app -> mod back-channel. The write side of
-- VDT.Baler, VDT.BaleWrapper and VDT.BaleCounter (see collect/aspects/).
--
-- Direct engine calls, like CombineControl: vdAI has none of these, and the rule is to use what
-- FS25_additionalInputs already has rather than to extend it for our own needs.
--
-- Every setter used here owns its multiplayer event (BaleCounterResetEvent, BalerBaleTypeEvent,
-- BalerAutomaticDropEvent, BaleWrapperAutomaticDropEvent, BalerSetIsUnloadingBaleEvent,
-- BalerDropFromPlatformEvent, and the wrapper's drop is a BaleWrapperStateEvent sent to the server),
-- so a client's command reaches the server the way the player's own key does.
--
-- Where the game's key toggles or steps, the command is ABSOLUTE instead -- a bale size by index, an
-- auto-drop state -- so a resent or doubled command on the lossy channel lands on the same state. The
-- one command that cannot be made absolute is the drop: `Baler:handleUnloadingBaleEvent` opens a
-- closed door and closes an open one. So the app sends the action it saw offered (`baler.unload`) and
-- the command runs only while the engine still offers that same action -- a late "unload" never
-- becomes a "close".
--
-- What the setters do NOT check is what the game's keys are gated on: every baler key and the
-- counter reset are registered with addPoweredActionEvent (refused while no motor on the rig runs),
-- and the size, drop and auto-drop keys only exist when the machine offers them. Those gates are
-- asked again here, for the reason WorkModeControl gives: state moves between the export and the
-- command answering it.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.BalerControl = {}

---@param vehicle Vehicle the controlled vehicle
---@param target string vehicle|front|back|selected
---@param command string for the debug line
---@param specName string the spec the object must carry
---@param debugger GrisuDebug
---@return table|nil object the resolved machine, or nil when it is absent or lacks the spec
local function resolve(vehicle, target, command, specName, debugger)
  local object = VDT.TargetResolver.resolve(vehicle, target, debugger)
  if object == nil then
    return nil
  end
  if object[specName] == nil then
    debugger:debug("%s: %s has no %s, ignoring", command, target, specName)
    return nil
  end
  return object
end

---Reset the session bale count. The lifetime count has no reset in the game, and gets none here.
---@param vehicle Vehicle
---@param target string
---@param debugger GrisuDebug
function VDT.BalerControl.resetBaleCounter(vehicle, target, debugger)
  local object = resolve(vehicle, target, "resetBaleCounter", "spec_baleCounter", debugger)
  if object == nil then
    return
  end
  if not VDT.Baler.isPowered(object) then
    debugger:debug("resetBaleCounter: %s is not powered, ignoring", target)
    return
  end
  object:doBaleCounterReset()
  debugger:debug("resetBaleCounter(%s)", target)
end

---Choose the bale size, 1-based into the export's `baler.baleTypes`. The engine applies it at once on
---an empty chamber and otherwise after the bale in progress; the export shows the wait as
---`nextBaleType`. Never forced: the game forces only when the chamber is already empty.
---@param vehicle Vehicle
---@param target string
---@param index number
---@param debugger GrisuDebug
function VDT.BalerControl.setBaleType(vehicle, target, index, debugger)
  local object = resolve(vehicle, target, "setBaleType", "spec_baler", debugger)
  if object == nil then
    return
  end
  local spec = object.spec_baler
  -- TOGGLE_BALE_TYPES is only bound on a machine with more than one size.
  if #spec.baleTypes < 2 or index == nil or index < 1 or #spec.baleTypes < index then
    debugger:debug("setBaleType: %s has no bale type %s, ignoring", target, tostring(index))
    return
  end
  if index == spec.preSelectedBaleTypeIndex then
    debugger:debug("setBaleType: %s already set to %d", target, index)
    return
  end
  if not VDT.Baler.isPowered(object) then
    debugger:debug("setBaleType: %s is not powered, ignoring", target)
    return
  end
  object:setBaleTypeIndex(index)
  debugger:debug("setBaleType(%s, %d)", target, index)
end

---Switch automatic dropping on or off -- the baler's (its platform's, on a platform baler) when
---`part` is "baler", the wrapping table's when it is "wrapper". A baler-wrapper carries both.
---@param vehicle Vehicle
---@param target string
---@param part string baler|wrapper
---@param on boolean
---@param debugger GrisuDebug
function VDT.BalerControl.setBaleAutoDrop(vehicle, target, part, on, debugger)
  if part == "wrapper" then
    local object = resolve(vehicle, target, "setBaleAutoDrop", "spec_baleWrapper", debugger)
    if object == nil then
      return
    end
    local spec = object.spec_baleWrapper
    if not spec.toggleableAutomaticDrop then
      debugger:debug("setBaleAutoDrop: %s wrapper auto-drop is fixed, ignoring", target)
      return
    end
    -- The wrapper's toggle is a plain addActionEvent: no power gate to repeat.
    object:setBaleWrapperAutomaticDrop(on)
    debugger:debug("setBaleAutoDrop(%s, wrapper, %s)", target, tostring(on))
    return
  end

  local object = resolve(vehicle, target, "setBaleAutoDrop", "spec_baler", debugger)
  if object == nil then
    return
  end
  if not object.spec_baler.toggleableAutomaticDrop then
    debugger:debug("setBaleAutoDrop: %s baler auto-drop is fixed, ignoring", target)
    return
  end
  if not VDT.Baler.isPowered(object) then
    debugger:debug("setBaleAutoDrop: %s is not powered, ignoring", target)
    return
  end
  object:setBalerAutomaticDrop(on)
  debugger:debug("setBaleAutoDrop(%s, baler, %s)", target, tostring(on))
end

---Do what the drop key does, provided it would still do `action` (a `baler.unload` token).
---
---The dispatch is `Baler.actionEventUnloading`'s, through the machine's registered functions rather
---than the game's key handler itself: a platform baler tips its platform unless it has an unfinished
---bale to push out and nothing waiting on the platform; everything else goes through
---handleUnloadingBaleEvent, which opens or closes the door depending on where it is.
---@param vehicle Vehicle
---@param target string
---@param action string UNLOAD | UNLOAD_UNFINISHED | CLOSE | DROP_PLATFORM
---@param debugger GrisuDebug
function VDT.BalerControl.unloadBale(vehicle, target, action, debugger)
  local object = resolve(vehicle, target, "unloadBale", "spec_baler", debugger)
  if object == nil then
    return
  end
  local offered = VDT.Baler.unloadAction(object)
  if action == nil or offered ~= action then
    debugger:debug("unloadBale: %s offers %s, not %s, ignoring", target, tostring(offered), tostring(action))
    return
  end
  local spec = object.spec_baler
  if spec.hasPlatform and not (object:getCanUnloadUnfinishedBale() and not spec.platformReadyToDrop) then
    object:dropBaleFromPlatform(false)
  else
    object:handleUnloadingBaleEvent()
  end
  debugger:debug("unloadBale(%s, %s)", target, action)
end

---Drop the wrapped bale off the wrapping table: the game's own drop key, `BaleWrapper.actionEventEmpty`,
---which asks the drop area is clear (warning in game when it is not) and sends the state change to
---the server.
---@param vehicle Vehicle
---@param target string
---@param debugger GrisuDebug
function VDT.BalerControl.dropWrappedBale(vehicle, target, debugger)
  local object = resolve(vehicle, target, "dropWrappedBale", "spec_baleWrapper", debugger)
  if object == nil then
    return
  end
  if not VDT.BaleWrapper.canDrop(object) then
    debugger:debug("dropWrappedBale: %s has no wrapped bale to drop, ignoring", target)
    return
  end
  BaleWrapper.actionEventEmpty(object)
  debugger:debug("dropWrappedBale(%s)", target)
end

VDT.CommandRegistry.register("resetBaleCounter", {
  parse = function(xml, key)
    return { target = xml:getString(key .. "#target") }
  end,
  execute = function(vehicle, params, debugger)
    VDT.BalerControl.resetBaleCounter(vehicle, params.target, debugger)
  end,
})

VDT.CommandRegistry.register("setBaleType", {
  parse = function(xml, key)
    return { target = xml:getString(key .. "#target"), index = xml:getInt(key .. "#index") }
  end,
  execute = function(vehicle, params, debugger)
    VDT.BalerControl.setBaleType(vehicle, params.target, params.index, debugger)
  end,
})

VDT.CommandRegistry.register("setBaleAutoDrop", {
  parse = function(xml, key)
    return {
      target = xml:getString(key .. "#target"),
      part = xml:getString(key .. "#part"),
      on = xml:getBool(key .. "#on", false),
    }
  end,
  execute = function(vehicle, params, debugger)
    VDT.BalerControl.setBaleAutoDrop(vehicle, params.target, params.part, params.on, debugger)
  end,
})

VDT.CommandRegistry.register("unloadBale", {
  parse = function(xml, key)
    return { target = xml:getString(key .. "#target"), action = xml:getString(key .. "#action") }
  end,
  execute = function(vehicle, params, debugger)
    VDT.BalerControl.unloadBale(vehicle, params.target, params.action, debugger)
  end,
})

VDT.CommandRegistry.register("dropWrappedBale", {
  parse = function(xml, key)
    return { target = xml:getString(key .. "#target") }
  end,
  execute = function(vehicle, params, debugger)
    VDT.BalerControl.dropWrappedBale(vehicle, params.target, debugger)
  end,
})
