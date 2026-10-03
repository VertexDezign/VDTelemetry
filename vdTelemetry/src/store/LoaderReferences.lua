-- Store: the player's "this is level" reference per loader tool MODEL (issue #169). The first data
-- VDTelemetry persists of its own -- every other write path drives a mod's or the game's own state.
-- Namespaced under VDT.* (see aspects/TurnOn.lua).
--
-- Why a reference exists at all: VDT.LoaderTool reads the tool's root node, and no node on a tool is
-- guaranteed to lie parallel to the shovel floor or the fork tines. The player sets the tool where
-- they want zero and the app records the raw pitch and distance it read there; the panel subtracts.
--
-- Why it is kept HERE, in modSettings/<modName>/loaderReferences.xml, and not in the savegame:
--   * it describes the tool's geometry, so every copy of that shovel in every savegame needs the
--     same offset -- per savegame it would have to be set again on every new save;
--   * the savegame belongs to the host, and on a multiplayer client it cannot be written without an
--     event of our own -- while this is a display preference of the player looking at the screen;
--   * writing into the savegame would need a specialization injected into every vehicle type, and the
--     mod reads vehicles, it does not extend them.
-- Client-local, then, like the command channel that writes it: on a multiplayer client it is that
-- player's zero and nobody else's.
--
-- Keyed by the tool's config file with the mods / DLC directory removed (Utils.removeModDirectory):
-- `FS25_SomeMod/xml/shovel.xml` for a mod, `pdlc_.../...` for a DLC, `data/vehicles/...` for the base
-- game. That is the same on every machine and survives the mods folder moving. (Tool Inclination
-- Helper keys a mod tool by its file NAME alone, which merges two tools of one mod that share it.)
--
-- Read back with the engine's XMLFile, not JSON: the sandbox's io.open is write-only (see
-- CommandChannel.lua). Loaded once at start, written whole on every change -- a reference is set by a
-- tap, not per frame, and a crash between setting it and the next savegame save must not lose it.

VDT = VDT or {}
VDT.LoaderReferences = {}

VDT.LoaderReferences.FILE_NAME = "loaderReferences.xml"
VDT.LoaderReferences.XML_VERSION = 1

local ROOT = "loaderReferences"

local filePath = nil
---@type table<string, LoaderReferenceModel>
local references = {}

---The store key for `object`'s model, or nil when it has no config file to name it by.
---@param object table a vehicle or implement
---@return string|nil
function VDT.LoaderReferences.keyOf(object)
  local file = object.configFileName
  if type(file) ~= "string" or file == "" then
    return nil
  end
  if Utils ~= nil and type(Utils.removeModDirectory) == "function" then
    file = Utils.removeModDirectory(file)
  end
  return file
end

---Load the store from `path`. A missing file is an empty store; an unreadable or foreign one is
---ignored and overwritten by the next set.
---@param path string
---@param debugger GrisuDebug|nil
function VDT.LoaderReferences.load(path, debugger)
  filePath = path
  references = {}
  local xml = XMLFile.loadIfExists("vdtLoaderReferences", path)
  if xml == nil then
    return
  end
  local version = xml:getInt(ROOT .. "#version", 0)
  if version ~= VDT.LoaderReferences.XML_VERSION then
    if debugger ~= nil then
      debugger:warn("loaderReferences: unknown version %d, starting empty", version)
    end
    xml:delete()
    return
  end
  xml:iterate(ROOT .. ".tool", function(_, key)
    local id = xml:getString(key .. "#key")
    local pitch = xml:getFloat(key .. "#pitch")
    if id ~= nil and pitch ~= nil then
      references[id] = { pitch = pitch, distance = xml:getFloat(key .. "#distance") }
    end
  end)
  xml:delete()
end

local function save(debugger)
  if filePath == nil then
    return
  end
  local xml = XMLFile.create("vdtLoaderReferences", filePath, ROOT)
  if xml == nil then
    if debugger ~= nil then
      debugger:error("loaderReferences: could not create %s", tostring(filePath))
    end
    return
  end
  xml:setInt(ROOT .. "#version", VDT.LoaderReferences.XML_VERSION)
  -- Sorted, so the file does not reshuffle on every save.
  local ids = {}
  for id in pairs(references) do
    table.insert(ids, id)
  end
  table.sort(ids)
  for i, id in ipairs(ids) do
    local key = string.format("%s.tool(%d)", ROOT, i - 1)
    local ref = references[id]
    xml:setString(key .. "#key", id)
    xml:setFloat(key .. "#pitch", ref.pitch)
    if ref.distance ~= nil then
      xml:setFloat(key .. "#distance", ref.distance)
    end
  end
  xml:save()
  xml:delete()
end

---@param key string|nil
---@return LoaderReferenceModel|nil
function VDT.LoaderReferences.get(key)
  if key == nil then
    return nil
  end
  return references[key]
end

---Record `reference` for `key` and write the store.
---@param key string
---@param reference LoaderReferenceModel
---@param debugger GrisuDebug|nil
function VDT.LoaderReferences.set(key, reference, debugger)
  references[key] = { pitch = reference.pitch, distance = reference.distance }
  save(debugger)
end

---Forget `key`'s reference and write the store. A key with none is left alone.
---@param key string
---@param debugger GrisuDebug|nil
function VDT.LoaderReferences.clear(key, debugger)
  if references[key] == nil then
    return
  end
  references[key] = nil
  save(debugger)
end
