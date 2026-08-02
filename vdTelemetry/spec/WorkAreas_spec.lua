-- Unit tests for the work-area aspect (src/collect/aspects/WorkAreas.lua) and the section list the
-- work-width aspect grew with it (src/collect/aspects/Work.lua).
--
-- Run with `busted` from the vdTelemetry/ directory. Both are reads of engine state, so the objects
-- below are plain tables shaped like the specs, and the three engine touchpoints -- the work-area
-- type manager, getWorldTranslation and MathUtil (via ValueMapper.mapFloat) -- are stubbed the way
-- MapVehiclesExporter_spec and WorkAspects_spec stub them.

if ValueMapper == nil then
  dofile("src/mapper/ValueMapper.lua")
end
if VDT == nil or VDT.MapExporter == nil then
  if VDT == nil or VDT.ExportChannels == nil then
    dofile("src/export/ExportChannels.lua")
  end
  dofile("src/collect/MapExporter.lua")
end
if VDT.WorkAreas == nil then
  dofile("src/collect/aspects/WorkAreas.lua")
end
if VDT.Work == nil then
  dofile("src/collect/aspects/Work.lua")
end

-- The four work-area types this file uses, in the manager's own uppercase form.
local TYPE_NAMES = { "SPRAYER", "CULTIVATOR", "AUXILIARY", "COMBINE" }

-- A work area's three corners are nodes; the stub resolves a node id to a world position, so a test
-- can lay an area out in meters and read the normalized footprint back.
local positions = {}

local function workArea(over)
  local area = { index = 1, type = 1, start = "start", width = "width", height = "height" }
  for k, v in pairs(over or {}) do
    area[k] = v
  end
  return area
end

local function tool(areas, over)
  local object = {
    spec_workArea = { workAreas = areas },
    getIsWorkAreaActive = function(_, area)
      return area.isActive ~= false
    end,
    getIsWorkAreaProcessing = function(_, area)
      return area.isProcessing == true
    end,
  }
  for k, v in pairs(over or {}) do
    object[k] = v
  end
  return object
end

