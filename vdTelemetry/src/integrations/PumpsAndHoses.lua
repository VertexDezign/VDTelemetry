-- Optional integration: the Pumps & Hoses DLC and its BIOGAS PLANT — which the DLC's own code calls
-- a "sandbox", a word that means nothing to a player, so everything user-facing here says BGA.
--
-- WHAT A BGA IS, in the DLC's model. Every part is its own placeable carrying the DLC's
-- SandboxPlaceable specialization, tagged with a type: FERMENTER, POWERPLANT (the BHKW), BUNKER,
-- SILO and TORCH. One of them is the ROOT — and only a fermenter can be one (`canBeRoot="true"` sits
-- on the fermenter placeables and nowhere else) — and the root owns the plant's NAME ("BGA (1)", or
-- whatever the player renamed it to in the placeable dialog) and the list of children inside its
-- radius. That root is the one thing a player means by "my BGA", and its id is what groups every
-- part of this export back together.
--
-- WHY THE PRODUCTION CHANNEL NEEDED NOTHING BUT A DIFFERENT LIST. Three fermenters are three
-- placeables with three ProductionPoints, but the DLC MERGES them: one of each type becomes the
-- "merged placeable", its production absorbing the others' inputs/outputs and its stations gaining
-- their storages (SandboxPlaceableProductionPoint:mergeProductionPointsAtRoot). It then overwrites
-- ProductionChainManager:getProductionPointsForFarmId so the merged points are the only ones the
-- farm's list returns. So the grouping the player sees in the production menu — ONE fermenter entry
-- and ONE power-plant entry per plant, however many machines are standing there — is the game's own,
-- and ProductionExporter gets it simply by asking through that function instead of walking
-- `manager.productionPoints` raw. Everything in this file is what that list cannot say: the plant's
-- name, and the parts that are not production points.
--
-- WHAT THE BUNKERS AND THE DIGESTATE TANK ARE. Both are PlaceableSilos — not PlaceableBunkerSilo,
-- despite the shape you drive into — which is why they used to surface on the storage channel beside
-- the farm's own silos. A BGA bunker is not a store: it is the plant's input hopper, and the DLC
-- pumps liters out of it into the fermenters at a fixed rate (updateMergedPlaceableBunkers) rather
-- than letting a trailer come and fetch them. So the bunkers leave storage.json (StorageExporter
-- skips role BUNKER) and are reported here as part of the plant instead — the same rule the stock
-- overview already applies to a production point's inputs. The digestate tank stays on BOTH channels
-- on purpose: it IS a store you pump out of, so the Storage app must keep it, and it is also half of
-- what tells you whether the plant is backing up, so the plant view carries a copy.
--
-- MOD-ENVIRONMENT ISOLATION (see EnhancedLoanSystem.lua, AdvancedDamageSystem.lua). The DLC's
-- `SandboxPlaceable`, `SandboxRunningState` and the numeric `SANDBOX_TYPE_*` constants are globals in
-- ITS Lua environment and are nil from ours. Everything below therefore goes through the methods the
-- specialization registers ON THE PLACEABLE — getSandboxTypeName() for the role, resolveSandboxRoot()
-- for the plant, getUtilizationPercentage() for the bar — and never names a constant we cannot see.
-- The one place that costs us something is getPlaceableChildrenByType(), which takes a numeric type:
-- we group getSandboxPlaceables() by name instead.
--
-- TWO THINGS WE DELIBERATELY DO NOT EXPORT, both from getUtilizationPercentage's second return:
--   * its MESSAGE. The torch alternates its text every 4 s as a GUI blink
--     (SandboxPlaceableTorch:getUtilizationPercentage), which on our 2 s cadence would be a field
--     that flickers for no reason; and the fermenter's "not all materials available" is DLC l10n,
--     which our environment resolves to the key rather than the sentence. The app says the same
--     things from the line status it already has.
--   * its forced RUNNING STATE, a SandboxRunningState value — another mod's enum numbers, which we
--     would have to hardcode to read. The percentage carries the same information: the game's own
--     thresholds (bad < 0.25 ≤ ok < 0.75 ≤ perfect ≤ 1 < at-its-limit) are applied app-side.
--
-- **Written against Pumps & Hoses 1.0.0.0.** One quirk of that version is baked into the app rather
-- than corrected here: SILO is the one role with no getUtilizationPercentage override, so it answers
-- the SandboxPlaceable base — 0, always, which the DLC's own panel duly prints as "Silos: 0%". We
-- report what we are told (a collector that invents numbers is worse than one that repeats a wrong
-- one) and the app draws that row from the tank's own level/capacity instead.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

