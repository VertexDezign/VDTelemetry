-- Unit tests for the Vehicle Speed Sync integration (src/integrations/VehicleSpeedSync.lua).
--
-- Run with `busted` from the vdTelemetry/ directory. The objects are shaped like the mod's own: a
-- `spec_vehicleSpeedSync` alias set in its onLoad, and the two getters it registers on the vehicle
-- type -- which the integration calls rather than reading the spec's fields.

if VDT == nil or VDT.VehicleSpeedSync == nil then
  dofile("src/integrations/VehicleSpeedSync.lua")
end

---A machine with the mod's spec on it, synced or not, waiting or not. `over` sets spec fields: by
---default the key is registered (toggleSpeedSyncEventId) and another machine is in range.
local function syncVehicle(active, waiting, over, partner)
  local spec = { speedSyncActive = active, isWaiting = waiting, setRange = 25, toggleSpeedSyncEventId = 7 }
  for k, v in pairs(over or {}) do
    spec[k] = v
  end
  if partner == nil then
    partner = { name = "combine" }
  end
  local vehicle = {
    spec_vehicleSpeedSync = spec,
    searches = 0,
    synced = nil,
    vehicleSpeedSyncGetSyncedSpeedActive = function()
      return active
    end,
    vehicleSpeedSyncGetSyncedSpeedWaiting = function()
      return waiting
    end,
  }
  vehicle.vehicleSpeedSyncFindVehicleInRange = function(self, range)
    self.searches = self.searches + 1
    assert.are.equal(25, range)
    return partner or nil
  end
  vehicle.vehicleSpeedSyncSetSyncedSpeedState = function(self, state, inRange)
    self.synced = { state = state, partner = inRange }
  end
  return vehicle
end

describe("VehicleSpeedSync.contributeObject", function()
  it("leaves the model alone without the mod", function()
    local model = {}
    VDT.VehicleSpeedSync.contributeObject({}, model)
    assert.is_nil(model.speedSync)
  end)

  it("reports a machine that has the mod but is not synced", function()
    local model = {}
    VDT.VehicleSpeedSync.contributeObject(syncVehicle(false, false), model)
    assert.are.same({ active = false, waiting = false, available = true }, model.speedSync)
  end)

  it("reports a sync, and a sync still waiting for the other machine to move", function()
    local model = {}
    VDT.VehicleSpeedSync.contributeObject(syncVehicle(true, false), model)
    assert.are.same({ active = true, waiting = false, available = true }, model.speedSync)

    VDT.VehicleSpeedSync.contributeObject(syncVehicle(true, true), model)
    assert.are.same({ active = true, waiting = true, available = true }, model.speedSync)
  end)

  it("never reports waiting without a sync", function()
    local model = {}
    VDT.VehicleSpeedSync.contributeObject(syncVehicle(false, true), model)
    assert.is_false(model.speedSync.waiting)
  end)

  it("skips a spec whose getters are missing (a renamed or reworked mod)", function()
    local model = {}
    VDT.VehicleSpeedSync.contributeObject({ spec_vehicleSpeedSync = {} }, model)
    assert.is_nil(model.speedSync)
  end)

  it("is not available without the mod's key (settings off, not entered, menu open)", function()
    local model = {}
    local vehicle = syncVehicle(false, false)
    vehicle.spec_vehicleSpeedSync.toggleSpeedSyncEventId = nil
    VDT.VehicleSpeedSync.contributeObject(vehicle, model)
    assert.is_false(model.speedSync.available)
    assert.are.equal(0, vehicle.searches)
  end)

  it("is not available with nothing in range", function()
    local model = {}
    VDT.VehicleSpeedSync.contributeObject(syncVehicle(false, false, nil, false), model)
    assert.is_false(model.speedSync.available)
  end)

  it("does not search while synced: switching off needs no partner", function()
    local model = {}
    local vehicle = syncVehicle(true, false)
    VDT.VehicleSpeedSync.contributeObject(vehicle, model)
    assert.is_true(model.speedSync.available)
    assert.are.equal(0, vehicle.searches)
  end)
end)

describe("SpeedSyncControl.setSpeedSync", function()
  local debugger = {
    debug = function() end,
    warn = function() end,
  }

  setup(function()
    if VDT.CommandRegistry == nil then
      dofile("src/command/CommandRegistry.lua")
    end
    dofile("src/command/SpeedSyncControl.lua")
  end)

  it("syncs to the machine the mod's own search finds", function()
    local vehicle = syncVehicle(false, false)
    VDT.SpeedSyncControl.setSpeedSync(vehicle, true, debugger)
    assert.are.same({ state = true, partner = { name = "combine" } }, vehicle.synced)
  end)

  it("refuses where the key would: nothing in range, or no key", function()
    local lonely = syncVehicle(false, false, nil, false)
    VDT.SpeedSyncControl.setSpeedSync(lonely, true, debugger)
    assert.is_nil(lonely.synced)

    local keyless = syncVehicle(false, false)
    keyless.spec_vehicleSpeedSync.toggleSpeedSyncEventId = nil
    VDT.SpeedSyncControl.setSpeedSync(keyless, true, debugger)
    assert.is_nil(keyless.synced)
  end)

  it("switches off, and is idempotent either way", function()
    local synced = syncVehicle(true, false)
    VDT.SpeedSyncControl.setSpeedSync(synced, false, debugger)
    assert.are.same({ state = false }, synced.synced)

    local again = syncVehicle(true, false)
    VDT.SpeedSyncControl.setSpeedSync(again, true, debugger)
    assert.is_nil(again.synced)
    assert.are.equal(0, again.searches)
  end)

  it("ignores a vehicle without the mod", function()
    VDT.SpeedSyncControl.setSpeedSync({}, true, debugger)
  end)

  it("parses the absolute state off the command", function()
    local handler = VDT.CommandRegistry.get("setSpeedSync")
    local xml = {
      getBool = function(_, key)
        assert.are.equal("c#active", key)
        return true
      end,
    }
    assert.are.same({ active = true }, handler.parse(xml, "c"))
  end)
end)
