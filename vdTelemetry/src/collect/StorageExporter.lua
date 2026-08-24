-- Storage export channel: everything the LOCAL player's farm is holding outside a production point —
-- written to storage.json on its OWN interval. Fill levels/counts drift as material moves, so this is
-- interval-driven like mapVehicles.json, not tied to the main tick. Four blocks, one key each:
--   * `storages` — the owned standalone storage PLACEABLES: liter silos (PlaceableSilo) and object
--     storages (PlaceableObjectStorage — bales/pallets put away). This is the app's Storage view.
--     A placeable a husbandry has taken over as its own store — the manure heap behind the barn, an
--     `isExtension` slurry tank — is NOT here: those liters are husbandry.json's (see feedsHusbandry).
--   * `bunkerSilos` — silage bunkers (PlaceableBunkerSilo / PlaceableMultiBunkerSilo).
--   * `looseBales` / `loosePallets` — the bales and pallets lying around the farm, aggregated.
--
-- Split off the sibling PRODUCTION channel (src/collect/ProductionExporter.lua, production.json) so
-- each app/channel can evolve on its own. This module REUSES ProductionExporter's id / storage-row
-- helpers (placeableId, storageRows) — one definition, so stable ids and fill-row formatting can't
-- drift between the two channels (same pattern as HusbandryExporter). ProductionExporter is sourced
-- first (see VDTelemetry.lua sourceFiles). The farm scope is the mod-wide VDT.Farm.ownFarmId.
--
-- Reads only base-game state (the placeable, item and vehicle systems), so it lives in collect/, not
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
-- 2: added bunkerSilos / looseBales / loosePallets, and type+level on an object storage's rows.
-- 3: a storage a husbandry counts as its own is no longer exported (see feedsHusbandry) -- its
--    liters live in husbandry.json instead, which carries a fill type on its bars from ITS v2.
VDT.StorageExporter.VERSION = 3
-- Write cadence in ms; matches ProductionExporter — fill levels/counts change on the order of seconds
-- at most, so a 2 s refresh keeps the overview live without churn.
VDT.StorageExporter.INTERVAL_MS = 2000

-- FillType.UNKNOWN — the engine's "no fill type" default index (see aspects/FillUnit.lua). An object
-- sitting on it holds nothing nameable, so it is no resource and gets skipped rather than grouped
-- under a fill type called UNKNOWN.
local FILL_TYPE_UNKNOWN = 1

-- BunkerSilo.STATE_* (objects/BunkerSilo.lua), named locally because the engine class is a bare
-- global the specs do not stand up. FILL is the open heap being driven in and compacted, CLOSED is
-- covered and fermenting, FERMENTED is done but still covered, DRAIN is opened and being taken out.
local BUNKER_STATES = { [0] = "FILL", [1] = "CLOSED", [2] = "FERMENTED", [3] = "DRAIN" }

local function num(v)
  return type(v) == "number" and v or 0
end

local function placeableName(placeable, fallback)
  local okName, name = pcall(placeable.getName, placeable)
  return (okName and type(name) == "string" and name ~= "") and name or fallback or "Storage"
end

