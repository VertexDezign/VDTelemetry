-- Model definitions for the husbandry export channel (husbandry.json,
-- src/collect/HusbandryExporter.lua).
--
-- Annotation-only (LuaLS @class): these files carry NO runtime logic and are not source()'d.
-- The shape maps 1:1 to the Kotlin model in VDTerminal/shared (model/Husbandry.kt) and the fixtures
-- in examples/json/husbandry/*.
--
-- Scope: the LOCAL player's farm only (own husbandries). Each pen carries the game's own aggregated
-- condition bars (getConditionInfos: food, water, straw, milk/manure/wool outputs, cleanliness), the
-- overall productivity, and the per-group animal breakdown.

---@class HusbandryConditionModel one condition/food bar (food group, water, straw, an output, ...)
---@field title string localized label (from getFoodInfos / getConditionInfos)
---@field ratio number fill/level ratio in [0,1] (for the bar)
---@field value number current fill level in liters
---@field capacity number? storage capacity in liters, when the info carries one (food does)
---@field inverted boolean? true when the bar reads inversely (a high value is bad, e.g. an output)

---@class HusbandryAnimalGroupModel one cluster of identical animals (same breed + age)
---@field name string breed/age label (the animal store name for that subtype at that age)
---@field count number number of animals in the group
---@field age number age in months
---@field health number health, 0..100
---@field reproduction number reproduction progress, 0..100
---@field supportsReproduction boolean? false for animals that don't breed (e.g. horses)

---@class HusbandryModel one owned animal husbandry (pen/barn)
---@field id string stable id for app selection (placeable uniqueId, else a synthesized fallback)
---@field name string display name (placeable's name)
---@field numAnimals number current animal count
---@field maxNumAnimals number capacity
---@field productivity number overall production factor in [0,1] (getGlobalProductionFactor)
---@field food HusbandryConditionModel[]? food-group bars (getFoodInfos), separate from conditions
---@field conditions HusbandryConditionModel[]? water/straw/output/cleanliness bars (getConditionInfos)
---@field animals HusbandryAnimalGroupModel[]?

---@class HusbandriesModel
---@field version string channel version, independent of VDTelemetry.VERSION
---@field husbandries HusbandryModel[]?
