-- Aspect collector: a bale wrapper -- the standalone kind that picks bales up off the field, and the
-- wrapping table on the back of a baler-wrapper combination, which is the same specialization.
-- Namespaced under VDT.* (see TurnOn.lua).
--
-- The engine runs a wrapper as one state machine (`spec.baleWrapperState`, BaleWrapper.STATE_*):
-- a bale is grabbed and carried onto the table, the table wraps it for `wrappingTime`, and the wrapped
-- bale waits there until it is dropped -- automatically, or on the driver's key when auto-drop is off.
-- The states are exported under names of our own, because the engine's say how the animation is
-- going rather than what the machine is doing:
--
--   EMPTY     STATE_NONE                    nothing on the table; ready to take a bale
--   LOADING   STATE_MOVING_BALE_TO_WRAPPER  the arm is carrying a bale onto the table
--   LOADED    STATE_MOVING_GRABBER_TO_WORK  bale on the table, arm going back; wrapping starts next
--   WRAPPING  STATE_WRAPPER_WRAPPING_BALE
--   WRAPPED   STATE_WRAPPER_FINSIHED        waiting to be dropped
--   DROPPING  STATE_WRAPPER_DROPPING_BALE
--   RESETTING STATE_WRAPPER_RESETTING_PLATFORM  the table tilting back after a drop
--
-- MULTIPLAYER: the state reaches a client through BaleWrapperStateEvent (and onWriteStream on join).
-- The wrap time is not synced as such, but BaleWrapper:onUpdate advances `currentWrapper.currentTime`
-- on every peer while the state is WRAPPING, from the same start, so a client's progress tracks the
-- server's. `showInvalidBaleWarning` is computed in onUpdateTick on the client itself.
--
-- Uses VDT.Baler.consumableFillUnit, so aspects/Baler.lua is sourced first.

VDT = VDT or {}
VDT.BaleWrapper = {}

local STATES = {
  [0] = "EMPTY",
  [1] = "LOADING",
  [2] = "LOADED",
  [3] = "WRAPPING",
  [4] = "WRAPPED",
  [5] = "DROPPING",
  [6] = "RESETTING",
}
local STATE_WRAPPING = 3
local STATE_FINISHED = 4

---Whether the game's drop key would drop the wrapped bale now. The key (IMPLEMENT_EXTRA3, bound to
---BaleWrapper.actionEventEmpty) is only registered while auto-drop is OFF, is a powered action event,
---and acts only on a finished bale. What it does NOT include is the drop-area check
---(`getIsBaleDropAllowed`): that runs an overlapBox, which has no place on the export timer. The key
---asks it when pressed and shows a warning if something is in the way; the command does the same.
---Public because command/BalerControl.lua asks it again when the command lands.
---@param object table
---@return boolean
function VDT.BaleWrapper.canDrop(object)
  local spec = object.spec_baleWrapper
  if spec == nil or spec.automaticDrop or spec.baleWrapperState ~= STATE_FINISHED then
    return false
  end
  if object.getIsPowered ~= nil then
    local powered = object:getIsPowered()
    if powered ~= true then
      return false
    end
  end
  return true
end

---@param object table
---@return BaleWrapperModel|nil nil when the object has no bale wrapper
function VDT.BaleWrapper.collect(object)
  local spec = object.spec_baleWrapper
  if spec == nil then
    return nil
  end
  local wrapper = spec.currentWrapper or {}
  local state = spec.baleWrapperState

  -- 0 before the wrap starts, the real fraction while it runs, 1 from then until the bale is gone:
  -- `currentTime` is left at animTime after a wrap and only reset when the next one starts.
  local progress = nil
  if state == STATE_WRAPPING and wrapper.animTime ~= nil and wrapper.animTime > 0 then
    progress = math.min(math.max((wrapper.currentTime or 0) / wrapper.animTime, 0), 1)
  elseif state == STATE_FINISHED or state == STATE_FINISHED + 1 then
    progress = 1
  end

  return {
    state = STATES[state] or "EMPTY",
    round = spec.roundBaleWrapper ~= nil and wrapper == spec.roundBaleWrapper,
    consumable = VDT.Baler.consumableFillUnit(object, { "BALE_WRAP" }),
    progress = progress,
    autoDrop = { on = spec.automaticDrop == true, canToggle = spec.toggleableAutomaticDrop == true },
    canDrop = VDT.BaleWrapper.canDrop(object),
    unsupportedBale = spec.showInvalidBaleWarning == true,
  }
end
