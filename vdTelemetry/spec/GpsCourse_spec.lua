-- Unit tests for the GPS course export channel (src/collect/GpsCourseExporter.lua): the pure line
-- math (cross-track error + distance to the line end), the worked bitmask, the change detection that
-- decides when the geometry is rewritten, and collect() against a stubbed steering course.
--
-- The line math is the part worth testing hardest: it is the number a lightbar reads, its sign
-- convention is derived from the game's own side-offset direction, and it is pure — the engine only
-- supplies a position and a heading. Whether the real spec tables still look like these stubs is what
-- the in-game check covers.
--
-- Run with `busted` from the vdTelemetry/ directory.

if VDT == nil or VDT.ExportChannels == nil then
  dofile("src/export/ExportChannels.lua")
end
if VDT.MapExporter == nil then
  dofile("src/collect/MapExporter.lua")
end
if ValueMapper == nil then
  dofile("src/mapper/ValueMapper.lua")
end
dofile("src/collect/GpsCourseExporter.lua")

-- The exported numbers are formatted through ValueMapper.mapFloat, which needs the engine's MathUtil
-- (stubbed the same way PrecisionFarming_spec and WorkAspects_spec do). File-scope because nearly
-- every case here goes through it.
rawset(_G, "MathUtil", {
  round = function(value, decimals)
    local mult = 10 ^ (decimals or 0)
    return math.floor(value * mult + 0.5) / mult
  end,
})

---A straight line along +z from (0,0) to (0,100), as the game stores it: {x, z} pairs.
local function straightLine()
  return { { 0, 0 }, { 0, 100 } }
end

