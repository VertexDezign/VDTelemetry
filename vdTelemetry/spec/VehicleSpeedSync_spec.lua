-- Unit tests for the Vehicle Speed Sync integration (src/integrations/VehicleSpeedSync.lua).
--
-- Run with `busted` from the vdTelemetry/ directory. The objects are shaped like the mod's own: a
-- `spec_vehicleSpeedSync` alias set in its onLoad, and the two getters it registers on the vehicle
-- type -- which the integration calls rather than reading the spec's fields.

if VDT == nil or VDT.VehicleSpeedSync == nil then
  dofile("src/integrations/VehicleSpeedSync.lua")
end

---A machine with the mod's spec on it, synced or not, waiting or not.
local function syncVehicle(active, waiting)
  return {
    spec_vehicleSpeedSync = { speedSyncActive = active, isWaiting = waiting },
    vehicleSpeedSyncGetSyncedSpeedActive = function()
      return active
    end,
    vehicleSpeedSyncGetSyncedSpeedWaiting = function()
      return waiting
    end,
  }
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
    assert.are.same({ active = false, waiting = false }, model.speedSync)
  end)

  it("reports a sync, and a sync still waiting for the other machine to move", function()
    local model = {}
    VDT.VehicleSpeedSync.contributeObject(syncVehicle(true, false), model)
    assert.are.same({ active = true, waiting = false }, model.speedSync)

    VDT.VehicleSpeedSync.contributeObject(syncVehicle(true, true), model)
    assert.are.same({ active = true, waiting = true }, model.speedSync)
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
end)
