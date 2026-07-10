---@class Vehicle
---@field spec_lights table|nil the Lights specialization state (nil when the vehicle has no lights)
---@field spec_motorized table|nil the Motorized specialization state (nil when the vehicle has no motor)
---@field spec_drivable table|nil the Drivable specialization state (nil when the vehicle isn't drivable)
Vehicle = {}

-- Engine + cruise setters used by the command back-channel (MotorControl / CruiseControl).
---@param noEventSend boolean|nil
function Vehicle:startMotor(noEventSend) end
---@param noEventSend boolean|nil
function Vehicle:stopMotor(noEventSend) end
---@param state number one of Drivable.CRUISECONTROL_STATE_OFF/ACTIVE/FULL
---@param noEventSend boolean|nil
function Vehicle:setCruiseControlState(state, noEventSend) end
---@param speed number target km/h
---@param speedReverse number|nil target reverse km/h
function Vehicle:setCruiseControlMaxSpeed(speed, speedReverse) end

---@param lightsTypesMask number bitmask of active Lights.LIGHT_TYPE_* bits
function Vehicle:setLightsTypesMask(lightsTypesMask) end

---@param visibility boolean
function Vehicle:setBeaconLightsVisibility(visibility) end

---@param state number one of Lights.TURNLIGHT_OFF/LEFT/RIGHT/HAZARD
function Vehicle:setTurnLightState(state) end

-- FS25_additionalInputs functions (registered on the vehicle type by that mod) for the command
-- back-channel: vdAI<Action><Position>(on) controls the controlled vehicle itself or its front/back
-- attached implements. Each takes an absolute on/off state (see ImplementControl.lua).
---@param on boolean
function Vehicle:vdAILowerVehicle(on) end
---@param on boolean
function Vehicle:vdAILowerFront(on) end
---@param on boolean
function Vehicle:vdAILowerBack(on) end
---@param on boolean
function Vehicle:vdAIFoldVehicle(on) end
---@param on boolean
function Vehicle:vdAIFoldFront(on) end
---@param on boolean
function Vehicle:vdAIFoldBack(on) end
---@param on boolean
function Vehicle:vdAIActivateVehicle(on) end
---@param on boolean
function Vehicle:vdAIActivateFront(on) end
---@param on boolean
function Vehicle:vdAIActivateBack(on) end

function Vehicle:addActionEvent(
  actionEventsTable,
  inputAction,
  target,
  callback,
  triggerUp,
  triggerDown,
  triggerAlways,
  startActive,
  callbackState,
  customIconName,
  ignoreCollisions,
  reportAnyDeviceCollision
)
end
