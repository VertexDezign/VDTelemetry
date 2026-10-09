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

-- Load straps: `fastened` of `count` belts are done up; all of them is the game's "all fastened".
---@class TensionBeltsModel
---@field fastened number
---@field count number

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
-- `available` is which of those groups can be switched to right now -- `names` is what the XML
-- declares, and a group whose moving tools are inactive has no sub-selection to reach it by.
---@class ControlGroupModel
---@field current number
---@field name string?
---@field names string[]
---@field available number[]? indices into names, in the order the game's selection cycle visits them

-- `selectable` is the engine's own verdict (getCanBeSelected and not getBlockSelection), and the
-- gate a dashboard needs before offering a tap: setSelectedVehicle silently selects something ELSE
-- when handed an object that fails it.
---@class SelectionModel
---@field selected boolean
---@field selectable boolean?
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
---@field canToggle boolean? whether a player can start/stop unloading at all, as opposed to the engine doing it while the machine works

-- The trough moving, as opposed to material leaving it (see DischargeModel). `side` is nil until a
-- tip side is picked; `preferredSide` is what the next tip will use.
---@class TippingModel
---@field state string CLOSED | OPENING | OPEN | CLOSING
---@field side number?
---@field preferredSide number?
---@field count number?
---@field sides string[]? the sides' localized names, index-aligned with side / preferredSide

-- The combine: what it is threshing, whether crop is flowing in, what it does with the straw, and how
-- much ground it has covered. `hectaresSession` counts from the savegame's figure on the host but
-- from the join on a client -- see collect/aspects/Harvest.lua.
-- `fillType`/`title` describe the material reaching the tank; `fruitType` is the crop that went in,
-- and the two differ on every converting machine (maize into chaff).
---@class HarvestModel
---@field swathActive boolean
---@field swathAvailable boolean?
---@field chopperAvailable boolean?
---@field canToggleSwath boolean the engine's own verdict on the straw toggle: the machine offers both
---  a swath and a chopper, AND the crop in the tank drops a windrow. False is why the game's own key
---  would refuse, so a consumer offering the toggle gates on this rather than on the two flags above
---@field filling boolean crop is entering the tank right now
---@field bufferCombine boolean?
---@field hectares number?
---@field hectaresSession number?
---@field fruitType string?
---@field fillType string?
---@field title string?
---@field rainBlocked boolean? rain is stopping the threshing now
---@field rainWarning boolean? the engine's earlier warning that it is about to

-- The header. `working` is the engine's own "is collecting" -- crop taken within the last 300 ms,
-- not within this frame; `load` is absent on a multiplayer client, where the number it comes from is
-- never sent (see collect/aspects/Cutter.lua).
---@class CutterModel
---@field working boolean
---@field windrow boolean picking up a windrow rather than cutting standing crop
---@field cutWhileRaised boolean
---@field fruitType string? the crop under the header right now, absent over bare ground
---@field fillType string? what the header hands to the machine behind it
---@field title string?
---@field inputFillType string? what a windrow pickup is lifting
---@field strawRatio number?
---@field load number? 0..1

---@class WorkModeModel
---@field current number
---@field count number
---@field name string?
---@field names string[]? every mode's name, index-aligned with `current` ("" where unnamed)
---@field canChange boolean? the engine's getIsWorkModeChangeAllowed

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

