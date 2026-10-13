-- Executes the AutoDrive commands from the app -> mod back-channel: the write side of
-- src/integrations/AutoDrive.lua (issue #97).
--
-- Every write goes through AutoDrive's OWN events, the ones its HUD sends: its state module's setters
-- only take effect on the server (the module streams server -> client and nothing back), so calling
-- them on a multiplayer client would change a copy nobody drives with. Two paths:
--
--   * start/stop and the mode go through ADInputManager:onInputCall, which is what a key press or a HUD
--     button does -- it sends AutoDriveInputEventEvent and the server runs the input;
--   * destination, unload destination, fill type and loop counter go through
--     AutoDriveHudInputEventEvent, which is what picking an entry in the HUD's lists does.
--
-- All of it is ABSOLUTE on the wire, as every command here is, so a resent command settles rather
-- than stepping again. Where AutoDrive only offers a step (the mode button, the loop counter's +/-),
-- the steps are worked out here from the state the command finds, and are no-ops once it is reached.
--
-- A destination is sent with its NAME as well as its index: AutoDrive's marker list is an array, and
-- deleting a marker shifts every index after it. A tap made against the old list would otherwise send
-- the machine to whatever marker now holds that index; with the name, the command is refused instead.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.AutoDriveControl = {}

-- The loop counter's range: ADStateModule:changeLoopCounter keeps it in 0..99, 0 meaning endless.
VDT.AutoDriveControl.MAX_LOOPS = 99

---The vehicle's state module and AutoDrive's environment, or nil (logged) when either is missing.
local function resolve(vehicle, command, debugger)
  local state = VDT.AutoDrive.stateOf(vehicle)
  local env = VDT.AutoDrive.env()
  if state == nil or env == nil then
    debugger:debug("%s: AutoDrive does not drive this vehicle, ignoring", command)
    return nil, nil
  end
  return state, env
end

---Run one of AutoDrive's inputs the way its key or HUD button does.
local function input(env, vehicle, name)
  env.ADInputManager:onInputCall(vehicle, name)
end

---@param vehicle Vehicle
---@param active boolean|nil
---@param debugger GrisuDebug
function VDT.AutoDriveControl.setActive(vehicle, active, debugger)
  local state, env = resolve(vehicle, "setAutoDriveActive", debugger)
  if state == nil or active == nil then
    return
  end
  if (state:isActive() == true) == active then
    debugger:debug("setAutoDriveActive: already %s", tostring(active))
    return
  end
  -- input_start_stop's own guard, checked here too so a refused start is logged rather than silent:
  -- there is nothing to drive to without a network and a destination.
  if active and (env.ADGraphManager:getWayPointById(1) == nil or state:getFirstMarker() == nil) then
    debugger:debug("setAutoDriveActive: no route network or no destination, ignoring")
    return
  end
  input(env, vehicle, "input_start_stop")
  debugger:debug("setAutoDriveActive(%s)", tostring(active))
end

---@param vehicle Vehicle
---@param mode string a mode token (VDT.AutoDrive.MODES)
---@param debugger GrisuDebug
function VDT.AutoDriveControl.setMode(vehicle, mode, debugger)
  local state, env = resolve(vehicle, "setAutoDriveMode", debugger)
  if state == nil then
    return
  end
  local modes = VDT.AutoDrive.modesFor(vehicle)
  local current, wanted
  for i, token in ipairs(modes) do
    if token == VDT.AutoDrive.modeToken(state:getMode()) then
      current = i
    end
    if token == mode then
      wanted = i
    end
  end
  if wanted == nil then
    debugger:debug("setAutoDriveMode: '%s' is not a mode of this vehicle, ignoring", tostring(mode))
    return
  end
  if current == nil then
    debugger:debug("setAutoDriveMode: the vehicle is in a mode we don't know, ignoring")
    return
  end
  -- Forward only: the mode button's own direction, which cycles with the same skip a train makes.
  -- (Its previous-mode input mis-handles that skip in this version.) At most four presses.
  --
  -- Counted from the mode this machine last heard from the server. On a multiplayer client a second
  -- mode command landing before the first one's presses have come back round would count from the
  -- old mode and overshoot; the terminal shows the mode the server reports, so a tap corrects it.
  local steps = (wanted - current) % #modes
  for _ = 1, steps do
    input(env, vehicle, "input_silomode")
  end
  debugger:debug("setAutoDriveMode(%s): %d step(s)", mode, steps)
end

---@param vehicle Vehicle
---@param slot string "target" | "unload"
---@param id number|nil the marker's index
---@param name string|nil the marker's name, as the terminal saw it
---@param debugger GrisuDebug
function VDT.AutoDriveControl.setTarget(vehicle, slot, id, name, debugger)
  local state, env = resolve(vehicle, "setAutoDriveTarget", debugger)
  if state == nil then
    return
  end
  local marker = id ~= nil and env.ADGraphManager:getMapMarkerById(id) or nil
  if marker == nil or tostring(marker.name) ~= tostring(name) then
    debugger:debug("setAutoDriveTarget: no marker %s named '%s' any more, ignoring", tostring(id), tostring(name))
    return
  end
  local event = env.AutoDriveHudInputEventEvent
  if slot == "target" then
    if state:getFirstMarkerId() ~= id then
      event:sendFirstMarkerEvent(vehicle, id)
    end
  elseif slot == "unload" then
    if state:getSecondMarkerId() ~= id then
      event:sendSecondMarkerEvent(vehicle, id)
    end
  else
    debugger:warn("setAutoDriveTarget: unknown slot '%s', ignoring", tostring(slot))
    return
  end
  debugger:debug("setAutoDriveTarget(%s, %d '%s')", slot, id, tostring(name))
end

---@param vehicle Vehicle
---@param fillType string|nil the engine's fill type name
---@param debugger GrisuDebug
function VDT.AutoDriveControl.setFillType(vehicle, fillType, debugger)
  local state, env = resolve(vehicle, "setAutoDriveFillType", debugger)
  if state == nil then
    return
  end
  -- Only one AutoDrive offers for this rig: its setFillType ignores the rest on the server anyway,
  -- and checking here keeps the log honest about why nothing happened.
  local offered = false
  for _, entry in ipairs(VDT.AutoDrive.fillTypesFor(vehicle) or {}) do
    offered = offered or entry.name == fillType
  end
  local index = offered and g_fillTypeManager:getFillTypeIndexByName(fillType) or nil
  if index == nil then
    debugger:debug("setAutoDriveFillType: '%s' is not offered for this rig, ignoring", tostring(fillType))
    return
  end
  if state:getFillType() ~= index then
    env.AutoDriveHudInputEventEvent:sendFillTypeEvent(vehicle, index)
  end
  debugger:debug("setAutoDriveFillType(%s)", fillType)
end

---The +/- presses that take the loop counter from `from` to `to`: tens first, then ones. Every press
---is sent with the "wheel" flag, which makes AutoDrive clamp at 0 and 99 instead of wrapping round.
---@param from number
---@param to number
---@return table[] presses { increment = boolean, fast = boolean }
function VDT.AutoDriveControl.loopPresses(from, to)
  local presses = {}
  local delta = to - from
  local increment = delta > 0
  delta = math.abs(delta)
  for _ = 1, math.floor(delta / 10) do
    presses[#presses + 1] = { increment = increment, fast = true }
  end
  for _ = 1, delta % 10 do
    presses[#presses + 1] = { increment = increment, fast = false }
  end
  return presses
end

---@param vehicle Vehicle
---@param loops number|nil 0 (endless) .. MAX_LOOPS
---@param debugger GrisuDebug
function VDT.AutoDriveControl.setLoops(vehicle, loops, debugger)
  local state, env = resolve(vehicle, "setAutoDriveLoops", debugger)
  if state == nil then
    return
  end
  if loops == nil or loops < 0 or loops > VDT.AutoDriveControl.MAX_LOOPS then
    debugger:debug("setAutoDriveLoops: %s is out of range, ignoring", tostring(loops))
    return
  end
  local presses = VDT.AutoDriveControl.loopPresses(tonumber(state:getLoopCounter()) or 0, loops)
  for _, press in ipairs(presses) do
    env.AutoDriveHudInputEventEvent:sendChangeLoopCounterEvent(vehicle, press.increment, press.fast, true)
  end
  debugger:debug("setAutoDriveLoops(%d): %d press(es)", loops, #presses)
end

VDT.CommandRegistry.register("setAutoDriveActive", {
  parse = function(xml, key)
    return { active = xml:getBool(key .. "#active") }
  end,
  execute = function(vehicle, params, debugger)
    VDT.AutoDriveControl.setActive(vehicle, params.active, debugger)
  end,
})

VDT.CommandRegistry.register("setAutoDriveMode", {
  parse = function(xml, key)
    return { mode = xml:getString(key .. "#mode") }
  end,
  execute = function(vehicle, params, debugger)
    VDT.AutoDriveControl.setMode(vehicle, params.mode, debugger)
  end,
})

VDT.CommandRegistry.register("setAutoDriveTarget", {
  parse = function(xml, key)
    return {
      slot = xml:getString(key .. "#slot"),
      -- `markerId`, not `id`: that attribute is the command's own (see CommandChannel).
      id = xml:getInt(key .. "#markerId"),
      name = xml:getString(key .. "#name"),
    }
  end,
  execute = function(vehicle, params, debugger)
    VDT.AutoDriveControl.setTarget(vehicle, params.slot, params.id, params.name, debugger)
  end,
})

VDT.CommandRegistry.register("setAutoDriveFillType", {
  parse = function(xml, key)
    return { fillType = xml:getString(key .. "#fillType") }
  end,
  execute = function(vehicle, params, debugger)
    VDT.AutoDriveControl.setFillType(vehicle, params.fillType, debugger)
  end,
})

VDT.CommandRegistry.register("setAutoDriveLoops", {
  parse = function(xml, key)
    return { loops = xml:getInt(key .. "#loops") }
  end,
  execute = function(vehicle, params, debugger)
    VDT.AutoDriveControl.setLoops(vehicle, params.loops, debugger)
  end,
})
