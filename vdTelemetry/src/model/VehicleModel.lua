-- Model definitions for the vehicle subtree. Annotation-only (see EnvironmentModel.lua).
-- (`combined` aggregation is intentionally not emitted — deferred until a consumer needs it.)

---@class SpeedModel
---@field value number
---@field unit string?
---@field direction string?

---@class BrandModel
---@field name string?
---@field title string?

---@class OperatingTimeModel
---@field value string
---@field unit string

-- The isTurnedOn..wearable fields are the shared aspects (see collect/aspects/), identical to the
-- ones on ImplementModel.
---@class VehicleModel
---@field name string
---@field type string
---@field speed SpeedModel
---@field brand BrandModel?
---@field operatingTime OperatingTimeModel?
---@field motor MotorModel?
---@field lights LightsModel?
---@field gps GpsModel?
---@field ai AiModel?
---@field cruiseControl CruiseControlModel?
---@field isTurnedOn boolean?
---@field foldable string?
---@field lowered boolean?
---@field fillUnits FillUnitsModel?
---@field pipe string?
---@field cover string?
---@field wearable WearableModel?
---@field implement ImplementModel[]?
