-- Unit tests for the AutoDrive integration (src/integrations/AutoDrive.lua) and its write side
-- (src/command/AutoDriveControl.lua).
--
-- Run with `busted` from the vdTelemetry/ directory. The stubs are shaped like AutoDrive 3.0.1.4's own
-- objects (references/FS25_AutoDrive): a `vehicle.ad.stateModule` with ADStateModule's getters, and the
-- managers and events installed under the mod's environment global, FS25_AutoDrive -- never as bare
-- globals, which from our environment they are not (see CropRotation.lua).

if VDT == nil or VDT.ExportChannels == nil then
  dofile("src/export/ExportChannels.lua")
end
if VDT.MapExporter == nil then
  dofile("src/collect/MapExporter.lua")
end
if VDT.CommandRegistry == nil then
  dofile("src/command/CommandRegistry.lua")
end
dofile("src/integrations/AutoDrive.lua")
dofile("src/command/AutoDriveControl.lua")

local debugger = { debug = function() end, warn = function() end }

local MARKERS = {
  { id = 11, markerIndex = 1, name = "Farm", group = "All" },
  { id = 12, markerIndex = 2, name = "Silo", group = "Grain" },
}

---AutoDrive's environment: graph manager, input manager and the HUD event, recording what is sent.
local function installAutoDrive()
  local sent = { inputs = {}, events = {} }
  local function record(kind)
    return function(_, vehicle, a, b, c)
      sent.events[#sent.events + 1] = { kind = kind, vehicle = vehicle, a = a, b = b, c = c }
    end
  end
  rawset(_G, "FS25_AutoDrive", {
    AutoDrive = {
      MODE_DRIVETO = 1,
      MODE_PICKUPANDDELIVER = 2,
      MODE_DELIVERTO = 3,
      MODE_LOAD = 4,
      MODE_UNLOAD = 5,
      UAL_FILLTYPE_ALL = 1,
      getValidSupportedFillTypesALfirst = function()
        return { 3, 2 }
      end,
      registerDestinationListener = function(self, object, callback)
        self.listener = { object = object, callback = callback }
      end,
    },
    ADGraphManager = {
      getMapMarkers = function()
        return MARKERS
      end,
      getMapMarkerById = function(_, id)
        return MARKERS[id]
      end,
      getWayPointById = function(_, id)
        local points = { [1] = { x = 0, z = 0 }, [11] = { x = 0, z = -1024 }, [12] = { x = 512, z = 0 } }
        return points[id]
      end,
    },
    ADInputManager = {
      onInputCall = function(_, vehicle, name)
        sent.inputs[#sent.inputs + 1] = name
      end,
    },
    AutoDriveHudInputEventEvent = {
      sendFirstMarkerEvent = record("first"),
      sendSecondMarkerEvent = record("second"),
      sendFillTypeEvent = record("fillType"),
      sendChangeLoopCounterEvent = record("loop"),
    },
  })
  return sent
end

---A machine AutoDrive drives; `over` replaces state fields.
local function adVehicle(over)
  local s = {
    active = false,
    mode = 1,
    first = MARKERS[1],
    second = MARKERS[2],
    fillType = 2,
    loops = 0,
    loopsDone = 0,
    task = "",
    remaining = 0,
    current = nil,
  }
  for k, v in pairs(over or {}) do
    s[k] = v
  end
  local state = {
    isActive = function()
      return s.active
    end,
    getMode = function()
      return s.mode
    end,
    -- `first = false` in `over` stands for "no destination": a nil would vanish from the table.
    getFirstMarker = function()
      return s.first or nil
    end,
    getFirstMarkerId = function()
      return s.first and s.first.markerIndex or -1
    end,
    getSecondMarker = function()
      return s.second
    end,
    getSecondMarkerId = function()
      return s.second ~= nil and s.second.markerIndex or -1
    end,
    getFillType = function()
      return s.fillType
    end,
    getLoopCounter = function()
      return s.loops
    end,
    getLoopsDone = function()
      return s.loopsDone
    end,
    getCurrentLocalizedTaskInfo = function()
      return s.task
    end,
    getRemainingDriveTime = function()
      return s.remaining
    end,
    getCurrentDestination = function()
      return s.current
    end,
  }
  return { ad = { stateModule = state } }
end

describe("AutoDrive integration", function()
  before_each(function()
    installAutoDrive()
    rawset(_G, "g_fillTypeManager", {
      getFillTypeByIndex = function(_, index)
        local types = { [2] = { name = "WHEAT", title = "Wheat" }, [3] = { name = "BARLEY", title = "Barley" } }
        return types[index]
      end,
      getFillTypeIndexByName = function(_, name)
        return ({ WHEAT = 2, BARLEY = 3 })[name]
      end,
    })
    rawset(_G, "g_currentMission", {
      terrainSize = 2048,
      hud = { ingameMap = { worldSizeX = 2048, worldSizeZ = 2048 } },
    })
  end)

  after_each(function()
    rawset(_G, "FS25_AutoDrive", nil)
    rawset(_G, "g_fillTypeManager", nil)
    rawset(_G, "g_currentMission", nil)
    VDT.AutoDrive.resetListening()
  end)

  describe("contributeObject", function()
    it("leaves a machine AutoDrive doesn't drive alone", function()
      local model = {}
      VDT.AutoDrive.contributeObject({}, model)
      assert.is_nil(model.autoDrive)
    end)

    it("reports a parked drive-to with only its destination", function()
      local model = { ai = { active = false } }
      VDT.AutoDrive.contributeObject(adVehicle(), model)
      assert.are.same({
        active = false,
        mode = "DRIVE_TO",
        modes = { "DRIVE_TO", "PICKUP_AND_DELIVER", "DELIVER_TO", "LOAD", "UNLOAD" },
        target = { id = 1, name = "Farm" },
        loops = 0,
        loopsDone = 0,
      }, model.autoDrive)
      assert.is_false(model.ai.active)
    end)

    it("adds the unload destination and the fill types in the modes that use them", function()
      local model = {}
      VDT.AutoDrive.contributeObject(adVehicle({ mode = 2, loops = 3, loopsDone = 1 }), model)
      local ad = model.autoDrive
      assert.are.equal("PICKUP_AND_DELIVER", ad.mode)
      assert.are.same({ id = 2, name = "Silo" }, ad.unloadTarget)
      assert.are.equal("WHEAT", ad.fillType)
      -- sorted by title, and UniversalAutoload's "all" entry is not a fill type
      assert.are.same({ { name = "BARLEY", title = "Barley" }, { name = "WHEAT", title = "Wheat" } }, ad.fillTypes)
      assert.are.equal(3, ad.loops)
      assert.are.equal(1, ad.loopsDone)
    end)

    it("reports the drive while active, and raises the AI lamp", function()
      local model = { ai = { active = false } }
      VDT.AutoDrive.contributeObject(
        adVehicle({ active = true, task = "Driving to Silo", remaining = 95.6, current = MARKERS[2] }),
        model
      )
      local ad = model.autoDrive
      assert.are.equal("Driving to Silo", ad.task)
      assert.are.equal(95, ad.remainingSeconds)
      assert.are.same({ id = 2, name = "Silo" }, ad.heading)
      assert.is_true(model.ai.active)
    end)

    it("treats a zero drive time as no estimate", function()
      local model = {}
      VDT.AutoDrive.contributeObject(adVehicle({ active = true }), model)
      assert.is_nil(model.autoDrive.remainingSeconds)
      assert.is_nil(model.autoDrive.task)
    end)

    it("leaves UNLOAD out of a locomotive's modes", function()
      local vehicle = adVehicle()
      vehicle.spec_locomotive = {}
      local model = {}
      VDT.AutoDrive.contributeObject(vehicle, model)
      assert.are.same({ "DRIVE_TO", "PICKUP_AND_DELIVER", "DELIVER_TO", "LOAD" }, model.autoDrive.modes)
    end)
  end)

  describe("destinations channel", function()
    it("is unavailable without the mod, and never reads a bare global", function()
      rawset(_G, "FS25_AutoDrive", nil)
      rawset(_G, "ADGraphManager", { getMapMarkers = error })
      assert.is_false(VDT.AutoDrive.isAvailable())
      rawset(_G, "ADGraphManager", nil)
    end)

    it("lists every marker with its folder and map position", function()
      assert.are.same({
        version = "1",
        destinations = {
          { id = 1, name = "Farm", posX = 0.5, posZ = 0 },
          { id = 2, name = "Silo", folder = "Grain", posX = 0.75, posZ = 0.5 },
        },
      }, VDT.AutoDrive.collect())
    end)

    it("subscribes to AutoDrive's destination listener once", function()
      VDT.AutoDrive.tick()
      local listener = FS25_AutoDrive.AutoDrive.listener
      assert.is_not_nil(listener)
      FS25_AutoDrive.AutoDrive.listener = nil
      VDT.AutoDrive.tick()
      assert.is_nil(FS25_AutoDrive.AutoDrive.listener)
    end)
  end)

  describe("commands", function()
    local sent
    before_each(function()
      sent = installAutoDrive()
    end)

    it("starts and stops through AutoDrive's own start/stop input, only when it would change", function()
      VDT.AutoDriveControl.setActive(adVehicle(), true, debugger)
      VDT.AutoDriveControl.setActive(adVehicle({ active = true }), true, debugger)
      VDT.AutoDriveControl.setActive(adVehicle({ active = true }), false, debugger)
      assert.are.same({ "input_start_stop", "input_start_stop" }, sent.inputs)
    end)

    it("refuses a start with no destination", function()
      VDT.AutoDriveControl.setActive(adVehicle({ first = false }), true, debugger)
      assert.are.same({}, sent.inputs)
    end)

    it("steps the mode button forward to the wanted mode", function()
      VDT.AutoDriveControl.setMode(adVehicle({ mode = 4 }), "PICKUP_AND_DELIVER", debugger)
      -- LOAD -> UNLOAD -> DRIVE_TO -> PICKUP_AND_DELIVER
      assert.are.same({ "input_silomode", "input_silomode", "input_silomode" }, sent.inputs)
    end)

    it("does nothing for the mode it is already in, or one the machine lacks", function()
      VDT.AutoDriveControl.setMode(adVehicle({ mode = 2 }), "PICKUP_AND_DELIVER", debugger)
      local train = adVehicle()
      train.spec_locomotive = {}
      VDT.AutoDriveControl.setMode(train, "UNLOAD", debugger)
      assert.are.same({}, sent.inputs)
    end)

    it("sets a destination by index, and refuses one whose name changed under the tap", function()
      local vehicle = adVehicle()
      VDT.AutoDriveControl.setTarget(vehicle, "target", 2, "Silo", debugger)
      VDT.AutoDriveControl.setTarget(vehicle, "unload", 1, "Farm", debugger)
      VDT.AutoDriveControl.setTarget(vehicle, "target", 2, "Old silo", debugger)
      assert.are.equal(2, #sent.events)
      assert.are.same({ "first", 2 }, { sent.events[1].kind, sent.events[1].a })
      assert.are.same({ "second", 1 }, { sent.events[2].kind, sent.events[2].a })
    end)

    it("sets only a fill type AutoDrive offers for the rig", function()
      VDT.AutoDriveControl.setFillType(adVehicle(), "BARLEY", debugger)
      VDT.AutoDriveControl.setFillType(adVehicle(), "DIESEL", debugger)
      assert.are.equal(1, #sent.events)
      assert.are.same({ "fillType", 3 }, { sent.events[1].kind, sent.events[1].a })
    end)

    it("works out the loop counter's presses, tens first, always clamping", function()
      assert.are.same({}, VDT.AutoDriveControl.loopPresses(5, 5))
      assert.are.same({
        { increment = true, fast = true },
        { increment = true, fast = false },
        { increment = true, fast = false },
      }, VDT.AutoDriveControl.loopPresses(0, 12))
      assert.are.same({ { increment = false, fast = false } }, VDT.AutoDriveControl.loopPresses(3, 2))

      VDT.AutoDriveControl.setLoops(adVehicle({ loops = 1 }), 3, debugger)
      assert.are.equal(2, #sent.events)
      for _, event in ipairs(sent.events) do
        assert.are.same({ "loop", true, false, true }, { event.kind, event.a, event.b, event.c })
      end
    end)

    it("refuses a loop counter out of range", function()
      VDT.AutoDriveControl.setLoops(adVehicle(), 100, debugger)
      VDT.AutoDriveControl.setLoops(adVehicle(), -1, debugger)
      assert.are.same({}, sent.events)
    end)
  end)
end)