---A line along +z from (0,-50) to (0,50) at 1 m spacing — a headland ring's resolution, and finer
---than MIN_POINT_SPACING_M, so decimate thins it and drops the end point (49 m is the last multiple
---of 3 it keeps, 1 m short of where the line really ends).
local function denseLine()
  local points = {}
  for z = -50, 50 do
    points[#points + 1] = { 0, z }
  end
  return points
end

describe("GpsCourse.lineState", function()
  it("reports no deviation dead on the line", function()
    local deviation, distance = VDT.GpsCourse.lineState(straightLine(), 0, 40, 0, 1)
    assert.are.equal(0, deviation)
    assert.are.equal(60, distance)
  end)

  it("signs the deviation by the side of the line, relative to travel", function()
    -- The game's left of a line running +z is (dirZ, -dirX) = (1, 0), i.e. +x. So a vehicle at +x is
    -- LEFT of the line and reports negative; -x is right and reports positive.
    local left = VDT.GpsCourse.lineState(straightLine(), 1.5, 40, 0, 1)
    local right = VDT.GpsCourse.lineState(straightLine(), -1.5, 40, 0, 1)
    assert.are.equal(-1.5, left)
    assert.are.equal(1.5, right)
  end)

  it("mirrors the sign when the same line is driven the other way", function()
    -- Same vehicle position, opposite heading: the side it is on has genuinely changed.
    local forward = VDT.GpsCourse.lineState(straightLine(), 1.5, 40, 0, 1)
    local backward = VDT.GpsCourse.lineState(straightLine(), 1.5, 40, 0, -1)
    assert.are.equal(-forward, backward)
  end)

  it("measures the distance to the end it is heading for", function()
    local _, forward = VDT.GpsCourse.lineState(straightLine(), 0, 30, 0, 1)
    local _, backward = VDT.GpsCourse.lineState(straightLine(), 0, 30, 0, -1)
    assert.are.equal(70, forward)
    assert.are.equal(30, backward)
  end)

  it("follows a polyline around its corners", function()
    -- An L: (0,0) -> (0,50) -> (50,50). Standing at the corner heading +z, the rest is the 50 m leg.
    local corner = { { 0, 0 }, { 0, 50 }, { 50, 50 } }
    local _, distance = VDT.GpsCourse.lineState(corner, 0, 50, 0, 1)
    assert.are.equal(50, distance)
  end)

  it("clamps to the ends rather than projecting past them", function()
    -- 20 m beyond the end of the line: the closest point is the end itself, so the error is the
    -- lateral offset from it and there is no line left ahead.
    local deviation, distance = VDT.GpsCourse.lineState(straightLine(), -3, 120, 0, 1)
    assert.are.equal(3, deviation)
    assert.are.equal(0, distance)
  end)

  it("returns nothing for geometry it cannot use", function()
    assert.is_nil(VDT.GpsCourse.lineState(nil, 0, 0, 0, 1))
    assert.is_nil(VDT.GpsCourse.lineState({ { 0, 0 } }, 0, 0, 0, 1))
    -- A degenerate segment (both points identical) has no direction to measure against.
    assert.is_nil(VDT.GpsCourse.lineState({ { 5, 5 }, { 5, 5 } }, 0, 0, 0, 1))
  end)
end)

describe("GpsCourse.workedMask", function()
  it("packs four segments per character, lowest index in bit 0", function()
    -- 1 and 3 worked -> 0b0101 = 5
    local mask, count = VDT.GpsCourse.workedMask({ true, false, true, false })
    assert.are.equal("5", mask)
    assert.are.equal(2, count)
  end)

  it("keeps group order ascending across characters", function()
    -- segment 5 only: second group, bit 0 -> "01"
    local mask, count = VDT.GpsCourse.workedMask({ false, false, false, false, true })
    assert.are.equal("01", mask)
    assert.are.equal(1, count)
  end)

  it("pads a partial last group and trims the all-zero tail", function()
    assert.are.equal("f", (VDT.GpsCourse.workedMask({ true, true, true, true, false, false })))
    assert.are.equal("", (VDT.GpsCourse.workedMask({ false, false, false })))
    assert.are.equal("", (VDT.GpsCourse.workedMask({})))
    assert.are.equal("", (VDT.GpsCourse.workedMask(nil)))
  end)
end)

describe("GpsCourse.refresh", function()
  local course

  before_each(function()
    VDT.GpsCourse.reset()
    VDT.ExportChannels.reset()
    VDT.ExportChannels.register({
      name = VDT.GpsCourse.CHANNEL,
      fileName = VDT.GpsCourse.FILE_NAME,
      isAvailable = VDT.GpsCourse.isAvailable,
      collect = VDT.GpsCourse.collect,
    })
    course = { segments = { { positions = straightLine() } }, segmentStates = { false } }
    rawset(_G, "g_currentMission", { terrainSize = 2048 })
    rawset(_G, "g_vdTelemetry", { currentVehicle = { spec_aiAutomaticSteering = { steeringFieldCourse = course } } })
  end)

  after_each(function()
    rawset(_G, "g_vdTelemetry", nil)
    rawset(_G, "g_currentMission", nil)
  end)

  -- What the tick does: publish the course of whatever the player is currently driving.
  local function refresh()
    return VDT.GpsCourse.refresh(VDT.GpsCourse.currentVehicle())
  end

  local function dirtyNames()
    local names = {}
    for _, channel in ipairs(VDT.ExportChannels.selectDirty()) do
      names[#names + 1] = channel.name
    end
    return names
  end

  it("marks the channel dirty for a course it has not seen", function()
    assert.is_true(refresh())
    assert.are.same({ "gpsCourse" }, dirtyNames())
  end)

  it("does nothing while the same course stays put", function()
    refresh()
    assert.is_false(refresh())
  end)

  it("notices segments arriving late on a client", function()
    course.segments = {}
    refresh()
    course.segments = { { positions = straightLine() } }
    assert.is_true(refresh())
  end)

  it("notices the course going away, and publishes an empty one", function()
    refresh()
    g_vdTelemetry.currentVehicle = nil
    assert.is_true(refresh())
    -- Not nil: nil means "skip the write", which would leave the last course on disk to be drawn
    -- after the driver has left the field.
    local model = VDT.GpsCourse.collect()
    assert.are.equal("", model.courseId)
    assert.is_nil(model.segments)
  end)
end)

describe("GpsCourse.collect", function()
  before_each(function()
    VDT.GpsCourse.reset()
    rawset(_G, "g_currentMission", { terrainSize = 2048 })
    rawset(_G, "g_vdTelemetry", {
      currentVehicle = {
        spec_aiAutomaticSteering = {
          steeringFieldCourse = {
            fieldCourseSettings = { implementWidth = 6, numHeadlands = 2, sideOffset = 0, workDirection = -1 },
            fieldCourse = {
              courseField = {
                boundaryPositions = { { -100, -100 }, { 100, -100 }, { 100, 100 }, { -100, 100 } },
                islands = { { rootBoundary = { boundaryLine = { { 0, 0 }, { 20, 0 }, { 20, 20 }, { 0, 20 } } } } },
              },
            },
            segments = {
              { positions = { { -50, -50 }, { -50, 50 } } },
              { positions = { { -44, -50 }, { -44, 50 } }, isHeadlandSegment = true, headlandIndex = 1 },
              { positions = { { 0, 0 }, { 6, 0 } }, isIslandSegment = true },
            },
          },
        },
      },
    })
    VDT.GpsCourse.refresh(VDT.GpsCourse.currentVehicle())
  end)

  after_each(function()
    rawset(_G, "g_vdTelemetry", nil)
    rawset(_G, "g_currentMission", nil)
  end)

  it("exports every segment with the index the worked flags are keyed by", function()
    local model = VDT.GpsCourse.collect()
    assert.are.equal(3, #model.segments)
    assert.are.equal(1, model.segments[1].i)
    assert.are.equal("line", model.segments[1].kind)
    assert.are.equal("headland", model.segments[2].kind)
    assert.are.equal(1, model.segments[2].headlandIndex)
    assert.are.equal("island", model.segments[3].kind)
    assert.is_nil(model.segments[3].headlandIndex)
  end)

  it("normalizes coordinates into the map frame the overlays already use", function()
    local model = VDT.GpsCourse.collect()
    -- (-50, -50) on a 2048 m terrain -> (2048/2 - 50) / 2048
    assert.are.equal(VDT.MapExporter.normalizeCoord(-50, 2048), model.segments[1].p[1])
    assert.are.equal(VDT.MapExporter.normalizeCoord(50, 2048), model.segments[1].p[4])
  end)

  it("carries the course settings and the detected field", function()
    local model = VDT.GpsCourse.collect()
    assert.are.equal(6, model.implementWidth)
    assert.are.equal(2, model.numHeadlands)
    assert.are.equal(8, #model.boundary)
    assert.are.equal(1, #model.islands)
    assert.are.equal("1", model.courseId)
  end)

  it("keeps the end point the thinning dropped", function()
    -- A line drawn short of the headland it actually reaches is a line the driver steers past, so
    -- project() puts the original end back whenever decimate loses it.
    local course = g_vdTelemetry.currentVehicle.spec_aiAutomaticSteering.steeringFieldCourse
    course.segments = { { positions = denseLine() } }
    VDT.GpsCourse.refresh(VDT.GpsCourse.currentVehicle())

    local points = VDT.GpsCourse.collect().segments[1].p
    assert.is_true(#points < 2 * 101, "the line was thinned, or this proves nothing")
    assert.are.equal(VDT.MapExporter.normalizeCoord(0, 2048), points[#points - 1])
    assert.are.equal(VDT.MapExporter.normalizeCoord(50, 2048), points[#points])
  end)

  it("skips the write until the world size is known", function()
    rawset(_G, "g_currentMission", {})
    assert.is_nil(VDT.GpsCourse.collect())
  end)
end)

describe("GpsCourse.collectState", function()
  local vehicle

  before_each(function()
    VDT.GpsCourse.reset()
    rawset(_G, "g_currentMission", { terrainSize = 2048 })
    rawset(_G, "getWorldTranslation", function()
      return 1.5, 0, 40
    end)
    rawset(_G, "localDirectionToWorld", function()
      return 0, 0, 1
    end)
    vehicle = {
      rootNode = 1,
      spec_aiAutomaticSteering = {
        steeringFieldCourse = {
          segments = { { positions = straightLine() }, { positions = straightLine() } },
          segmentStates = { true, false },
          currentSegmentIndex = 2,
          currentSegmentIsLeft = true,
          currentSegment = { positions = straightLine() },
        },
      },
    }
  end)

  after_each(function()
    rawset(_G, "g_vdTelemetry", nil)
    rawset(_G, "g_currentMission", nil)
    rawset(_G, "getWorldTranslation", nil)
    rawset(_G, "localDirectionToWorld", nil)
  end)

  it("reports the line being followed, its error and what is done", function()
    local state = VDT.GpsCourse.collectState(vehicle)
    assert.are.equal(2, state.segmentIndex)
    assert.is_true(state.isLeft)
    assert.are.equal(2, state.segmentCount)
    assert.are.equal(1, state.workedCount)
    assert.are.equal("1", state.worked)
    assert.are.equal(-1.5, state.deviationM)
    assert.are.equal(60, state.distanceToEndM)
  end)

  it("quotes the same courseId the geometry channel is publishing", function()
    local state = VDT.GpsCourse.collectState(vehicle)
    assert.are.equal(state.courseId, VDT.GpsCourse.collect().courseId)
  end)

  it("omits the line error until a line is picked", function()
    vehicle.spec_aiAutomaticSteering.steeringFieldCourse.currentSegment = nil
    local state = VDT.GpsCourse.collectState(vehicle)
    assert.is_nil(state.deviationM)
    assert.is_nil(state.distanceToEndM)
  end)

  it("is absent for a vehicle with no steering course", function()
    assert.is_nil(VDT.GpsCourse.collectState({ rootNode = 1 }))
    assert.is_nil(VDT.GpsCourse.collectState({ rootNode = 1, spec_aiAutomaticSteering = {} }))
  end)

  it("describes the vehicle it was handed, not whatever the mod last tracked", function()
    -- Every number in the state comes from the passed vehicle's course, so the id it quotes — and
    -- therefore the geometry published under that id — has to come from the same one.
    rawset(_G, "g_vdTelemetry", {
      currentVehicle = { spec_aiAutomaticSteering = { steeringFieldCourse = { segments = {} } } },
    })
    local state = VDT.GpsCourse.collectState(vehicle)
    assert.are.equal(2, state.segmentCount)
    local model = VDT.GpsCourse.collect()
    assert.are.equal(state.courseId, model.courseId)
    assert.are.equal(2, #model.segments)
  end)
end)
