-- Aspect collector: the header — what the machine at the front is cutting or picking up, and how
-- hard it is working. Applies to any object (vehicle or implement); the Cutter spec sits on combine
-- headers, on forage-harvester headers (which convert their crop into CHAFF) and on the pickups that
-- feed both. Namespaced under VDT.* (see TurnOn.lua).
--
-- The combine says what is going into the tank (see aspects/Harvest.lua); this says what is coming
-- off the field, and the two differ on every converting header. A header is also the machine an
-- operator watches for "am I actually cutting anything", which no other aspect answers: the work
-- areas say the header is over ground, `isTurnedOn` says the drum is spinning, and neither says crop
-- is being taken.
--
-- MULTIPLAYER, and the trap that shapes this file: `spec.isWorking` and `spec.cutterLoad` are both
-- written from processCutterArea / onEndWorkAreaProcessing, i.e. inside work-area processing, which
-- runs server-side. On a joined client they keep whatever Cutter:onLoad left (false / 0) forever.
--   * For "is it cutting" the engine has a synced answer and that is what is used here:
--     `lastAreaBiggerZero` rides Cutter:onWriteStream and every onWriteUpdateStream, so `working`
--     below is true on a client -- but it is a PER-FRAME flag, written from work-area processing and
--     cleared again in onEndWorkAreaProcessing, so an export poll lands on a false frame often enough
--     to matter (the first capture of a chopper mid-pass caught exactly that: `filling` true on the
--     machine, `working` false on the header feeding it). The engine never reads it bare either: its
--     effects, its client-side branch and getIsWorkAreaActive all gate on a WINDOW over
--     `lastAreaBiggerZeroTime` -- 300 ms for "is collecting", 150 ms for the work area. `working`
--     uses the engine's own 300 ms, and the timestamp is set on the streamed path too
--     (Cutter:onReadStream / onReadUpdateStream), so the window survives on a client.
--   * For the load there is no synced equivalent, so it is exported only where it is real
--     (`object.isServer`, which covers single player and the host) and left ABSENT on a client
--     rather than reported as an idle header. Absent means unknown throughout this model; a hard
--     zero would mean "not loaded", which is a different and wrong claim.
-- The fruit and fill types, the windrow flag and the conversion all ride readCutterFromStream, so
-- they are true everywhere.
--
-- Deliberately NOT collected: `spec.currentCutHeight`. Nothing in the engine writes it except the
-- savegame load and `setCutterCutHeight`, which no engine code calls, so it reads 0 on essentially
-- every machine — a stubble-height gauge that is always zero is worse than none.

VDT = VDT or {}
VDT.Cutter = {}

-- The engine's "no fruit / no fill type" sentinel; both managers use index 1 for it.
local FRUIT_TYPE_UNKNOWN = 1
local FILL_TYPE_UNKNOWN = 1

-- How long after the last crop-bearing frame the header still counts as collecting. The engine's own
-- figure for that question (Cutter:onUpdateTick's isCollecting, and its client-side effect gate).
local COLLECTING_WINDOW_MS = 300

---Whether the header is taking crop, over the engine's own 300 ms window rather than this frame.
---@param spec table spec_cutter
---@return boolean
local function isCollecting(spec)
  if spec.lastAreaBiggerZero == true then
    return true
  end
  -- The timestamp starts at -1 and the mission clock starts near 0, so a bare comparison would report
  -- a header that has never cut as collecting for the first fraction of a second of the save.
  local since = spec.lastAreaBiggerZeroTime
  if since == nil or since <= 0 or g_currentMission == nil or g_currentMission.time == nil then
    return false
  end
  return g_currentMission.time < since + COLLECTING_WINDOW_MS
end

---Resolve a fill type index onto its engine name and localized title.
---@param fillTypeIndex number|nil
---@return string|nil name, string|nil title
local function fillTypeNames(fillTypeIndex)
  if fillTypeIndex == nil or fillTypeIndex == FILL_TYPE_UNKNOWN then
    return nil, nil
  end
  local fillType = g_fillTypeManager:getFillTypeByIndex(fillTypeIndex)
  if fillType == nil then
    return nil, nil
  end
  return fillType.name, fillType.title
end

---@param object table a vehicle or implement
---@return CutterModel|nil nil when the object is not a header
function VDT.Cutter.collect(object)
  local spec = object.spec_cutter
  if spec == nil then
    return nil
  end

  ---@type CutterModel
  local model = {
    working = isCollecting(spec),
    windrow = spec.useWindrow == true,
    -- Why a header can keep cutting with the hydraulics up: draper and pickup headers declare it, and
    -- without the flag a raised header that is still taking crop looks like a bug in the readout.
    cutWhileRaised = spec.allowCuttingWhileRaised == true,
  }

  -- The crop being cut. Nil while the header is over bare ground, which is the honest answer -- the
  -- combine keeps the LAST valid one (harvest.fruitType) for the machine as a whole.
  if spec.currentInputFruitType ~= nil and spec.currentInputFruitType ~= FRUIT_TYPE_UNKNOWN then
    model.fruitType = g_fruitTypeManager:getFruitTypeNameByIndex(spec.currentInputFruitType)
  end

  -- What the header hands on. On a converting header (maize into chaff) this is not the crop's own
  -- fill type, which is the whole reason it is exported next to the fruit type.
  model.fillType, model.title = fillTypeNames(spec.currentOutputFillType)

  -- A pickup working a windrow has no standing crop to name, so the material it lifts is the only
  -- thing that identifies the pass.
  if model.windrow then
    model.inputFillType = fillTypeNames(spec.currentInputFillType)
  end

  -- How much straw this header leaves behind, as a share of what the crop would drop. A machine
  -- constant from the XML, and the number that explains why two headers on the same field produce
  -- different swaths.
  if spec.strawRatio ~= nil then
    model.strawRatio = tonumber(ValueMapper.mapFloat(spec.strawRatio))
  end

  -- Server-only; see the multiplayer note in the header.
  if object.isServer and spec.cutterLoad ~= nil then
    model.load = tonumber(ValueMapper.mapFloat(spec.cutterLoad))
  end

  return model
end
