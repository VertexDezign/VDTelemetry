---@class Vehicle
---@field spec_lights table|nil the Lights specialization state (nil when the vehicle has no lights)
Vehicle = {}

---@param lightsTypesMask number bitmask of active Lights.LIGHT_TYPE_* bits
function Vehicle:setLightsTypesMask(lightsTypesMask) end

---@param visibility boolean
function Vehicle:setBeaconLightsVisibility(visibility) end

---@param state number one of Lights.TURNLIGHT_OFF/LEFT/RIGHT/HAZARD
function Vehicle:setTurnLightState(state) end

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
