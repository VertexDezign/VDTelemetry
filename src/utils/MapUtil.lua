MapUtil = {}

---@class PDA
---@field filename string
---@field width number
---@field height number

---@return PDA | nil The filename to the pda or nil if not found.
function MapUtil.getMapPDAFile()
  for _, item in pairs(g_mapManager.maps) do
    if item.id == g_currentMission.missionInfo.map.id then
      local mapXMLFilename = item.mapXMLFilename

      if mapXMLFilename:find("$data") then
        mapXMLFilename = getAppBasePath() .. mapXMLFilename:sub(2)
      else
        mapXMLFilename = item.baseDirectory .. mapXMLFilename
      end

      local mapXML = XMLFile.loadIfExists("map", mapXMLFilename)

      local pdaMapFile = mapXML:getString("map#imageFilename")
      local width = mapXML:getInt("map#width")
      local height = mapXML:getInt("map#height")

      if pdaMapFile:find("$data") then
        pdaMapFile = getAppBasePath() .. pdaMapFile:sub(2)
      else
        pdaMapFile = item.baseDirectory .. pdaMapFile
      end

      if pdaMapFile:find(".png") then
        pdaMapFile = pdaMapFile:sub(0, pdaMapFile:len() - 4) .. ".dds"
      end
      return {
        filename = pdaMapFile,
        width = width,
        height = height
      }
    end
  end

  return nil
end