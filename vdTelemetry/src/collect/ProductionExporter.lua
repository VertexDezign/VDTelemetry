-- Production export channel: the LOCAL player's owned production points (with their production lines
-- + shared internal storage) and factories, written to production.json on its OWN interval — fill
-- levels drift as material is delivered/consumed, so this is interval-driven like mapVehicles.json,
-- not tied to the main tick. Standalone storages (owned silos + object storages) are a SIBLING
-- channel, src/collect/StorageExporter.lua (storage.json) — the two were split so each app/channel
-- can evolve on its own. StorageExporter reuses this module's id / storage-row helpers (placeableId,
-- storageRows), same as HusbandryExporter does; the farm scope itself is VDT.Farm.ownFarmId, shared by
-- every channel there is (src/utils/Farm.lua).
--
-- Reads only base-game state (g_currentMission.productionChainManager), so it lives in collect/, not
-- integrations/ -- and it keeps doing so under the Pumps & Hoses DLC, whose biogas plant it reports
-- correctly because the DLC overwrites the very chain-manager function this asks (see collect()).
-- What a plant is BESIDES its production points -- its name, its bunkers, its torch -- is the one
-- part that needs the DLC's own vocabulary, and that lives in integrations/PumpsAndHoses.lua.
-- Every engine read is pcall-guarded (fail-soft house rule): a point that throws is dropped, a bad
-- storage row skipped, and writeDirty()'s pcall contains the rest.
--
-- Scope is own-farm only (g_localPlayer.farmId): the points come from the chain manager's per-farm
-- list, and a factory is included only when its owning placeable's farm matches. Absence of
-- production.json means "no data yet / export off", same as map.json; the app clears its overview then.
--
-- Enum tokens (status, output mode) are derived from the game's own enum KEYS, camelCased — so they
-- stay stable even if Giants renumbers ProductionPoint.PROD_STATUS / .OUTPUT_MODE (see MapExporter).
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.ProductionExporter = {}

VDT.ProductionExporter.CHANNEL = "production"
VDT.ProductionExporter.FILE_NAME = "production.json"
-- Own version, evolving independently of VDTelemetry.VERSION and the shared Kotlin ProductionData.
-- 2: the farm's points now come from the chain manager's own per-farm list, so a Pumps & Hoses biogas
--    plant reports the way the game's menu shows it (one fermenter entry + one power-plant entry per
--    plant, not one per machine); storage rows resolve through the point's stations, so a merged
--    plant's liters are the whole plant's; `construction` ties a point to its plant and the new
--    `constructions` key describes the rest of it (see integrations/PumpsAndHoses.lua).
VDT.ProductionExporter.VERSION = 2
-- Write cadence in ms. Its own constant (not the main telemetry interval): production fill levels
-- change on the order of seconds at most, so a 2 s refresh keeps the overview live without churn.
VDT.ProductionExporter.INTERVAL_MS = 2000

-- "MISSING_INPUTS" -> "missingInputs": the wire vocabulary is the game's enum key, camelCased.
local function camelToken(key)
  local lower = string.lower(key)
  return (string.gsub(lower, "_(%a)", string.upper))
end

local statusTokens, statusSource -- lazy reverse map of ProductionPoint.PROD_STATUS, value -> token
local modeTokens, modeSource -- lazy reverse map of ProductionPoint.OUTPUT_MODE, value -> token

--- Wire token for a PROD_STATUS value; "inactive" when the enum isn't reachable or the value is unknown.
---@param value any
---@param enum  table<string, number> | nil enum override for tests; defaults to ProductionPoint.PROD_STATUS
---@return string
function VDT.ProductionExporter.statusToken(value, enum)
  enum = enum or (ProductionPoint ~= nil and ProductionPoint.PROD_STATUS or nil)
  if enum == nil or value == nil then
    return "inactive"
  end
  if statusTokens == nil or statusSource ~= enum then
    statusTokens, statusSource = {}, enum
    for key, v in pairs(enum) do
      if type(key) == "string" then
        statusTokens[v] = camelToken(key)
      end
    end
  end
  return statusTokens[value] or "inactive"
end

