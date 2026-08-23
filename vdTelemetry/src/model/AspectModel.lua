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

-- `current` is 0 while moving, else 1..numStates (1 = retracted). `target` is where it is heading.
---@class PipeModel
---@field state string RETRACTED | EXTENDED | MOVING
---@field current number
---@field target number
---@field numStates number

-- `index` is 0 when closed, else which of `count` covers is open.
---@class CoverModel
---@field state string CLOSED | OPEN
---@field index number
---@field count number

-- Where a child hangs off this object in the schema diagram. Raw engine values -- composing them
-- down the tree is the consumer's job (see collect/aspects/Schema.lua).
---@class SchemaJointModel
---@field x number
---@field y number
---@field rotation number
---@field invertX boolean
---@field liftedOffsetX number
---@field liftedOffsetY number

-- The object's silhouette in the game's rig diagram. `attacherJoint` is absent when it has none.
---@class SchemaModel
---@field name string VEHICLE | HARVESTER | TRAILER | ... (mod-prefixed for modded silhouettes)
---@field offsetX number
---@field offsetY number
---@field borderLeft number?
---@field borderRight number?
---@field attacherJoint SchemaJointModel[]?

-- The moving-tool group the player is cycling through on a Cylindered object (crane, front loader).
-- `current` is 0 when none is active; `name` is names[current], absent when current is 0.
---@class ControlGroupModel
---@field current number
---@field name string?
---@field names string[]

---@class SelectionModel
---@field selected boolean
---@field controlGroup ControlGroupModel?

-- `reason` is the engine's own code for why unloading is blocked; absent when nothing is wrong.
---@class DischargeModel
---@field state string OFF | OBJECT | GROUND
---@field allowed boolean
---@field nodeIndex number?
---@field fillUnitIndex number?
---@field hasObject boolean?
---@field hitTerrain boolean?
---@field reason string? NOT_ALLOWED_HERE | NO_FREE_CAPACITY | FILLTYPE_NOT_SUPPORTED | TOOLTYPE_NOT_SUPPORTED | NO_ACCESS | NO_ACCESS_LAND

-- The trough moving, as opposed to material leaving it (see DischargeModel). `side` is nil until a
-- tip side is picked; `preferredSide` is what the next tip will use.
---@class TippingModel
---@field state string CLOSED | OPENING | OPEN | CLOSING
---@field side number?
---@field preferredSide number?
---@field count number?
---@field sides string[]? the sides' localized names, index-aligned with side / preferredSide

-- Straw handling on a combine: swath it for baling, or chop it back onto the field.
---@class HarvestModel
---@field swathActive boolean
---@field swathAvailable boolean?
---@field chopperAvailable boolean?

---@class WorkModeModel
---@field current number
---@field count number
---@field name string?

-- One shutoff section of a boom, in the game's own HUD order. A CENTER section is in neither side
-- list and so is never switched off.
---@class WorkSectionModel
---@field active boolean
---@field side string LEFT | CENTER | RIGHT

-- Live width of a tool with retractable sections; sides are independent. Each side is measured from
-- the tool's centre line, so `total` is the two of them added. `sections` is the same spec one level
-- deeper: the individual sections, absent on a tool that has none.
---@class WorkWidthModel
---@field left number
---@field leftMax number
---@field right number
---@field rightMax number
---@field total number left + right, the whole swath
---@field unit string
---@field sections WorkSectionModel[]?
---@field activeCount number?

-- One work area of a tool: the ground it processes. `active` is the engine's own predicate (ground
-- contact / direction / lowered, and the section it belongs to), `processing` means it actually
-- touched ground within the last 200 ms. `shape` is three corners of the footprint parallelogram
-- (start, width, height) in normalized [0,1] map coordinates, absent when the world size is unknown.
-- The parallelogram is a rectangle on most tools and a rhombus on a spreader, where `start` sits on
-- the centre line and `width`/`height` are the two ends of the fan.
---@class WorkAreaModel
---@field index number
---@field type string? SPRAYER | CULTIVATOR | COMBINE | ... (nil when the enum is unreachable)
---@field active boolean
---@field processing boolean
---@field width number? how far the area reaches across the tool, not the length of any one edge
---@field unit string?
---@field shape number[]?

---@class BaleCounterModel
---@field session number
---@field lifetime number

-- A sowing machine's hopper: which crop is selected out of the machine's declared list, and how the
-- hopper is set up. `fruitType` is the crop token (WHEAT), `fillType` the fill type it is carried as
-- -- which is what joins this to the matching FillUnitModel -- and `title` the localized name to
-- print. All three are absent when the machine declares no seeds. `usageScale` is absent at the
-- engine default of 1. See collect/aspects/Sowing.lua for what is deliberately not here.
---@class SowingModel
---@field seedIndex number
---@field seedCount number
---@field changeAllowed boolean
---@field directPlanting boolean
---@field usageScale number?
---@field fruitType string?
---@field fillType string?
---@field title string?