-- A liter-fill storage placeable, from whatever list of Storage-shaped objects it keeps. Their objects
-- carry NO back-reference to the placeable (PlaceableSilo sets owningPlaceable on its loading/unloading
-- stations, never on the Storage), which is why these can't be discovered from
-- storageSystem:getStorages() -- the specs are read directly.
--
-- TWO KINDS OF PLACEABLE come through here, because they answer the same three questions
-- (getFillLevels / getFillLevel / getCapacity) and so go through ProductionExporter.storageRows
-- unchanged:
--   * PlaceableSilo -- spec_silo.storages, a LIST. A storagePerFarm silo holds one Storage per farm
--     (storage.ownerFarmId per set); include only the local farm's set. A normal owned silo sets
--     ownerFarmId to the placeable owner, so the same check passes.
--   * PlaceableManureHeap -- spec_manureHeap.manureHeap, ONE object, and not a Storage subclass at all
--     (it descends from Object) but with the identical reading surface. It is a building the farm
--     unloads manure out of, so it belongs on this list next to the slurry tank that is its liquid
--     twin; it was invisible until someone went looking for their Misthaufen and did not find it.
--
-- Duplicate fill types across sets are merged; an all-empty (0-level) storage still shows as long as
-- it has capacity for some fill type. Returns nil when there's nothing to show.
---@param placeable table
---@param storages  table[] the placeable's Storage-shaped objects
---@return StandaloneStorageModel | nil
local function collectFillStorage(placeable, storages, farmId, fallbackIndex)
  local rows = {}
  local seen = {}
  for _, storage in ipairs(storages) do
    local sOwner = storage.ownerFarmId
    if sOwner == nil or sOwner == farmId then
      for _, row in ipairs(VDT.ProductionExporter.storageRows(storage)) do
        local existing = seen[row.type]
        if existing then
          existing.level = existing.level + row.level
          existing.capacity = existing.capacity + row.capacity
        else
          seen[row.type] = row
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

