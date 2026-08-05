-- Unit tests for the missions export channel (src/collect/MissionExporter.lua).
--
-- Run with `busted` from the vdTelemetry/ directory. The collector reuses ProductionExporter's
-- own-farm helper and MapExporter's normalization (both loaded first; each self-registers a channel,
-- so ExportChannels ahead of them) and reads the mission manager off the FS globals. The missions
-- themselves are plain tables shaped like AbstractMission -- the collector only ever calls getters,
-- which is what makes that possible.
--
-- The behaviour worth pinning down: what a contract on offer says versus one this farm is running,
-- the multiplayer filter that hides another farm's contracts, and that a mission type whose
-- getDetails() trips costs its own rows and nothing else.

if VDT == nil or VDT.ExportChannels == nil then
  dofile("src/export/ExportChannels.lua")
end
if VDT.ProductionExporter == nil then
  dofile("src/collect/ProductionExporter.lua")
end
if VDT.MapExporter == nil then
  dofile("src/collect/MapExporter.lua")
end
if VDT.MissionExporter == nil then
  dofile("src/collect/MissionExporter.lua")
end

local STATUS = { CREATED = 1, PREPARING = 2, RUNNING = 3, FINISHED = 4, DISMISSED = 5 }
local FINISH = { NONE = 1, SUCCESS = 2, FAILED = 3, TIMED_OUT = 4, CANCELED = 5 }

local TERRAIN_SIZE = 2048

-- A mission shaped like the engine's: `over` replaces any default. Every entry the collector reads
-- is a getter, because that is all AbstractMission exposes.
local function mission(over)
  over = over or {}
  local m = {
    -- `objectId = false` is the spec's way to say "not registered yet, so it has no id"
    objectId = over.objectId ~= false and (over.objectId or 41) or nil,
    status = over.status or STATUS.CREATED,
    finishState = over.finishState or FINISH.NONE,
    farmId = over.farmId,
    completion = over.completion,
    field = over.field,
    type = { name = over.type or "harvestMission" },
    getTitle = function()
      return over.title or "Harvesting"
    end,
    getDescription = function()
      return over.description
    end,
    getLocation = function()
      return over.location or "Field 12"
    end,
    getReward = function()
      return over.reward or 5400.4
    end,
    getTotalReward = function()
      return over.totalReward or 4800
    end,
    getVehicleCosts = function()
      return over.vehicleCosts or 0
    end,
    hasLeasableVehicles = function()
      return over.leasable == true
    end,
    getWasStarted = function()
      return (over.status or STATUS.CREATED) ~= STATUS.CREATED
    end,
    getMinutesLeft = function()
      return over.minutesLeft
    end,
    getExtraProgressText = function()
      return over.extraProgress or ""
    end,
    getWorldPosition = function()
      return over.worldX or 512, over.worldZ or -512
    end,
    getNPC = function()
      return over.npc
    end,
    getDetails = function()
      if over.detailsThrow then
        error("selling station not resolved")
      end
      return over.details or { { title = "Field", value = "12" } }
    end,
    getFinishedDetails = function()
      return over.finishedDetails or { { title = "Reward", value = "4.800 €" } }
    end,
  }
  if over.farmlandId ~= nil then
    m.getFarmlandId = function()
      return over.farmlandId
    end
  end
  -- The engine sets these on the missions that have them; the collector reads them by presence, not
  -- by mission type, so the spec adds them the same way.
  m.fruitTypeIndex = over.fruitTypeIndex
  m.fruitTypeTitle = over.fruitTypeTitle
  m.needRoundbaler = over.needRoundbaler
  m.baleTypeIndex = over.baleTypeIndex
  m.pendingSellingStationId = over.pendingSellingStationId
  if over.station ~= nil then
    m.sellingStation = {
      getName = function()
        return over.station.name
      end,
      owningPlaceable = over.station.worldX ~= nil and {
        getHotspot = function()
          return {
            getWorldPosition = function()
              return over.station.worldX, over.station.worldZ
            end,
          }
        end,
      } or nil,
    }
  end
  if over.resolveStation ~= nil then
    m.tryToResolveSellingStation = function(self)
      self.sellingStation = over.resolveStation
      self.pendingSellingStationId = nil
    end
  end
  return m
end