--- Wire token for an OUTPUT_MODE value; "keep" when the enum isn't reachable or the value is unknown.
---@param value any
---@param enum  table<string, number> | nil enum override for tests; defaults to ProductionPoint.OUTPUT_MODE
---@return string
function VDT.ProductionExporter.outputModeToken(value, enum)
  enum = enum or (ProductionPoint ~= nil and ProductionPoint.OUTPUT_MODE or nil)
  if enum == nil or value == nil then
    return "keep"
  end
  if modeTokens == nil or modeSource ~= enum then
    modeTokens, modeSource = {}, enum
    for key, v in pairs(enum) do
      if type(key) == "string" then
        modeTokens[v] = camelToken(key)
      end
    end
  end
  return modeTokens[value] or "keep"
end

local function num(v)
  return type(v) == "number" and v or 0
end

-- Stable id for app selection: the placeable's uniqueId (persisted across sessions), else its scene
-- node id (stable within a session), else the caller's fallback. Public so the write side resolves a
-- point's exported id back to the live ProductionPoint with the identical id function (no drift).
function VDT.ProductionExporter.placeableId(placeable, fallback)
  if placeable ~= nil then
    local uid = placeable.uniqueId
    if type(uid) == "string" and uid ~= "" then
      return uid
    end
    if type(placeable.rootNode) == "number" then
      return "node" .. placeable.rootNode
    end
  end
  return fallback
end

-- Does this production point count that fill type as one of its own? Only then do its getters answer
-- for it -- ProductionPoint:getFillLevel/getCapacity return a flat 0 for anything that is neither an
-- input nor an output of the point, which would read as "empty" rather than "not mine".
local function pointKnows(point, fillTypeIndex)
  local inputs, outputs = point.inputFillTypeIds, point.outputFillTypeIds
  return (type(inputs) == "table" and inputs[fillTypeIndex] ~= nil)
    or (type(outputs) == "table" and outputs[fillTypeIndex] ~= nil)
end

-- One ProductionFillModel row from a Storage + fill-type index, or nil when the fill type is unknown.
--
-- `point` is the production point the storage belongs to, and when it is given the row is read
-- through ITS getters rather than the storage's. That is not a refinement, it is the difference
-- between right and wrong on a merged biogas plant: the point's getters go through its loading /
-- unloading station, which after a Pumps & Hoses merge covers every fermenter's storage and any
-- storage extension standing in range, while the storage answers only for the one tank it is. The
-- game's own production menu prints exactly these two getters (InGameMenuProductionFrame, fillTypeCell).
local function fillRow(storage, fillTypeIndex, point)
  local fillType = g_fillTypeManager:getFillTypeByIndex(fillTypeIndex)
  if fillType == nil then
    return nil
  end
  local source = storage
  if point ~= nil and pointKnows(point, fillTypeIndex) then
    source = point
  end
  local okLevel, level = pcall(source.getFillLevel, source, fillTypeIndex)
  local okCap, capacity = pcall(source.getCapacity, source, fillTypeIndex)
  return {
    type = fillType.name,
    title = fillType.title,
    level = math.floor(okLevel and num(level) or 0),
    capacity = math.floor(okCap and num(capacity) or 0),
  }
end

