-- Production & storage export channel: the LOCAL player's owned production points (with their
-- production lines + shared internal storage) and standalone storages (owned silos with no
-- production), written to productionStorage.json on its OWN interval — fill levels drift as material
-- is delivered/consumed, so this is interval-driven like mapVehicles.json, not tied to the main tick.
--
-- Reads only base-game state (g_currentMission.productionChainManager / .storageSystem), so it lives
-- in collect/, not integrations/. Every engine read is pcall-guarded (fail-soft house rule): a point
-- that throws is dropped, a bad storage row skipped, and writeDirty()'s pcall contains the rest.
--
-- Scope is own-farm only (g_localPlayer.farmId): a production point / storage is included only when
-- its owning placeable's farm matches. Absence of productionStorage.json means "no data yet / export
-- off", same as map.json; the app clears its overview then.
--
-- Enum tokens (status, output mode) are derived from the game's own enum KEYS, camelCased — so they
-- stay stable even if Giants renumbers ProductionPoint.PROD_STATUS / .OUTPUT_MODE (see MapExporter).
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.ProductionExporter = {}

VDT.ProductionExporter.CHANNEL = "productionStorage"
VDT.ProductionExporter.FILE_NAME = "productionStorage.json"
-- Own version, evolving independently of VDTelemetry.VERSION and the shared Kotlin ProductionsData.
VDT.ProductionExporter.VERSION = 1
-- Write cadence in ms. Its own constant (not the main telemetry interval): production fill levels
-- change on the order of seconds at most, so a 2 s refresh keeps the overview live without churn.
VDT.ProductionExporter.INTERVAL_MS = 2000

-- Accumulated ms since the last markDirty; on the table so specs can reset it.
VDT.ProductionExporter.timerMs = 0

-- "MISSING_INPUTS" -> "missingInputs": the wire vocabulary is the game's enum key, camelCased.
local function camelToken(key)
  local lower = string.lower(key)
  return (string.gsub(lower, "_(%a)", string.upper))
end

local statusTokens, statusSource -- lazy reverse map of ProductionPoint.PROD_STATUS, value -> token
local modeTokens, modeSource -- lazy reverse map of ProductionPoint.OUTPUT_MODE, value -> token

---Wire token for a PROD_STATUS value; "inactive" when the enum isn't reachable or the value is unknown.
---@param value any
---@param enum table<string, number>|nil enum override for tests; defaults to ProductionPoint.PROD_STATUS
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

---Wire token for an OUTPUT_MODE value; "keep" when the enum isn't reachable or the value is unknown.
---@param value any
---@param enum table<string, number>|nil enum override for tests; defaults to ProductionPoint.OUTPUT_MODE
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

-- The local player's farm, or nil while spectating / before a player exists (see EnvironmentExporter).
-- Public so the write side (src/command/ProductionControl.lua) enforces ownership against the exact
-- same "which farm are we" rule the read side scopes to — one definition, so they can't drift.
function VDT.ProductionExporter.ownFarmId()
  if g_localPlayer ~= nil and type(g_localPlayer.farmId) == "number" and g_localPlayer.farmId > 0 then
    return g_localPlayer.farmId
  end
  return nil
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

-- One ProductionFillModel row from a Storage + fill-type index, or nil when the fill type is unknown.
local function fillRow(storage, fillTypeIndex)
  local fillType = g_fillTypeManager:getFillTypeByIndex(fillTypeIndex)
  if fillType == nil then
    return nil
  end
  local okLevel, level = pcall(storage.getFillLevel, storage, fillTypeIndex)
  local okCap, capacity = pcall(storage.getCapacity, storage, fillTypeIndex)
  return {
    type = fillType.name,
    title = fillType.title,
    level = math.floor(okLevel and num(level) or 0),
    capacity = math.floor(okCap and num(capacity) or 0),
  }
end

-- All fill rows of a Storage, sorted by type for deterministic output (pairs order is undefined).
-- getFillLevels() returns a fillTypeIndex -> level map covering every supported type (0 when empty).
local function storageRows(storage)
  local rows = {}
  local okLevels, levels = pcall(storage.getFillLevels, storage)
  if okLevels and type(levels) == "table" then
    for fillTypeIndex in pairs(levels) do
      local row = fillRow(storage, fillTypeIndex)
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

