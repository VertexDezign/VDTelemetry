-- Aspect collector: pipe state. Applies to any object (vehicle or implement).
-- Namespaced under VDT.* (see TurnOn.lua).
--
-- Pipes are not just in/out: spec.numStates comes from the XML and is often > 2 (an auger wagon with
-- several unloading positions). `current` is 0 while the pipe is moving and 1..numStates once it
-- settles; `target` is where it is heading, so current ~= target means "still travelling, towards
-- target". `state` is the coarse label for consumers that only care in/out/moving.

VDT = VDT or {}
VDT.Pipe = {}

---@param object table
---@return PipeModel|nil nil when the object has no pipe
function VDT.Pipe.collect(object)
  local spec = object.spec_pipe
  if spec == nil then
    return nil
  end
  return {
    state = ValueMapper.mapPipeState(spec.currentState),
    current = spec.currentState,
    target = spec.targetState,
    numStates = spec.numStates,
  }
end