-- A baler (aspects/Baler.lua). The bale's level is NOT here: `fillUnit` points at the chamber's entry
-- in the same node's `fillUnits` (1-based), which already carries it.
---@class BalerModel
---@field round boolean
---@field fillUnit number? absent when the chamber is not an exported fill unit
---@field consumable number? the net or twine's entry in `fillUnits`
---@field working boolean crop is coming in (Baler's lastAreaBiggerZero)
---@field powered boolean a motor on the rig is running; the game's keys refuse otherwise
---@field door string? CLOSED | OPENING | OPEN | CLOSING -- round balers only
---@field bales BaleModel[]? finished bales still on the machine
---@field baleTypes BaleTypeModel[]?
---@field baleType number 1-based into baleTypes
---@field nextBaleType number? chosen, waiting for the chamber to empty
---@field autoDrop AutoDropModel?
---@field platform BalerPlatformModel?
---@field buffer BalerBufferModel? non-stop balers only
---@field collector BaleCollectorModel? a bale collector on the back (BaleLoader)
---@field unload string? UNLOAD | UNLOAD_UNFINISHED | CLOSE | DROP_PLATFORM -- what the drop key would do

---@class BaleModel
---@field position number? 0..1 along a square baler's channel; absent on a round baler

-- Metres. A round bale has diameter + width, a square bale width + height + length.
---@class BaleTypeModel
---@field diameter number?
---@field width number
---@field height number?
---@field length number?

---@class AutoDropModel
---@field on boolean
---@field canToggle boolean

---@class BalerPlatformModel
---@field ready boolean a bale is waiting on the platform

---@class BaleCollectorModel
---@field fillUnit number? its entry in `fillUnits`, counted in bales

---@class BalerBufferModel
---@field fillUnit number? the buffer's entry in `fillUnits`
---@field overloading boolean the buffer is emptying into the chamber

-- A bale wrapper, standalone or on a baler-wrapper (aspects/BaleWrapper.lua).
---@class BaleWrapperModel
---@field state string EMPTY | LOADING | LOADED | WRAPPING | WRAPPED | DROPPING | RESETTING
---@field round boolean
---@field progress number? 0..1 while wrapping, 1 once wrapped
---@field consumable number? the wrap film's entry in `fillUnits`
---@field autoDrop AutoDropModel
---@field canDrop boolean the drop key would drop the wrapped bale now
---@field unsupportedBale boolean the bale in reach cannot be wrapped by this machine

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

-- A tool on a loader's tool joint (issue #169). `pitch` is the tool's root-node angle against the
-- horizon in degrees, positive nose up; `distance` is metres to the nearest thing under it that is not
-- part of the rig, negative when the only hit was above, absent when there was none within reach. Both
-- RAW: no node is guaranteed parallel to a shovel floor, so "level" is a reference the player sets.
-- See collect/aspects/LoaderTool.lua.
---@class LoaderToolModel
---@field joint string FRONTLOADER | TELEHANDLER | WHEEL_LOADER | SKID_STEER | LOADER_FORK
---@field kind string SHOVEL | FORK | BALE_GRAB | LOG_GRAB | OTHER -- by tool specialization, see LoaderTool.kindOf
---@field pitch number
---@field distance number?
---@field reference LoaderReferenceModel? the player's level for this tool model, absent until set

-- The raw `pitch` / `distance` a loader tool read when the player said "this is level". Both are
-- subtracted by the consumer; `distance` is absent when the tool read none at the time.
-- See store/LoaderReferences.lua.
---@class LoaderReferenceModel
---@field pitch number
---@field distance number?

-- One front-loader-driven cylinder. `role` is read off its input axis: LIFT, TELESCOPE, TILT, or AUX
-- for TOOL2..5, whose meaning is the tool's own. `travel` is 0..1 along the stroke, turned so 1 is up
-- (LIFT), curled back (TILT) or out (TELESCOPE); AUX keeps the engine's direction.
-- See collect/aspects/LoaderCylinders.lua.
---@class LoaderCylinderModel
---@field role string LIFT | TELESCOPE | TILT | TIP | AUX
---@field axis string the engine's input axis name, e.g. AXIS_FRONTLOADER_ARM
---@field travel number
---@field icon string? the control's engine icon (InputHelpElement.AXIS_ICON), e.g. GRABBER_OPEN_CLOSE
---@field angle number? TIP only: degrees nose up the bucket is turned from its travel-0 end
