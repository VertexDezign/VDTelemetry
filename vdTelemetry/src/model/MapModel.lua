-- Model definitions for the map export channel (map.json, src/collect/MapExporter.lua).
--
-- Annotation-only (LuaLS @class): these files carry NO runtime logic and are not source()'d.
-- The shape maps 1:1 to the Kotlin model in VDTerminal/shared (model/MapData.kt) and the fixtures
-- in examples/json/map/*.
--
-- All posX/posZ/labelX/labelZ/polygon coordinates are normalized [0,1] map coordinates in the same
-- frame as PlayerModel.posX/posZ (world origin at the terrain center); terrainSize (meters) converts
-- them back to world units.

---@class MapPoiModel
---@field type string PlaceableHotspot.TYPE key, camelCased ("unloading", "shop", "productionPoint", ...)
---@field name string?
---@field posX number
---@field posZ number
---@field ownerFarmId number? omitted when accessible to everyone (no owner)

---@class MapFieldModel
---@field id number? farmland id (FS25 keys fields by farmland); absent when unresolvable
---@field name string?
---@field farmlandId number? same value as id, kept explicit for the app
---@field ownerFarmId number? omitted when unowned
---@field areaHa number?
---@field labelX number position of the field-number label (the game's own indicator node)
---@field labelZ number
---@field polygon number[]? flat border outline [x1,z1,x2,z2,...]; omitted when unresolvable

---@class MapFarmModel
---@field id number
---@field name string?
---@field color string? the farm's in-game map color as sRGB "#rrggbb" (Farm:getColor(), gamma-converted)

---@class MapModel
---@field version string
---@field terrainSize number world edge length in meters
---@field pois MapPoiModel[]?
---@field fields MapFieldModel[]?
---@field farms MapFarmModel[]?