-- Installs the globals collect() reads. `missions` is the manager's whole list; the manager's own
-- filter is stubbed to the engine's rule (no farm, or this farm).
local function installWorld(missions, opts)
  opts = opts or {}
  _G.MissionStatus = STATUS
  _G.MissionFinishState = FINISH
  _G.MissionManager = { MAX_MISSIONS_PER_FARM = opts.maxPerFarm or 3 }
  _G.Farm = { PERMISSION = { MANAGE_CONTRACTS = "manageContracts" } }
  _G.NetworkUtil = {
    getObjectId = function(object)
      return object.objectId
    end,
  }
  _G.g_localPlayer = opts.farmId ~= false and { farmId = opts.farmId or 1 } or nil
  _G.g_missionManager = {
    missions = missions,
    getMissionsByFarmId = function(_, farmId)
      local list = {}
      for _, m in ipairs(missions) do
        if m.farmId == nil or m.farmId == farmId then
          list[#list + 1] = m
        end
      end
      return list
    end,
  }
  _G.g_fruitTypeManager = {
    getFruitTypeNameByIndex = function(_, index)
      return ({ [4] = "WHEAT", [11] = "OAT", [20] = "GRASS" })[index]
    end,
    getFillTypeByFruitTypeIndex = function(_, index)
      return ({ [4] = { title = "Weizen" }, [11] = { title = "Hafer" }, [20] = { title = "Gras" } })[index]
    end,
  }
  _G.g_baleManager = {
    getIsRoundBale = function(_, index)
      return index == 1
    end,
  }
  _G.g_i18n = {
    getText = function(_, key)
      return key == "fillType_roundBale" and "Rundballen" or "Quaderballen"
    end,
  }
  _G.g_currentMission = {
    terrainSize = TERRAIN_SIZE,
    getHasPlayerPermission = function()
      return opts.canManage ~= false
    end,
  }
end

after_each(function()
  _G.MissionStatus = nil
  _G.MissionFinishState = nil
  _G.MissionManager = nil
  _G.Farm = nil
  _G.NetworkUtil = nil
  _G.g_localPlayer = nil
  _G.g_missionManager = nil
  _G.g_currentMission = nil
  _G.g_fruitTypeManager = nil
  _G.g_baleManager = nil
  _G.g_i18n = nil
end)

describe("MissionExporter.collect", function()
  it("describes a contract still on offer", function()
    installWorld({
      mission({
        objectId = 77,
        type = "sowMission",
        title = "Sowing",
        description = "Sow the field",
        reward = 5400.4,
        leasable = true,
        vehicleCosts = 620.5,
        farmlandId = 12,
        npc = { title = "Anna", imageFilename = "npc/anna.png" },
        details = { { title = "Field", value = "12" }, { title = "Crop", value = "Wheat" } },
        field = {
          getAreaHa = function()
            return 3.456
          end,
        },
      }),
    })

    local model = VDT.MissionExporter.collect()

    assert.are.equal("2", model.version)
    assert.are.equal(1, #model.missions)
    local m = model.missions[1]
    assert.are.equal(77, m.id)
    assert.are.equal("sowMission", m.type)
    assert.are.equal("Sowing", m.title)
    assert.are.equal("Sow the field", m.description)
    assert.are.equal("CREATED", m.status)
    assert.are.equal("Field 12", m.location)
    -- money is whole units, area two decimals
    assert.are.equal(5400, m.reward)
    assert.are.equal(621, m.vehicleCosts)
    assert.is_true(m.leasable)
    assert.are.equal(3.46, m.areaHa)
    assert.are.equal(12, m.fieldId)
    assert.are.equal("Anna", m.npc.name)
    assert.are.equal("npc/anna.png", m.npc.image)
    assert.are.equal(2, #m.details)
    assert.are.equal("Crop", m.details[2].title)
    assert.are.equal("Wheat", m.details[2].value)
    -- normalized into the same frame as the player marker: 512 m east of centre on a 2048 m map
    assert.are.equal(0.75, m.posX)
    assert.are.equal(0.25, m.posZ)

    -- nothing has started, so there is no outcome, no progress and no owner
    assert.is_nil(m.finishState)
    assert.is_nil(m.completion)
    assert.is_nil(m.own)
    assert.is_nil(m.totalReward)
  end)

  it("reports progress on a contract this farm is running", function()
    installWorld({
      mission({
        status = STATUS.RUNNING,
        farmId = 1,
        completion = 0.42371,
        minutesLeft = 320.8,
        extraProgress = "3 trees remaining",
      }),
    })

    local m = VDT.MissionExporter.collect().missions[1]
    assert.are.equal("RUNNING", m.status)
    assert.is_true(m.own)
    assert.are.equal(0.4237, m.completion)
    assert.are.equal(320, m.minutesLeft)
    assert.are.equal("3 trees remaining", m.extraProgress)
  end)

  it("switches to the reward breakdown once a contract is finished", function()
    installWorld({
      mission({
        status = STATUS.FINISHED,
        finishState = FINISH.SUCCESS,
        farmId = 1,
        completion = 1,
        totalReward = 4799.6,
        extraProgress = "still running",
        finishedDetails = { { title = "Reward", value = "4.800 €" }, { title = "Vehicle costs", value = "-600 €" } },
      }),
    })

    local m = VDT.MissionExporter.collect().missions[1]
    assert.are.equal("FINISHED", m.status)
    assert.are.equal("SUCCESS", m.finishState)
    assert.are.equal(4800, m.totalReward)
    -- getFinishedDetails, not getDetails
    assert.are.equal(2, #m.details)
    assert.are.equal("Vehicle costs", m.details[2].title)
    -- the progress line is a running-contract thing; a finished one is not still working
    assert.is_nil(m.extraProgress)
  end)

  it("hides a contract another farm is running", function()
    installWorld({
      mission({ objectId = 1 }), -- on offer to everyone
      mission({ objectId = 2, status = STATUS.RUNNING, farmId = 1 }), -- ours
      mission({ objectId = 3, status = STATUS.RUNNING, farmId = 2 }), -- someone else's
    })

    local model = VDT.MissionExporter.collect()
    assert.are.equal(2, #model.missions)
    assert.are.equal(1, model.missions[1].id)
    assert.are.equal(2, model.missions[2].id)
  end)

  it("reports the farm's contract limit and its right to manage them", function()
    installWorld({
      mission({ objectId = 1 }),
      mission({ objectId = 2, status = STATUS.RUNNING, farmId = 1 }),
      mission({ objectId = 3, status = STATUS.FINISHED, farmId = 1 }),
      mission({ objectId = 4, status = STATUS.RUNNING, farmId = 2 }), -- another farm's, not ours
    })

    local model = VDT.MissionExporter.collect()
    -- started AND ours: the engine's own hasFarmReachedMissionLimit count
    assert.are.equal(2, model.limit.active)
    assert.are.equal(3, model.limit.max)
    assert.is_true(model.canManage)
  end)

  it("says so when the player may not manage contracts", function()
    installWorld({ mission({}) }, { canManage = false })
    assert.is_false(VDT.MissionExporter.collect().canManage)
  end)

  it("keeps a mission whose detail rows throw", function()
    -- HarvestMission:getDetails resolves its selling station from inside the getter; a mission type
    -- that trips there must cost its own rows and nothing else.
    installWorld({ mission({ objectId = 9, detailsThrow = true }) })

    local m = VDT.MissionExporter.collect().missions[1]
    assert.are.equal(9, m.id)
    assert.are.equal("Harvesting", m.title)
    assert.is_nil(m.details)
  end)

  it("leaves out a position the mission does not actually have", function()
    -- AbstractMission:getWorldPosition returns 0,0 on the base class. Normalizing that would put a
    -- marker dead centre of the map, so it is treated as "this type does not say".
    installWorld({ mission({ worldX = 0, worldZ = 0 }) })

    local m = VDT.MissionExporter.collect().missions[1]
    assert.is_nil(m.posX)
    assert.is_nil(m.posZ)
  end)

  it("skips a mission with no network id", function()
    -- A mission that is not registered yet has no object id, and that id is the command handle --
    -- exporting it would offer a contract the app cannot act on.
    installWorld({ mission({ objectId = false }), mission({ objectId = 5 }) })

    local model = VDT.MissionExporter.collect()
    assert.are.equal(1, #model.missions)
    assert.are.equal(5, model.missions[1].id)
  end)

  it("stays empty without a farm", function()
    installWorld({ mission({}) }, { farmId = false })

    local model = VDT.MissionExporter.collect()
    assert.are.equal("2", model.version)
    assert.is_nil(model.missions)
    assert.is_nil(model.limit)
  end)

  it("is unavailable until the mission manager is up", function()
    assert.is_false(VDT.MissionExporter.isAvailable())
    assert.is_nil(VDT.MissionExporter.collect())
  end)
end)

describe("MissionExporter contract subject", function()
  it("names the crop a harvest contract is for", function()
    installWorld({ mission({ fruitTypeIndex = 11 }) })

    local m = VDT.MissionExporter.collect().missions[1]
    assert.are.equal("OAT", m.fruitType)
    assert.are.equal("Hafer", m.subtitle)
    assert.is_nil(m.baleType)
  end)

  it("prefers the title the mission already resolved", function()
    -- BaleMission caches the localized crop title at setFruitType; going back through the fill type
    -- would only be a second way to reach the same string.
    installWorld({ mission({ fruitTypeIndex = 4, fruitTypeTitle = "Weizen (Vertrag)" }) })
    assert.are.equal("Weizen (Vertrag)", VDT.MissionExporter.collect().missions[1].subtitle)
  end)

  it("names the bale form from either field the engine states it in", function()
    -- A baling contract says it outright; a wrapping one carries a bale type the manager resolves.
    installWorld({
      mission({ objectId = 1, needRoundbaler = true }),
      mission({ objectId = 2, needRoundbaler = false }),
      mission({ objectId = 3, baleTypeIndex = 1 }),
      mission({ objectId = 4, baleTypeIndex = 2 }),
    })

    local ms = VDT.MissionExporter.collect().missions
    assert.are.equal("ROUND", ms[1].baleType)
    assert.are.equal("Rundballen", ms[1].subtitle)
    assert.are.equal("SQUARE", ms[2].baleType)
    assert.are.equal("Quaderballen", ms[2].subtitle)
    assert.are.equal("ROUND", ms[3].baleType)
    assert.are.equal("SQUARE", ms[4].baleType)
  end)

  it("says both when a contract names both", function()
    -- A baling contract is for a form AND a crop; the list line should not have to pick one.
    installWorld({ mission({ needRoundbaler = true, fruitTypeIndex = 20 }) })
    assert.are.equal("Rundballen \194\183 Gras", VDT.MissionExporter.collect().missions[1].subtitle)
  end)

  it("leaves the subject out when the contract has none", function()
    -- A ploughing or mowing contract names neither, and an empty string would still render a line.
    installWorld({ mission({}) })

    local model = VDT.MissionExporter.collect().missions[1]
    assert.is_nil(model.subtitle)
    assert.is_nil(model.fruitType)
    assert.is_nil(model.baleType)
  end)
end)

describe("MissionExporter selling station", function()
  it("places the station where the game marks it, not by name", function()
    installWorld({
      mission({ station = { name = "Getreidemühle", worldX = 512, worldZ = -512 } }),
    })

    local station = VDT.MissionExporter.collect().missions[1].sellingStation
    assert.are.equal("Getreidemühle", station.name)
    -- the placeable's own hotspot position, normalized into the shared map frame
    assert.are.equal(0.75, station.posX)
    assert.are.equal(0.25, station.posZ)
  end)

  it("resolves a station the client has only as a pending id", function()
    -- A client receives the station as a network id and looks it up lazily; the engine's own
    -- getDetails does the same, so a station nobody has asked for yet must still report.
    installWorld({
      mission({
        pendingSellingStationId = 77,
        resolveStation = {
          getName = function()
            return "Sägemühle"
          end,
        },
      }),
    })

    assert.are.equal("Sägemühle", VDT.MissionExporter.collect().missions[1].sellingStation.name)
  end)

  it("keeps a station it can name but not place", function()
    installWorld({ mission({ station = { name = "Sägemühle" } }) })

    local station = VDT.MissionExporter.collect().missions[1].sellingStation
    assert.are.equal("Sägemühle", station.name)
    assert.is_nil(station.posX)
  end)

  it("leaves the key out for a contract that delivers nowhere", function()
    installWorld({ mission({}) })
    assert.is_nil(VDT.MissionExporter.collect().missions[1].sellingStation)
  end)
end)

describe("MissionExporter enum tokens", function()
  it("names the status and the outcome", function()
    assert.are.equal("RUNNING", VDT.MissionExporter.statusToken(STATUS.RUNNING, STATUS))
    assert.are.equal("TIMED_OUT", VDT.MissionExporter.finishStateToken(FINISH.TIMED_OUT, FINISH))
  end)

  it("treats NONE as no outcome at all", function()
    -- MissionFinishState.NONE is the value every unfinished contract carries; it is not an outcome,
    -- so it must leave the key out rather than publish a fifth state the app has to filter.
    assert.is_nil(VDT.MissionExporter.finishStateToken(FINISH.NONE, FINISH))
  end)

  it("survives the functions Enum() hangs off the table", function()
    -- Enum(MissionStatus) decorates the table with getName/writeStream/... -- the reverse lookup must
    -- take only the numeric entries, or a function value ends up keyed as a status.
    local decorated = { CREATED = 1, RUNNING = 3, getName = function() end, writeStream = function() end }
    assert.are.equal("RUNNING", VDT.MissionExporter.statusToken(3, decorated))
    assert.is_nil(VDT.MissionExporter.statusToken(99, decorated))
  end)
end)
