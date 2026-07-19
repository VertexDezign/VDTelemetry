-- Model definitions for the production export channel (production.json,
-- src/collect/ProductionExporter.lua).
--
-- Annotation-only (LuaLS @class): these files carry NO runtime logic and are not source()'d.
-- The shape maps 1:1 to the Kotlin model in VDTerminal/shared (model/Production.kt) and the
-- fixtures in examples/json/production/*.
--
-- Scope: the LOCAL player's farm only (see g_localPlayer.farmId) — the owned production points (with
-- their production lines + shared internal storage) and factories. Standalone storages live on the
-- sibling storage channel (src/model/StorageModel.lua). Fill levels/capacities are liters; the app
-- derives the fill percentage from level/capacity. ProductionFillModel is shared with that channel.

---@class ProductionFillModel a fill-type row in a shared storage (input, output, or a standalone silo)
---@field type string fill type internal name, stable token (g_fillTypeManager name, e.g. "MANURE")
---@field title string localized display name (fillType.title)
---@field level number current fill level in liters (floored)
---@field capacity number storage capacity in liters (floored)

---@class ProductionIoModel one input or output of a production line (per-cycle recipe amount)
---@field type string fill type internal name (joins to ProductionFillModel.type in the point storage)
---@field title string localized display name
---@field amount number liters consumed/produced per cycle
---@field mode string? output distribution mode ("keep"|"directSell"|"autoDeliver"); outputs only
---@field sellDirectly boolean? true for a direct-sell output (never buffered in storage); outputs only

---@class ProductionLineModel one production ("recipe") of a production point
---@field id string production id (stable within the point; the setProductionState/outputMode key)
---@field name string localized production name
---@field status string "inactive"|"running"|"missingInputs"|"noOutputSpace" (live status)
---@field enabled boolean whether the line is switched on (independent of status; the on/off toggle)
---@field cyclesPerMonth number cycles run per in-game month at full throughput
---@field costsPerMonth number operating cost per in-game month while active (currency units)
---@field inputs ProductionIoModel[]
---@field outputs ProductionIoModel[]

---@class ProductionPointModel one owned production point (greenhouse, biogas plant, ...) or factory
---@field id string stable id for app selection (placeable uniqueId, else a synthesized fallback)
---@field name string display name (ProductionPoint:getName())
---@field isFactory boolean? true for a PlaceableFactory (read-only: no on/off, no output-mode control)
---@field lines ProductionLineModel[]
---@field storage ProductionFillModel[] the point's shared internal storage, one row per fill type

---@class ProductionModel
---@field version string channel version, independent of VDTelemetry.VERSION
---@field productionPoints ProductionPointModel[]?
