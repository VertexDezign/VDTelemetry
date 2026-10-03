-- Aspect collector: the load straps. Applies to any object (vehicle or implement) -- a flatbed
-- trailer mostly, but a pickup or a forwarder can carry them too.
-- Namespaced under VDT.* (see TurnOn.lua).
--
-- A belt is fastened while it has a mesh: that is the engine's own test, both in
-- TensionBelts:updateFastenState (which derives areAllBeltsFastened from it) and in the stream it
-- sends a joining client. So `fastened` counts meshes over every belt the machine declares, and
-- `fastened == count` is the game's "all fastened".
--
-- Whether anything is actually ON the bed is NOT here. The engine only finds that out when a belt is
-- tightened (getObjectToMount runs an overlap test then), so an unstrapped load is invisible to it
-- until someone straps it -- a consumer that wants "load not secured" pairs this with the machine's
-- fill units or mass instead.
--
-- MULTIPLAYER: fine. TensionBeltsEvent runs setTensionBeltsActive on every client, which builds and
-- removes the meshes there too, and a joining client gets each belt's state in onReadStream.

VDT = VDT or {}
VDT.TensionBelts = {}

---@param object table
---@return TensionBeltsModel|nil nil when the object has no tension belts
function VDT.TensionBelts.collect(object)
  local spec = object.spec_tensionBelts
  if spec == nil or not spec.hasTensionBelts or spec.sortedBelts == nil then
    return nil
  end

  local fastened = 0
  for _, belt in ipairs(spec.sortedBelts) do
    if belt.mesh ~= nil then
      fastened = fastened + 1
    end
  end
  return { fastened = fastened, count = #spec.sortedBelts }
end
