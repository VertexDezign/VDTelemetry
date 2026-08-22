-- Unit tests for the fleet export channel (src/collect/FleetExporter.lua).
--
-- Run with `busted` from the vdTelemetry/ directory. The collector mirrors the game's own vehicle
-- overview, so what is worth pinning down is the gate (all three parts of it, since dropping any one
-- lists machines the game would not), that the id is the network object id rather than uniqueId (the
-- multiplayer trap), and that a machine the game hides -- a pallet, another farm's tractor -- never
-- reaches the file.

if VDT == nil or VDT.Farm == nil then
  dofile("src/utils/Farm.lua")
end
if Set == nil then
  dofile("src/utils/Set.lua")
end
if ValueMapper == nil then
  dofile("src/mapper/ValueMapper.lua")
end
if VDT.ExportChannels == nil then
  dofile("src/export/ExportChannels.lua")
end
if VDT.MapExporter == nil then
  dofile("src/collect/MapExporter.lua")
end
if VDT.MapVehicles == nil then
  dofile("src/collect/MapVehiclesExporter.lua")
end
if VDT.Wearable == nil then
  dofile("src/collect/aspects/Wearable.lua")
end
if VDT.FillUnit == nil then
  dofile("src/collect/aspects/FillUnit.lua")
end
if VDT.Motor == nil then
  dofile("src/collect/vehicle/Motor.lua")
end
if VDT.FleetExporter == nil then
  dofile("src/collect/FleetExporter.lua")
end
if Json == nil then
  dofile("src/utils/Json.lua")
end

local OWN_FARM = 1
local OTHER_FARM = 2

---A machine as the vehicle system hands it over. `listed` drives the game's own overview flag, which
---Pallet and Rideable override to false; `farmId` is its owner.
local function makeVehicle(over)
  over = over or {}
  local vehicle = {
    -- `id = false` stands for a machine the network has not registered yet.
    id = over.id ~= false and (over.id or 100) or nil,
    age = over.age,
    operatingTime = over.operatingTime,
    propertyState = over.propertyState or 2,
    price = over.price,
    configFileName = over.configFileName,
    mapHotspotType = over.mapHotspotType,
    rootNode = over.rootNode,
    spec_enterable = over.enterable,
    spec_wearable = over.wearable and {} or nil,
    spec_motorized = over.motorized,
  }
  vehicle.rootVehicle = over.rootVehicle or vehicle
  function vehicle:getShowInVehiclesOverview()
    return over.listed ~= false
  end
  function vehicle:getOwnerFarmId()
    return over.farmId or OWN_FARM
  end
  function vehicle:getFullName()
    return over.name or "Machine"
  end
  function vehicle:getDamageAmount()
    return over.damage or 0
  end
  function vehicle:getWearTotalAmount()
    return over.wear or 0
  end
  function vehicle:getSellPrice()
    return over.sellPrice or 0
  end
  function vehicle:getIsAIActive()
    return over.isAI == true
  end
  if over.enterable ~= nil then
    function vehicle:getIsTabbable()
      return over.tabbable ~= false
    end
  end
  if over.throwsOnName then
    function vehicle:getFullName()
      error("third-party getter blew up")
    end
  end
  return vehicle
end

---The world the collector reads: a vehicle system, an access handler and a farm.
local function installWorld(vehicles, over)
  over = over or {}
  _G.g_localPlayer = over.farmId ~= false and { farmId = over.farmId or OWN_FARM } or nil
  _G.NetworkUtil = {
    getObjectId = function(object)
      return object ~= nil and object.id or nil
    end,
  }
  _G.getWorldTranslation = function(node)
    return node.x, 0, node.z
  end
  _G.EconomyManager = { DEFAULT_RUNNING_LEASING_FACTOR = 0.05, PER_DAY_LEASING_FACTOR = 0.01 }
  -- The engine's fill-type enum, which Motor's fill-unit walk compares against by name.
  _G.FillType = { DEF = "DEF", AIR = "AIR" }
  _G.g_storeManager = over.storeManager
  _G.g_currentMission = {
    terrainSize = 2048,
    vehicleSystem = { vehicles = vehicles },
    accessHandler = {
      canPlayerAccess = function(_, vehicle)
        return over.noAccess ~= true and vehicle.blocked ~= true
      end,
    },
    environment = over.environment or { currentYear = 3, currentPeriod = 7, currentDayInPeriod = 2 },
  }
