-- GPS course export channel: the guidance lines the game's steering assist generated for the field
-- the driven vehicle is on, written to gpsCourse.json.
--
-- FS25's steering assist is not an AB line — it is a whole field course, generated once per field and
-- hanging off the vehicle (`spec_aiAutomaticSteering.steeringFieldCourse`, a SteeringFieldCourse over
-- a FieldCourse). Everything a real GPS terminal draws is already in there: every line as a polyline
-- (`segments[i].positions`), which lines are done (`segmentStates`), the line you are on
-- (`currentSegmentIndex`) and the detected field boundary with its islands.
--
-- Split by change rate, like map.json vs mapVehicles.json:
--   * this channel carries GEOMETRY, and is marked dirty only when the course object itself changes
--     (a new field, a different implement width, changed AI settings) — typically once per field;
--   * the fast part of the same subject — current line, cross-track error, distance to the line end,
--     which lines are worked — rides on the main telemetry's `vehicle.gps.course` at the 10 Hz tick
--     (collectState below, called from VDT.SupportSystems.collectGps).
-- `courseId` joins the two: it changes whenever the geometry does, so a live index that refers to a
-- course the app has not received yet is ignorable rather than drawn against the wrong lines.
--
-- Multiplayer: all of this reaches a client. The course arrives over AIAutomaticSteering's
-- onReadStream and is re-generated locally (FieldCourse.readStream runs the segment generator), the
-- worked flags stream server -> client every update, and currentSegmentIndex is computed locally for
-- the entered vehicle. The one thing that does NOT is the game's own distance-to-end
-- (`spec.lastDistanceToEnd` is only computed inside `if self.isServer`), which is why lineState below
-- derives ours from the course itself — the same number on a host and on a client.
--
-- Coordinates are the normalized [0,1] map frame of map.json / the player marker
-- (MapExporter.resolveWorldSize + normalizeCoord), thinned with MapExporter.decimate: headland rings
-- follow the boundary at terrain-detail resolution, so unthinned they are tens of thousands of points.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.GpsCourse = {}

VDT.GpsCourse.CHANNEL = "gpsCourse"
VDT.GpsCourse.FILE_NAME = "gpsCourse.json"
-- Own version, evolving independently of VDTelemetry.VERSION and the shared Kotlin GpsCourseData.
VDT.GpsCourse.VERSION = 1

-- How often the poll looks for a new course. The course changes at most once per field, so this is
-- about noticing promptly, not about cadence: it is two table comparisons.
VDT.GpsCourse.POLL_MS = 500

-- Polyline thinning, in the same spirit as MapExporter's: 3 m is well under a swath width and far
-- below anything visible on a dashboard map. Lines are 2 points to begin with; the caps exist for the
-- headland rings, which are boundary-resolution.
VDT.GpsCourse.MIN_POINT_SPACING_M = 3
VDT.GpsCourse.MAX_SEGMENT_POINTS = 128
VDT.GpsCourse.MAX_BOUNDARY_POINTS = 256

-- The course whose geometry was last published, with the id it was published under. Held rather than
-- re-read at write time so the file and the id in the live telemetry always describe the same course.
local publishedCourse = nil
local publishedSegmentCount = 0
local courseId = ""
local courseCounter = 0
local pollTimer = 0

---The vehicle the player is driving, from the mod's own tracked value (VDTelemetry:setCurrentVehicle).
---nil on foot — which is also "no course", since the course lives on the vehicle.
---@return table|nil
function VDT.GpsCourse.currentVehicle()
  local mod = g_vdTelemetry
  return mod ~= nil and mod.currentVehicle or nil
end