---@class ConstructionRefModel which construction a production point / storage belongs to
---@field id string the ROOT placeable's stable id — the join key, on both channels
---@field name string the plant's display name ("BGA (1)", or the player's own)
---@field role string this member's part type: "FERMENTER"|"POWERPLANT"|"BUNKER"|"SILO"|"TORCH"

---@class ConstructionPartModel one part TYPE of a construction, however many machines it is
---@field role string as above
---@field count number how many placeables of that role the plant has
---@field utilization number? the DLC's utilization percent, 0..n — NOT clamped to 100: a plant
---  running past its input rate, and a torch flaring the surplus, both read above it by design.
---  Absent when the part cannot answer.
---@field fills ProductionFillModel[]? BUNKER and SILO only: what the part is holding, summed over
---  every placeable of that role (the digestate tank's rows also appear on the storage channel)

---@class ConstructionModel one BGA — a root placeable and everything connected to it
---@field id string the root placeable's stable id
---@field kind string what sort of construction it is; "bga" is the only one the DLC builds
---@field name string display name ("BGA (1)")
---@field parts ConstructionPartModel[] one row per role present, in a fixed order

VDT = VDT or {}
VDT.PumpsAndHoses = {}

-- Role order for the parts list: the way the plant works, input first — bunkers feed fermenters,
-- fermenters feed the power plant, the torch burns what it cannot use, the tank holds what is left.
-- Fixed so a re-render never reorders the bars under the reader (pairs order is undefined).
local ROLE_ORDER = { "BUNKER", "FERMENTER", "POWERPLANT", "SILO", "TORCH" }

---Is this placeable part of a BGA at all? False for every placeable in a game without the DLC, and —
---this is the trap — also for most placeables in a game WITH it: the DLC injects SandboxPlaceable
---into every `silo`-type placeable there is (inject_sandbox.lua), so the farm's own grain silo
---answers `isSandboxPlaceable ~= nil` too. Only the call says whether it is really a member.
---@param placeable table|nil
---@return boolean
local function isMember(placeable)
  if placeable == nil or placeable.isSandboxPlaceable == nil then
    return false
  end
  local ok, isIt = pcall(placeable.isSandboxPlaceable, placeable)
  return ok and isIt == true
end

---The role token of a member ("FERMENTER", ...), or nil when it cannot be read.
---@param placeable table
---@return string|nil
local function roleOf(placeable)
  if placeable.getSandboxTypeName == nil then
    return nil
  end
  local ok, name = pcall(placeable.getSandboxTypeName, placeable)
  return (ok and type(name) == "string" and name ~= "") and name or nil
end

---The plant this placeable belongs to: its root placeable, or nil when it is unconnected (a fermenter
---dropped on its own, before a plant is built around it). The DLC's own production list drops an
---unconnected part the same way, and a part with no root produces nothing.
---@param placeable table
---@return table|nil
local function rootOf(placeable)
  if placeable.resolveSandboxRoot == nil then
    return nil
  end
  local ok, root = pcall(placeable.resolveSandboxRoot, placeable)
  return (ok and type(root) == "table") and root or nil
end

---The plant's display name, off the root. `false` asks for the full name rather than the one with the
---" (1)" index stripped — the index is what tells two plants apart.
---@param root table
---@return string
local function plantName(root)
  if root.getSandboxRootName ~= nil then
    local ok, name = pcall(root.getSandboxRootName, root, false)
    if ok and type(name) == "string" and name ~= "" then
      return (name:gsub("%s+$", ""))
    end
  end
  return "BGA"
end

---The plant's id, and the ONE place it is derived. Both sides of the join compute it from the same
---root: the reference hung on a point or a storage, and the `constructions[]` entry it points at. The
---fallback matters for that reason -- an index ("bga1") would differ between the two callers, so it is
---the root's own identity that stands in when the engine gives neither a uniqueId nor a rootNode.
---@param root table
---@return string
local function plantId(root)
  return VDT.ProductionExporter.placeableId(root, "bga" .. tostring(root))
end

---The construction reference to hang on a production point or a storage, or nil when the placeable is
---not part of a BGA (no DLC, not a member, or a member with no plant around it yet).
---@param placeable table|nil
---@return ConstructionRefModel|nil
function VDT.PumpsAndHoses.reference(placeable)
  if not isMember(placeable) then
    return nil
  end
  local role = roleOf(placeable)
  local root = rootOf(placeable)
  if role == nil or root == nil then
    return nil
  end
  return {
    id = plantId(root),
    name = plantName(root),
    role = role,
  }
end

---TRUE for a placeable the STORAGE channel must not report: a BGA bunker, which is the plant's input
---hopper rather than a store the farm can take from (see the header).
---@param placeable table|nil
---@return boolean
function VDT.PumpsAndHoses.isPlantInput(placeable)
  return isMember(placeable) and roleOf(placeable) == "BUNKER" and rootOf(placeable) ~= nil
end

-- Every part of a plant, grouped by role. Reads the root's own list (which includes the root itself),
-- so it is the same membership the DLC's GUI walks.
---@param root table
---@return table<string, table[]> role -> placeables
local function partsByRole(root)
  local byRole = {}
  local ok, placeables = pcall(root.getSandboxPlaceables, root)
  if not ok or type(placeables) ~= "table" then
    return byRole
  end
  for _, placeable in ipairs(placeables) do
    local role = roleOf(placeable)
    if role ~= nil then
      local list = byRole[role]
      if list == nil then
        list = {}
        byRole[role] = list
      end
      list[#list + 1] = placeable
    end
  end
  return byRole
end

-- The placeable the DLC merged this role into — the one whose getUtilizationPercentage speaks for the
-- whole role. Only the root keeps that map, and only roles with a production point (FERMENTER,
-- POWERPLANT) are in it; BUNKER and TORCH aggregate their siblings inside their own implementation,
-- so any one of them answers for all, which is what the fallback returns.
---@param root table
---@param role string
---@param placeables table[] every placeable of that role
---@return table|nil
local function speakerFor(root, role, placeables)
  if root.getMergedPlaceables ~= nil then
    local ok, merged = pcall(root.getMergedPlaceables, root)
    if ok and type(merged) == "table" then
      for _, placeable in pairs(merged) do
        if roleOf(placeable) == role then
          return placeable
        end
      end
    end
  end
  return placeables[1]
end

-- The utilization percent for a role, or nil when the part cannot answer. The raw fraction is scaled
-- to percent and left UNCLAMPED: > 100 is the DLC's way of saying "past its input rate" / "flaring",
-- and clamping here would delete the one reading the torch exists to give.
---@param speaker table|nil
---@return number|nil
local function utilizationOf(speaker)
  if speaker == nil or speaker.getUtilizationPercentage == nil then
    return nil
  end
  local ok, fraction = pcall(speaker.getUtilizationPercentage, speaker)
  if not ok or type(fraction) ~= "number" or fraction ~= fraction then
    return nil
  end
  return math.floor(math.max(fraction, 0) * 100 + 0.5)
end

-- What a role's placeables are holding, summed per fill type over all of them. Built through
-- ProductionExporter.storageRows so a bunker's rows and a farm silo's rows can't format differently.
---@param placeables table[]
---@return ProductionFillModel[]|nil
local function fillsOf(placeables)
  local rows, seen = {}, {}
  for _, placeable in ipairs(placeables) do
    local spec = placeable.spec_silo
    local storages = spec ~= nil and spec.storages or nil
    if type(storages) == "table" then
      for _, storage in ipairs(storages) do
        for _, row in ipairs(VDT.ProductionExporter.storageRows(storage)) do
          local existing = seen[row.type]
          if existing then
            existing.level = existing.level + row.level
            existing.capacity = existing.capacity + row.capacity
          else
            seen[row.type] = row
            rows[#rows + 1] = row
          end
        end
      end
    end
  end
  if #rows == 0 then
    return nil
  end
  table.sort(rows, function(a, b)
    return a.type < b.type
  end)
  return rows
end

-- THE FOURTH OUTPUT MODE. The DLC adds one to the game's three (keep / directSell / autoDeliver):
-- "distribute across biogas plant", which routes a fermenter's output into the plant's own parts
-- rather than keeping, selling or shipping it. It registers the value at load time as
-- max(existing) + 1 (SandboxProductionPoint's registerSandboxProductionPointOutputMode), so it is a
-- NUMBER we cannot name — `SandboxProductionPoint.OUTPUT_MODE` is a global in the DLC's environment.
--
-- Reading it needs no number at all. The sandbox point keeps its own `outputFillTypeIdsAutoDistribution`
-- set, and its getOutputDistributionMode consults exactly that before deferring to the base class, so
-- the field IS the answer. That is what isAutoDistribution asks.
--
-- WRITING it does need the number, and getting it wrong is not harmless: the base setter takes any
-- unrecognised value as "neither sell nor deliver", i.e. silently KEEP. So the number is discovered
-- rather than assumed wherever the point can tell us — if any of its outputs is already distributing,
-- getOutputDistributionMode returns the true value — and only falls back to the DLC's own registration
-- rule when none is. The caller verifies the write landed (see command/ProductionControl.lua).
VDT.PumpsAndHoses.AUTO_DISTRIBUTION = "autoDistribution"

---Is this output of this production point set to "distribute across biogas plant"?
---@param pp table a ProductionPoint (a sandbox one carries the set; any other answers false)
---@param fillTypeIndex number
---@return boolean
function VDT.PumpsAndHoses.isAutoDistribution(pp, fillTypeIndex)
  local set = type(pp) == "table" and pp.outputFillTypeIdsAutoDistribution or nil
  return type(set) == "table" and set[fillTypeIndex] ~= nil
end

---The numeric output mode meaning "distribute across biogas plant" for this point, or nil when the
---point is not a sandbox one (so the mode does not exist for it).
---@param pp table a ProductionPoint
---@return number|nil
function VDT.PumpsAndHoses.autoDistributionMode(pp)
  if type(pp) ~= "table" or type(pp.outputFillTypeIdsAutoDistribution) ~= "table" then
    return nil
  end
  -- Exact, when the point is already distributing something: ask it what that mode is.
  for fillTypeIndex in pairs(pp.outputFillTypeIdsAutoDistribution) do
    local ok, mode = pcall(pp.getOutputDistributionMode, pp, fillTypeIndex)
    if ok and type(mode) == "number" then
      return mode
    end
  end
  -- Otherwise the DLC's registration rule: one past the highest mode the base game defines.
  local enum = ProductionPoint ~= nil and ProductionPoint.OUTPUT_MODE or nil
  if type(enum) ~= "table" then
    return nil
  end
  local highest = 0
  for _, value in pairs(enum) do
    if type(value) == "number" and value > highest then
      highest = value
    end
  end
  return highest + 1
end

---Every BGA the farm owns, for the production channel's `constructions` key. Empty (returns nil) in a
---game without the DLC, or one where the farm has not built a plant — the key is then omitted and the
---app falls back to its no-constructions rendering.
---@param farmId number
---@return ConstructionModel[]|nil
function VDT.PumpsAndHoses.collect(farmId)
  local system = g_currentMission ~= nil and g_currentMission.placeableSystem or nil
  local placeables = system ~= nil and system.placeables or nil
  if type(placeables) ~= "table" then
    return nil
  end

  local out = {}
  for _, placeable in ipairs(placeables) do
    -- Roots only: one entry per plant, found by walking the farm's placeables rather than the DLC's
    -- own `placeableSystem.sandboxPlaceables` registry, which a game without the DLC does not have.
    local isRoot = false
    if isMember(placeable) and placeable.isSandboxRoot ~= nil then
      local ok, answer = pcall(placeable.isSandboxRoot, placeable)
      isRoot = ok and answer == true
    end
    local okOwner, owner = false, nil
    if isRoot then
      okOwner, owner = pcall(placeable.getOwnerFarmId, placeable)
    end
    if isRoot and okOwner and owner == farmId then
      local byRole = partsByRole(placeable)
      local parts = {}
      for _, role in ipairs(ROLE_ORDER) do
        local ofRole = byRole[role]
        if ofRole ~= nil and #ofRole > 0 then
          parts[#parts + 1] = {
            role = role,
            count = #ofRole,
            utilization = utilizationOf(speakerFor(placeable, role, ofRole)),
            -- only the two roles that HOLD something; a fermenter's liters are its production
            -- point's storage, reported on that point rather than twice
            fills = (role == "BUNKER" or role == "SILO") and fillsOf(ofRole) or nil,
          }
        end
      end
      out[#out + 1] = {
        id = plantId(placeable),
        kind = "bga",
        name = plantName(placeable),
        parts = #parts > 0 and parts or nil,
      }
    end
  end

  table.sort(out, function(a, b)
    return a.name < b.name
  end)
  -- omit the empty array: the Json encoder writes {} for an empty table (see MapExporter)
  return #out > 0 and out or nil
end
