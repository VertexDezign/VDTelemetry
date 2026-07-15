-- Unit tests for the vehicle-marker export channel (src/collect/MapVehiclesExporter.lua): the
-- interval tick, the type-token lookup, and collect() against a stubbed vehicle system. Whether the
-- real engine objects still look like those stubs is what the in-game smoke test covers.
--
-- Run with `busted` from the vdTelemetry/ directory. The exporter self-registers a channel and
-- reuses VDT.MapExporter's normalization helpers, so both dependencies load first.

if VDT == nil or VDT.ExportChannels == nil then
  dofile("src/export/ExportChannels.lua")
end
if VDT.MapExporter == nil then
  dofile("src/collect/MapExporter.lua")
end
dofile("src/collect/MapVehiclesExporter.lua")

describe("MapVehicles.typeToken", function()
  local types = { TRACTOR = 1, HARVESTER = 4, TOOL_TRAILED = 8, WOOD_HARVESTER = 14 }

  it("camelCases the enum key of a known value", function()
    assert.are.equal("tractor", VDT.MapVehicles.typeToken(1, types))
    assert.are.equal("harvester", VDT.MapVehicles.typeToken(4, types))
    assert.are.equal("toolTrailed", VDT.MapVehicles.typeToken(8, types))
    assert.are.equal("woodHarvester", VDT.MapVehicles.typeToken(14, types))
  end)

  it("falls back to 'other' for unknown values or a missing enum", function()
    assert.are.equal("other", VDT.MapVehicles.typeToken(999, types))
    assert.are.equal("other", VDT.MapVehicles.typeToken(nil, types))
    assert.are.equal("other", VDT.MapVehicles.typeToken(1, nil))
  end)
end)

describe("MapVehicles.tick", function()
  local markDirtyOrig, marked

  before_each(function()
    VDT.MapVehicles.timerMs = 0
    marked = 0
    markDirtyOrig = VDT.ExportChannels.markDirty
    VDT.ExportChannels.markDirty = function(name)
      assert.are.equal(VDT.MapVehicles.CHANNEL, name)
      marked = marked + 1
    end
  end)

  after_each(function()
    VDT.ExportChannels.markDirty = markDirtyOrig
  end)

  it("marks dirty once per INTERVAL_MS of accumulated frame time", function()
    local interval = VDT.MapVehicles.INTERVAL_MS
    for _ = 1, 3 do
      VDT.MapVehicles.tick(nil, interval / 2)
    end
    assert.are.equal(1, marked) -- 1.5 intervals -> exactly one write queued
    VDT.MapVehicles.tick(nil, interval)
    assert.are.equal(2, marked)
  end)

  it("ignores a tick without dt (older framework caller)", function()
    VDT.MapVehicles.tick(nil, nil)
    assert.are.equal(0, marked)
    assert.are.equal(0, VDT.MapVehicles.timerMs)
  end)
end)

describe("MapVehicles.collect", function()
  local function vehicle(over)
    local v = {
      rootNode = 1,
      mapHotspotType = 1,
      getFullName = function()
        return "Valtra T195"
      end,
      getOwnerFarmId = function()
        return 1
      end,
      getIsAIActive = function()
        return false
      end,
    }
    v.rootVehicle = v
    for k, val in pairs(over or {}) do
      v[k] = val
    end
    return v
  end

  before_each(function()
    rawset(_G, "getWorldTranslation", function()
      return 0, 0, -1024
    end)
    rawset(_G, "VehicleHotspot", { TYPE = { TRACTOR = 1, TRAILER = 6 } })
    rawset(_G, "ValueMapper", {
      calculateHeading = function()
        return 91.7
      end,
    })
    rawset(_G, "g_currentMission", {
      terrainSize = 2048,
      hud = { ingameMap = { worldSizeX = 2048, worldSizeZ = 2048 } },
      vehicleSystem = { vehicles = {} },
    })
  end)

  after_each(function()
    rawset(_G, "getWorldTranslation", nil)
    rawset(_G, "VehicleHotspot", nil)
    rawset(_G, "ValueMapper", nil)
    rawset(_G, "g_currentMission", nil)
  end)

  it("returns nil without a mission", function()
    rawset(_G, "g_currentMission", nil)
    assert.is_nil(VDT.MapVehicles.collect())
  end)

  it("omits the empty vehicles array", function()
    local model = VDT.MapVehicles.collect()
    assert.are.equal("1", model.version)
    assert.is_nil(model.vehicles)
  end)

  it("exports root vehicles with normalized position, heading and flags", function()
    local tractor = vehicle({ spec_enterable = { isControlled = true, isEntered = true } })
    g_currentMission.vehicleSystem.vehicles = { tractor }

    local vehicles = VDT.MapVehicles.collect().vehicles
    assert.are.same({
      {
        type = "tractor",
        name = "Valtra T195",
        posX = 0.5,
        posZ = 0,
        heading = 91,
        farmId = 1,
        isAI = false,
        isControlled = true,
        isEntered = true,
      },
    }, vehicles)
  end)

  it("skips attached implements, hidden hotspots, and node-less vehicles", function()
    local tractor = vehicle({})
    local attached = vehicle({}) -- an implement: its root is the tractor
    attached.rootVehicle = tractor
    local pallet = vehicle({ mapHotspotAvailable = false })
    local broken = vehicle({})
    broken.rootNode = nil -- can't override to nil through the helper table (nil keys vanish)
    g_currentMission.vehicleSystem.vehicles = { tractor, attached, pallet, broken }

    local vehicles = VDT.MapVehicles.collect().vehicles
    assert.are.equal(1, #vehicles)
  end)

  it("omits the enterable flags for non-enterables and tags AI drivers", function()
    local trailer = vehicle({
      mapHotspotType = 6,
      getFullName = function()
        return "Krampe Bandit"
      end,
      getIsAIActive = function()
        return true
      end,
    })
    g_currentMission.vehicleSystem.vehicles = { trailer }

    local entry = VDT.MapVehicles.collect().vehicles[1]
    assert.are.equal("trailer", entry.type)
    assert.is_true(entry.isAI)
    assert.is_nil(entry.isControlled)
    assert.is_nil(entry.isEntered)
  end)
end)
