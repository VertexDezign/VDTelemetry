-- Model definitions for the motor subtree. Annotation-only (see EnvironmentModel.lua).
-- Shape maps 1:1 to Model.kt (Motor / Temperatur / Rpm / Load / Gear / MaxSpeed / MotorFillUnits).
--
-- Note the JSON number types the collector must honour: temperatur/rpm values are Int, load.value
-- is Double (2-decimal presentation), fillLevelPercentage is Int, usage is Float.

---@class TemperaturModel
---@field value number
---@field min number
---@field max number
---@field unit string

---@class RpmModel
---@field value number
---@field min number
---@field max number

---@class LoadModel
---@field value number
---@field min number
---@field max number
---@field unit string

---@class GearModel
---@field value string
---@field isNeutral boolean
---@field group string

---@class MaxSpeedModel
---@field forward number?
---@field backward number?

-- Motor fill units use fixed, named children (fuel/def/air) — distinct from the repeated
-- <fillUnit> form used elsewhere. Only `fuel` carries `type`; def/air leave it nil.
---@class MotorFillUnitModel
---@field value number
---@field type string?
---@field title string
---@field unit string
---@field capacity number
---@field fillLevelPercentage number
---@field usage number?

---@class MotorFillUnitsModel
---@field fuel MotorFillUnitModel?
---@field def MotorFillUnitModel?
---@field air MotorFillUnitModel?

-- Optional third-party integrations extend MotorModel with extra @field lines in their own files
-- (LuaLS merges class fields across files). See integrations/EnhancedVehicle.lua.
---@class MotorModel
---@field state string
---@field temperatur TemperaturModel
---@field rpm RpmModel
---@field load LoadModel
---@field gear GearModel
---@field maxSpeed MaxSpeedModel?
---@field fillUnits MotorFillUnitsModel?
