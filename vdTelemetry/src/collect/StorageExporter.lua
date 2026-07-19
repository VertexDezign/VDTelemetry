-- Storage export channel: the LOCAL player's owned standalone storages with no production — liter
-- silos (PlaceableSilo) and object storages (PlaceableObjectStorage — bales/pallets) — written to
-- storage.json on its OWN interval. Fill levels/counts drift as material moves, so this is
-- interval-driven like mapVehicles.json, not tied to the main tick.
--
-- Split off the sibling PRODUCTION channel (src/collect/ProductionExporter.lua, production.json) so
-- each app/channel can evolve on its own. This module REUSES ProductionExporter's own-farm / id /
-- storage-row helpers (ownFarmId, placeableId, storageRows) — one definition, so ownership scoping,
-- stable ids, and fill-row formatting can't drift between the two channels (same pattern as
-- HusbandryExporter). ProductionExporter is sourced first (see VDTelemetry.lua sourceFiles).
--
-- Reads only base-game state (g_currentMission.placeableSystem), so it lives in collect/, not
-- integrations/. Every engine read is pcall-guarded (fail-soft house rule): a placeable that throws
-- is dropped, a bad storage row skipped, and writeDirty()'s pcall contains the rest.
--
-- Scope is own-farm only (g_localPlayer.farmId): a storage is included only when its owning
-- placeable's farm matches. Absence of storage.json means "no data yet / export off", same as
-- map.json; the app clears its overview then.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.StorageExporter = {}

VDT.StorageExporter.CHANNEL = "storage"
VDT.StorageExporter.FILE_NAME = "storage.json"
-- Own version, evolving independently of VDTelemetry.VERSION and the shared Kotlin StorageData.
VDT.StorageExporter.VERSION = 1
-- Write cadence in ms; matches ProductionExporter — fill levels/counts change on the order of seconds
-- at most, so a 2 s refresh keeps the overview live without churn.
VDT.StorageExporter.INTERVAL_MS = 2000

-- Accumulated ms since the last markDirty; on the table so specs can reset it.
VDT.StorageExporter.timerMs = 0

local function num(v)
  return type(v) == "number" and v or 0
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
      for _, row in ipairs(VDT.ProductionExporter.storageRows(storage)) do
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
  -- The per-action unload cap (XML default 25); the effective max per type is min(this, count).
  local maxUnload = math.floor(num(spec.maxUnloadAmount))
  if maxUnload <= 0 then
    maxUnload = 25
  end
  local objects = {}
  -- `index` is the objectInfoIndex (position in spec.objectInfos) the unload command addresses; keep
  -- the true index even though title-less groups are skipped, so the mapping stays correct.
  for index, info in ipairs(spec.objectInfos or {}) do
    local count = math.floor(num(info.numObjects))
    local first = type(info.objects) == "table" and info.objects[1] or nil
    if count > 0 and first ~= nil then
      local okText, title = pcall(first.getDialogText, first)
      if okText and type(title) == "string" and title ~= "" then
        objects[#objects + 1] = { index = index, title = title, count = count }
      end
    end
  end
  return {
    id = VDT.ProductionExporter.placeableId(placeable, "storage" .. fallbackIndex),
    name = placeableName(placeable),
    kind = "object",
    count = math.floor(num(spec.numStoredObjects)),
    capacity = capacity,
    maxUnloadAmount = maxUnload,
    -- omit when empty: an empty Lua table encodes as {} which the Kotlin List<StoredObject> rejects
    objects = #objects > 0 and objects or nil,
  }
end

-- Standalone storages: owned silo and object-storage placeables. Production points are excluded for
-- free (they carry spec_productionPoint, not spec_silo / spec_objectStorage, and their storage is
-- reported by the production channel). Silo extensions stay out of scope for v1. Walked over the
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

-- Channel is available once the placeable system exists (map loaded) and fill types are known. While
-- spectating, it still writes an empty file so the app shows the (owned-nothing) empty state rather
-- than freezing.
function VDT.StorageExporter.isAvailable()
  return g_currentMission ~= nil and g_currentMission.placeableSystem ~= nil and g_fillTypeManager ~= nil
end

---Build the storage model, or nil when the placeable system isn't up yet (skips the write).
---@return StorageModel|nil
function VDT.StorageExporter.collect()
  if not VDT.StorageExporter.isAvailable() then
    return nil
  end
  local farmId = VDT.ProductionExporter.ownFarmId()
  if farmId == nil then
    -- spectator / no owned farm: keep the channel present but empty (no storages)
    return { version = tostring(VDT.StorageExporter.VERSION) }
  end

  local storages = collectStorages(farmId)

  return {
    version = tostring(VDT.StorageExporter.VERSION),
    -- omit empty arrays: the Json encoder emits {} for an empty table, so a nil keeps the key absent
    -- and the Kotlin model falls back to emptyList() (see MapExporter / TaskList.lua).
    storages = #storages > 0 and storages or nil,
  }
end

-- Interval-driven: accumulate the frame delta and queue a write every INTERVAL_MS. markDirty on an
-- unavailable channel stays pending (selectDirty skips it without clearing), so ticking before the
-- placeable system is up costs nothing.
function VDT.StorageExporter.tick(_, dt)
  if type(dt) ~= "number" then
    return
  end
  VDT.StorageExporter.timerMs = VDT.StorageExporter.timerMs + dt
  if VDT.StorageExporter.timerMs >= VDT.StorageExporter.INTERVAL_MS then
    VDT.StorageExporter.timerMs = 0
    VDT.ExportChannels.markDirty(VDT.StorageExporter.CHANNEL)
  end
end

-- Self-register the channel (see ExportChannels).
VDT.ExportChannels.register({
  name = VDT.StorageExporter.CHANNEL,
  fileName = VDT.StorageExporter.FILE_NAME,
  isAvailable = VDT.StorageExporter.isAvailable,
  collect = VDT.StorageExporter.collect,
  tick = VDT.StorageExporter.tick,
})
