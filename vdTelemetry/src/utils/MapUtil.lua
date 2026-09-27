MapUtil = {}

---@class PDA
---@field filename string

---Resolves the map's PDA overview image to a path VDTerminal's server can open.
---
---Logged step by step, because this is the one value in the whole export that nobody can check from
---inside the game: it is a path handed across a file to another process, and every way it can go
---wrong -- a map whose `map.xml` names a `.png` that ships as `.dds`, a zipped map whose folder
---exists only in the engine's eyes, a map the mod manager loaded from somewhere this install has
---never heard of -- ends as an empty square in the dashboard with nothing anywhere to say why. These
---lines are what a bug report gets built from, so they name the map and the resolved filename, and
---say whether the engine can see a file there -- under that name or as the `.dds` beside it.
---@param debugger GrisuDebug
---@return PDA | nil The filename to the pda or nil if not found.
function MapUtil.getMapPDAFile(debugger)
  local mapInfo = g_currentMission.missionInfo.map
  local mapId = mapInfo ~= nil and mapInfo.id or nil
  if mapId == nil then
    debugger:warn("PDA: the mission names no map, so its overview image cannot be resolved")
    return nil
  end

  for _, item in pairs(g_mapManager.maps) do
    if item.id == mapId then
      debugger:debug(
        "PDA: map '%s' -> mapXmlFilename '%s', baseDirectory '%s'",
        tostring(mapId),
        tostring(item.mapXMLFilename),
        tostring(item.baseDirectory)
      )
      local mapXMLFilename = item.mapXMLFilename

      -- `$data/...` is the base game's own content, relative to the install; anything else is a mod
      -- map, relative to wherever the mod was loaded from. Plain find (`$` is a pattern anchor).
      if mapXMLFilename:find("$data", 1, true) then
        mapXMLFilename = getAppBasePath() .. mapXMLFilename:sub(2)
      else
        mapXMLFilename = item.baseDirectory .. mapXMLFilename
      end

      local mapXML = XMLFile.loadIfExists("map", mapXMLFilename)
      if mapXML == nil then
        debugger:warn("PDA: map.xml could not be read at '%s' -- no overview image", mapXMLFilename)
        return nil
      end

      local declared = mapXML:getString("map#imageFilename")
      mapXML:delete()
      if declared == nil or declared == "" then
        debugger:warn("PDA: '%s' declares no map#imageFilename -- no overview image", mapXMLFilename)
        return nil
      end

      local pdaMapFile = declared
      if pdaMapFile:find("$data", 1, true) then
        pdaMapFile = getAppBasePath() .. pdaMapFile:sub(2)
      else
        pdaMapFile = item.baseDirectory .. pdaMapFile
      end

      -- Exported as map.xml declares it. A released map names a PNG and ships only the DDS the
      -- engine loads in its place, while a map still in development may have just the PNG, so which
      -- file is really there is VDTerminal's to find out (AssetResolver.lookupTexture) rather than
      -- ours to assume. The log looks for both spellings for the same reason.
      local shipped = pdaMapFile
      if pdaMapFile:sub(-4):lower() == ".png" then
        -- Matched on the extension itself rather than with `find(".png")`, whose `.` is a pattern
        -- wildcard and so also matches any folder called e.g. `xpng` along the way.
        shipped = pdaMapFile:sub(1, -5) .. ".dds"
      end

      -- A `false` here is worth saying out loud but is not proof of anything: the path may still be
      -- one the terminal can reach inside the mod's zip. It is the first thing to look at when the
      -- dashboard's map stays empty.
      local exists = fileExists(pdaMapFile) or fileExists(shipped)
      debugger:info("PDA overview: '%s' (fileExists=%s, as itself or as .dds)", pdaMapFile, tostring(exists))
      if not exists then
        debugger:warn(
          "PDA: the engine reports no file at '%s' or its .dds -- VDTerminal may not find it either",
          pdaMapFile
        )
      end

      return { filename = pdaMapFile }
    end
  end

  debugger:warn("PDA: no installed map has id '%s' -- no overview image", tostring(mapId))
  return nil
end