-- TRUE when a husbandry already counts these liters as its own, in which case the placeable holding
-- them is not exported at all -- the manure would otherwise be summed twice by the stock overview
-- and the price list, once here and once out of husbandry.json.
--
-- The wiring, which is the game's and not ours: a manure heap and any storage flagged `isExtension`
-- (the slurry tank next to a barn) register themselves as a TARGET STORAGE of every extendable
-- unloading station in range -- PlaceableManureHeap:onFinalizePlacement ->
-- storageSystem:addStorageToUnloadingStations, and PlaceableHusbandry:onFinalizePlacement does the
-- same sweep from its own side, so the link forms whichever placeable loads first. The barn's manure
-- and slurry bars are then built from getHusbandryFillLevel(), which is
-- UnloadingStation:getFillLevel() summing exactly those target storages
-- (PlaceableHusbandryStraw / PlaceableHusbandryLiquidManure : getConditionInfos). The heap IS the
-- barn's manure store; there is one heap of manure and it is reported once, by the pen that made it.
--
-- Whether a given heap IS one of those is decided at runtime, per placement, not by what it is in the
-- shop: StorageSystem:getIsStationCompatible asks only for the same farm, distance <
-- station.storageRadius, and one shared fill type. So the same heap is a plain farm store standing
-- alone and the barn's own store standing beside one -- which is why this must be read live rather
-- than inferred from the placeable. (ManureHeap.isExtension defaults to TRUE, where
-- Storage.isExtension defaults to false, so every heap is a candidate and a slurry tank only where
-- its XML says so.)
--
-- Read off the STORAGE rather than off the barn: the engine keeps `storage.unloadingStations` in
-- sync from the other side (UnloadingStation:addTargetStorage calls storage:addUnloadingStation), so
-- one plain field read answers it without walking every husbandry. A husbandry's station is the one
-- whose owningPlaceable carries spec_husbandry -- the same storage attached to a plain silo's station
-- keeps its export, because no silo double-reports it.
--
-- Seen in a game (singleplayer, 2026-08-24): build a barn beside a standing heap and the heap leaves
-- this export, its liters appearing on the pen's manure bar instead. Recapturing the singleplayer
-- fixtures at v3 changed the version string and nothing else, so a heap with no barn in range keeps
-- its export -- both directions of the rule, in the game rather than only in the specs.
--
-- Wired on a multiplayer CLIENT too: Placeable:postReadStream calls finalizePlacement(), which
-- raises onFinalizePlacement there exactly as on the host (untested with a heap; a client reporting
-- a barn's own internal store is covered by examples/json/storage/mp_modded.json).
---@param storages table[] the placeable's Storage-shaped objects
---@return boolean
local function feedsHusbandry(storages)
  for _, storage in ipairs(storages) do
    local stations = type(storage) == "table" and storage.unloadingStations or nil
    if type(stations) == "table" then
      for station in pairs(stations) do
        local placeable = type(station) == "table" and station.owningPlaceable or nil
        if type(placeable) == "table" and placeable.spec_husbandry ~= nil then
          return true
        end
      end
    end
  end
  return false
end

-- ROUND or SQUARE, off the bale's own XML through the bale manager's registry. A filename the manager
-- does not know answers false, which is also the answer for "square" — a modded bale it has never
-- heard of lands in the square bucket rather than one of its own.
---@param bale table
---@return string
local function baleShape(bale)
  if g_baleManager == nil then
    return "SQUARE"
  end
  local ok, isRound = pcall(g_baleManager.getBaleInfoByXMLFilename, g_baleManager, bale.xmlFilename, true)
  return (ok and isRound == true) and "ROUND" or "SQUARE"
end

-- What one object-storage group holds, per STORED OBJECT: its fill type, its liters, and WHAT IT IS —
-- `kind` (BALE / PALLET / BIGBAG, the three things an object storage can hold) plus `shape` where a
-- bale wants to say more. Same vocabulary as the loose rows, so a reader can union the two lists
-- without parsing a localized title. A group is objects the game considers
-- identical, fill level included (getIsIdentical), so one object's figures describe them all and the
-- group total is level * count.
--
-- Read the way the game reads it, which differs by what the abstract object is standing in for: a
-- *fermenting* bale is kept as a live hidden Bale (AbstractBaleObject.addToStorage) and answers
-- getRealObject(); everything else — every other bale, every pallet — was deleted on the way in and
-- survives only as a flat attributes table, `baleAttributes` or `palletAttributes`. WHICH of those two
-- it is is the only honest bale/pallet discriminator here: the dialog text says "Rundballen" or
-- "Bigbag" in the player's language, which is a label, not a type. Neither table exposes a getter, so
-- both are read directly; both are filled from the update stream, so a multiplayer client has them.
---@return StoredObjectDetail | nil nil when there is no nameable fill type on the object
local function storedObjectDetail(object)
  local fillTypeIndex, level, shape, kind
  local okReal, real = pcall(object.getRealObject, object)
  if okReal and type(real) == "table" then
    local okType, t = pcall(real.getFillType, real)
    local okLevel, l = pcall(real.getFillLevel, real)
    fillTypeIndex, level = okType and t or nil, okLevel and l or nil
    kind, shape = "BALE", baleShape(real)
  else
    local bale, pallet = object.baleAttributes, object.palletAttributes
    if type(bale) == "table" then
      fillTypeIndex, level = bale.fillType, bale.fillLevel
      kind, shape = "BALE", baleShape(bale)
    elseif type(pallet) == "table" then
      fillTypeIndex, level = pallet.fillType, pallet.fillLevel
      -- a big bag IS a pallet vehicle, but the game names the two apart everywhere it lists them, and
      -- so does the stock overview's type column -- so they are two kinds here, not a kind and a flag
      kind = pallet.isBigBag == true and "BIGBAG" or "PALLET"
    end
  end
  if kind == nil then
    return nil
  end
  -- What it IS and what is IN it are two questions, and the second can fail on its own: a crate or a
  -- vegetable pallet has no fill type at all (FillType.UNKNOWN), and the game's own dialog text drops
  -- the liter figure for exactly those. Such a row still says PALLET -- it is stock the farm owns,
  -- just stock nothing can price.
  local detail = { kind = kind, shape = shape }
  if fillTypeIndex ~= nil and fillTypeIndex ~= FILL_TYPE_UNKNOWN then
    local fillType = g_fillTypeManager:getFillTypeByIndex(fillTypeIndex)
    if fillType ~= nil then
      detail.type = fillType.name
      detail.level = math.floor(num(level))
    end
  end
  return detail
end

-- An object storage (PlaceableObjectStorage — bales/pallets). Count-based, not liters:
-- spec.numStoredObjects / spec.capacity total, with spec.objectInfos grouping identical stored
-- objects. The per-group title comes from the object's getDialogText() and the rest from
-- storedObjectDetail; a group whose title does not resolve is skipped rather than shown blank. The
-- whole breakdown reaches a multiplayer client live -- the spec re-sends its full write stream
-- whenever setObjectStorageObjectInfosDirty() raises its dirty flag, which every store and unload
-- does. Shown even when empty (capacity > 0) like an empty silo.
---@return StandaloneStorageModel | nil
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
        -- `kind`/`shape` say what the objects are, `type`/`level` what is in them -- and the second
        -- pair goes missing on its own for a crate or a vegetable pallet, which holds no fill type.
        -- `level` is ONE object's liters, so the group holds level * count.
        local detail = storedObjectDetail(first)
        objects[#objects + 1] = {
          index = index,
          title = title,
          count = count,
          type = detail ~= nil and detail.type or nil,
          level = detail ~= nil and detail.level or nil,
          shape = detail ~= nil and detail.shape or nil,
          kind = detail ~= nil and detail.kind or nil,
        }
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

-- One silage bunker. Its fill level, state, compacted and fermenting percentages all ride the silo's
-- own network streams (BunkerSilo:readStream / readUpdateStream), so a multiplayer client reads the
-- same figures as the host.
--
-- WHICH FILL TYPE: the game's own answer, off BunkerSilo:update — the input type while filling, the
-- output type once the silo is closed. Never the type the density map actually holds, which between
-- covering and opening is the TARP "fermenting" type; the game says silage there and so do we.
--
-- There is no capacity to report: a bunker's ceiling is the shape of its walls, and the game itself
-- only ever prints a fill level for one.
---@param silo table  a base-game BunkerSilo
---@param id   string
---@param name string
---@return BunkerSiloModel | nil nil when the silo is in a state this build does not know
local function collectBunkerSilo(silo, id, name)
  local state = BUNKER_STATES[silo.state]
  if state == nil then
    return nil
  end
  local fillTypeIndex = silo.outputFillType
  if state == "FILL" then
    fillTypeIndex = silo.inputFillType
  end
  local fillType = nil
  if fillTypeIndex ~= nil and fillTypeIndex ~= FILL_TYPE_UNKNOWN then
    fillType = g_fillTypeManager:getFillTypeByIndex(fillTypeIndex)
  end
  local row = {
    id = id,
    name = name,
    state = state,
    type = fillType ~= nil and fillType.name or nil,
    title = fillType ~= nil and fillType.title or nil,
    level = math.floor(num(silo.fillLevel)),
  }
  -- Each percentage only means something in the states that maintain it — the game prints one or the
  -- other, never both — so the other is left out rather than reported as a stale zero.
  if state == "FILL" then
    row.compacted = math.floor(num(silo.compactedPercent))
  elseif state == "CLOSED" or state == "FERMENTED" then
    -- fermentingPercent is a 0..1 fraction, and the game rounds it up for display; match that.
    row.fermenting = math.min(100, math.ceil(num(silo.fermentingPercent) * 100))
  end
  return row
end

-- The owned silage bunkers. Walked over the placeable list, the way the storages above are, rather
-- than over the placeableSystem:getBunkerSilos() registry the engine also keeps: that one is filled
-- from onFinalizePlacement, so it holds whatever was *placed*, while the placeable list is what the
-- rest of this channel already reads and is the one thing here that has been seen to be complete.
--
-- A placeable carries either ONE silo (PlaceableBunkerSilo) or a row of bays (PlaceableMultiBunkerSilo,
-- one silo per configured bay). A bay gets the bay number appended to both id and name, because the
-- placeable's own name is the same for every bay of it and two bays fill independently.
---@param farmId number
---@return BunkerSiloModel[]
local function collectBunkerSilos(farmId)
  local out = {}
  local system = g_currentMission.placeableSystem
  local placeables = system ~= nil and system.placeables or nil
  if type(placeables) ~= "table" then
    return out
  end

  for _, placeable in ipairs(placeables) do
    local silos
    if placeable.spec_bunkerSilo ~= nil then
      silos = { placeable.spec_bunkerSilo.bunkerSilo }
    elseif placeable.spec_multiBunkerSilo ~= nil then
      silos = placeable.spec_multiBunkerSilo.bunkerSilos
    end
    if type(silos) == "table" and #silos > 0 then
      local okOwner, owner = pcall(placeable.getOwnerFarmId, placeable)
      if okOwner and owner == farmId then
        local name = placeableName(placeable, "Bunker silo")
        local id = VDT.ProductionExporter.placeableId(placeable, "bunker" .. (#out + 1))
        local isBay = #silos > 1
        for index, silo in ipairs(silos) do
          if type(silo) == "table" then
            local row =
              collectBunkerSilo(silo, isBay and (id .. "_" .. index) or id, isBay and (name .. " " .. index) or name)
            if row ~= nil then
              out[#out + 1] = row
            end
          end
        end
      end
    end
  end
  return out
end

-- Find or start group `key`, seeded from `seed`. Every group carries a running count and liter total,
-- so a caller only ever adds to them.
---@return LooseStockModel
local function groupRow(rows, seen, key, seed)
  local row = seen[key]
  if row == nil then
    row = seed
    row.count = 0
    row.level = 0
    seen[key] = row
    rows[#rows + 1] = row
  end
  return row
end

-- Floor the accumulated liters and sort by the whole group key -- fill type, then kind, then shape --
-- so the file is byte-stable between writes that saw the same stock (Lua table order is not).
---@param rows LooseStockModel[]
---@return LooseStockModel[]
local function finishGroups(rows)
  for _, row in ipairs(rows) do
    row.level = math.floor(row.level)
  end
  table.sort(rows, function(a, b)
    if a.type ~= b.type then
      return a.type < b.type
    end
    if a.kind ~= b.kind then
      return a.kind < b.kind
    end
    return (a.shape or "") < (b.shape or "")
  end)
  return rows
end

-- Bales lying around the farm: one row per (fill type, ROUND/SQUARE), each saying `kind = "BALE"` so
-- it reads the same way a stored one does, with how many there are and how many liters they hold
-- between them.
--
-- WHERE THEY LIVE: g_currentMission.itemSystem, the list the savegame's items are written from.
-- Bale:readStream adds the bale to it, so a multiplayer client walks the same bales as the host.
-- Bales put away in an object storage are NOT in it — storing one deletes the object and keeps an
-- abstract stand-in — so nothing here is also counted under `storages`. Bales riding a trailer are,
-- which is right: they are stock, they are just in transit.
--
-- Mission bales fall out for free: a contract's bales are owned by AccessHandler.NOBODY (BaleMission),
-- so the own-farm gate never lets them in. The bale's own isMissionBale flag would NOT have done it —
-- it is not streamed, so a client sees false on every bale.
--
-- A FERMENTING bale still reports what it went in as — grass, not silage; the swap happens at
-- Bale:onFermentationEnd — so `fermenting` says how many of the group are on their way to becoming
-- something else. Without it a stock overview would quietly price a wrapped grass bale as grass.
---@param farmId number
---@return LooseStockModel[]
local function collectLooseBales(farmId)
  local rows, seen = {}, {}
  local system = g_currentMission.itemSystem
  local items = system ~= nil and system.sortedItemsToSave or nil
  if type(items) ~= "table" or Bale == nil then
    return rows
  end

  for _, entry in ipairs(items) do
    local bale = type(entry) == "table" and entry.item or nil
    -- isa() rather than the registered class name: every subclass is stock too (a packed bale, a bale
    -- in a tube), and a modded one registers under its own name. ForestryLog shares this list and is
    -- not a Bale, which is exactly what the check is here to say.
    local isBale = false
    if type(bale) == "table" and type(bale.isa) == "function" then
      local ok, result = pcall(bale.isa, bale, Bale)
      isBale = ok and result == true
    end
    -- the farm off the field rather than getOwnerFarmId(): Object keeps it there and streams it, and
    -- this loop runs once per bale on the map every 2 s, where a method call per bale is not free
    if isBale and bale.ownerFarmId == farmId then
      local fillType = g_fillTypeManager:getFillTypeByIndex(bale.fillType)
      if fillType ~= nil and bale.fillType ~= FILL_TYPE_UNKNOWN then
        local shape = baleShape(bale)
        local row = groupRow(rows, seen, fillType.name .. "|" .. shape, {
          type = fillType.name,
          title = fillType.title,
          kind = "BALE",
          shape = shape,
        })
        row.count = row.count + 1
        row.level = row.level + num(bale.fillLevel)
        if bale.isFermenting == true then
          row.fermenting = (row.fermenting or 0) + 1
        end
      end
    end
  end
  return finishGroups(rows)
end

-- Pallets standing around the farm: one row per (fill type, PALLET/BIGBAG).
--
-- A pallet is a Vehicle carrying the Pallet specialization (`isPallet`), so these come off the vehicle
-- list — the same list the fleet channel walks, which never reports one because the game's own
-- overview does not (Pallet:getShowInVehiclesOverview returns false). The two channels cannot overlap.
-- A pallet put away in an object storage is deleted like a bale, so again nothing is counted twice.
--
-- WHICH FILL UNIT: the pallet spec's own fillUnitIndex — the game's definition of what a pallet is a
-- pallet OF, and the one AbstractPalletObject reads when it stores one. A pallet whose unit holds no
-- nameable fill type is skipped: there is no resource on it to report.
---@param farmId number
---@return LooseStockModel[]
local function collectLoosePallets(farmId)
  local rows, seen = {}, {}
  local system = g_currentMission.vehicleSystem
  local vehicles = system ~= nil and system.vehicles or nil
  if type(vehicles) ~= "table" then
    return rows
  end

  for _, vehicle in ipairs(vehicles) do
    if type(vehicle) == "table" and vehicle.isPallet == true and type(vehicle.spec_pallet) == "table" then
      local okOwner, owner = pcall(vehicle.getOwnerFarmId, vehicle)
      if okOwner and owner == farmId then
        local unit = vehicle.spec_pallet.fillUnitIndex or 1
        local okType, fillTypeIndex = pcall(vehicle.getFillUnitFillType, vehicle, unit)
        local fillType = nil
        if okType and fillTypeIndex ~= nil and fillTypeIndex ~= FILL_TYPE_UNKNOWN then
          fillType = g_fillTypeManager:getFillTypeByIndex(fillTypeIndex)
        end
        if fillType ~= nil then
          local okLevel, level = pcall(vehicle.getFillUnitFillLevel, vehicle, unit)
          -- A big bag is a pallet with the BigBag specialization; the game names the two apart in its
          -- own storage dialog and a stock list wants the same, since one is refillable and one is not.
          -- The stored side reaches the same two words through palletAttributes.isBigBag.
          local kind = vehicle.spec_bigBag ~= nil and "BIGBAG" or "PALLET"
          local row = groupRow(rows, seen, fillType.name .. "|" .. kind, {
            type = fillType.name,
            title = fillType.title,
            kind = kind,
          })
          row.count = row.count + 1
          row.level = row.level + (okLevel and num(level) or 0)
        end
      end
    end
  end
  return finishGroups(rows)
end

-- Standalone storages: owned silo, manure-heap and object-storage placeables. Production points are
-- excluded for free (they carry spec_productionPoint, none of the three specs below, and their
-- storage is reported by the production channel), and a placeable a husbandry feeds off is excluded
-- deliberately (feedsHusbandry) -- WHOLE, not row by row, because an extension only ever holds what
-- the barn it extends can put in it. Silo extensions (spec_siloExtension) stay out of scope for v1.
-- Walked over the placeable list because a Storage/object-storage has no reliable back-reference to
-- its placeable.
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
      local fillStorages
      if placeable.spec_silo ~= nil and type(placeable.spec_silo.storages) == "table" then
        fillStorages = placeable.spec_silo.storages
      elseif placeable.spec_manureHeap ~= nil and type(placeable.spec_manureHeap.manureHeap) == "table" then
        fillStorages = { placeable.spec_manureHeap.manureHeap }
      end
      if fillStorages ~= nil then
        if not feedsHusbandry(fillStorages) then
          entry = collectFillStorage(placeable, fillStorages, farmId, #out + 1)
        end
      elseif placeable.spec_objectStorage ~= nil then
        -- no husbandry check here: an object storage is not a Storage, so it can never extend a barn
        entry = collectObjectStorage(placeable, farmId, #out + 1)
      end
      if entry ~= nil then
        out[#out + 1] = entry
      end
    end
  end
  return out
end

-- Channel is available once the placeable system exists (map loaded) and fill types are known — the
-- item and vehicle systems the loose blocks read are guarded where they are used, so a build without
-- one of them still writes the rest. While spectating, it still writes an empty file so the app shows
-- the (owned-nothing) empty state rather than freezing.
function VDT.StorageExporter.isAvailable()
  return g_currentMission ~= nil and g_currentMission.placeableSystem ~= nil and g_fillTypeManager ~= nil
end

--- Build the storage model, or nil when the placeable system isn't up yet (skips the write).
---@return StorageModel | nil
function VDT.StorageExporter.collect()
  if not VDT.StorageExporter.isAvailable() then
    return nil
  end
  local farmId = VDT.Farm.ownFarmId()
  if farmId == nil then
    -- spectator / no owned farm: keep the channel present but empty (no storages)
    return { version = tostring(VDT.StorageExporter.VERSION) }
  end

  local storages = collectStorages(farmId)
  local bunkerSilos = collectBunkerSilos(farmId)
  -- The two loose blocks walk the item and vehicle lists rather than the placeable list; either
  -- system being absent yields an empty table, which is omitted below like any other empty one.
  local looseBales = collectLooseBales(farmId)
  local loosePallets = collectLoosePallets(farmId)

  return {
    version = tostring(VDT.StorageExporter.VERSION),
    -- omit empty arrays: the Json encoder emits {} for an empty table, so a nil keeps the key absent
    -- and the Kotlin model falls back to emptyList() (see MapExporter / TaskList.lua).
    storages = #storages > 0 and storages or nil,
    bunkerSilos = #bunkerSilos > 0 and bunkerSilos or nil,
    looseBales = #looseBales > 0 and looseBales or nil,
    loosePallets = #loosePallets > 0 and loosePallets or nil,
  }
end

-- Self-register the channel (see ExportChannels). Interval-driven: the registry owns the cadence.
-- markDirty on an unavailable channel stays pending (selectDirty skips it without clearing), so
-- ticking before the placeable system is up costs nothing.
VDT.ExportChannels.register({
  name = VDT.StorageExporter.CHANNEL,
  fileName = VDT.StorageExporter.FILE_NAME,
  isAvailable = VDT.StorageExporter.isAvailable,
  collect = VDT.StorageExporter.collect,
  intervalMs = VDT.StorageExporter.INTERVAL_MS,
  -- Only this farm's silos, bunkers, bales and pallets are exported (ownFarmId).
  farmScoped = true,
})
