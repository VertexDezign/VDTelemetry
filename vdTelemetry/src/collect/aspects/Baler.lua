-- Aspect collector: a baler -- round or square, towed or self-propelled, with or without a wrapper on
-- the back (the wrapper is its own aspect, aspects/BaleWrapper.lua). Namespaced under VDT.* (see
-- TurnOn.lua).
--
-- What it adds on top of what the machine already exports is the baler's own state machine: the bale
-- being formed, the door, the finished bales still on the machine, the sizes, auto-drop, and what the
-- game's drop key would do right now. The bale's LEVEL is not repeated here -- it is the baler's fill
-- unit, already in `fillUnits` -- only which entry of that list it is (`fillUnit`), because a baler
-- carries others beside it (net or twine, a non-stop baler's buffer, a silage-additive tank) and the
-- chamber's fill type is simply the crop, which the buffer shares.
--
-- Two kinds of baler, told apart by the engine's `hasUnloadingAnimation`:
--   * a round baler forms ONE bale in a closed chamber and drops it through a door
--     (`spec.unloadingState`, driven by setIsUnloadingBale). The finished bale sits in `spec.bales`
--     until the door opens; `door` is exported only for these machines.
--   * a square baler pushes its bales along a channel. Each finished bale in `spec.bales` carries a
--     `time` 0..1 along `spec.baleAnimCurve`, advanced by the next bale's worth of pickup
--     (Baler:processBalerArea -> moveBales), and drops off the end at 1 -- exported as `position`.
--     A square baler with a platform (an accumulator) holds the bale on it until it drops.
--
-- MULTIPLAYER: everything here reaches a joined client. The door state, the bales and their times,
-- the platform flag, both bale-type indices and the chamber's level and capacity are all in
-- Baler:onWriteStream; afterwards the door rides BalerSetIsUnloadingBaleEvent, bale times
-- BalerSetBaleTimeEvent, the size BalerBaleTypeEvent, auto-drop BalerAutomaticDropEvent, and
-- `lastAreaBiggerZero` and the non-stop buffer's overloading flag the update stream. The bale types
-- themselves come from the XML at load.
--
-- Deliberately NOT collected:
--   * the additive (`spec.additives`) -- its tank is a fill unit and already exported; whether it is
--     being sprayed this second adds nothing a screen could act on.
--   * `showBaleLimitWarning` -- the bale slot limit is a savegame-wide fact, not this machine's.
--   * the bale collector's state machine. A baler with a collector on the back (the base game's
--     `balerLoader`, e.g. a KRONE BiG Pack with its collector configured) carries a second
--     specialization for it, BaleLoader -- the one bale-collecting trailers use, with its own chain of
--     unload states. Only where its bales are counted is exported (`collector.fillUnit`: a fill unit in
--     BALES, capacity = places on the rack); setting the stack down is still the game's key.

VDT = VDT or {}
VDT.Baler = {}

-- Baler.UNLOADING_*, 1-based in the engine.
local DOOR_STATES = { [1] = "CLOSED", [2] = "OPENING", [3] = "OPEN", [4] = "CLOSING" }
local UNLOADING_CLOSED = 1
local UNLOADING_OPEN = 3

-- getIsPowered returns `isPowered, warning`; only the first is ours (see aspects/Mixer.lua). Every one
-- of the baler's keys is registered with addPoweredActionEvent, whose wrapper refuses with "start the
-- motor" unless a motor on the rig is running. The engine's setters check nothing of the kind.
---@param object table
---@return boolean
local function isPowered(object)
  if object.getIsPowered == nil then
    return true
  end
  local powered = object:getIsPowered()
  return powered == true
end

---What the game's drop key (IMPLEMENT_EXTRA3) would do right now, or nil when it would not be shown.
---
---A copy of `Baler.updateActionEvents`' decision tree, token for token, with the power gate the key's
---registration adds on top. Exported rather than left to the app for the reason `discharge.reason`
---is: it is the engine's verdict, and it turns on state (`isUnloadingAllowed` looks at the platform
---and, on a baler-wrapper, at whether the wrapper can take a bale) the app has no business
---re-deriving. Public because command/BalerControl.lua asks it again when the command lands.
---
---  UNLOAD            -- open the door on a finished bale (round baler, auto-drop off)
---  UNLOAD_UNFINISHED -- push out a part-formed bale; only machines that declare canUnloadUnfinishedBale
---  CLOSE             -- shut the door after a drop
---  DROP_PLATFORM     -- tip the bale waiting on the platform
---@param object table
---@return string|nil
function VDT.Baler.unloadAction(object)
  local spec = object.spec_baler
  if spec == nil or not isPowered(object) then
    return nil
  end
  local canUnload = object:isUnloadingAllowed() and (spec.hasUnloadingAnimation or spec.allowsBaleUnloading)
  local action = nil
  if not spec.automaticDrop and canUnload then
    if spec.unloadingState == UNLOADING_CLOSED then
      -- Both tests run, in this order, and the second overrides: the game's text ends up on "unload"
      -- when there is a finished bale AND enough for an unfinished one.
      if object:getCanUnloadUnfinishedBale() then
        action = "UNLOAD_UNFINISHED"
      end
      if #spec.bales > 0 then
        action = "UNLOAD"
      end
    elseif spec.unloadingState == UNLOADING_OPEN and spec.hasUnloadingAnimation then
      action = "CLOSE"
    end
  end
  if spec.platformReadyToDrop then
    action = "DROP_PLATFORM"
  elseif spec.hasPlatform then
    if
      spec.automaticDrop
      and canUnload
      and spec.unloadingState == UNLOADING_CLOSED
      and object:getCanUnloadUnfinishedBale()
    then
      action = "UNLOAD_UNFINISHED"
    end
  end
  return action