---The steering course of a vehicle, or nil when it has none (no steering spec, not in steering-assist
---mode, or off the field — the game drops the course 20 s after leaving one).
---@param vehicle table|nil
---@return table|nil SteeringFieldCourse
function VDT.GpsCourse.courseOf(vehicle)
  if vehicle == nil then
    return nil
  end
  local spec = vehicle.spec_aiAutomaticSteering
  local course = spec ~= nil and spec.steeringFieldCourse or nil
  -- `segments` is SteeringFieldCourse's alias of its FieldCourse's list, and it is the list
  -- `segmentStates` was sized against — so it is the one whose indices the worked flags refer to.
  if course == nil or type(course.segments) ~= "table" then
    return nil
  end
  return course
end

---Notice a course change and queue a geometry rewrite. Cheap enough to call from both the tick and
---the 10 Hz state collector: it compares an identity and a count.
---
---The segment count is part of the comparison because a course can arrive empty — on a client the
---segments are re-generated locally over several frames, so the same course table goes from 0 to N
---segments and that IS a geometry change.
---
---Takes the vehicle rather than reading the mod's tracked one itself, so the geometry it publishes
---and the `courseId` its caller is about to quote always describe the same vehicle's course.
---@param vehicle table|nil the vehicle whose course to publish; nil is "no course" (on foot)
---@return boolean whether the course changed
function VDT.GpsCourse.refresh(vehicle)
  local course = VDT.GpsCourse.courseOf(vehicle)
  local count = course ~= nil and #course.segments or 0
  if course == publishedCourse and count == publishedSegmentCount then
    return false
  end
  publishedCourse = course
  publishedSegmentCount = count
  if course == nil then
    courseId = ""
  else
    courseCounter = courseCounter + 1
    courseId = tostring(courseCounter)
  end
  VDT.ExportChannels.markDirty(VDT.GpsCourse.CHANNEL)
  return true
end

---Test seam: drop the published course, as if nothing had been seen yet.
function VDT.GpsCourse.reset()
  publishedCourse = nil
  publishedSegmentCount = 0
  courseId = ""
  courseCounter = 0
  pollTimer = 0
end