end

local function clearWorld()
  _G.g_localPlayer = nil
  _G.g_currentMission = nil
  _G.g_storeManager = nil
  _G.NetworkUtil = nil
  _G.getWorldTranslation = nil
  _G.EconomyManager = nil
  _G.VehicleHotspot = nil
  _G.FillType = nil
  _G.g_fillTypeManager = nil
  _G.VDTelemetry = nil
end

describe("FleetExporter", function()
  before_each(function()
    -- The integration registry is exercised on its own (see AdvancedDamageSystem_spec); here it only
    -- has to exist, and one case checks that the fleet stage is offered every machine.
    VDT.Integrations = VDT.Integrations or {}
    VDT.Integrations.run = function() end
    VDT.FleetExporter.reset()
  end)

  after_each(clearWorld)

  describe("the gate", function()
    it("lists the farm's own machines", function()
      installWorld({ makeVehicle({ id = 7, name = "Fendt 942" }) })
      local model = VDT.FleetExporter.collect()
      assert.equals("1", model.version)
      assert.equals(1, #model.vehicles)
      assert.equals(7, model.vehicles[1].id)
      assert.equals("Fendt 942", model.vehicles[1].name)
    end)

    it("drops what the game hides from its own overview -- a pallet, a horse", function()
      installWorld({ makeVehicle({ listed = false }) })
      assert.is_nil(VDT.FleetExporter.collect().vehicles)
    end)

    it("drops another farm's machines", function()
      installWorld({ makeVehicle({ farmId = OTHER_FARM }) })
      assert.is_nil(VDT.FleetExporter.collect().vehicles)
    end)

    it("drops a machine this player cannot reach, which is the multiplayer half of the gate", function()
      local blocked = makeVehicle({ id = 9 })
      blocked.blocked = true
      installWorld({ makeVehicle({ id = 8 }), blocked })
      local vehicles = VDT.FleetExporter.collect().vehicles
      assert.equals(1, #vehicles)
      assert.equals(8, vehicles[1].id)
    end)

    it("lists nothing at all while spectating", function()
      installWorld({ makeVehicle({}) }, { farmId = false })
      local model = VDT.FleetExporter.collect()
      -- The document still goes out: an empty fleet and no fleet are different statements.
      assert.equals("1", model.version)
      assert.is_nil(model.vehicles)
    end)

    it("skips the write entirely before the vehicle system is up", function()
      _G.g_currentMission = nil
      assert.is_nil(VDT.FleetExporter.collect())
    end)
  end)

  describe("a row", function()
    it("keys on the network object id, never on uniqueId", function()
      local vehicle = makeVehicle({ id = 42 })
      vehicle.uniqueId = "would-be-nil-on-a-client"
      installWorld({ vehicle })
      assert.equals(42, VDT.FleetExporter.collect().vehicles[1].id)
    end)

    it("drops a machine the network has no id for yet", function()
      installWorld({ makeVehicle({ id = false }) })
      assert.is_nil(VDT.FleetExporter.collect().vehicles)
    end)

    it("reports operating time in hours, as a number the overview can sort on", function()
      -- 1234.5 h in ms, and the tenth is kept rather than rounded away.
      installWorld({ makeVehicle({ operatingTime = 1234.5 * 3600000 }) })
      assert.equals(1234.5, VDT.FleetExporter.collect().vehicles[1].hours)
      assert.equals(0, VDT.FleetExporter.hours(nil))
    end)

    it("reports age in months and the property state as a token", function()
      installWorld({ makeVehicle({ age = 14, propertyState = 3 }) })
      local row = VDT.FleetExporter.collect().vehicles[1]
      assert.equals(14, row.age)
      assert.equals("LEASED", row.propertyState)
    end)

    it("prints each money column only where the game prints it", function()
      installWorld({
        makeVehicle({ id = 1, sellPrice = 84900.6 }),
        makeVehicle({ id = 2, propertyState = 3, price = 100000 }),
      })
      local owned, leased = table.unpack(VDT.FleetExporter.collect().vehicles)
      assert.equals(84900, owned.sellPrice)
      assert.is_nil(owned.leasePerDay)
      -- The game's own formula: running cost plus the per-day charge.
      assert.equals(6000, leased.leasePerDay)
      assert.is_nil(leased.sellPrice)
    end)

    it("carries condition from the wearable aspect the vehicle app uses", function()
      installWorld({ makeVehicle({ wearable = true, damage = 0.25, wear = 0.4 }) })
      local wearable = VDT.FleetExporter.collect().vehicles[1].wearable
      assert.equals(25, wearable.damage)
      assert.equals(40, wearable.wear)
    end)

    it("names the rig an implement is hanging off", function()
      local tractor = makeVehicle({ id = 1 })
      local plough = makeVehicle({ id = 2, rootVehicle = tractor })
      installWorld({ tractor, plough })
      local rows = VDT.FleetExporter.collect().vehicles
      assert.is_nil(rows[1].attachedTo)
      assert.equals(1, rows[2].attachedTo)
    end)

    it("says who has the machine, in the map channel's tokens", function()
      installWorld({ makeVehicle({ isAI = true, enterable = { isControlled = true, isEntered = false } }) })
      local row = VDT.FleetExporter.collect().vehicles[1]
      assert.is_true(row.isAI)
      assert.is_true(row.isControlled)
      -- Absent rather than false: the Kotlin model defaults it, and the file stays small.
      assert.is_nil(row.isEntered)
    end)

    it("reports the tab rotation, which is how a machine is marked as parked", function()
      installWorld({
        makeVehicle({ id = 1, enterable = {}, tabbable = false }),
        makeVehicle({ id = 2, enterable = {} }),
        makeVehicle({ id = 3 }),
      })
      local parked, ready, implement = table.unpack(VDT.FleetExporter.collect().vehicles)
      assert.is_false(parked.isTabbable)
      -- Written even when true: "not in the rotation" and "has no seat at all" are different answers,
      -- and the second one is the absent key.
      assert.is_true(ready.isTabbable)
      assert.is_nil(implement.isTabbable)
    end)

    it("asks the engine's getter, which a parking mod may overwrite outright", function()
      local vehicle = makeVehicle({ enterable = { isTabbable = true } })
      function vehicle:getIsTabbable()
        return false
      end
      installWorld({ vehicle })
      assert.is_false(VDT.FleetExporter.collect().vehicles[1].isTabbable)
    end)

    it("normalizes the position into the map channels' frame", function()
      installWorld({ makeVehicle({ rootNode = { x = 0, z = 512 } }) })
      local row = VDT.FleetExporter.collect().vehicles[1]
      assert.equals(0.5, row.posX)
      assert.equals(0.75, row.posZ)
    end)

    it("keeps a machine whose position cannot be read", function()
      installWorld({ makeVehicle({ id = 5 }) })
      local row = VDT.FleetExporter.collect().vehicles[1]
      assert.equals(5, row.id)
      assert.is_nil(row.posX)
    end)

    it("survives a third-party getter that throws", function()
      installWorld({ makeVehicle({ id = 3, throwsOnName = true }) })
      local row = VDT.FleetExporter.collect().vehicles[1]
      assert.equals(3, row.id)
      assert.equals("", row.name)
    end)
  end)

  describe("the store category", function()
    local function storeManager(categoryTitle)
      return {
        getItemByXMLFilename = function(_, path)
          return path == "data/fendt.xml" and { categoryName = "tractorsL" } or nil
        end,
        getCategoryByName = function(_, name)
          return name == "tractorsL" and { title = categoryTitle } or nil
        end,
      }
    end

    it("is the localized title both menus print", function()
      installWorld({ makeVehicle({ configFileName = "data/fendt.xml" }) }, { storeManager = storeManager("Tractors") })
      assert.equals("Tractors", VDT.FleetExporter.collect().vehicles[1].category)
    end)

    it("falls back to the raw category name, and to nothing at all", function()
      installWorld({ makeVehicle({ configFileName = "data/fendt.xml" }) }, { storeManager = storeManager(nil) })
      assert.equals("tractorsL", VDT.FleetExporter.collect().vehicles[1].category)

      clearWorld()
      installWorld(
        { makeVehicle({ configFileName = "data/unknown.xml" }) },
        { storeManager = storeManager("Tractors") }
      )
      assert.is_nil(VDT.FleetExporter.collect().vehicles[1].category)
    end)
  end)

  describe("the document", function()
    it("carries today, so a log date can be read as months ago", function()
      installWorld({ makeVehicle({}) })
      assert.same({ year = 3, month = 7, day = 2 }, VDT.FleetExporter.collect().date)
    end)

    it("omits an empty vehicle list rather than encoding it as an object", function()
      installWorld({})
      assert.is_nil(string.find(Json.encode(VDT.FleetExporter.collect()), "vehicles", 1, true))
    end)

    it("offers every machine to the integrations, which is where the ADS block comes from", function()
      local seen = {}
      VDT.Integrations.run = function(stage, vehicle, row)
        seen[#seen + 1] = { stage = stage, id = row.id, vehicle = vehicle }
      end
      installWorld({ makeVehicle({ id = 1 }), makeVehicle({ id = 2 }) })
      VDT.FleetExporter.collect()
      assert.equals(2, #seen)
      assert.equals("contributeFleetVehicle", seen[1].stage)
      assert.equals(1, seen[1].id)
      assert.equals(2, seen[2].id)
    end)
  end)

  describe("fuel", function()
    it("reports the motor's fill units without collecting the whole motor subtree", function()
      _G.g_fillTypeManager = {
        getFillTypeByIndex = function(_, index)
          return index == 1 and { name = "DIESEL", title = "Diesel", unitShort = "l" }
            or { name = "DEF", title = "DEF", unitShort = "l" }
        end,
      }
      _G.VDTelemetry = {
        mainFuelTypes = {
          contains = function(_, name)
            return name == "DIESEL"
          end,
        },
      }
      local vehicle = makeVehicle({
        motorized = { consumersByFillType = { [1] = { fillUnitIndex = 1 }, [2] = { fillUnitIndex = 2 } } },
      })
      function vehicle:getFillUnitFillLevel(index)
        return index == 1 and 320.7 or 18.2
      end
      function vehicle:getFillUnitCapacity(index)
        return index == 1 and 600 or 40
      end
      function vehicle:getFillUnitFillLevelPercentage(index)
        return index == 1 and 0.5345 or 0.455
      end
      installWorld({ vehicle })

      local units = VDT.FleetExporter.collect().vehicles[1].motorFillUnits
      assert.equals(320, units.fuel.value)
      assert.equals(600, units.fuel.capacity)
      assert.equals(53, units.fuel.fillLevelPercentage)
      assert.equals("diesel", units.fuel.type)
      assert.equals(18, units.def.value)
    end)

    it("says nothing about a machine with no motor", function()
      installWorld({ makeVehicle({}) })
      assert.is_nil(VDT.FleetExporter.collect().vehicles[1].motorFillUnits)
    end)
  end)
end)
