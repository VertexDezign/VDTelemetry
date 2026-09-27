-- Orchestrates collection of a vehicle into a VehicleModel: assembles the header fields, delegates
-- to per-aspect collectors, walks the recursive implement tree, then lets optional integrations
-- decorate each object.
-- Namespaced under VDT.* (see aspects/TurnOn.lua). Combined aggregation is deferred until a
-- consumer needs it.

VDT = VDT or {}
VDT.VehicleExporter = {}

-- The brand logo's path, made absolute. BrandManager resolves a mod or DLC brand against the folder it
-- came from, but the base game's own brands.xml is loaded with an empty base directory, so theirs stay
-- relative (`data/store/brands/...`) -- the engine resolves that against the install, and a reader on
-- the far side of the file would resolve it against the profile folder instead, where only mods live.
-- Anchored the way MapUtil anchors a vanilla map's overview. A leading `$` is the engine's own marker
-- for the install, should one ever get through unresolved.
---@param image string|nil
---@return string|nil
local function absoluteBrandImage(image)
  if image == nil or image == "" then
    return nil
  end
  if image:sub(1, 1) == "$" then
    return getAppBasePath() .. image:sub(2)
  end
  if image:sub(1, 1) == "/" or image:find("^%a:[/\\]") ~= nil then
    return image
  end
  return getAppBasePath() .. image
end

-- Recursively collect an object's attached implements into ImplementModel[]. `position` comes from
-- FS25_additionalInputs (a hard requirement, so no presence guard beyond the nil default). Returns
-- nil when there are no attached implements, so the JSON key is absent (the Kotlin model defaults it to []) —
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

    -- Which of the PARENT's schema attacher joints this implement hangs off — the link that turns
    -- the flat per-object schema data into a drawable rig (see aspects/Schema.lua). It lives on the
    -- attacher-joint entry, not on the object, so it is set here rather than in an aspect.
    implModel.jointDescIndex = attachedImplement.jointDescIndex

    local object = attachedImplement.object
    if object ~= nil then
      implModel.name = object:getFullName()
      implModel.type = object.typeName
      -- brand is emitted (behaviour-preserving) though the Kotlin model drops it via ignoreUnknownKeys
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

  -- unit/direction only when the vehicle reports a driving direction
  if vehicle.getDrivingDirection ~= nil then
    model.speed.unit = "km/h"
    model.speed.direction = ValueMapper.mapDirection(vehicle:getDrivingDirection())
  end

  local brand = ValueMapper.resolveBrand(vehicle)
  if brand ~= nil then
    -- `image` is the logo as BrandManager stored it, made absolute (see absoluteBrandImage), so the
    -- terminal needs to know neither the install nor the mod folder. It names the `.png` the XML
    -- declared, which the engine swaps for the `.dds` that actually ships.
    model.brand = { name = brand.name, title = brand.title, image = absoluteBrandImage(brand.image) }
  end

  if vehicle.operatingTime ~= nil then
    model.operatingTime = { value = ValueMapper.formatOperatingTime(vehicle.operatingTime), unit = "h" }
  end

  model.motor = VDT.Motor.collect(vehicle)
  model.lights = VDT.Lights.collect(vehicle)
  model.steering = VDT.Steering.collect(vehicle)
  model.gps = VDT.SupportSystems.collectGps(vehicle)
  model.ai = VDT.SupportSystems.collectAi(vehicle)
  model.cruiseControl = VDT.SupportSystems.collectCruiseControl(vehicle)
  VDT.Aspects.apply(vehicle, model)
  model.implement = collectImplements(vehicle)

  -- optional third-party integrations decorate the assembled object model (e.g. Enhanced Vehicle)
  VDT.Integrations.run("contributeObject", vehicle, model)

  return model
end
