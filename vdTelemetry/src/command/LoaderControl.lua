-- Executes the loader reference command from the app -> mod back-channel (issue #169): "where this
-- tool is now is level" (on = true) or "forget that" (on = false). The write side of the `reference`
-- the VDT.LoaderTool aspect carries.
--
-- Not an engine call at all -- the reference is VDTelemetry's own data (store/LoaderReferences.lua),
-- so there is no vdAI function to prefer and nothing for the game to sync. It is client-local like the
-- channel that carries it.
--
-- Addressed by the diagram's node path, through VDT.SelectionControl.resolve, rather than by a
-- ControlTarget: a front loader's tool is two levels down (tractor -> loader -> tool), past what
-- FRONT/BACK can name, and the panel that offers the button already holds the path.
--
-- What is recorded is measured HERE, a tick fresher than the reading the app drew the button against,
-- by the same collector the export uses -- so the zero is the pose the tool was in when the tap
-- arrived, and the subtraction the panel does is against numbers taken the same way.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.LoaderControl = {}

---@param vehicle Vehicle the controlled vehicle
---@param node string the diagram's node path to the tool
---@param on boolean true records the tool's current pose as level, false forgets it
---@param debugger GrisuDebug
function VDT.LoaderControl.setReference(vehicle, node, on, debugger)
  local object = VDT.SelectionControl.resolve(vehicle, node, debugger)
  if object == nil then
    return
  end

  local key = VDT.LoaderReferences.keyOf(object)
  if key == nil then
    debugger:debug("setLoaderReference: %s has no config file to key a reference by", node)
    return
  end

  if not on then
    VDT.LoaderReferences.clear(key, debugger)
    debugger:debug("setLoaderReference(%s): cleared %s", node, key)
    return
  end

  local reading = VDT.LoaderTool.collect(object)
  if reading == nil then
    -- Unhitched, or hitched somewhere that is not a loader's tool joint, between the export and the
    -- tap. There is nothing to call level.
    debugger:debug("setLoaderReference: %s is not on a loader any more", node)
    return
  end

  VDT.LoaderReferences.set(key, { pitch = reading.pitch, distance = reading.distance }, debugger)
  debugger:debug(
    "setLoaderReference(%s): %s level at pitch %s, distance %s",
    node,
    key,
    tostring(reading.pitch),
    tostring(reading.distance)
  )
end

VDT.CommandRegistry.register("setLoaderReference", {
  parse = function(xml, key)
    return {
      node = xml:getString(key .. "#node"),
      on = xml:getBool(key .. "#on", false),
    }
  end,
  execute = function(vehicle, params, debugger)
    VDT.LoaderControl.setReference(vehicle, params.node, params.on, debugger)
  end,
})
