-- Aspect collector: sowing machine — which crop is loaded, and how the hopper is configured.
-- Applies to any object (vehicle or implement): the spec sits on towed seeders and on self-propelled
-- ones alike. Namespaced under VDT.* (see TurnOn.lua).
--
-- This is the one thing a seeding terminal exists to say and the mod has never exported: the fill
-- unit reports SEEDS, not *which* seeds. The crop lives one indirection away — `spec.seeds` is a list
-- of fruit type indices and `spec.currentSeed` indexes it — so it is resolved here into both the
-- engine token (WHEAT) and the localized title the panel prints.
--
-- Deliberately NOT collected:
--   * `spec.isWorking` / `spec.isProcessing` -- both are written from processSowingMachineArea, i.e.
--     inside work-area processing, so on a client they are whatever onLoad left behind. The workAreas
--     aspect already answers "is this thing processing ground" from the engine's own predicate; a
--     second, less trustworthy answer to the same question is worse than none.
--   * the `spec.warnings` family (showFruitCanNotBePlantedWarning and friends) -- same origin, same
--     problem, and the engine only ever reads them behind isActiveForInputIgnoreSelectionIgnoreAI.
--     Worth revisiting if they turn out to survive on a client; they are the best "you are about to
--     waste a hopper" signal the game has.
--
-- `spec.currentSeed` IS stream-synced (SowingMachine:onReadStream/onWriteStream), so the crop itself
-- is trustworthy in multiplayer. Everything else here is read from the vehicle XML at load.

VDT = VDT or {}
VDT.Sowing = {}

-- The engine's own default for `seedUsageScale`; left out of the JSON at that value like the other
-- display hints (see aspects/FillUnit.lua).
local DEFAULT_USAGE_SCALE = 1

---@param object table a vehicle or implement
---@return SowingModel|nil nil when the object does not sow
function VDT.Sowing.collect(object)
  local spec = object.spec_sowingMachine
  if spec == nil then
    return nil
  end

  local seeds = spec.seeds or {}

  ---@type SowingModel
  local model = {
    seedIndex = spec.currentSeed,
    seedCount = #seeds,
    -- Not `spec.allowsSeedChanging` directly: the engine exposes a getter and a spec may be driven
    -- through setIsSeedChangeAllowed by something else (a mission locks the hopper).
    changeAllowed = spec.allowsSeedChanging ~= false,
    directPlanting = spec.useDirectPlanting == true,
  }

  if spec.seedUsageScale ~= nil and spec.seedUsageScale ~= DEFAULT_USAGE_SCALE then
    model.usageScale = tonumber(ValueMapper.mapFloat(spec.seedUsageScale))
  end

  -- The selected crop. `currentSeed` is clamped by setSeedIndex, but a machine can load with an empty
  -- seeds list (nothing declared in its XML), so the lookup is guarded rather than assumed.
  local fruitTypeIndex = seeds[spec.currentSeed]
  if fruitTypeIndex ~= nil then
    model.fruitType = g_fruitTypeManager:getFruitTypeNameByIndex(fruitTypeIndex)

    -- The fill type the crop is carried as -- `type` joins this to the matching fillUnits entry, and
    -- `title` is the localized name to print. Read off the fill type rather than the fruit type
    -- because that is what the hopper is measured in.
    -- NB: getFillTypeNameByFruitTypeIndex looks like the shortcut for this and is broken in the
    -- engine (it dereferences an undefined `fillTypeIndex`), so go through the record.
    local fillType = g_fruitTypeManager:getFillTypeByFruitTypeIndex(fruitTypeIndex)
    if fillType ~= nil then
      model.fillType = fillType.name
      model.title = fillType.title
    end
  end

  return model
end
