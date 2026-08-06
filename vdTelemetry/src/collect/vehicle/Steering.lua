-- Collects how the machine is set up to be *driven*: the steering mode it is in (crab steering) and
-- whether the driving position has been turned round. Vehicle-only.
-- Namespaced under VDT.* (see aspects/TurnOn.lua).
--
-- The steering mode's `layout` is derived rather than read: see layoutOf below for why the mode's own
-- name can't be used for it.

VDT = VDT or {}
VDT.Steering = {}

-- Below this a held-over axle is modelling noise rather than a driver's dog walk. ~0.6°, in radians,
-- which is the unit every angle in the wheel data is in.
local OFFSET_EPSILON = 0.01

-- Which way a positive steering offset points a wheel. The engine's vehicle space is +Z forward (its
-- own AI code takes localDirectionToWorld(rootNode, 0, 0, 1) as the way a vehicle faces) and +X left
-- (a work area's right-hand extent comes back with a negative local X), and a steering offset is a
-- rotation about +Y — which takes +Z towards +X. So a wheel held at a positive offset points left.
--
-- This is the one thing here that assumes a convention rather than comparing two things that share
-- one, and it is worth knowing that if a dog-walk mode ever draws mirrored, this constant is the
-- whole fix.
local OFFSET_POSITIVE_IS = "LEFT"

---@param value number
---@return number -1, 0 or 1
local function signOf(value)
  if value > 0 then
    return 1
  elseif value < 0 then
    return -1
  end
  return 0
end

---One thing on the machine that can steer: a wheel, or a steering node an axle hangs off.
---
---`rate` is signed, and **only its sign relative to the other axle's is ever used** — nothing here
---assumes which way round the engine's own convention runs, because a machine whose rear axle is
---built to counter-steer carries that in the sign of its resting rate.
---@param z number position along the machine, in the root component's space
---@param rate number
---@param offset number signed angle this element is held at regardless of the steering wheel
local function element(z, rate, offset)
  return { z = z, rate = rate, offset = offset }
end

---Index a mode's per-element entries by the wheel or steering node they are about, so the loops below
---can ask "what does this mode do to this thing" instead of walking the list again per element.
local function entriesBy(list, key)
  local byObject = {}
  for _, entry in ipairs(list or {}) do
    if entry[key] ~= nil then
      byObject[entry[key]] = entry
    end
  end
  return byObject
end

---Every steerable element on the vehicle, positioned along it, as **this mode** leaves it. nil when
---the vehicle has no wheels to place, or no scene root to place them against.
---
---Read from the mode's own declared entries rather than from the live wheel state. The live values
---are animated towards the mode's over about a second, so a glyph built from them shows the shape the
---machine is coming *from* for as long as it takes to get there — and a wheel the new mode does not
---mention keeps the offset an older mode gave it, since nothing animates it back.
---@param vehicle Vehicle
---@param mode table the current steering mode
---@return table[]|nil
local function gatherElements(vehicle, mode)
  local wSpec = vehicle.spec_wheels
  if wSpec == nil or wSpec.wheels == nil then
    return nil
  end
  local components = vehicle.components
  local root = components ~= nil and components[1] ~= nil and components[1].node or nil
  if root == nil then
    return nil
  end

  local elements = {}
  local wheelEntries = entriesBy(mode.wheels, "wheel")
  for _, wheel in ipairs(wSpec.wheels) do
    -- The wheel's own node, so its origin is the hub. It turns with the steering, but a node's origin
    -- does not move when the node rotates, so the position holds whatever lock the wheels are on.
    if wheel.repr ~= nil and wheel.physics ~= nil then
      local _, _, z = localToLocal(wheel.repr, root, 0, 0, 0)
      -- A wheel's steering sense is fixed by its own rotSpeed, and a mode can only take it away:
      -- `rotSpeedBackUp` is where CrabSteering parks the built-in value while a mode holds the live
      -- one at zero, so it is the one to read. A wheel this mode says nothing about steers as built.
      local entry = wheelEntries[wheel]
      local rate = wheel.rotSpeedBackUp or wheel.physics.rotSpeed or 0
      local offset = 0
      if entry ~= nil then
        rate = entry.locked and 0 or rate
        offset = entry.offset or 0
      end
      table.insert(elements, element(z, rate, offset))
    end
  end
  if #elements == 0 then
    return nil
  end

  -- Steering nodes are the other half of the mechanism: a mode can flip a whole axle by setting the
  -- node's rotScale negative, or stop it by locking it. The node's own rotSpeed carries the axle's
  -- built-in sense, so the product is the rate.
  local nodeEntries = entriesBy(mode.steeringNodes, "steeringNode")
  for _, steeringNode in ipairs(wSpec.steeringNodes or {}) do
    if steeringNode.node ~= nil then
      local _, _, z = localToLocal(steeringNode.node, root, 0, 0, 0)
      local entry = nodeEntries[steeringNode]
      local scale = steeringNode.rotScaleOrig or steeringNode.rotScale or 1
      local offset = 0
      if entry ~= nil then
        if entry.locked then
          scale = 0
        elseif entry.rotScale ~= nil then
          scale = entry.rotScale
        end
        offset = entry.offset or 0
      end
      table.insert(elements, element(z, scale * (steeringNode.rotSpeed or 1), offset))
    end
  end

  return elements
end

---The strongest-steering element of a group, plus the furthest any of them is held over.
---
---Strongest rather than summed: a machine can carry both a steered wheel and the steering node its
---axle hangs off, and two entries for one axle would otherwise cancel each other out and report an
---axle that steers as one that doesn't.
local function axleOf(group)
  local rate, offset = 0, 0
  for _, entry in ipairs(group) do
    if math.abs(entry.rate) > math.abs(rate) then
      rate = entry.rate
    end
    if math.abs(entry.offset) > math.abs(offset) then
      offset = entry.offset
    end
  end
  return {
    steers = rate ~= 0,
    sense = signOf(rate),
    -- Kept signed and as an angle: which way the wheels are held over is the difference between the
    -- two dog walks, and the two axles' offsets are added up to find the machine's own.
    held = math.abs(offset) > OFFSET_EPSILON and offset or 0,
  }
end

---Which wheels this steering mode steers, and which way — the shape a dashboard draws.
---
---Derived from the wheels themselves rather than from the mode's name, because that name is free
---L10N text out of each vehicle's XML: the game prints it and nothing more, every mod picks its own
---wording, and it arrives translated. The wheels are the same on every machine.
---
---nil when the machine can't be read this way — a single axle, an articulated frame that steers on
---its component joint rather than on a wheel, anything with no root component. The name still goes
---out, so a consumer has something to fall back on.
---@param vehicle Vehicle
---@return string|nil "FRONT" / "BACK" / "ALL_WHEEL" / "CRAB" / "CRAB_LEFT" / "CRAB_RIGHT"
local function layoutOf(vehicle, mode)
  local elements = gatherElements(vehicle, mode)
  if elements == nil then
    return nil
  end

  -- Split the machine at the midpoint of its own wheelbase rather than at the root node, which sits
  -- wherever the modeller left it. Ends, not axles: a three-axle machine puts its two rear ones in
  -- the same group, which is what the drawing shows anyway.
  local minZ, maxZ = elements[1].z, elements[1].z
  for _, entry in ipairs(elements) do
    minZ = math.min(minZ, entry.z)
    maxZ = math.max(maxZ, entry.z)
  end
  if maxZ - minZ < OFFSET_EPSILON then
    return nil
  end
  local middle = (minZ + maxZ) * 0.5

  local front, back = {}, {}
  for _, entry in ipairs(elements) do
    table.insert(entry.z >= middle and front or back, entry)
  end
  if #front == 0 or #back == 0 then
    return nil
  end

  local frontAxle, backAxle = axleOf(front), axleOf(back)

  -- Being **held over** is what makes a dog walk, and it is checked before anything else because the
  -- axles of one go on steering exactly as they did before. The offset is a *rest angle* — the engine
  -- steers from it rather than instead of it (CrabSteering:updateSteeringAngle: `steeringAngle =
  -- wheel.steeringOffset + rotatedTime * f * rotSpeed`) — so a machine in "dog walk left" still has
  -- one axle counter-steering the other and reads as ordinary four-wheel steering if you only compare
  -- the senses. That bakes the direction into the mode, which is why such a machine offers *two* of
  -- them, and why the side belongs in the layout rather than being flattened into one "crab".
  --
  -- Summed across the two ends: a dog walk holds them the same way, so they add up, while a symmetric
  -- toe-in cancels to nothing and falls through to the senses below, which is right — that is not a
  -- machine tracking diagonally.
  local held = frontAxle.held + backAxle.held
  if math.abs(held) > OFFSET_EPSILON then
    return held > 0 == (OFFSET_POSITIVE_IS == "LEFT") and "CRAB_LEFT" or "CRAB_RIGHT"
  end

  if frontAxle.steers and backAxle.steers then
    -- Nothing held over, so the shape is in the senses alone: both ends turning the same way is a
    -- crab that has no side of its own — it walks whichever way the driver steers — and opposite ways
    -- is the tight four-wheel turn.
    return frontAxle.sense == backAxle.sense and "CRAB" or "ALL_WHEEL"
  elseif frontAxle.steers then
    return "FRONT"
  elseif backAxle.steers then
    return "BACK"
  end
  return nil
end

---@param vehicle Vehicle
---@return SteeringModel|nil nil unless the vehicle has steering modes or a reversible driving position
function VDT.Steering.collect(vehicle)
  local model = nil

  local cSpec = vehicle.spec_crabSteering
  -- `hasSteeringModes` and not the spec alone: the specialization is on the vehicle *type*, and a
  -- configuration that loaded no modes leaves it there with an empty list.
  if cSpec ~= nil and cSpec.hasSteeringModes and vehicle.getCrabSteeringMode ~= nil then
    local mode = vehicle:getCrabSteeringMode()
    if mode ~= nil then
      local name = mode.name
      model = {
        mode = {
          -- Absent rather than empty: the name defaults to "" when the vehicle's XML names no mode,
          -- and nothing downstream should have to tell an unnamed mode from a missing one.
          name = (name ~= nil and name ~= "") and name or nil,
          index = mode.index or cSpec.state,
          count = cSpec.stateMax,
          layout = layoutOf(vehicle, mode),
        },
      }
    end
  end

  local rSpec = vehicle.spec_reverseDriving
  -- Same gate: the spec sits on plenty of types whose model has no reverse-driving animation, and
  -- those report a permanent `false` that would draw a seat symbol on a tractor that has no such seat.
  if rSpec ~= nil and rSpec.hasReverseDriving then
    model = model or {}
    model.reversed = rSpec.isReverseDriving == true
    model.changing = rSpec.isChangingDirection == true
  end

  return model
end
