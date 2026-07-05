-- Orchestrates collection of a vehicle into a VehicleModel: assembles the header fields, delegates
-- to per-aspect collectors, walks the recursive implement tree, then lets optional integrations
-- decorate each object. Mirrors VDTelemetry:populateXMLFromVehicle + populateXMLFromAttacherJoints.
-- Namespaced under VDT.* (see aspects/TurnOn.lua). Grows as slices land (combined is out of scope).

VDT = VDT or {}
VDT.VehicleExporter = {}

-- Recursively collect an object's attached implements into ImplementModel[]. `position` comes from
-- FS25_additionalInputs (a hard requirement, so no presence guard beyond the nil default). Returns
-- nil when there are no attached implements, so the JSON key is absent (Model.kt defaults to []) —
-- never an empty Lua table, which would encode as `{}` instead of `[]`.
---@param rootObject table
---@return ImplementModel[]|nil
local function collectImplements(rootObject)
  local ajSpec = rootObject.spec_attacherJoints
  if ajSpec == nil then
    return nil
  end

  local implements = {}
  for _, attachedImplement in ipairs(ajSpec.attachedImplements) do
    local position = ""
    if rootObject.vdAIGetAttacherJointPosition ~= nil then
      position = rootObject:vdAIGetAttacherJointPosition(attachedImplement)
    end

    ---@type ImplementModel
    local implModel = { position = position }

    local object = attachedImplement.object
    if object ~= nil then
      implModel.name = object:getFullName()
      implModel.type = object.typeName
      -- brand is emitted (behaviour-preserving) though Model.kt drops it via ignoreUnknownKeys
      local brand = ValueMapper.resolveBrand(object)
      if brand ~= nil then
        implModel.brand = { name = brand.name, title = brand.title }
      end
      VDT.Aspects.apply(object, implModel)
      implModel.implement = collectImplements(object)
      VDT.Integrations.run("contributeObject", object, implModel)
    end

    table.insert(implements, implModel)
  end

  if #implements == 0 then
    return nil
  end
  return implements
end

---@param vehicle Vehicle|nil
---@return VehicleModel|nil nil when there is no current vehicle
function VDT.VehicleExporter.collect(vehicle)
  if vehicle == nil then
    return nil
  end

  ---@type VehicleModel
  local model = {
    name = vehicle:getFullName(),
    type = vehicle.typeName,
    speed = { value = tonumber(ValueMapper.mapFloat(vehicle:getLastSpeed())) },
  }

  -- unit/direction only when the vehicle reports a driving direction (matches the XML guard)
  if vehicle.getDrivingDirection ~= nil then
    model.speed.unit = "km/h"
    model.speed.direction = ValueMapper.mapDirection(vehicle:getDrivingDirection())
  end

  local brand = ValueMapper.resolveBrand(vehicle)
  if brand ~= nil then
    model.brand = { name = brand.name, title = brand.title }
  end

  if vehicle.operatingTime ~= nil then
    model.operatingTime = { value = ValueMapper.formatOperatingTime(vehicle.operatingTime), unit = "h" }
  end

  model.motor = VDT.Motor.collect(vehicle)
  model.lights = VDT.Lights.collect(vehicle)
  model.gps = VDT.SupportSystems.collectGps(vehicle)
  model.ai = VDT.SupportSystems.collectAi(vehicle)
  model.cruiseControl = VDT.SupportSystems.collectCruiseControl(vehicle)
  VDT.Aspects.apply(vehicle, model)
  model.implement = collectImplements(vehicle)

  -- optional third-party integrations decorate the assembled object model (e.g. Enhanced Vehicle)
  VDT.Integrations.run("contributeObject", vehicle, model)

  return model
end