---Flat world coordinates [x1,z1,x2,z2,...] from the game's list of {x,z} pairs. Non-numeric entries
---are skipped rather than trusted — this walks engine-owned tables.
---@param positions table
---@return number[]
local function flatten(positions)
  local out = {}
  for i = 1, #positions do
    local point = positions[i]
    if type(point) == "table" and type(point[1]) == "number" and type(point[2]) == "number" then
      out[#out + 1] = point[1]
      out[#out + 1] = point[2]
    end
  end
  return out
end

---Thin a flat world polyline and convert it to normalized map coordinates.
---
---Both of decimate's rules (minimum spacing, then a cap that keeps every step-th point) can drop the
---LAST point, which on a guidance line means drawing it short of the headland it actually reaches. So
---the original end point is restored when thinning lost it.
---@param world number[] flat world coordinates
---@param sizeX number
---@param sizeZ number
---@param maxPoints number
---@return number[] flat normalized coordinates
local function project(world, sizeX, sizeZ, maxPoints)
  local count = #world
  if count < 4 then
    return {}
  end
  local thinned = VDT.MapExporter.decimate(world, VDT.GpsCourse.MIN_POINT_SPACING_M, maxPoints)
  local kept = #thinned
  if kept < 2 or thinned[kept - 1] ~= world[count - 1] or thinned[kept] ~= world[count] then
    thinned[#thinned + 1] = world[count - 1]
    thinned[#thinned + 1] = world[count]
  end
  local out = {}
  for i = 1, #thinned - 1, 2 do
    out[#out + 1] = VDT.MapExporter.normalizeCoord(thinned[i], sizeX)
    out[#out + 1] = VDT.MapExporter.normalizeCoord(thinned[i + 1], sizeZ)
  end
  return out
end

---Wire token for a segment: the game's own three kinds, which it also colors its debug draw by.
---@param segment table
---@return string
local function kindOf(segment)
  if segment.isIslandSegment then
    return "island"
  end
  if segment.isHeadlandSegment then
    return "headland"
  end
  return "line"
end

---The course settings that describe the geometry, guarded — a mod is free to put anything on these.
---@param model GpsCourseModel
---@param settings table|nil
local function addSettings(model, settings)
  if type(settings) ~= "table" then
    return
  end
  model.implementWidth = tonumber(ValueMapper.mapFloat(settings.implementWidth))
  model.sideOffset = tonumber(ValueMapper.mapFloat(settings.sideOffset))
  model.workDirection = tonumber(ValueMapper.mapFloat(settings.workDirection))
  if type(settings.numHeadlands) == "number" then
    model.numHeadlands = math.floor(settings.numHeadlands)
  end
end

---The detected field boundary and its islands, from the course's own field data. This is the boundary
---the course was generated against — finer than map.json's farmland polygon, and the shape the
---headland rings follow.
---@param model GpsCourseModel
---@param course table
---@param sizeX number
---@param sizeZ number
local function addField(model, course, sizeX, sizeZ)
  local field = course.fieldCourse ~= nil and course.fieldCourse.courseField or nil
  if type(field) ~= "table" then
    return
  end
  if type(field.boundaryPositions) == "table" then
    local boundary = project(flatten(field.boundaryPositions), sizeX, sizeZ, VDT.GpsCourse.MAX_BOUNDARY_POINTS)
    if #boundary >= 6 then
      model.boundary = boundary
    end
  end
  local islands = {}
  for _, island in ipairs(type(field.islands) == "table" and field.islands or {}) do
    local line = island.rootBoundary ~= nil and island.rootBoundary.boundaryLine or nil
    if type(line) == "table" then
      local polygon = project(flatten(line), sizeX, sizeZ, VDT.GpsCourse.MAX_BOUNDARY_POINTS)
      if #polygon >= 6 then
        islands[#islands + 1] = polygon
      end
    end
  end
  if #islands > 0 then
    model.islands = islands
  end
end

function VDT.GpsCourse.isAvailable()
  return g_currentMission ~= nil
end

---Build the geometry model. Publishes the course `refresh()` last saw, so the geometry and the
---`courseId` the live telemetry is quoting can never disagree.
---
---With no course this returns the empty model (courseId "") rather than nil: nil means "skip the
---write", which would leave the last course's file on disk for the app to keep drawing after the
---driver has left the field.
---@return GpsCourseModel|nil
function VDT.GpsCourse.collect()
  local sizeX, sizeZ = VDT.MapExporter.resolveWorldSize()
  if sizeX == nil then
    return nil
  end

  ---@type GpsCourseModel
  local model = { version = tostring(VDT.GpsCourse.VERSION), courseId = courseId }
  local course = publishedCourse
  if course == nil then
    return model
  end

  addSettings(model, course.fieldCourseSettings)
  addField(model, course, sizeX, sizeZ)

  local segments = {}
  for index, segment in ipairs(course.segments) do
    local positions = type(segment) == "table" and segment.positions or nil
    if type(positions) == "table" and #positions >= 2 then
      local points = project(flatten(positions), sizeX, sizeZ, VDT.GpsCourse.MAX_SEGMENT_POINTS)
      if #points >= 4 then
        -- The loop index, not segment.index: this is the index segmentStates and the live state's
        -- segmentIndex are keyed by, and it is what the app joins them on.
        local entry = { i = index, kind = kindOf(segment), p = points }
        if entry.kind == "headland" and type(segment.headlandIndex) == "number" then
          entry.headlandIndex = segment.headlandIndex
        end
        segments[#segments + 1] = entry
      end
    end
  end
  -- omit the empty array: the Json encoder emits {} for an empty table (see TaskList.lua)
  model.segments = #segments > 0 and segments or nil

  return model
end

---Hex bitmask of the worked lines, plus how many are set.
---
---One character per FOUR segments, ascending: character k covers indices 4k-3 .. 4k, bit 0 of it
---being the lowest of those. Trailing all-zero characters are trimmed, so an untouched course is the
---empty string. Compact enough to ride on the 10 Hz telemetry for a course of any size.
---@param states table|nil the game's segmentStates (index -> boolean)
---@return string mask, number workedCount
function VDT.GpsCourse.workedMask(states)
  if type(states) ~= "table" then
    return "", 0
  end
  local chars = {}
  local worked = 0
  local nibble = 0
  for index = 1, #states do
    if states[index] == true then
      nibble = nibble + 2 ^ ((index - 1) % 4)
      worked = worked + 1
    end
    if index % 4 == 0 then
      chars[#chars + 1] = string.format("%x", nibble)
      nibble = 0
    end
  end
  if #states % 4 ~= 0 then
    chars[#chars + 1] = string.format("%x", nibble)
  end
  -- right-trim the all-zero tail
  local last = #chars
  while last > 0 and chars[last] == "0" do
    last = last - 1
  end
  return table.concat(chars, "", 1, last), worked
end

---Where the vehicle sits relative to the line it is following: signed cross-track error and how far
---is left of the line ahead of it.
---
---Pure (no engine calls) so it is unit-testable, which is why it re-derives the closest point rather
---than calling the game's SteeringFieldCourse.getClosestPositionSegment — the same projection onto
---each sub-segment, clamped to its ends.
---
---SIGN: positive means the vehicle is on the side the game calls *right*. The game's own left is the
---direction it shifts a line by for a positive sideOffset — `lx + lDirZ * offset, lz - lDirX * offset`
---(SteeringFieldCourse:setCurrentSegmentIndex) — i.e. left = (lDirZ, -lDirX), so right is its
---negation. The line direction is flipped to point along travel first, so driving a line the other
---way round reports the same side rather than mirroring it.
---
---@param positions table the current segment's {x,z} points — already side-offset by the game
---@param wx number vehicle world x
---@param wz number vehicle world z
---@param dirX number vehicle forward direction x (need not be normalized)
---@param dirZ number vehicle forward direction z
---@return number|nil deviation meters, + = right of the line; nil when the line has no usable geometry
---@return number|nil distanceToEnd meters to the end of the line ahead
function VDT.GpsCourse.lineState(positions, wx, wz, dirX, dirZ)
  if type(positions) ~= "table" or #positions < 2 then
    return nil, nil
  end

  local bestDistSq, bestIndex, bestX, bestZ
  for i = 1, #positions - 1 do
    local a, b = positions[i], positions[i + 1]
    if type(a) == "table" and type(b) == "table" then
      local sx, sz = b[1] - a[1], b[2] - a[2]
      local lengthSq = sx * sx + sz * sz
      if lengthSq > 0 then
        local t = ((wx - a[1]) * sx + (wz - a[2]) * sz) / lengthSq
        t = math.max(0, math.min(1, t))
        local px, pz = a[1] + sx * t, a[2] + sz * t
        local distSq = (wx - px) * (wx - px) + (wz - pz) * (wz - pz)
        if bestDistSq == nil or distSq < bestDistSq then
          bestDistSq, bestIndex, bestX, bestZ = distSq, i, px, pz
        end
      end
    end
  end
  if bestIndex == nil then
    return nil, nil
  end

  local a, b = positions[bestIndex], positions[bestIndex + 1]
  local sx, sz = b[1] - a[1], b[2] - a[2]
  local length = math.sqrt(sx * sx + sz * sz)
  sx, sz = sx / length, sz / length
  -- Orient the line along travel: the polyline's stored order is arbitrary, but left and right are
  -- not — they are relative to the direction the vehicle is going.
  local forward = (sx * dirX + sz * dirZ) >= 0
  if not forward then
    sx, sz = -sx, -sz
  end

  local vx, vz = wx - bestX, wz - bestZ
  local deviation = vz * sx - vx * sz

  -- Remaining length along the polyline in the direction of travel: the rest of the sub-segment the
  -- vehicle is on, plus every sub-segment beyond it.
  local remaining = 0
  local function span(i, j)
    local p, q = positions[i], positions[j]
    return math.sqrt((q[1] - p[1]) * (q[1] - p[1]) + (q[2] - p[2]) * (q[2] - p[2]))
  end
  if forward then
    remaining = math.sqrt((b[1] - bestX) * (b[1] - bestX) + (b[2] - bestZ) * (b[2] - bestZ))
    for i = bestIndex + 1, #positions - 1 do
      remaining = remaining + span(i, i + 1)
    end
  else
    remaining = math.sqrt((a[1] - bestX) * (a[1] - bestX) + (a[2] - bestZ) * (a[2] - bestZ))
    for i = 1, bestIndex - 1 do
      remaining = remaining + span(i, i + 1)
    end
  end

  return tonumber(ValueMapper.mapFloat(deviation)), tonumber(ValueMapper.mapFloat(remaining))
end

---The live half of the course, for the main telemetry's `vehicle.gps.course`. nil when the vehicle
---has no course, which is what tells the app there is nothing to guide against.
---@param vehicle table
---@return GpsCourseStateModel|nil
function VDT.GpsCourse.collectState(vehicle)
  local course = VDT.GpsCourse.courseOf(vehicle)
  if course == nil then
    return nil
  end
  -- Keep the id honest at telemetry cadence rather than only at POLL_MS: the app ignores indices
  -- whose courseId it has no geometry for, so a stale id here costs a visibly unhighlighted line.
  -- This vehicle, not the mod's tracked one: everything else in this model comes from the course
  -- read above, and an id describing a different vehicle's geometry is exactly the stale id the
  -- refresh exists to avoid.
  VDT.GpsCourse.refresh(vehicle)

  local worked, workedCount = VDT.GpsCourse.workedMask(course.segmentStates)
  ---@type GpsCourseStateModel
  local state = {
    courseId = courseId,
    segmentIndex = type(course.currentSegmentIndex) == "number" and course.currentSegmentIndex or -1,
    isLeft = course.currentSegmentIsLeft == true,
    segmentCount = #course.segments,
    workedCount = workedCount,
    worked = worked ~= "" and worked or nil,
  }

  -- currentSegment is the game's own side-offset clone of the line being followed, so the error is
  -- measured against what the vehicle is actually steering to. Absent until a line is picked.
  local segment = course.currentSegment
  if type(segment) == "table" and type(segment.positions) == "table" and vehicle.rootNode ~= nil then
    local okPos, wx, _, wz = pcall(getWorldTranslation, vehicle.rootNode)
    local okDir, dirX, _, dirZ = pcall(localDirectionToWorld, vehicle.rootNode, 0, 0, 1)
    if okPos and okDir and type(wx) == "number" and type(dirX) == "number" then
      local deviation, distanceToEnd = VDT.GpsCourse.lineState(segment.positions, wx, wz, dirX, dirZ)
      state.deviationM = deviation
      state.distanceToEndM = distanceToEnd
    end
  end

  return state
end

---Per-tick poll. The state collector refreshes too, but only while there IS a vehicle with a course:
---this is what notices the course going away (left the field, left the vehicle) and clears the file.
---@param _ GrisuDebug
---@param dt number? frame delta in ms
function VDT.GpsCourse.tick(_, dt)
  pollTimer = pollTimer + (type(dt) == "number" and dt or 0)
  if pollTimer < VDT.GpsCourse.POLL_MS then
    return
  end
  pollTimer = 0
  VDT.GpsCourse.refresh(VDT.GpsCourse.currentVehicle())
end

-- Self-register the channel (see ExportChannels). Poll-driven, like the event channels: no intervalMs,
-- so it is written only when tick/collectState notice a different course.
VDT.ExportChannels.register({
  name = VDT.GpsCourse.CHANNEL,
  fileName = VDT.GpsCourse.FILE_NAME,
  isAvailable = VDT.GpsCourse.isAvailable,
  collect = VDT.GpsCourse.collect,
  tick = VDT.GpsCourse.tick,
})