end

---Whether the size and auto-drop keys would be accepted -- only the power gate; the keys themselves
---are bound whenever the machine offers the choice at all.
---@param object table
---@return boolean
function VDT.Baler.isPowered(object)
  return isPowered(object)
end

---The automatic-drop setting the machine actually uses: a platform baler has its own flag for it
---(Baler:setBalerAutomaticDrop writes `platformAutomaticDrop` there, and leaves `automaticDrop` true).
---@param spec table spec_baler
---@return boolean|nil
local function autoDropOn(spec)
  if spec.hasPlatform then
    return spec.platformAutomaticDrop
  end
  return spec.automaticDrop
end

---Where a consumable's fill unit (net, twine, wrap film) lands in the exported `fillUnits`. Found
---through the Consumable spec rather than by the unit's fill type, because the fill type is not a
---reliable name for it: the captured GÖWEIL VARIO-Master exports its net roll with no fill type at
---all, where a KRONE VariPack's reads BALE_NET. The consumable's `typeName` is the name the engine
---itself goes by (Baler.CONSUMABLE_TYPE_NAME_ROUND / _SQUARE, BaleWrapper.CONSUMABLE_TYPE_NAME).
---Shared with aspects/BaleWrapper.lua.
---@param object table
---@param typeNames string[] tried in order
---@return number|nil
function VDT.Baler.consumableFillUnit(object, typeNames)
  local spec = object.spec_consumable
  if spec == nil or spec.typesByName == nil then
    return nil
  end
  for _, typeName in ipairs(typeNames) do
    local type = spec.typesByName[typeName]
    if type ~= nil then
      return VDT.FillUnit.reportedIndex(object, type.fillUnitIndex)
    end
  end
  return nil
end

---@param definition table an entry of spec.baleTypes
---@return BaleTypeModel
local function baleType(definition)
  if definition.isRoundBale then
    return { diameter = definition.diameter, width = definition.width }
  end
  return { width = definition.width, height = definition.height, length = definition.length }
end

---@param object table
---@return BalerModel|nil nil when the object is not a baler
function VDT.Baler.collect(object)
  local spec = object.spec_baler
  if spec == nil then
    return nil
  end

  local baleTypes = {}
  for _, definition in ipairs(spec.baleTypes or {}) do
    table.insert(baleTypes, baleType(definition))
  end

  local bales = {}
  for _, bale in ipairs(spec.bales or {}) do
    -- A round baler's bale has no curve to travel and waits in the chamber; its time is meaningless.
    local position = nil
    if spec.baleAnimCurve ~= nil and bale.time ~= nil then
      position = math.min(math.max(bale.time, 0), 1)
    end
    table.insert(bales, { position = position })
  end

  local autoDrop = nil
  local on = autoDropOn(spec)
  if on ~= nil then
    autoDrop = { on = on, canToggle = spec.toggleableAutomaticDrop == true }
  end

  local door = nil
  if spec.hasUnloadingAnimation then
    door = DOOR_STATES[spec.unloadingState]
  end

  local nextBaleType = nil
  if spec.preSelectedBaleTypeIndex ~= spec.currentBaleTypeIndex then
    nextBaleType = spec.preSelectedBaleTypeIndex
  end

  local platform = nil
  if spec.hasPlatform then
    platform = { ready = spec.platformReadyToDrop == true }
  end

  local buffer = nil
  if spec.nonStopBaling and spec.buffer ~= nil and spec.buffer.fillUnitIndex ~= nil then
    buffer = {
      fillUnit = VDT.FillUnit.reportedIndex(object, spec.buffer.fillUnitIndex),
      overloading = spec.buffer.unloadingStarted == true,
    }
  end

  -- An empty Lua table encodes as `{}`, which a JSON list will not parse from: leave empty lists out,
  -- as every other aspect does (the Kotlin model defaults them to empty).
  if #bales == 0 then
    bales = nil
  end
  if #baleTypes == 0 then
    baleTypes = nil
  end

  local collector = nil
  if object.spec_baleLoader ~= nil then
    collector = { fillUnit = VDT.FillUnit.reportedIndex(object, object.spec_baleLoader.fillUnitIndex) }
  end

  -- Net on a round baler, twine on a square one; the other name second, for a machine that breaks
  -- the pattern.
  local consumables = { "BALE_TWINE", "BALE_NET" }
  if spec.isRoundBaler then
    consumables = { "BALE_NET", "BALE_TWINE" }
  end

  return {
    round = spec.isRoundBaler == true,
    fillUnit = VDT.FillUnit.reportedIndex(object, spec.fillUnitIndex),
    consumable = VDT.Baler.consumableFillUnit(object, consumables),
    working = spec.lastAreaBiggerZero == true,
    powered = isPowered(object),
    door = door,
    bales = bales,
    baleTypes = baleTypes,
    baleType = spec.currentBaleTypeIndex,
    nextBaleType = nextBaleType,
    autoDrop = autoDrop,
    platform = platform,
    buffer = buffer,
    collector = collector,
    unload = VDT.Baler.unloadAction(object),
  }
end