-- Anything that puts material on the ground: liquid sprayers, solid fertilizer and lime spreaders,
-- slurry tankers, manure spreaders. One engine spec covers all of them.
--
-- `kind` and `category` answer different questions and a panel wants both. `kind` is a CAPABILITY --
-- what the tank accepts, fixed for the machine, and what decides the unit a rate is quoted in
-- (kg/ha solid, l/ha liquid, m3/ha slurry, t/ha manure). `category` is what is loaded RIGHT NOW.
-- A lime spreader is kind SOLID_FERTILIZER with category LIME.
--
-- `fillType` joins to the matching FillUnitModel (the fill unit list carries no indices, and a
-- combination machine has several tanks). `sprayType` / `category` are absent for a material the game
-- registers no spray type for. `nominalUsagePerMin` is measured at the machine's speed limit, NOT the
-- current draw -- see collect/aspects/Spraying.lua.
---@class SprayingModel
---@field kind string SOLID_FERTILIZER | LIQUID_FERTILIZER | SLURRY_TANKER | MANURE_SPREADER | SPRAYER
---@field active boolean sprayer effect running; a positive signal only -- see Spraying.lua's caveat
---@field doubledAmount boolean
---@field doubledAmountAvailable boolean base game: slurry/manure only. Always false under Precision Farming
---@field allowsSpraying boolean
---@field fillType string?
---@field title string?
---@field sprayType string?
---@field category string? FERTILIZER | LIME | HERBICIDE
---@field externalSource boolean? absent unless true: the material comes from a tank on another vehicle
---@field nominalUsagePerMin number?

-- A plough. `side` is which way the bodies are turned, absent on a plough that does not reverse; the
-- engine stores a `rotationMax` bool whose meaning is per-machine, so see collect/aspects/Plow.lua
-- for the mapping. `limitToField` is not in the join stream and can be stale on a client.
---@class PlowModel
---@field rotationAllowed boolean the mechanical half -- not mid-fold
---@field canToggleRotation boolean rotationAllowed plus lowered and powered
---@field limitToField boolean
---@field forceLimitToField boolean the player does not get to choose
---@field side string? LEFT | RIGHT

-- A cultivator / power harrow / subsoiler. Thin by design -- width, sections and depth modes are
-- already answered by workWidth / workAreas / workMode. None of it is synchronized in multiplayer.
---@class TillageModel
---@field kind string CULTIVATOR | POWER_HARROW | SUBSOILER
---@field deepMode boolean
---@field limitToField boolean

-- A mixer wagon. `fillType` is the engine's own verdict on the mix -- `recipe`'s fill type once every
-- ingredient sits inside its window, FORAGE_MIXING while one does not, the single ingredient's own
-- type while only one is loaded -- so a panel reads it rather than re-deriving it. `running` is the
-- drum turning, which is NOT the isTurnedOn aspect next to it: turn-on is the machine's PICKUP. The
-- ingredient list, its names and its windows are map data (animalFood.xml) and are absent on a
-- machine whose XML names no recipe. See collect/aspects/Mixer.lua.
---@class MixerModel
---@field running boolean the drum is turning: powered, and mixing or picking up or discharging
---@field powered boolean nothing turns without it
---@field remaining number ms left of the mix cycle, 0 once mixed
---@field mixingTime number ms this machine takes to mix after a fill change
---@field value number litres in the tub
---@field capacity number
---@field mass number? tonnes of feed in the tub; 0 when empty, absent when the material has no density
---@field fillType string?
---@field title string?
---@field recipe string? fill type the finished mix becomes; absent when the recipe was not resolved
---@field ingredients MixerIngredientModel[]?

-- One bar of the mixing-ratio readout. `value` is LITRES and the share a bar draws is
-- `value / sum(value)` -- a share of what is loaded, never of the tub's capacity. `mass` is present
-- only when the ingredient pools a single material; several materials share one litre count with no
-- record of which went in, so no honest weight exists for it.
---@class MixerIngredientModel
---@field name string the recipe's ingredient token
---@field title string? localized label
---@field fillTypes string[] the materials this ingredient accepts, by ascending fill type index
---@field minPercentage number
---@field maxPercentage number
---@field value number
---@field mass number? tonnes

-- What the machine weighs right now, in TONNES, and what it weighs empty -- the payload a panel
-- prints is the difference. Per-machine: a tractor and its implements each report their own.
-- `empty` is absent until the engine has run its first mass update on the machine.
---@class MassModel
---@field value number
---@field empty number?
