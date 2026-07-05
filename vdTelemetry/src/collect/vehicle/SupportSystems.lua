-- Collects the driver-assist subtrees of a vehicle: gps (auto steering), ai (field work), and
-- cruise control. Vehicle-only.
-- Namespaced under VDT.* (see aspects/TurnOn.lua). Each is an independent model key, so they are
-- separate collectors the orchestrator maps to model.gps / model.ai / model.cruiseControl.

VDT = VDT or {}
VDT.SupportSystems = {}

---@param vehicle Vehicle
---@return GpsModel|nil nil unless the vehicle has both AI mode selection and auto steering
function VDT.SupportSystems.collectGps(vehicle)
  local aiMSpec = vehicle.spec_aiModeSelection
  local aiSSpec = vehicle.spec_aiAutomaticSteering
  if aiMSpec == nil or aiSSpec == nil then
    return nil
  end

  return {
    enabled = vehicle:getAIModeSelection() == AIModeSelection.MODE.STEERING_ASSIST,
    active = aiSSpec.steeringEnabled,
    heading = math.floor(ValueMapper.calculateHeading(vehicle)),
    headingUnit = "°",
  }
end

---@param vehicle Vehicle
---@return AiModel|nil nil when the vehicle has no field-work concept
function VDT.SupportSystems.collectAi(vehicle)
  if vehicle.getIsFieldWorkActive == nil then
    return nil
  end
  local aiDSpec = vehicle.spec_aiDrivable
  return {
    active = vehicle:getIsFieldWorkActive() or (aiDSpec ~= nil and aiDSpec.isRunning == true),
  }
end

---@param vehicle Vehicle
---@return CruiseControlModel|nil nil when the vehicle isn't drivable
function VDT.SupportSystems.collectCruiseControl(vehicle)
  local dSpec = vehicle.spec_drivable
  if dSpec == nil then
    return nil
  end
  local cruiseControl = dSpec.cruiseControl
  return {
    -- cruiseControl.speed is km/h; keep 2-decimal precision like speed (mods allow sub-1 steps).
    targetSpeed = tonumber(ValueMapper.mapFloat(cruiseControl.speed)),
    active = cruiseControl.state ~= Drivable.CRUISECONTROL_STATE_OFF,
  }
end