-- All fill rows of a Storage, sorted by type for deterministic output (pairs order is undefined).
-- getFillLevels() returns a fillTypeIndex -> level map covering every supported type (0 when empty).
-- Public so the sibling StorageExporter builds standalone-silo rows through the identical path (one
-- definition, so a production point's internal storage and a silo can't format their rows differently).
---@param storage table       a base-game Storage (getFillLevels / getFillLevel / getCapacity)
---@param point   table | nil the owning ProductionPoint, when there is one -- see fillRow
---@return ProductionFillModel[]
function VDT.ProductionExporter.storageRows(storage, point)
  local rows = {}
  local okLevels, levels = pcall(storage.getFillLevels, storage)
  if okLevels and type(levels) == "table" then
    for fillTypeIndex in pairs(levels) do
      local row = fillRow(storage, fillTypeIndex, point)
      if row ~= nil then
        rows[#rows + 1] = row
      end
    end
  end
  table.sort(rows, function(a, b)
    return a.type < b.type
  end)
  return rows
end

-- Local alias so this module's own call sites stay terse.
local storageRows = VDT.ProductionExporter.storageRows

-- One ProductionIoModel row (input or output) from a production's recipe entry, or nil when unknown.
local function ioRow(pp, entry, isOutput)
  local fillType = g_fillTypeManager:getFillTypeByIndex(entry.type)
  if fillType == nil then
    return nil
  end
  local row = { type = fillType.name, title = fillType.title, amount = math.floor(num(entry.amount)) }
  if isOutput then
    row.sellDirectly = entry.sellDirectly == true
    -- The Pumps & Hoses fourth mode first: its value is registered at load time and so is not a key of
    -- ProductionPoint.OUTPUT_MODE, which would make outputModeToken name it "keep" -- the wrong answer,
    -- and a plausible-looking one. The point itself knows (see integrations/PumpsAndHoses.lua).
    if VDT.PumpsAndHoses ~= nil and VDT.PumpsAndHoses.isAutoDistribution(pp, entry.type) then
      row.mode = VDT.PumpsAndHoses.AUTO_DISTRIBUTION
    else
      local okMode, mode = pcall(pp.getOutputDistributionMode, pp, entry.type)
      if okMode then
        row.mode = VDT.ProductionExporter.outputModeToken(mode)
      end
    end
  end
  return row
end

-- Build a point model from anything exposing the ProductionPoint reading surface. Kept
-- source-agnostic because a **factory** (PlaceableFactory) is a different object than a
-- ProductionPoint: it IS the placeable (so `placeable` is passed explicitly, not read off
-- `.owningPlaceable`), its input storage lives on a different field (passed as `storage`), its lone
-- production has no id/status, and it has no getIsProductionEnabled / getOutputDistributionMode --
-- the pcall guards below degrade those to enabled=false / no mode, which is exactly what a read-only
-- factory should report. `isFactory` marks the point so the app hides the on/off + mode controls.
---@param pp         table       a ProductionPoint or a PlaceableFactory (the reading surface: getName, productions)
---@param placeable  table | nil the placeable to derive the stable id from
---@param storage    table | nil the shared/input storage, or nil
---@param fallbackId string
---@param isFactory  boolean
---@return ProductionPointModel
local function collectPoint(pp, placeable, storage, fallbackId, isFactory)
  local okName, name = pcall(pp.getName, pp)
  -- Every array field is omitted when empty (set nil, not {}): the Json encoder writes {} for an
  -- empty Lua table, which the Kotlin model rejects as "expected [" -- an omitted key falls back to
  -- emptyList() instead (see the collect() note / MapExporter / TaskList.lua).
  -- A factory is not a ProductionPoint and has none of its getters, so its rows stay storage-read;
  -- a real point's are read through the point (see fillRow).
  local storageRowsList = storage ~= nil and storageRows(storage, not isFactory and pp or nil) or {}
  local lines = {}

  for index, production in ipairs(pp.productions or {}) do
    local okEnabled, enabled = pcall(pp.getIsProductionEnabled, pp, production.id)
    local inputs = {}
    for _, input in ipairs(production.inputs or {}) do
      local row = ioRow(pp, input, false)
      if row ~= nil then
        inputs[#inputs + 1] = row
      end
    end
    local outputs = {}
    for _, output in ipairs(production.outputs or {}) do
      local row = ioRow(pp, output, true)
      if row ~= nil then
        outputs[#outputs + 1] = row
      end
    end
    lines[#lines + 1] = {
      id = production.id ~= nil and tostring(production.id) or ("line" .. index),
      name = (type(production.name) == "string" and production.name ~= "") and production.name or ("Line " .. index),
      status = VDT.ProductionExporter.statusToken(production.status),
      enabled = okEnabled and enabled == true,
      cyclesPerMonth = math.floor(num(production.cyclesPerMonth)),
      costsPerMonth = math.floor(num(production.costsPerActiveMonth)),
      inputs = #inputs > 0 and inputs or nil,
      outputs = #outputs > 0 and outputs or nil,
    }
  end

  return {
    id = VDT.ProductionExporter.placeableId(placeable, fallbackId),
    name = (okName and type(name) == "string" and name ~= "") and name or "Production",
    isFactory = isFactory or nil,
    -- Which biogas plant this point is a part of, when the Pumps & Hoses DLC built one around it;
    -- nil for every point in a game without the DLC. Resolved at runtime, so the source order
    -- between this collector and the integration does not matter.
    construction = VDT.PumpsAndHoses ~= nil and VDT.PumpsAndHoses.reference(placeable) or nil,
    lines = #lines > 0 and lines or nil,
    storage = #storageRowsList > 0 and storageRowsList or nil,
  }
end

-- Channel is available once the production chain manager exists (map loaded). While spectating, it
-- still writes an empty file so the app shows the (owned-nothing) empty state rather than freezing.
function VDT.ProductionExporter.isAvailable()
  return g_currentMission ~= nil and g_currentMission.productionChainManager ~= nil and g_fillTypeManager ~= nil
end

--- Build the production model, or nil when the chain manager isn't up yet (skips the write).
---@return ProductionModel | nil
function VDT.ProductionExporter.collect()
  if not VDT.ProductionExporter.isAvailable() then
    return nil
  end
  local farmId = VDT.Farm.ownFarmId()
  if farmId == nil then
    -- spectator / no owned farm: keep the channel present but empty (no points, no storages)
    return { version = tostring(VDT.ProductionExporter.VERSION) }
  end

  local manager = g_currentMission.productionChainManager
  local points = {}
  -- The chain manager's OWN per-farm list, not `manager.productionPoints` filtered by owner. The two
  -- are the same list in a base game, but the Pumps & Hoses DLC overwrites this function so a biogas
  -- plant returns only its MERGED points -- one fermenter entry and one power-plant entry per plant,
  -- with every machine's recipe already summed into them -- which is how the game's own production
  -- menu lists them. Asking through the function is therefore how our overview agrees with the menu
  -- beside it, for free and for whatever Giants or a DLC does to the list next. (One consequence,
  -- and the DLC's menu shows it too: a sandbox part with no plant around it yet is in neither list.)
  local okList, farmPoints = pcall(manager.getProductionPointsForFarmId, manager, farmId)
  for index, pp in ipairs(okList and farmPoints or {}) do
    points[#points + 1] = collectPoint(pp, pp.owningPlaceable, pp.storage, "point" .. index, false)
  end
  -- Factories (PlaceableFactory) are a separate chain-manager list the game's own menu also shows.
  -- A factory is the placeable itself; its input storage lives on spec_factory.storage. Read-only:
  -- collectPoint's guards report no on/off + no output mode, and isFactory=true hides those controls.
  for index, factory in ipairs(manager.factories or {}) do
    local okOwner, owner = pcall(factory.getOwnerFarmId, factory)
    if okOwner and owner == farmId then
      local spec = factory.spec_factory
      local storage = spec ~= nil and spec.storage or nil
      points[#points + 1] = collectPoint(factory, factory, storage, "factory" .. index, true)
    end
  end

  return {
    version = tostring(VDT.ProductionExporter.VERSION),
    -- omit empty arrays: the Json encoder emits {} for an empty table, so a nil keeps the key absent
    -- and the Kotlin model falls back to emptyList() (see MapExporter / TaskList.lua).
    productionPoints = #points > 0 and points or nil,
    -- The farm's biogas plants: the name each of the points above is grouped under, and the parts of
    -- a plant that are not production points (bunkers, digestate tank, torch) with the DLC's own
    -- utilization reading. Absent without the DLC. See integrations/PumpsAndHoses.lua.
    constructions = VDT.PumpsAndHoses ~= nil and VDT.PumpsAndHoses.collect(farmId) or nil,
  }
end

-- Self-register the channel (see ExportChannels). Interval-driven: the registry owns the cadence
-- (accumulates the frame delta and queues a write every intervalMs). markDirty on an unavailable
-- channel stays pending (selectDirty skips it without clearing), so ticking before the chain manager
-- is up costs nothing.
VDT.ExportChannels.register({
  name = VDT.ProductionExporter.CHANNEL,
  fileName = VDT.ProductionExporter.FILE_NAME,
  isAvailable = VDT.ProductionExporter.isAvailable,
  collect = VDT.ProductionExporter.collect,
  intervalMs = VDT.ProductionExporter.INTERVAL_MS,
  -- Only this farm's production points are exported (ownFarmId).
  farmScoped = true,
})
