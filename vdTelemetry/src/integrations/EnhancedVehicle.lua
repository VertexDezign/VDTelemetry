-- Optional integration: Enhanced Vehicle (FS25_EnhancedVehicle). Namespaced under VDT.* (see
-- aspects/TurnOn.lua).
--
-- Unlike FS25_additionalInputs (a hard requirement, in collect/), Enhanced Vehicle may or may not
-- be installed. Its data is additive, so it *contributes* to an already-assembled model rather than
-- defining shape. The "am I present?" check (vData) lives here, once.
--
-- Fields this integration adds are declared here, next to the code that sets them. LuaLS merges
-- @field lines across files, so this extends MotorModel without model/ knowing Enhanced Vehicle
-- exists. These fields are not (yet) in the shared Kotlin model, so the server ignores them via
-- ignoreUnknownKeys — same as the XML writer's behaviour today.
---@class DiffLockModel
---@field front boolean?
---@field back boolean?

---@class MotorModel
---@field diffLock DiffLockModel?
---@field awd boolean?
---@field parkingBrake boolean?

VDT = VDT or {}
VDT.EnhancedVehicle = {}

-- Object stage: runs per vehicle/implement during the walk (see registry.lua). Enhanced Vehicle
-- only decorates the controlled vehicle's motor, so it no-ops for implements (no vData / no motor).
---@param object table a vehicle or implement (only vehicles carry vData)
---@param model table the object's already core-collected model
function VDT.EnhancedVehicle.contributeObject(object, model)
  local vData = object.vData
  -- absent when the mod isn't installed / this object isn't an Enhanced Vehicle; nothing to attach to
  if vData == nil or model.motor == nil then
    return
  end

  -- we read Enhanced Vehicle internal state, so type-check defensively to reduce fatal errors
  local is = vData.is
  local diffLock = {}
  if type(is[1]) == "boolean" then
    diffLock.front = is[1]
  end
  if type(is[2]) == "boolean" then
    diffLock.back = is[2]
  end
  if next(diffLock) ~= nil then
    model.motor.diffLock = diffLock
  end
  if is[3] ~= nil then
    model.motor.awd = is[3] == 1
  end
  if type(is[13]) == "boolean" then
    model.motor.parkingBrake = is[13]
  end
end
