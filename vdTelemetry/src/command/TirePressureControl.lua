-- Executes the tyre-pressure command from the app -> mod back-channel: the write side of the Variable
-- Tire Pressure integration (src/integrations/VariableTirePressure.lua, issue #193).
--
-- Road or field pressure for the tractor, for everything hitched to it, or for both -- the mod's own
-- two keys, tractor (Alt+T) and implements (Alt+O). The whole-combination key (Alt+V) is not used:
-- "both" is the two keys in turn, each only where it is needed, which also covers the rig whose
-- tractor and trailer already disagree -- a toggle of the whole would flip both and keep them apart.
--
-- Through the mod's OWN key handler, called the way its multiplayer event calls it, rather than
-- vtpSetRoadMode: the handler is where the mod keeps everything a key press means -- the axles run
-- apart and brought back together, the sounds, and on a multiplayer client the event that hands the
-- press to the server, which runs the transitions. Re-implementing that would drift with every update.
--
-- The keys toggle, but the command is ABSOLUTE, so a resent command settles rather than flipping back:
-- a key is pressed only when the state it toggles from is not the one asked for. That state is the one
-- the mod's handler reads: the tractor's mode, and for the implements the FIRST one with the mod,
-- depth-first, which the handler toggles every implement away from.
--
-- Two cases a single press does not settle, both left to the next tap rather than guessed at:
--   * the tractor's axles apart, one at each pressure: the mod's tractor key brings the stray axle to
--     the other one's mode first. A tap that asked for the other mode is then one tap short.
--   * implements that disagree among themselves, the first already right: its key would turn them all
--     the wrong way. On the server (single player, the host) the stragglers are set directly, the call
--     the mod's handler makes for each implement; a multiplayer client has no such call, and logs.
--
-- The class is looked up off the vehicle's own type, under whatever folder name the mod was installed
-- as, so nothing is read from the mod's environment.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.TirePressureControl = {}

-- The mod's input actions (its modDesc), which its handler dispatches on.
VDT.TirePressureControl.ACTION_TRACTOR = "VTP_SET_TIRE_PRESSURE_TRACTOR"
VDT.TirePressureControl.ACTION_TOOLS = "VTP_SET_TIRE_PRESSURE_TOOLS"

local SCOPES = { all = true, vehicle = true, implements = true }

---The mod's specialization class, off the vehicle type's own table. Keyed by the mod-qualified name
---(`<modFolder>.variableTirePressure`), so matched on the suffix.
---@param object table
---@return table|nil
local function specClass(object)
  local byName = object.specializationsByName
  if type(byName) ~= "table" then
    return nil
  end
  for name, class in pairs(byName) do
    if
      type(name) == "string"
      and (name == "variableTirePressure" or name:find("%.variableTirePressure$") ~= nil)
      and type(class) == "table"
      and type(class.actionEventTogglePressure) == "function"
    then
      return class
    end
  end
  return nil
end

---Every implement with the mod working on it, depth-first: the order the mod's handler walks them in,
---so the first is the one it toggles from.
---@param object table
---@param out table[]|nil
---@return table[]
function VDT.TirePressureControl.implementsOf(object, out)
  out = out or {}
  if type(object.getAttachedImplements) ~= "function" then
    return out
  end
  for _, implement in ipairs(object:getAttachedImplements() or {}) do
    local child = implement ~= nil and implement.object or nil
    if child ~= nil then
      if VDT.VariableTirePressure.specOf(child) ~= nil then
        out[#out + 1] = child
      end
      VDT.TirePressureControl.implementsOf(child, out)
    end
  end
  return out
end

---Press one of the mod's keys, the way its own multiplayer event replays a press.
local function press(class, vehicle, action, debugger)
  class.actionEventTogglePressure(vehicle, action, 0, InputAction.CALLBACK_STATE_START, false)
  debugger:debug("setTirePressure: pressed %s", action)
end

---@param vehicle Vehicle the controlled vehicle
---@param scope string|nil all | vehicle | implements
---@param road boolean|nil true for road pressure, false for field
---@param debugger GrisuDebug
function VDT.TirePressureControl.setTirePressure(vehicle, scope, road, debugger)
  if road == nil or not SCOPES[scope] then
    debugger:warn("setTirePressure: bad scope '%s' or no mode given, ignoring", tostring(scope))
    return
  end
  local class = specClass(vehicle)
  if class == nil then
    debugger:debug("setTirePressure: Variable Tire Pressure is not on this vehicle, ignoring")
    return
  end
  local wanted = road and "ROAD" or "FIELD"

  if scope ~= "implements" then
    if VDT.VariableTirePressure.specOf(vehicle) == nil then
      debugger:debug("setTirePressure: the vehicle's own tyres have no inflation system")
    elseif VDT.VariableTirePressure.modeOf(vehicle) ~= wanted then
      press(class, vehicle, VDT.TirePressureControl.ACTION_TRACTOR, debugger)
    end
  end

  if scope ~= "vehicle" then
    local implements = VDT.TirePressureControl.implementsOf(vehicle)
    local first = implements[1]
    if first == nil then
      debugger:debug("setTirePressure: no implement with an inflation system")
    elseif (VDT.VariableTirePressure.specOf(first).isRoadMode == true) ~= road then
      press(class, vehicle, VDT.TirePressureControl.ACTION_TOOLS, debugger)
    else
      for i = 2, #implements do
        local implement = implements[i]
        if (VDT.VariableTirePressure.specOf(implement).isRoadMode == true) ~= road then
          if vehicle.isServer == true and type(implement.vtpSetRoadMode) == "function" then
            implement:vtpSetRoadMode(road, false)
            debugger:debug("setTirePressure: set a straggling implement to %s", wanted)
          else
            debugger:debug("setTirePressure: an implement disagrees and only the server can set it alone")
          end
        end
      end
    end
  end
end

VDT.CommandRegistry.register("setTirePressure", {
  parse = function(xml, key)
    return { scope = xml:getString(key .. "#scope"), road = xml:getBool(key .. "#road") }
  end,
  execute = function(vehicle, params, debugger)
    VDT.TirePressureControl.setTirePressure(vehicle, params.scope, params.road, debugger)
  end,
})
