-- Unit tests for the Pumps & Hoses biogas-plant integration (src/integrations/PumpsAndHoses.lua) and
-- for what the two channels it feeds do with it.
--
-- Run with `busted` from the vdTelemetry/ directory. The DLC is not here, so the plant is stubbed at
-- the only surface the integration is allowed to use: the methods its SandboxPlaceable specialization
-- registers ON THE PLACEABLE. The DLC's own globals are deliberately absent from these stubs, because
-- they are absent from our Lua environment in a real game too (see the integration's header).

if VDT == nil or VDT.Farm == nil then
  dofile("src/utils/Farm.lua")
end
if VDT.ExportChannels == nil then
  dofile("src/export/ExportChannels.lua")
end
if VDT.ProductionExporter == nil then
  dofile("src/collect/ProductionExporter.lua")
end
if VDT.PumpsAndHoses == nil then
  dofile("src/integrations/PumpsAndHoses.lua")
end
if VDT.StorageExporter == nil then
  dofile("src/collect/StorageExporter.lua")
end

local FILL_TYPES = {
  [1] = { name = "UNKNOWN", title = "Unknown" },
  [11] = { name = "FERMENTERMANURE", title = "Fermenter manure" },
  [22] = { name = "SILAGE", title = "Silage" },
  [23] = { name = "CHAFF", title = "Chaff" },
  [40] = { name = "DIGESTATE", title = "Digestate" },
  [41] = { name = "METHANE", title = "Methane" },
}

local function makeStorage(levels, caps)
  local s = { _levels = levels, _caps = caps }
  function s:getFillLevels()
    return self._levels
  end
  function s:getFillLevel(ft)
    return self._levels[ft] or 0
  end
  function s:getCapacity(ft)
    return self._caps[ft] or 0
  end
  return s
end

-- One part of a plant. `role` is what getSandboxTypeName() answers; `utilization` is the fraction the
-- DLC's getUtilizationPercentage() returns (0..n — a value above 1 is the DLC saying "past its rate").
-- A BUNKER/SILO part carries a spec_silo, because in the DLC both really are PlaceableSilos.
local function makePart(role, uniqueId, opts)
  opts = opts or {}
  local part = {
    uniqueId = uniqueId,
    _role = role,
    _owner = opts.owner or 1,
    _utilization = opts.utilization,
  }
  if opts.levels ~= nil then
    part.spec_silo = { storages = { makeStorage(opts.levels, opts.caps or {}) } }
  end
  function part:getOwnerFarmId()
    return self._owner
  end
  function part:getName()
    return role
  end
  function part:isSandboxPlaceable()
    return true
  end
  function part:getSandboxTypeName()
    return self._role
  end
  function part:isSandboxRoot()
    return self._root == self
  end
  function part:resolveSandboxRoot()
    return self._root
  end
  function part:getSandboxRootName(stripped)
    -- The DLC pads the generated name with a trailing space; asking stripped=true drops the " (1)"
    -- index, which is exactly what must NOT happen when two plants have to be told apart.
    if stripped then
      return self._root._plantName
    end
    return self._root._plantName .. " (1) "
  end
  function part:getSandboxPlaceables()
    return self._root._parts
  end
  function part:getUtilizationPercentage()
    if self._utilization == nil then
      return 0, nil, nil
    end
    return self._utilization, "some blinking text", 3
  end
  return part
end

-- Wire a list of parts into one plant: the first FERMENTER is the root (only a fermenter can be one),
-- and every part points at it. `getMergedPlaceables` lives on the root alone, keyed by an opaque
-- number — the DLC's sandbox-type constant, which our environment cannot name and this code therefore
-- never reads.
local function makePlant(plantName, parts)
  local root
  for _, part in ipairs(parts) do
    if part._role == "FERMENTER" and root == nil then
      root = part
    end
  end
  root._plantName = plantName
  root._parts = parts
  local merged, nextKey = {}, 1
  for _, part in ipairs(parts) do
    part._root = root
    local seen = false
    for _, already in pairs(merged) do
      seen = seen or already._role == part._role
    end
    if not seen then
      merged[nextKey] = part
      nextKey = nextKey + 1
    end
  end
  function root:getMergedPlaceables()
    return merged
  end
  return root
end

local function setupWorld(placeables, farmId)
  _G.g_fillTypeManager = {
    getFillTypeByIndex = function(_, idx)
      return FILL_TYPES[idx]
    end,
  }
  _G.g_localPlayer = farmId ~= nil and { farmId = farmId } or nil
  _G.g_currentMission = { placeableSystem = { placeables = placeables } }
end

local function fullPlant()
  local fermenterA = makePart("FERMENTER", "ferm-a", { utilization = 0.82 })
  local fermenterB = makePart("FERMENTER", "ferm-b")
  local powerplant = makePart("POWERPLANT", "chp-1", { utilization = 0.65 })
  local bunkerA =
    makePart("BUNKER", "bunk-a", { utilization = 0.4, levels = { [22] = 40000 }, caps = { [22] = 100000 } })
  local bunkerB =
    makePart("BUNKER", "bunk-b", { levels = { [22] = 10000, [23] = 5000 }, caps = { [22] = 100000, [23] = 50000 } })
  local tank = makePart("SILO", "tank-1", { levels = { [40] = 30000 }, caps = { [40] = 200000 } })
  local torch = makePart("TORCH", "torch-1", { utilization = 1.35 })
  makePlant("BGA", { fermenterA, fermenterB, powerplant, bunkerA, bunkerB, tank, torch })
  return {
    parts = { fermenterA, fermenterB, powerplant, bunkerA, bunkerB, tank, torch },
    fermenterA = fermenterA,
    fermenterB = fermenterB,
    powerplant = powerplant,
    bunkerA = bunkerA,
    tank = tank,
    torch = torch,
  }
end

describe("PumpsAndHoses.reference", function()
  after_each(function()
    _G.g_currentMission, _G.g_fillTypeManager, _G.g_localPlayer = nil, nil, nil
  end)

  it("names the plant off its root, index and all", function()
    local plant = fullPlant()
    local ref = VDT.PumpsAndHoses.reference(plant.powerplant)
    assert.are.equal("ferm-a", ref.id)
    assert.are.equal("BGA (1)", ref.name)
    assert.are.equal("POWERPLANT", ref.role)
  end)

  it("gives every part of one plant the same id", function()
    local plant = fullPlant()
    assert.are.equal(VDT.PumpsAndHoses.reference(plant.fermenterB).id, VDT.PumpsAndHoses.reference(plant.tank).id)
  end)

  it("is nil for a placeable that is not part of a plant", function()
    assert.is_nil(VDT.PumpsAndHoses.reference({ uniqueId = "plain-silo" }))
    assert.is_nil(VDT.PumpsAndHoses.reference(nil))
  end)

  it("is nil for a sandbox placeable with no plant around it yet", function()
    -- The DLC's own production list drops an unconnected part the same way, and a part with no root
    -- produces nothing: its production is reset the moment it is disconnected.
    local lonely = makePart("FERMENTER", "lonely")
    lonely._root = nil
    assert.is_nil(VDT.PumpsAndHoses.reference(lonely))
  end)

  it("is nil for a plain silo in a game that HAS the DLC", function()
    -- The trap: the DLC injects its specialization into every silo-type placeable there is, so a farm
    -- grain silo answers `isSandboxPlaceable ~= nil` too. Only the call tells the two apart.
    local grainSilo = {
      uniqueId = "grain",
      isSandboxPlaceable = function()
        return false
      end,
    }
    assert.is_nil(VDT.PumpsAndHoses.reference(grainSilo))
  end)
end)

describe("PumpsAndHoses.isPlantInput", function()
  it("is true for a plant's bunker and false for everything else", function()
    local plant = fullPlant()
    assert.is_true(VDT.PumpsAndHoses.isPlantInput(plant.bunkerA))
    assert.is_false(VDT.PumpsAndHoses.isPlantInput(plant.tank))
    assert.is_false(VDT.PumpsAndHoses.isPlantInput(plant.fermenterA))
    assert.is_false(VDT.PumpsAndHoses.isPlantInput({ uniqueId = "plain" }))
  end)
end)

describe("PumpsAndHoses.collect", function()
  after_each(function()
    _G.g_currentMission, _G.g_fillTypeManager, _G.g_localPlayer = nil, nil, nil
  end)

  it("reports one construction per plant, in working order", function()
    local plant = fullPlant()
    setupWorld(plant.parts, 1)

    local constructions = VDT.PumpsAndHoses.collect(1)
    assert.are.equal(1, #constructions)
    local bga = constructions[1]
    assert.are.equal("ferm-a", bga.id)
    assert.are.equal("bga", bga.kind)
    assert.are.equal("BGA (1)", bga.name)

    local roles = {}
    for _, part in ipairs(bga.parts) do
      roles[#roles + 1] = part.role
    end
    assert.are.same({ "BUNKER", "FERMENTER", "POWERPLANT", "SILO", "TORCH" }, roles)
  end)

  it("counts the machines of a role and asks one of them for the whole role", function()
    local plant = fullPlant()
    setupWorld(plant.parts, 1)

    local parts = {}
    for _, part in ipairs(VDT.PumpsAndHoses.collect(1)[1].parts) do
      parts[part.role] = part
    end
    assert.are.equal(2, parts.FERMENTER.count)
    assert.are.equal(82, parts.FERMENTER.utilization)
    assert.are.equal(2, parts.BUNKER.count)
    assert.are.equal(40, parts.BUNKER.utilization)
    assert.are.equal(1, parts.POWERPLANT.count)
  end)

  it("leaves a utilization above 100 alone", function()
    -- Clamping here would delete the one reading the torch exists to give: it burns the surplus the
    -- plant cannot use, so the DLC's own panel lets its bar run past full.
    local plant = fullPlant()
    setupWorld(plant.parts, 1)
    for _, part in ipairs(VDT.PumpsAndHoses.collect(1)[1].parts) do
      if part.role == "TORCH" then
        assert.are.equal(135, part.utilization)
      end
    end
  end)

  it("sums what the bunkers hold, per fill type", function()
    local plant = fullPlant()
    setupWorld(plant.parts, 1)
    for _, part in ipairs(VDT.PumpsAndHoses.collect(1)[1].parts) do
      if part.role == "BUNKER" then
        assert.are.equal(2, #part.fills)
        assert.are.equal("CHAFF", part.fills[1].type)
        assert.are.equal(5000, part.fills[1].level)
        assert.are.equal("SILAGE", part.fills[2].type)
        assert.are.equal(50000, part.fills[2].level)
        assert.are.equal(200000, part.fills[2].capacity)
      end
      if part.role == "FERMENTER" then
        -- a fermenter's liters are its production point's storage, reported once, on that point
        assert.is_nil(part.fills)
      end
    end
  end)

  it("skips another farm's plant", function()
    local mine = fullPlant()
    local theirs = fullPlant()
    for _, part in ipairs(theirs.parts) do
      part._owner = 2
    end
    local all = {}
    for _, part in ipairs(mine.parts) do
      all[#all + 1] = part
    end
    for _, part in ipairs(theirs.parts) do
      all[#all + 1] = part
    end
    setupWorld(all, 1)

    assert.are.equal(1, #VDT.PumpsAndHoses.collect(1))
  end)

  it("is nil without the DLC", function()
    setupWorld({ {
      uniqueId = "grain",
      getOwnerFarmId = function()
        return 1
      end,
    } }, 1)
    assert.is_nil(VDT.PumpsAndHoses.collect(1))
  end)
end)

describe("the DLC's fourth output mode", function()
  it("names it off the point's own set rather than the base enum", function()
    -- The value is registered at load time and so is not a key of ProductionPoint.OUTPUT_MODE: asking
    -- the enum to name it answers "keep", which is wrong and looks right. The point keeps the set its
    -- own getOutputDistributionMode consults first, and that is what is read.
    local pp = { outputFillTypeIdsAutoDistribution = { [40] = true } }
    assert.is_true(VDT.PumpsAndHoses.isAutoDistribution(pp, 40))
    assert.is_false(VDT.PumpsAndHoses.isAutoDistribution(pp, 41))
  end)

  it("is false for a base-game production point, which has no such set", function()
    assert.is_false(VDT.PumpsAndHoses.isAutoDistribution({}, 40))
    assert.is_false(VDT.PumpsAndHoses.isAutoDistribution(nil, 40))
  end)

  it("has no mode number for a point that is not a sandbox one", function()
    assert.is_nil(VDT.PumpsAndHoses.autoDistributionMode({}))
  end)
end)

describe("the storage channel under a biogas plant", function()
  after_each(function()
    _G.g_currentMission, _G.g_fillTypeManager, _G.g_localPlayer = nil, nil, nil
  end)

  it("drops the bunkers and tags the digestate tank", function()
    local plant = fullPlant()
    setupWorld(plant.parts, 1)

    local model = VDT.StorageExporter.collect()
    assert.are.equal(1, #model.storages)
    local tank = model.storages[1]
    assert.are.equal("tank-1", tank.id)
    assert.are.equal("DIGESTATE", tank.fills[1].type)
    assert.are.equal("ferm-a", tank.construction.id)
    assert.are.equal("BGA (1)", tank.construction.name)
    assert.are.equal("SILO", tank.construction.role)
  end)
end)