-- One ProductionIoModel row (input or output) from a production's recipe entry, or nil when unknown.
local function ioRow(pp, entry, isOutput)
  local fillType = g_fillTypeManager:getFillTypeByIndex(entry.type)
  if fillType == nil then
    return nil
  end
  local row = {
    type = fillType.name,
    title = fillType.title,
    amount = math.floor(num(entry.amount)),
  }
  if isOutput then
    row.sellDirectly = entry.sellDirectly == true
    local okMode, mode = pcall(pp.getOutputDistributionMode, pp, entry.type)
    if okMode then
      row.mode = VDT.ProductionExporter.outputModeToken(mode)
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
---@param pp table a ProductionPoint or a PlaceableFactory (the reading surface: getName, productions)
---@param placeable table|nil the placeable to derive the stable id from
---@param storage table|nil the shared/input storage, or nil
---@param fallbackId string
---@param isFactory boolean
---@return ProductionPointModel
local function collectPoint(pp, placeable, storage, fallbackId, isFactory)
  local okName, name = pcall(pp.getName, pp)
  -- Every array field is omitted when empty (set nil, not {}): the Json encoder writes {} for an
  -- empty Lua table, which the Kotlin model rejects as "expected [" -- an omitted key falls back to
  -- emptyList() instead (see the collect() note / MapExporter / TaskList.lua).
  local storageRowsList = storage ~= nil and storageRows(storage) or {}
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
    lines = #lines > 0 and lines or nil,
    storage = #storageRowsList > 0 and storageRowsList or nil,
  }
end

local function placeableName(placeable)
  local okName, name = pcall(placeable.getName, placeable)
  return (okName and type(name) == "string" and name ~= "") and name or "Storage"
end

