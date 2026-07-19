-- Model definitions for the production & storage export channel (productionStorage.json,
-- src/collect/ProductionExporter.lua).
--
-- Annotation-only (LuaLS @class): these files carry NO runtime logic and are not source()'d.
-- The shape maps 1:1 to the Kotlin model in VDTerminal/shared (model/Productions.kt) and the
-- fixtures in examples/json/productionStorage/*.
--
-- Scope: the LOCAL player's farm only (see g_localPlayer.farmId). Two sibling lists — production
-- points (with their production lines) and standalone storages (owned silos with no production).
-- Fill levels/capacities are liters; the app derives the fill percentage from level/capacity.

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

---@class StoredObjectModel a group of identical stored objects in an object storage
---@field index number the group's objectInfoIndex (1-based) — the unload command's addressing key
---@field title string display name (the abstract object's dialog text, e.g. "Round bale (Straw)")
---@field count number number of that object currently stored

---@class StandaloneStorageModel an owned storage placeable with no production
---@field id string stable id for app selection (placeable uniqueId, else a synthesized fallback)
---@field name string display name (owning placeable's name)
---@field kind string "fill" (liter silo) or "object" (object storage: bales/pallets, count-based)
---@field fills ProductionFillModel[]? kind=="fill": one row per stored fill type
---@field objects StoredObjectModel[]? kind=="object": per-type item counts (may be partial on MP clients)
---@field count number? kind=="object": total number of objects currently stored
---@field capacity number? kind=="object": maximum number of objects
---@field maxUnloadAmount number? kind=="object": per-action unload cap (the effective max per type is
---  min(this, that group's count))

---@class ProductionsModel
---@field version string channel version, independent of VDTelemetry.VERSION
---@field productionPoints ProductionPointModel[]?
---@field storages StandaloneStorageModel[]?
