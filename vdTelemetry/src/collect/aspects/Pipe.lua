-- Aspect collector: pipe state. Applies to any object (vehicle or implement).
-- Mirrors VDTelemetry:populateXMLFromPipe. Namespaced under VDT.* (see TurnOn.lua).

VDT = VDT or {}
VDT.Pipe = {}

---@param object table
---@return string|nil mapped pipe state, or nil when the object has no pipe
function VDT.Pipe.collect(object)
  if object.getCurrentPipeState == nil or object:getCurrentPipeState() == nil then
    return nil
  end
  return ValueMapper.mapPipeState(object:getCurrentPipeState())
end
