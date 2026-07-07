-- Executes light commands from the app -> mod back-channel. The inverse of the read-only
-- VDT.Lights collector: it maps an absolute target ("beacon on", "highBeam off", "turn left") onto
-- the FS25 engine setters. Absolute (not toggle) on purpose — the command channel is lossy/async, so
-- idempotent set-to-state is self-correcting where a relative toggle would desync (see ROADMAP #4).
--
-- Reads the engine global `Lights` (LIGHT_TYPE_*, TURNLIGHT_*), so it must stay namespaced under
-- VDT.* — a bare `Lights = {}` would clobber the specialization (see collect/vehicle/Lights.lua).

VDT = VDT or {}
VDT.LightControl = {}

-- Wire `light` token -> the Lights.LIGHT_TYPE_* index whose bit lives in spec_lights.lightsTypesMask.
-- `beacon` is handled separately (it's a boolean, not a mask bit).
local function lightTypeFor(token)
  if token == "lowBeam" then
    return Lights.LIGHT_TYPE_DEFAULT
  elseif token == "highBeam" then
    return Lights.LIGHT_TYPE_HIGHBEAM
  elseif token == "workFront" then
    return Lights.LIGHT_TYPE_WORK_FRONT
  elseif token == "workBack" then
    return Lights.LIGHT_TYPE_WORK_BACK
  end
  return nil
end

-- Wire `state` token -> the Lights.TURNLIGHT_* state. Indicators are one enum (off/left/right/hazard),
-- not three booleans — the collector's left/right/hazard are derived from this single state.
local function turnStateFor(token)
  if token == "off" then
    return Lights.TURNLIGHT_OFF
  elseif token == "left" then
    return Lights.TURNLIGHT_LEFT
  elseif token == "right" then
    return Lights.TURNLIGHT_RIGHT
  elseif token == "hazard" then
    return Lights.TURNLIGHT_HAZARD
  end
  return nil
end

---Apply an absolute light state. `beacon` sets beaconLightsVisibility; the four beam/work lights are
---bits in lightsTypesMask, so we read-modify-write the single bit to preserve the other lights.
---@param vehicle Vehicle
---@param light string one of beacon|lowBeam|highBeam|workFront|workBack
---@param on boolean
---@param debugger GrisuDebug
function VDT.LightControl.setLight(vehicle, light, on, debugger)
  if vehicle.spec_lights == nil then
    debugger:debug("setLight: vehicle has no lights spec, ignoring")
    return
  end

  if light == "beacon" then
    vehicle:setBeaconLightsVisibility(on)
    debugger:debug("setLight beacon=%s", tostring(on))
    return
  end

  local lightType = lightTypeFor(light)
  if lightType == nil then
    debugger:warn("setLight: unknown light '%s'", tostring(light))
    return
  end

  -- bits are distinct powers of two, so add/subtract flips exactly one bit — no bitOR/bitNOT needed
  -- (the engine exposes bitAND, which the collector already relies on).
  local bit = 2 ^ lightType
  local mask = vehicle.spec_lights.lightsTypesMask
  if on then
    if bitAND(mask, bit) == 0 then
      mask = mask + bit
    end
  else
    if bitAND(mask, bit) ~= 0 then
      mask = mask - bit
    end
  end
  vehicle:setLightsTypesMask(mask)
  debugger:debug("setLight %s=%s -> mask %d", tostring(light), tostring(on), mask)
end

---Apply an absolute turn-light state (off/left/right/hazard).
---@param vehicle Vehicle
---@param state string
---@param debugger GrisuDebug
function VDT.LightControl.setTurnLight(vehicle, state, debugger)
  if vehicle.spec_lights == nil then
    debugger:debug("setTurnLight: vehicle has no lights spec, ignoring")
    return
  end
  local turnState = turnStateFor(state)
  if turnState == nil then
    debugger:warn("setTurnLight: unknown state '%s'", tostring(state))
    return
  end
  vehicle:setTurnLightState(turnState)
  debugger:debug("setTurnLight state=%s", tostring(state))
end

-- Command handlers (see CommandRegistry): parse each light command off its XML element and run it.
-- Kept next to the setters so a light command's schema lives with its logic, not in the reader.
VDT.CommandRegistry.register("setLight", {
  parse = function(xml, key)
    return {
      light = xml:getString(key .. "#light"),
      on = xml:getBool(key .. "#on", false),
    }
  end,
  execute = function(vehicle, params, debugger)
    VDT.LightControl.setLight(vehicle, params.light, params.on, debugger)
  end,
})

VDT.CommandRegistry.register("setTurnLight", {
  parse = function(xml, key)
    return { state = xml:getString(key .. "#state") }
  end,
  execute = function(vehicle, params, debugger)
    VDT.LightControl.setTurnLight(vehicle, params.state, debugger)
  end,
})
