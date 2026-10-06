-- Optional integration: More Vehicle Controls (FS25_moreVehicleControls). Namespaced under VDT.* (see
-- aspects/TurnOn.lua).
--
-- The alternative to Enhanced Vehicle for the drivetrain telltales: the mod switches the front and
-- rear diff locks and 2WD/4WD itself, and has a handbrake of its own. It fills the same three fields
-- Enhanced Vehicle does (declared in EnhancedVehicle.lua), so the app has nothing to learn.
--
-- Both state tables are plain fields on the vehicle (the mod's onLoad assigns `spec_mvcDifferentials`
-- and `spec_moreVehicleControls` by those names), so they are reachable from our environment without
-- going through FS25_moreVehicleControls -- and their absence is the "not installed" check. Every field
-- is synced to multiplayer clients by the mod's own stream and events, so a client reports the same
-- switch positions as the host.
--
-- What is reported is the switch, which the mod (re)applies to the engine's differentials on the host
-- whenever the two disagree -- not a reading back from the physics, which the engine doesn't offer.

VDT = VDT or {}
VDT.MoreVehicleControls = {}

---What the host can see of this vehicle's drivetrain, or nil where it can't.
---
---The mod hands front lock, rear lock and drive mode to *every* motorized vehicle, whatever it has to
---lock: on a machine without a centre differential it falls back to toggling a wheel differential as
---the "centre" one, and in its automatic mode it flips 2WD/4WD with speed on a 2WD truck all the same.
---Reporting that would light an AWD lamp on a machine that has no AWD. So the lamps follow the
---differentials the vehicle actually has, counted the way the mod itself sorts them in onPostLoad: a
---differential between two wheels is an axle (the first one front, the next rear), one between two
---differentials is the centre.
---
---The engine loads `spec_motorized.differentials` on the server only, so a multiplayer client sees an
---empty list and gets nil here -- it reports the switches unfiltered, as the mod's own HUD does.
---@return table? drivetrain { axles = number, centre = boolean }
local function drivetrainOf(vehicle)
  local differentials = vehicle.spec_motorized ~= nil and vehicle.spec_motorized.differentials or nil
  if type(differentials) ~= "table" or #differentials == 0 then
    return nil
  end
  local axles, centre = 0, false
  for _, diff in ipairs(differentials) do
    if diff.diffIndex1IsWheel and diff.diffIndex2IsWheel then
      axles = axles + 1
    elseif not diff.diffIndex1IsWheel and not diff.diffIndex2IsWheel then
      centre = true
    end
  end
  return { axles = axles, centre = centre }
end

-- Object stage: runs per vehicle/implement during the walk (see registry.lua). Only motorized
-- vehicles carry the mod's specs, so it no-ops for implements.
--
-- Enhanced Vehicle runs first and wins: with both installed both mods drive the same differentials,
-- and the lamp can only show one of them. A field Enhanced Vehicle left unset is still filled here.
---@param object table a vehicle or implement
---@param model table the object's already core-collected model
function VDT.MoreVehicleControls.contributeObject(object, model)
  local motor = model.motor
  if motor == nil then
    return
  end

  local diffs = object.spec_mvcDifferentials
  if type(diffs) == "table" then
    local drivetrain = drivetrainOf(object)
    -- With one wheel differential the mod's "front" and "rear" both fall back onto it, and since it
    -- applies rear after front, the rear switch is the one that sticks -- so that is the lock there is,
    -- reported under the mod's own name for it even on a machine whose one driven axle is the front.
    local hasFront = drivetrain == nil or drivetrain.axles >= 2
    local hasRear = drivetrain == nil or drivetrain.axles >= 1
    local hasCentre = drivetrain == nil or drivetrain.centre

    if motor.diffLock == nil then
      local diffLock = {}
      if hasFront and type(diffs.frontDiff) == "boolean" then
        diffLock.front = diffs.frontDiff
      end
      if hasRear and type(diffs.rearDiff) == "boolean" then
        diffLock.back = diffs.rearDiff
      end
      if next(diffLock) ~= nil then
        motor.diffLock = diffLock
      end
    end
    -- driveMode: 0 = 2WD, 1 = 4WD (the mod clamps anything higher to 1 on load)
    if motor.awd == nil and hasCentre and type(diffs.driveMode) == "number" then
      motor.awd = diffs.driveMode == 1
    end
  end

  local controls = object.spec_moreVehicleControls
  if motor.parkingBrake == nil and type(controls) == "table" and type(controls.handbrake) == "boolean" then
    motor.parkingBrake = controls.handbrake
  end
end
