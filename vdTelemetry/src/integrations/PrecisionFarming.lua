-- Precision Farming (FS25_precisionFarming) detection — a shared gate for the channels that must
-- suppress base-game data PF supersedes. It's the internal Precision Farming mod, keyed by its mod
-- name in the shared g_modIsLoaded table; that is the exact gate the game's own code uses
-- (PlayerHUDUpdater / PrecisionFarming's MOD_NAME check), so reading the same table matches the game.
--
-- When PF is installed it replaces the base fertilizer + lime model with its own soil maps and, in
-- FieldInfoDisplayExtension, deactivates the vanilla yield-bonus / fertilized / needs-lime lines. So
-- the channels that mirror the base HUD drop that superseded data when PF is present:
--   * FieldInfoExporter — omits yieldBonus / fertilized / needsLime from the field-info popup.
--   * MapLayersExporter — omits the fertilized + needs-lime soil layers from the ground overlay.
-- We do NOT (yet) surface PF's own soil layers; this module is detection only for now. When we add the
-- PF layers/values, they belong here next to the detection (one place that knows about the mod).
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.PrecisionFarming = {}

-- The internal mod's name (its folder / customEnv), the key it registers under in g_modIsLoaded.
VDT.PrecisionFarming.MOD_NAME = "FS25_precisionFarming"

---True when the Precision Farming mod is loaded. g_modIsLoaded is a shared engine global (populated
---in mods.lua), readable from any mod environment — so this matches the game's own gate exactly.
---@return boolean
function VDT.PrecisionFarming.isActive()
  return type(g_modIsLoaded) == "table" and g_modIsLoaded[VDT.PrecisionFarming.MOD_NAME] == true
end
