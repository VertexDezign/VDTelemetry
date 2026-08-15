-- Model definitions for the lights subtree. Annotation-only (see EnvironmentModel.lua).
-- Maps to the Kotlin model's Lights / Indicator / Light / WorkLight (model/Vehicle.kt).

---@class IndicatorModel
---@field left boolean
---@field right boolean
---@field hazard boolean

---@class LightModel
---@field lowBeam boolean
---@field highBeam boolean

---@class WorkLightModel
---@field front boolean
---@field back boolean

---@class LightsModel
---@field indicator IndicatorModel
---@field beaconLight boolean?
---@field light LightModel
---@field workLight WorkLightModel
