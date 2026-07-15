-- Model definitions for the environment subtree.
--
-- Annotation-only (LuaLS @class): these files carry NO runtime logic and are not source()'d.
-- Collectors (src/collect/) build plain Lua tables shaped like these classes; the serializer
-- (src/utils/Json.lua) turns them straight into JSON. The shape maps 1:1 to the Kotlin model
-- in VDTerminal/shared (Model.kt) and the fixtures in examples/json/*.

---@class TemperatureModel
---@field min number
---@field max number
---@field current number
---@field unit string

---@class WeatherModel
---@field temperature TemperatureModel

---@class PlayerModel
---@field posX number
---@field posZ number
---@field heading number compass heading, same convention as GpsModel.heading
---@field headingUnit string
---@field farmId number? the local player's farm; omitted while spectating (farm 0)

---@class PdaModel
---@field filename string?
---@field width number?
---@field height number?
---@field player PlayerModel

---@class EnvironmentModel
---@field date string
---@field time string
---@field weather WeatherModel
---@field pda PdaModel
