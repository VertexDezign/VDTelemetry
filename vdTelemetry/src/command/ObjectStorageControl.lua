-- Executes the object-storage unload command (app -> mod): spawn N stored objects (bales/pallets) of
-- a chosen type out of an owned PlaceableObjectStorage, exactly as the in-game trigger dialog does --
-- by sending PlaceableObjectStorageUnloadEvent to the server, which runs
-- removeAbstractObjectsFromStorage (server authority spawns the objects at the storage's spawn area).
-- The event path works in singleplayer (loopback) and multiplayer alike. Touches no vehicle, so the
-- handler declares requiresVehicle = false.
--
-- The storage is addressed by the same exported `id` the read side emits (resolved with
-- ProductionExporter.placeableId), own-farm ownership enforced via ProductionExporter.ownFarmId.
-- The group is addressed by its objectInfoIndex, but that shifts as groups deplete, so the app also
-- sends the selected title and we re-resolve against the CURRENT objectInfos (title first, index as a
-- fallback for clients where titles aren't available). The amount is clamped to the live limits:
-- min(requested, that group's stored count, maxUnloadAmount) -- the server itself refuses more than
-- the stored count, and maxUnloadAmount is the per-action cap the game's dialog uses.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.ObjectStorageControl = {}

local function num(v)
  return type(v) == "number" and v or 0
end

-- The dialog text of an objectInfo group, or nil when the objects aren't present (an MP client holds
-- only counts, not the abstract objects -- see the read side).
local function groupTitle(info)
  local first = type(info.objects) == "table" and info.objects[1] or nil
  if first == nil then
    return nil
  end
  local ok, title = pcall(first.getDialogText, first)
  if ok and type(title) == "string" and title ~= "" then
    return title
  end
  return nil
end

-- Resolve the object-storage placeable by its exported id, enforcing own-farm ownership.
local function resolveStorage(storageId, debugger, label)
  if storageId == nil then
    debugger:warn("%s: missing storageId", label)
    return nil
  end
  local system = g_currentMission ~= nil and g_currentMission.placeableSystem or nil
  local placeables = system ~= nil and system.placeables or nil
  if type(placeables) ~= "table" then
    debugger:warn("%s: placeable system not available", label)
    return nil
  end
  local farmId = VDT.ProductionExporter.ownFarmId()
  if farmId == nil then
    debugger:warn("%s: no local farm resolved, refusing to unload %s", label, tostring(storageId))
    return nil
  end
  for index, placeable in ipairs(placeables) do
    if placeable.spec_objectStorage ~= nil then
      local id = VDT.ProductionExporter.placeableId(placeable, "storage" .. index)
      if id == storageId then
        local okOwner, owner = pcall(placeable.getOwnerFarmId, placeable)
        if not okOwner or owner ~= farmId then
          debugger:warn("%s: object storage %s is not owned by the local farm -- ignoring", label, tostring(storageId))
          return nil
        end
        return placeable
      end
    end
  end
  debugger:warn("%s: no object storage with id %s", label, tostring(storageId))
  return nil
end

-- Re-resolve the addressed group against the CURRENT objectInfos: keep the sent index when it still
-- carries the sent title (or when titles aren't available), else search by title, else fall back to
-- the raw index. Returns nil when nothing matches.
local function resolveObjectInfoIndex(infos, index, title)
  local atIndex = infos[index]
  if atIndex ~= nil then
    local t = groupTitle(atIndex)
    if t == nil or t == title then
      return index
    end
  end
  if title ~= nil then
    for i, info in ipairs(infos) do
      if groupTitle(info) == title then
        return i
      end
    end
  end
  if atIndex ~= nil then
    return index
  end
  return nil
end

VDT.CommandRegistry.register("unloadObjectStorage", {
  requiresVehicle = false,
  parse = function(xml, key)
    return {
      storageId = xml:getString(key .. "#storageId"),
      index = xml:getInt(key .. "#index"),
      title = xml:getString(key .. "#title"),
      amount = xml:getInt(key .. "#amount"),
    }
  end,
  execute = function(_, params, debugger)
    local placeable = resolveStorage(params.storageId, debugger, "unloadObjectStorage")
    if placeable == nil then
      return
    end
    -- Refresh the grouping like the trigger dialog does, so indices and counts are current.
    pcall(placeable.updateDirtyObjectStorageObjectInfos, placeable)
    local spec = placeable.spec_objectStorage
    local infos = spec ~= nil and spec.objectInfos or nil
    if type(infos) ~= "table" then
      debugger:warn("unloadObjectStorage: %s has no object infos", tostring(params.storageId))
      return
    end
    local idx = resolveObjectInfoIndex(infos, params.index, params.title)
    if idx == nil or infos[idx] == nil then
      debugger:warn(
        "unloadObjectStorage: %s has no group %s / '%s'",
        tostring(params.storageId),
        tostring(params.index),
        tostring(params.title)
      )
      return
    end
    local available = math.floor(num(infos[idx].numObjects))
    local maxUnload = math.floor(num(spec.maxUnloadAmount))
    if maxUnload <= 0 then
      maxUnload = 25
    end
    local amount = math.min(math.floor(num(params.amount)), available, maxUnload)
    if amount < 1 then
      debugger:debug("unloadObjectStorage: %s group %d has nothing to unload", tostring(params.storageId), idx)
      return
    end
    if PlaceableObjectStorageUnloadEvent == nil then
      debugger:warn("unloadObjectStorage: PlaceableObjectStorageUnloadEvent unavailable")
      return
    end
    -- Same client -> server send the trigger dialog uses (loopback in singleplayer); the server then
    -- runs removeAbstractObjectsFromStorage. Contained so a send failure can't take down the poll.
    local okConn, conn = pcall(g_client.getServerConnection, g_client)
    if not okConn or conn == nil then
      debugger:warn("unloadObjectStorage: no server connection")
      return
    end
    local okSend = pcall(conn.sendEvent, conn, PlaceableObjectStorageUnloadEvent.new(placeable, idx, amount))
    if not okSend then
      debugger:warn("unloadObjectStorage: failed to send unload event for %s", tostring(params.storageId))
      return
    end
    debugger:debug("unloadObjectStorage %s[%d] x%d", tostring(params.storageId), idx, amount)
  end,
})
