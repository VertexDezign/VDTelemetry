-- Model definitions for the shared aspects. Annotation-only (see EnvironmentModel.lua).
-- The scalar aspects (isTurnedOn/foldable/lowered/pipe/cover) live directly on VehicleModel /
-- ImplementModel; this file holds the structured ones (fill units, wearable).

-- Repeated <fillUnit> form (vehicle / implement / combined). Distinct from MotorFillUnitModel.
-- `value` is fractional: a consumable unit (bale net/twine/wrap) is measured in slots and reads e.g.
-- 1.5 for one spare roll plus a half-used one. `precision`/`display` are the game's own display hints
-- and are absent at their engine defaults (0 / "BAR").
---@class FillUnitModel
---@field value number
---@field type string?
---@field title string
---@field unit string
---@field capacity number
---@field fillLevelPercentage number
---@field usage number?
---@field precision number?
---@field display string?

---@class FillUnitsModel
---@field fillUnit FillUnitModel[]

---@class WearableModel
---@field damage number?
---@field wear number?
---@field dirt number?
---@field unit string