describe("WorkAreas", function()
  before_each(function()
    positions = {
      -- A 24 m boom straddling the middle of a 2048 m map: start left of center, width right of it,
      -- height one meter behind the start.
      start = { -12, 0 },
      width = { 12, 0 },
      height = { -12, 1 },
    }
    rawset(_G, "MathUtil", {
      round = function(v, decimals)
        local mult = 10 ^ (decimals or 0)
        return math.floor(v * mult + 0.5) / mult
      end,
    })
    rawset(_G, "getWorldTranslation", function(node)
      local pos = positions[node]
      if pos == nil then
        error("no position for node " .. tostring(node))
      end
      return pos[1], 0, pos[2]
    end)
    rawset(_G, "g_workAreaTypeManager", {
      getWorkAreaTypeNameByIndex = function(_, index)
        return TYPE_NAMES[index]
      end,
    })
    rawset(_G, "g_currentMission", {
      terrainSize = 2048,
      hud = { ingameMap = { worldSizeX = 2048, worldSizeZ = 2048 } },
    })
  end)

  after_each(function()
    rawset(_G, "MathUtil", nil)
    rawset(_G, "getWorldTranslation", nil)
    rawset(_G, "g_workAreaTypeManager", nil)
    rawset(_G, "g_currentMission", nil)
  end)

  it("returns nil when the object works no ground", function()
    assert.is_nil(VDT.WorkAreas.collect({}))
    assert.is_nil(VDT.WorkAreas.collect({ spec_workArea = {} }))
  end)

  it("reports the type, both predicates and the measured width", function()
    local areas = VDT.WorkAreas.collect(tool({ workArea({ isProcessing = true }) }))
    assert.are.equal(1, #areas)
    assert.are.equal(1, areas[1].index)
    assert.are.equal("SPRAYER", areas[1].type)
    assert.is_true(areas[1].active)
    assert.is_true(areas[1].processing)
    -- Measured start->width, not read off the spec's own workWidth (which starts at -1).
    assert.are.equal(24, areas[1].width)
    assert.are.equal("m", areas[1].unit)
  end)

  it("keeps active and processing apart", function()
    -- Lowered and in gear, but nothing has touched ground in the last 200 ms: the tool is ready to
    -- work, and isn't working.
    local areas = VDT.WorkAreas.collect(tool({ workArea() }))
    assert.is_true(areas[1].active)
    assert.is_false(areas[1].processing)

    local off = VDT.WorkAreas.collect(tool({ workArea({ isActive = false }) }))
    assert.is_false(off[1].active)
  end)

  it("normalizes the footprint into the shared map frame", function()
    local areas = VDT.WorkAreas.collect(tool({ workArea() }))
    -- Three corners of the parallelogram, x/z interleaved; the fourth is the consumer's to derive.
    assert.are.same({ 0.49414, 0.5, 0.50586, 0.5, 0.49414, 0.50049 }, areas[1].shape)
  end)

  it("still reports the area when the world size is unknown", function()
    rawset(_G, "g_currentMission", { hud = {} })
    local areas = VDT.WorkAreas.collect(tool({ workArea() }))
    assert.are.equal(24, areas[1].width)
    assert.is_nil(areas[1].shape)
  end)

  it("drops auxiliary areas, which never touch ground", function()
    local areas = VDT.WorkAreas.collect(tool({
      workArea({ index = 1, type = 3 }),
      workArea({ index = 2, type = 2 }),
    }))
    assert.are.equal(1, #areas)
    assert.are.equal("CULTIVATOR", areas[1].type)
    assert.are.equal(2, areas[1].index)
  end)

  it("returns nil when every area was skipped", function()
    assert.is_nil(VDT.WorkAreas.collect(tool({ workArea({ type = 3 }) })))
  end)

  it("survives an unreadable node, keeping what it does know", function()
    positions.height = nil
    local areas = VDT.WorkAreas.collect(tool({ workArea({ isProcessing = true }) }))
    assert.are.equal(1, #areas)
    assert.is_true(areas[1].processing)
    -- No corners, so neither a footprint nor a width measured from them.
    assert.is_nil(areas[1].shape)
    assert.is_nil(areas[1].width)
  end)

  it("falls back to no type token when the manager is unreachable", function()
    rawset(_G, "g_workAreaTypeManager", nil)
    local areas = VDT.WorkAreas.collect(tool({ workArea() }))
    assert.is_nil(areas[1].type)
    -- And an unknown index is not an auxiliary area, so it is still exported.
    rawset(_G, "g_workAreaTypeManager", {
      getWorkAreaTypeNameByIndex = function()
        return nil
      end,
    })
    assert.are.equal(1, #VDT.WorkAreas.collect(tool({ workArea() })))
  end)
end)

describe("Work.collectWidth sections", function()
  before_each(function()
    rawset(_G, "MathUtil", {
      round = function(v, decimals)
        local mult = 10 ^ (decimals or 0)
        return math.floor(v * mult + 0.5) / mult
      end,
    })
  end)

  after_each(function()
    rawset(_G, "MathUtil", nil)
  end)

  local function boom(sections)
    return {
      spec_variableWorkWidth = { hasSections = true, sections = sections },
      getVariableWorkWidth = function()
        return 3, 6, true
      end,
    }
  end

  it("exports the sections in the spec's own (HUD) order", function()
    local w = VDT.Work.collectWidth(boom({
      { isLeft = true, isActive = false },
      { isLeft = true, isActive = true },
      { isCenter = true, isActive = true },
      { isActive = true },
      { isActive = false },
    }))
    assert.are.same({
      { active = false, side = "LEFT" },
      { active = true, side = "LEFT" },
      { active = true, side = "CENTER" },
      { active = true, side = "RIGHT" },
      { active = false, side = "RIGHT" },
    }, w.sections)
    assert.are.equal(3, w.activeCount)
  end)

  it("calls a center section CENTER whichever side it claims", function()
    -- isCenter wins: the engine puts it in neither side list, so it is never switched off.
    local w = VDT.Work.collectWidth(boom({ { isLeft = true, isCenter = true, isActive = true } }))
    assert.are.equal("CENTER", w.sections[1].side)
  end)

  it("omits the sections when the spec carries none", function()
    -- hasSections is the gate for the whole aspect, but a spec can still hold an empty list.
    local w = VDT.Work.collectWidth(boom({}))
    assert.are.equal(6, w.total)
    assert.is_nil(w.sections)
    assert.is_nil(w.activeCount)
  end)
end)
