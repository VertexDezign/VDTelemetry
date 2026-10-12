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

-- The interval cadence (accumulate dt -> markDirty every intervalMs) is owned by ExportChannels now,
-- not this exporter; it's covered generically in ExportChannels_spec. Here we just register with
-- intervalMs = INTERVAL_MS.

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
      calculatePlayerHeading = function(yaw)
        return yaw * 100
      end,
    })
    rawset(_G, "NetworkUtil", {
      getObjectId = function(object)
        return object.objectId
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
    rawset(_G, "NetworkUtil", nil)
    rawset(_G, "g_currentMission", nil)
    rawset(_G, "g_localPlayer", nil)
  end)

  it("returns nil without a mission", function()
    rawset(_G, "g_currentMission", nil)
    assert.is_nil(VDT.MapVehicles.collect())
  end)

  it("omits the empty vehicles and players arrays", function()
    local model = VDT.MapVehicles.collect()
    assert.are.equal("2", model.version)
    assert.is_nil(model.vehicles)
    assert.is_nil(model.players)
  end)

  it("exports root vehicles with normalized position, heading and flags", function()
    local tractor = vehicle({ objectId = 42, spec_enterable = { isControlled = true, isEntered = true } })
    g_currentMission.vehicleSystem.vehicles = { tractor }

    local vehicles = VDT.MapVehicles.collect().vehicles
    assert.are.same({
      {
        id = 42,
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

  describe("players", function()
    local function player(over)
      local p = {
        isControlled = true,
        farmId = 2,
        getCurrentVehicle = function()
          return nil
        end,
        getPosition = function()
          return 1024, 10, 0
        end,
        getYaw = function()
          return 0.5
        end,
        getNickname = function()
          return "Grisu118"
        end,
      }
      for k, val in pairs(over or {}) do
        p[k] = val
      end
      return p
    end

    it("exports another player on foot with their own position and heading", function()
      g_currentMission.playerSystem = { players = { player() } }

      assert.are.same({
        { name = "Grisu118", posX = 1, posZ = 0.5, heading = 50, farmId = 2 },
      }, VDT.MapVehicles.collect().players)
    end)

    it("leaves the local player out", function()
      local me = player()
      rawset(_G, "g_localPlayer", me)
      g_currentMission.playerSystem = { players = { me } }

      assert.is_nil(VDT.MapVehicles.collect().players)
    end)

    it("places a seated player on the root vehicle, though entering hid them", function()
      local tractor = vehicle({ objectId = 42 })
      local trailer = vehicle({ objectId = 43 })
      trailer.rootVehicle = tractor
      local driver = player({
        isControlled = false, -- Player:hide() on entering
        getCurrentVehicle = function()
          return trailer
        end,
      })
      g_currentMission.playerSystem = { players = { driver } }

      local entry = VDT.MapVehicles.collect().players[1]
      -- the vehicle's node and heading, not the player's parked capsule
      assert.are.equal(0.5, entry.posX)
      assert.are.equal(0, entry.posZ)
      assert.are.equal(91, entry.heading)
      assert.are.equal(42, entry.vehicleId)
    end)

    it("skips a hidden player who is in no vehicle, and the spectator farm", function()
      local joining = player({ isControlled = false })
      local spectator = player({ farmId = 0 })
      g_currentMission.playerSystem = { players = { joining, spectator } }

      local players = VDT.MapVehicles.collect().players
      assert.are.equal(1, #players)
      assert.is_nil(players[1].farmId)
    end)
  end)
end)
