-- Aspect collector: the combine itself — what it is threshing, whether crop is flowing in, what it
-- does with the straw, and how much ground it has covered. Applies to any object (vehicle or
-- implement) — the Combine spec sits on self-propelled harvesters but also on towed and stationary
-- ones. Namespaced under VDT.* (see TurnOn.lua).
--
-- The straw half is the one setting a harvester operator actually toggles mid-field: drop a straw
-- swath to bale later, or chop it back onto the ground. `swathActive` is the live state; the two
-- `*Available` flags say whether this machine offers the choice at all (some have no chopper, some no
-- swath), which is what tells a consumer whether to show a toggle or nothing. There is no separate
-- "chopper running" flag in the engine and none is invented here: on a machine with a chopper, the
-- chopper is what handles the straw whenever the swath is off.
--
-- MULTIPLAYER: everything here rides Combine's own streams, so it is true on a joined client.
-- onWriteStream carries lastValidInputFruitType, isFilling, the fill type, isSwathActive and
-- workedHectars; onWriteUpdateStream keeps the fruit type, the hectares and isFilling current. The
-- one thing to know is `hectaresSession`: workedHectarsInitial is set from the SAVEGAME on the host
-- but from the join stream on a client (Combine:onReadStream calls setWorkedHectars(_, true)), so on
-- a client the session counts from when that player joined, not from when the machine was loaded.
-- Both numbers are exported rather than only the difference, so a consumer can say which it means.
--
-- Deliberately NOT collected:
--   * `getCombineLoadPercentage()` -- it averages `cutter:getCutterLoad()` over the attached cutters,
--     and cutterLoad is written inside work-area processing, i.e. server-side only. The per-header
--     load is exported by the cutter aspect instead, where it can be gated on isServer honestly
--     (see aspects/Cutter.lua).
--   * `spec.lastLostFillLevel` -- initialised to 0 in Combine:onLoad and never written again by any
--     engine code. A grain-loss readout would be a nice thing to have; this field is not it.
--   * `getCombineFillLevelPercentage()` -- the fill units already carry the tank, and this variant
--     only differs by the litres still in the loading-delay pipeline.

VDT = VDT or {}
VDT.Harvest = {}

-- The engine's "no fruit / no fill type" sentinel. Both managers use index 1 for it, and the combine
-- reports it whenever it has not threshed anything yet this session.
local FRUIT_TYPE_UNKNOWN = 1
local FILL_TYPE_UNKNOWN = 1

---@param object table
---@return HarvestModel|nil nil when the object is not a combine
function VDT.Harvest.collect(object)
  local spec = object.spec_combine
  if spec == nil then
    return nil
  end

  ---@type HarvestModel
  local model = {
    swathActive = spec.isSwathActive == true,
    -- Crop entering the tank right now. Not the same question as isTurnedOn (a running combine
    -- driving over stubble is turned on and filling nothing) and not the same as the work areas
    -- (which say the header is over crop, not that the threshing drum is passing grain on).
    filling = spec.isFilling == true,
  }
  if spec.swath ~= nil then
    model.swathAvailable = spec.swath.isAvailable
  end
  if spec.chopper ~= nil then
    model.chopperAvailable = spec.chopper.isAvailable
  end

  -- A buffer combine (forage harvester, beet/potato harvester) has an infinite fill unit: it holds
  -- what it cuts only until the pipe passes it on, so its "tank level" is not a level at all.
  if spec.isBufferCombine ~= nil then
    model.bufferCombine = spec.isBufferCombine
  end

  if spec.workedHectars ~= nil then
    model.hectares = tonumber(ValueMapper.mapFloat(spec.workedHectars))
    if spec.workedHectarsInitial ~= nil then
      model.hectaresSession = tonumber(ValueMapper.mapFloat(spec.workedHectars - spec.workedHectarsInitial))
    end
  end

  -- What went in, and what the tank is taking. The two differ wherever the machine converts (maize
  -- into chaff, sunflower into its own fill type), which is exactly when a terminal wants both.
  if spec.lastValidInputFruitType ~= nil and spec.lastValidInputFruitType ~= FRUIT_TYPE_UNKNOWN then
    model.fruitType = g_fruitTypeManager:getFruitTypeNameByIndex(spec.lastValidInputFruitType)
  end
  if object.getCombineLastValidFillType ~= nil then
    local fillTypeIndex = object:getCombineLastValidFillType()
    if fillTypeIndex ~= nil and fillTypeIndex ~= FILL_TYPE_UNKNOWN then
      local fillType = g_fillTypeManager:getFillTypeByIndex(fillTypeIndex)
      if fillType ~= nil then
        model.fillType = fillType.name
        model.title = fillType.title
      end
    end
  end

  -- Rain is the one weather state that stops a harvest outright, and the engine answers it in two
  -- steps: the early warning fires at a tenth of the rainfall the stop needs, which is the half hour
  -- of notice a terminal can actually act on. A machine whose XML allows threshing during rain
  -- answers false to both.
  if object.getIsThreshingDuringRain ~= nil then
    model.rainBlocked = object:getIsThreshingDuringRain() == true
    model.rainWarning = object:getIsThreshingDuringRain(true) == true
  end

  return model
end
