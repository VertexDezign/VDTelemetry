-- Model definitions for the driver-assist subtrees (gps / ai / cruise control). Annotation-only
-- (see EnvironmentModel.lua). Maps to Model.kt Gps / Ai / CruiseControl.

---@class GpsModel
---@field enabled boolean
---@field active boolean
---@field heading number
---@field headingUnit string
---@field linesVisible boolean whether the steering-assist lines are drawn (a global client setting)
---@field course GpsCourseStateModel? absent when the vehicle has no steering course

--- The live half of the steering course — the part that changes as you drive, so it rides here on the
--- 10 Hz telemetry rather than in the gpsCourse.json geometry channel (see GpsCourseModel.lua).
--- `courseId` says which course the indices below belong to; the app ignores them until it holds the
--- geometry published under that same id.
---@class GpsCourseStateModel
---@field courseId string
---@field segmentIndex number the line being followed, -1 when none is picked
---@field isLeft boolean which side of the line the game has the vehicle assigned to
---@field segmentCount number
---@field workedCount number how many lines are done
---@field worked string? hex bitmask over segment indices, 4 per character (VDT.GpsCourse.workedMask)
---@field deviationM number? signed cross-track error in meters, + = right of the line
---@field distanceToEndM number? meters of line left ahead of the vehicle

---@class AiModel
---@field active boolean

---@class CruiseControlModel
---@field targetSpeed number?
---@field active boolean?
