-- Model definitions for the crop calendar export channel (cropCalendar.json,
-- src/collect/CropCalendarExporter.lua).
--
-- Annotation-only (LuaLS @class): these files carry NO runtime logic and are not source()'d.
-- The shape maps 1:1 to the Kotlin model in VDTerminal/shared (model/CropCalendar.kt) and the
-- fixtures in examples/json/cropCalendar/*.
--
-- This is the game's own Anbaukalender (InGameMenuCalendarFrame): for every crop the game shows on
-- the map, which of the twelve periods it may be SOWN in and which it may be HARVESTED in. World
-- state, identical for every farm, and near-static -- the only thing that moves is `today`.

---@class CropCalendarTodayModel where the year currently stands, for the "today" marker
---@field period number the current period, 1..12 (1 = the first period of spring)
---@field dayInPeriod number the day within that period, 1..daysPerPeriod
---@field daysPerPeriod number the season-length setting; user-changeable in game
---@field year number the current game year, 1-based

---@class CropCalendarPeriodModel one column of the calendar
---@field period number 1..12
---@field label string the game's own localized short label for it ("Mar", "Sep", ...). NOT derivable
---  app-side: g_i18n:formatPeriod shifts the month by hemisphere, so a southern map labels period 1
---  as September
---@field season string SPRING | SUMMER | AUTUMN | WINTER

---@class CropCalendarCropModel one crop row
---@field id string the fruit type's internal name ("WHEAT"), stable across locales -- the row key
---@field name string the localized display name, from the fruit's fill type title
---@field catchCrop boolean? true for a cover/catch crop (fruitDesc:getIsCatchCrop()); omitted when false
---@field plant number[]? the periods it may be sown in, ascending; omitted when there are none
---@field harvest number[]? the periods it may be harvested in, ascending; omitted when there are none

---@class CropCalendarModel
---@field version string channel version, independent of VDTelemetry.VERSION
---@field growthMode string SEASONAL | DAILY | DISABLED. Outside SEASONAL the game answers "yes" to
---  every period for every crop, so `plant`/`harvest` are all twelve and mean nothing -- the app says
---  so rather than drawing twelve full bars
---@field today CropCalendarTodayModel?
---@field periods CropCalendarPeriodModel[]? the twelve columns, in order
---@field crops CropCalendarCropModel[]? sorted by name, the way the game's own frame sorts them
