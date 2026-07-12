-- Unit tests for the map export channel (src/collect/MapExporter.lua): the pure helpers plus the
-- collect() path against stubbed game globals (hotspots, field manager, getWorldTranslation).
-- Whether the real engine objects still look like those stubs is what the in-game smoke test covers.
--
-- Run with `busted` from the vdTelemetry/ directory. MapExporter.lua self-registers an export
-- channel at load, so we load ExportChannels first (only if not already loaded, so we don't reset a
-- registry another spec populated).

if VDT == nil or VDT.ExportChannels == nil then
  dofile("src/export/ExportChannels.lua")
end
dofile("src/collect/MapExporter.lua")

describe("MapExporter.normalizeCoord", function()
  it("maps the world center to 0.5 (world origin is the terrain center)", function()
    assert.are.equal(0.5, VDT.MapExporter.normalizeCoord(0, 2048))
  end)

  it("maps the terrain corners to 0 and 1", function()
    assert.are.equal(0, VDT.MapExporter.normalizeCoord(-1024, 2048))
    assert.are.equal(1, VDT.MapExporter.normalizeCoord(1024, 2048))
  end)

  it("clamps positions outside the terrain", function()
    assert.are.equal(0, VDT.MapExporter.normalizeCoord(-5000, 2048))
    assert.are.equal(1, VDT.MapExporter.normalizeCoord(5000, 2048))
  end)

  it("rounds to 5 decimals", function()
    -- (100 + 1024) / 2048 = 0.548828125 -> 0.54883
    assert.are.equal(0.54883, VDT.MapExporter.normalizeCoord(100, 2048))
  end)
end)

describe("MapExporter.roundCoord", function()
  it("keeps 5 decimals", function()
    assert.are.equal(0.12346, VDT.MapExporter.roundCoord(0.123456789))
  end)

  it("leaves round values untouched", function()
    assert.are.equal(0.5, VDT.MapExporter.roundCoord(0.5))
    assert.are.equal(0, VDT.MapExporter.roundCoord(0))
    assert.are.equal(1, VDT.MapExporter.roundCoord(1))
  end)
end)

