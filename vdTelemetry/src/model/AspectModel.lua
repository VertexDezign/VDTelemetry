-- Model definitions for the shared aspects. Annotation-only (see EnvironmentModel.lua).
-- The scalar aspects (isTurnedOn/foldable/lowered/pipe/cover) live directly on VehicleModel /
-- ImplementModel; this file holds the structured ones (fill units, wearable).

-- Repeated <fillUnit> form (vehicle / implement / combined). Distinct from MotorFillUnitModel.
---@class FillUnitModel
---@field value number
---@field type string?
---@field title string
---@field unit string
---@field capacity number
---@field fillLevelPercentage number
---@field usage number?

---@class FillUnitsModel
---@field fillUnit FillUnitModel[]

---@class WearableModel
---@field damage number?
---@field wear number?
---@field dirt number?
---@field unit string
