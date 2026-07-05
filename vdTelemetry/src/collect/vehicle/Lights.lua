-- Collects the lights subtree of a vehicle. Vehicle-only.
-- Namespaced under VDT.* — critically here, since the module reads the FS25 engine global `Lights`
-- (Lights.TURNLIGHT_LEFT, Lights.LIGHT_TYPE_*); a bare `Lights = {}` module would clobber it.

VDT = VDT or {}
VDT.Lights = {}

---@param vehicle Vehicle
---@return LightsModel|nil nil when the vehicle has no lights spec
function VDT.Lights.collect(vehicle)
  local spec = vehicle.spec_lights
  if spec == nil then
    return nil
  end

  return {
    indicator = {
      left = spec.turnLightState == Lights.TURNLIGHT_LEFT or spec.turnLightState == Lights.TURNLIGHT_HAZARD,
      right = spec.turnLightState == Lights.TURNLIGHT_RIGHT or spec.turnLightState == Lights.TURNLIGHT_HAZARD,
      hazard = spec.turnLightState == Lights.TURNLIGHT_HAZARD,
    },
    beaconLight = next(spec.beaconLights) ~= nil and spec.beaconLightsActive,
    light = {
      lowBeam = bitAND(spec.lightsTypesMask, 2 ^ Lights.LIGHT_TYPE_DEFAULT) ~= 0,
      highBeam = bitAND(spec.lightsTypesMask, 2 ^ Lights.LIGHT_TYPE_HIGHBEAM) ~= 0,
    },
    workLight = {
      front = bitAND(spec.lightsTypesMask, 2 ^ Lights.LIGHT_TYPE_WORK_FRONT) ~= 0,
      back = bitAND(spec.lightsTypesMask, 2 ^ Lights.LIGHT_TYPE_WORK_BACK) ~= 0,
    },
  }
end