describe("MapExporter.decimate", function()
  it("keeps the first point and drops points closer than minDist to the last kept one", function()
    -- 0, 2, 4, 6, 8 on the x axis with 5 m spacing required -> keep 0, drop 2/4 (dist 2/4), keep 6, drop 8
    local points = { 0, 0, 2, 0, 4, 0, 6, 0, 8, 0 }
    assert.are.same({ 0, 0, 6, 0 }, VDT.MapExporter.decimate(points, 5, 256))
  end)

  it("keeps everything when spacing is already wide enough", function()
    local points = { 0, 0, 10, 0, 20, 0 }
    assert.are.same(points, VDT.MapExporter.decimate(points, 5, 256))
  end)

  it("measures distance in 2D, not per axis", function()
    -- (3,4) is exactly 5 m from (0,0) -> kept at minDist 5
    assert.are.same({ 0, 0, 3, 4 }, VDT.MapExporter.decimate({ 0, 0, 3, 4 }, 5, 256))
    -- (3,3) is ~4.24 m -> dropped
    assert.are.same({ 0, 0 }, VDT.MapExporter.decimate({ 0, 0, 3, 3 }, 5, 256))
  end)

  it("caps the point count by keeping every Nth point", function()
    -- 8 points, 10 m apart, cap 4 -> step ceil(8/4)=2 -> points 1,3,5,7
    local points = {}
    for i = 0, 7 do
      points[#points + 1] = i * 10
      points[#points + 1] = 0
    end
    assert.are.same({ 0, 0, 20, 0, 40, 0, 60, 0 }, VDT.MapExporter.decimate(points, 5, 4))
  end)

  it("handles an empty polygon", function()
    assert.are.same({}, VDT.MapExporter.decimate({}, 5, 256))
  end)
end)

describe("MapExporter.linearToSrgbHex", function()
  it("converts the game's linear RGB to sRGB hex", function()
    -- Farm.COLORS[1] and COLOR_SINGLEPLAYER from the game source; sRGB per IEC 61966-2-1
    assert.are.equal("#ffaf00", VDT.MapExporter.linearToSrgbHex(1, 0.4287, 0))
    assert.are.equal("#82ab0c", VDT.MapExporter.linearToSrgbHex(0.22323, 0.40724, 0.00368))
  end)

  it("passes black and white through unchanged", function()
    assert.are.equal("#000000", VDT.MapExporter.linearToSrgbHex(0, 0, 0))
    assert.are.equal("#ffffff", VDT.MapExporter.linearToSrgbHex(1, 1, 1))
  end)

  it("clamps out-of-range channels", function()
    assert.are.equal("#ff0000", VDT.MapExporter.linearToSrgbHex(1.5, -0.2, 0))
  end)
end)

describe("MapExporter.collect", function()
  -- A polygon node id -> world coordinate table; the engine global getWorldTranslation is stubbed
  -- to read from it (returns x, y, z like the real one).
  local nodeCoords

  local function hotspot(placeableType, x, z, name, ownerFarmId)
    return {
      placeableType = placeableType,
      ownerFarmId = ownerFarmId,
      getIsVisible = function()
        return true
      end,
      getWorldPosition = function()
        return x, z
      end,
      getName = function()
        return name
      end,
    }
  end

  local function field(id, name, owner, areaHa, labelX, labelZ, polygonNodes)
    return {
      getIndicatorPosition = function()
        return labelX, labelZ
      end,
      getId = function()
        return id
      end,
      getName = function()
        return name
      end,
      getOwner = function()
        return owner
      end,
      getAreaHa = function()
        return areaHa
      end,
      getPolygonPoints = function()
        return polygonNodes or {}
      end,
    }
  end

  before_each(function()
    nodeCoords = {}
    rawset(_G, "getWorldTranslation", function(node)
      local c = nodeCoords[node]
      assert(c ~= nil, "unknown polygon node " .. tostring(node))
      return c[1], 0, c[2]
    end)
    rawset(_G, "PlaceableHotspot", { TYPE = { LOADING = 1, UNLOADING = 2, SHOP = 5 } })
    rawset(_G, "g_currentMission", {
      terrainSize = 2048,
      isMissionStarted = true,
      hud = { ingameMap = { worldSizeX = 2048, worldSizeZ = 2048, hotspots = {} } },
    })
    rawset(_G, "g_fieldManager", { fields = {} })
    rawset(_G, "g_farmManager", { farms = {} })
  end)

  after_each(function()
    rawset(_G, "getWorldTranslation", nil)
    rawset(_G, "PlaceableHotspot", nil)
    rawset(_G, "g_currentMission", nil)
    rawset(_G, "g_fieldManager", nil)
    rawset(_G, "g_farmManager", nil)
  end)

  it("returns nil without a mission", function()
    rawset(_G, "g_currentMission", nil)
    assert.is_nil(VDT.MapExporter.collect())
  end)

  it("omits empty poi/field/farm arrays (Json can't distinguish [] from {})", function()
    local model = VDT.MapExporter.collect()
    assert.are.equal("1", model.version)
    assert.are.equal(2048, model.terrainSize)
    assert.is_nil(model.pois)
    assert.is_nil(model.fields)
    assert.is_nil(model.farms)
  end)

  it("exports farms with their map color, skipping the spectator farm", function()
    g_farmManager.farms = {
      { farmId = 0, isSpectator = true, name = "Spectator" },
      {
        farmId = 1,
        name = "My Farm",
        getColor = function()
          return { 1, 0.4287, 0, 1 }
        end,
      },
      { farmId = 2 }, -- no name, no readable color -> both omitted
    }

    local farms = VDT.MapExporter.collect().farms
    assert.are.equal(2, #farms)
    assert.are.same({ id = 1, name = "My Farm", color = "#ffaf00" }, farms[1])
    assert.are.same({ id = 2 }, farms[2])
  end)

  it("exports placeable hotspots as normalized POIs and skips non-placeable ones", function()
    g_currentMission.hud.ingameMap.hotspots = {
      hotspot(2, 0, -1024, "Grain Mill", 1),
      {
        getWorldPosition = function() -- a vehicle hotspot: no placeableType -> skipped
          return 0, 0
        end,
      },
      hotspot(5, 1024, 0, nil, 0), -- ownerFarmId 0 = everyone -> omitted; nil name -> omitted
    }

    local pois = VDT.MapExporter.collect().pois
    assert.are.equal(2, #pois)
    assert.are.same({ type = "unloading", name = "Grain Mill", posX = 0.5, posZ = 0, ownerFarmId = 1 }, pois[1])
    assert.are.same({ type = "shop", posX = 1, posZ = 0.5 }, pois[2])
  end)

  it("exports fields with normalized labels and border polygons", function()
    nodeCoords = { [11] = { -1024, -1024 }, [12] = { 0, -1024 }, [13] = { 0, 0 }, [14] = { -1024, 0 } }
    g_fieldManager.fields = {
      field(7, "7", 1, 2.312, -512, -512, { 11, 12, 13, 14 }),
      field(12, "12", 0, 4.5, 512, 512), -- unowned, no polygon nodes
    }

    local fields = VDT.MapExporter.collect().fields
    assert.are.equal(2, #fields)
    assert.are.same({
      id = 7,
      name = "7",
      farmlandId = 7,
      ownerFarmId = 1,
      areaHa = 2.31,
      labelX = 0.25,
      labelZ = 0.25,
      polygon = { 0, 0, 0.5, 0, 0.5, 0.5, 0, 0.5 },
    }, fields[1])
    assert.are.same({ id = 12, name = "12", farmlandId = 12, areaHa = 4.5, labelX = 0.75, labelZ = 0.75 }, fields[2])
  end)

  it("degrades a field to its label when a polygon node can't be resolved", function()
    nodeCoords = { [11] = { 0, 0 } } -- nodes 12/13 unknown -> getWorldTranslation throws
    g_fieldManager.fields = { field(3, "3", nil, 1, 0, 0, { 11, 12, 13 }) }

    local fields = VDT.MapExporter.collect().fields
    assert.are.equal(3, fields[1].id)
    assert.is_nil(fields[1].polygon)
  end)

  it("is unavailable until fields exist or the mission started", function()
    g_currentMission.isMissionStarted = false
    assert.is_false(VDT.MapExporter.isAvailable())
    g_fieldManager.fields = { field(1, "1", nil, 1, 0, 0) }
    assert.is_true(VDT.MapExporter.isAvailable())
    g_fieldManager.fields = {}
    g_currentMission.isMissionStarted = true
    assert.is_true(VDT.MapExporter.isAvailable())
  end)
end)

describe("MapExporter.poiTypeToken", function()
  -- mirror of a slice of the game's PlaceableHotspot.TYPE
  local types = {
    LOADING = 1,
    UNLOADING = 2,
    UNLOADING_TRAIN = 3,
    PRODUCTION_POINT = 4,
    SHOP = 5,
    EXCLAMATION_MARK = 17,
  }

  it("camelCases the enum key of a known value", function()
    assert.are.equal("unloading", VDT.MapExporter.poiTypeToken(2, types))
    assert.are.equal("shop", VDT.MapExporter.poiTypeToken(5, types))
    assert.are.equal("productionPoint", VDT.MapExporter.poiTypeToken(4, types))
    assert.are.equal("unloadingTrain", VDT.MapExporter.poiTypeToken(3, types))
    assert.are.equal("exclamationMark", VDT.MapExporter.poiTypeToken(17, types))
  end)

  it("falls back to 'other' for unknown values", function()
    assert.are.equal("other", VDT.MapExporter.poiTypeToken(999, types))
  end)

  it("falls back to 'other' when the enum is unreachable", function()
    assert.are.equal("other", VDT.MapExporter.poiTypeToken(2, nil))
    assert.are.equal("other", VDT.MapExporter.poiTypeToken(nil, types))
  end)
end)
