-- Model definitions for the driver-assist subtrees (gps / ai / cruise control). Annotation-only
-- (see EnvironmentModel.lua). Maps to Model.kt Gps / Ai / CruiseControl.

---@class GpsModel
---@field enabled boolean
---@field active boolean
---@field heading number
---@field headingUnit string
---@field linesVisible boolean whether the steering-assist lines are drawn (a global client setting)

---@class AiModel
---@field active boolean

---@class CruiseControlModel
---@field targetSpeed number?
---@field active boolean?