-- A liter-fill silo (PlaceableSilo). Its Storage objects carry NO back-reference to the placeable
-- (PlaceableSilo sets owningPlaceable on its loading/unloading stations, never on the Storage), which
-- is why these can't be discovered from storageSystem:getStorages() -- we read spec_silo.storages
-- directly. A storagePerFarm silo holds one Storage per farm (storage.ownerFarmId per set); include
-- only the local farm's set. A normal owned silo sets ownerFarmId to the placeable owner, so the same
-- check passes. Duplicate fill types across sets are merged; an all-empty (0-level) silo still shows
-- as long as it has capacity for some fill type. Returns nil when there's nothing to show.
---@return StandaloneStorageModel|nil
local function collectSilo(placeable, farmId, fallbackIndex)
  local rows = {}
  local seen = {}
  for _, storage in ipairs(placeable.spec_silo.storages) do
    local sOwner = storage.ownerFarmId
    if sOwner == nil or sOwner == farmId then
      for _, row in ipairs(storageRows(storage)) do
        if not seen[row.type] then
          seen[row.type] = true
          rows[#rows + 1] = row
        end
      end
    end
  end
  if #rows == 0 then
    return nil
  end
  table.sort(rows, function(a, b)
    return a.type < b.type
  end)
  return {
    id = VDT.ProductionExporter.placeableId(placeable, "storage" .. fallbackIndex),
    name = placeableName(placeable),
    kind = "fill",
    fills = rows,
  }
end

-- An object storage (PlaceableObjectStorage — bales/pallets). Count-based, not liters:
-- spec.numStoredObjects / spec.capacity total, with spec.objectInfos grouping identical stored
-- objects. The per-group title comes from the live object's getDialogText(), which exists on the
-- server/SP host but not necessarily on an MP client (only counts stream there -- the game's own HUD
-- skips those too); we still report the accurate total and include a group row only when its title
-- resolves. Shown even when empty (capacity > 0) like an empty silo.
---@return StandaloneStorageModel|nil
local function collectObjectStorage(placeable, farmId, fallbackIndex)
  local spec = placeable.spec_objectStorage
  local capacity = math.floor(num(spec.capacity))
  if capacity <= 0 then
    return nil
  end
  local objects = {}
  for _, info in ipairs(spec.objectInfos or {}) do
    local count = math.floor(num(info.numObjects))
    local first = type(info.objects) == "table" and info.objects[1] or nil
    if count > 0 and first ~= nil then
      local okText, title = pcall(first.getDialogText, first)
      if okText and type(title) == "string" and title ~= "" then
        objects[#objects + 1] = { title = title, count = count }
      end
    end
  end
  return {
    id = VDT.ProductionExporter.placeableId(placeable, "storage" .. fallbackIndex),
    name = placeableName(placeable),
    kind = "object",
    count = math.floor(num(spec.numStoredObjects)),
    capacity = capacity,
    -- omit when empty: an empty Lua table encodes as {} which the Kotlin List<StoredObject> rejects
    objects = #objects > 0 and objects or nil,
  }
end

-- Standalone storages: owned silo and object-storage placeables. Production points are excluded for
-- free (they carry spec_productionPoint, not spec_silo / spec_objectStorage, and their storage is
-- already reported under productionPoints). Silo extensions stay out of scope for v1. Walked over the
-- placeable list because a Storage/object-storage has no reliable back-reference to its placeable.
---@param farmId number
---@return StandaloneStorageModel[]
local function collectStorages(farmId)
  local out = {}
  local system = g_currentMission.placeableSystem
  local placeables = system ~= nil and system.placeables or nil
  if type(placeables) ~= "table" then
    return out
  end

  for _, placeable in ipairs(placeables) do
    local okOwner, owner = pcall(placeable.getOwnerFarmId, placeable)
    if okOwner and owner == farmId then
      local entry
      if placeable.spec_silo ~= nil and type(placeable.spec_silo.storages) == "table" then
        entry = collectSilo(placeable, farmId, #out + 1)
      elseif placeable.spec_objectStorage ~= nil then
        entry = collectObjectStorage(placeable, farmId, #out + 1)
      end
      if entry ~= nil then
        out[#out + 1] = entry
      end
    end
  end
  return out
end

-- Channel is available once the production chain manager exists (map loaded). While spectating, it
-- still writes an empty file so the app shows the (owned-nothing) empty state rather than freezing.
function VDT.ProductionExporter.isAvailable()
  return g_currentMission ~= nil and g_currentMission.productionChainManager ~= nil and g_fillTypeManager ~= nil
end

---Build the productions model, or nil when the chain manager isn't up yet (skips the write).
---@return ProductionsModel|nil
function VDT.ProductionExporter.collect()
  if not VDT.ProductionExporter.isAvailable() then
    return nil
  end
  local farmId = VDT.ProductionExporter.ownFarmId()
  if farmId == nil then
    -- spectator / no owned farm: keep the channel present but empty (no points, no storages)
    return { version = tostring(VDT.ProductionExporter.VERSION) }
  end

  local manager = g_currentMission.productionChainManager
  local points = {}
  for index, pp in ipairs(manager.productionPoints or {}) do
    local okOwner, owner = pcall(pp.getOwnerFarmId, pp)
    if okOwner and owner == farmId then
      points[#points + 1] = collectPoint(pp, pp.owningPlaceable, pp.storage, "point" .. index, false)
    end
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

  local storages = collectStorages(farmId)

  return {
    version = tostring(VDT.ProductionExporter.VERSION),
    -- omit empty arrays: the Json encoder emits {} for an empty table, so a nil keeps the key absent
    -- and the Kotlin model falls back to emptyList() (see MapExporter / TaskList.lua).
    productionPoints = #points > 0 and points or nil,
    storages = #storages > 0 and storages or nil,
  }
end

-- Interval-driven: accumulate the frame delta and queue a write every INTERVAL_MS. markDirty on an
-- unavailable channel stays pending (selectDirty skips it without clearing), so ticking before the
-- chain manager is up costs nothing.
function VDT.ProductionExporter.tick(_, dt)
  if type(dt) ~= "number" then
    return
  end
  VDT.ProductionExporter.timerMs = VDT.ProductionExporter.timerMs + dt
  if VDT.ProductionExporter.timerMs >= VDT.ProductionExporter.INTERVAL_MS then
    VDT.ProductionExporter.timerMs = 0
    VDT.ExportChannels.markDirty(VDT.ProductionExporter.CHANNEL)
  end
end

-- Self-register the channel (see ExportChannels).
VDT.ExportChannels.register({
  name = VDT.ProductionExporter.CHANNEL,
  fileName = VDT.ProductionExporter.FILE_NAME,
  isAvailable = VDT.ProductionExporter.isAvailable,
  collect = VDT.ProductionExporter.collect,
  tick = VDT.ProductionExporter.tick,
})
