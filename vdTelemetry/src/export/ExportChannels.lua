-- Registry of telemetry EXPORT CHANNELS: independent files written to the telemetry/ folder, each on
-- its own cadence. The main vdTelemetry.json is one channel (marked dirty every write interval); the
-- optional event-driven channels (TaskList, ...) are marked dirty from a messageCenter subscription
-- and written on the next tick. update() flushes whatever is dirty, so there's no second timer and
-- the slow, rarely-changing data isn't rewritten at the 100 ms telemetry cadence.
--
-- A channel is a table:
--   name        string           unique id; also the markDirty() key
--   fileName    string           written as <telemetryDir><fileName>
--   isAvailable fun(): boolean    false => never written (the mod isn't installed / no data yet)
--   collect     fun(): table|nil  builds the model to serialize; nil => skip this flush
--   tick        fun(debugger, dt)?  optional per-tick hook; event-driven channels subscribe lazily
--                                 here, interval-driven ones accumulate dt (ms) and mark themselves
--   minProfile  string?           lowest performance profile this channel runs at (see PROFILES);
--                                 below it the channel is off entirely -- for channels too expensive
--                                 to justify on a low-end machine. nil => runs at every profile
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.ExportChannels = {}

local channels = {} -- name -> channel
local order = {} -- registration order, so writes are deterministic
local dirty = {} -- name -> true while awaiting a write
local dirtyAt = {} -- name -> monotonic ms when it went clean->dirty (the longest-waiting drains first)
local timers = {} -- name -> ms accumulated since last fire, for interval-driven channels
local nowMs = 0 -- monotonic clock accumulated from tick(dt); the timebase for dirtyAt

-- Per-channel user config (from the settings XML, see configure()); absent entries fall back to the
-- channel's registered defaults (enabled, ch.intervalMs).
local enabledOverride = {} -- name -> boolean user toggle
local intervalOverride = {} -- name -> ms user interval, for interval-driven channels only

-- Interval channels are phase-staggered at registration so channels sharing an interval don't fire on
-- the same frame (each is seeded this far into its first period). Kept below the smallest interval.
local STAGGER_STEP_MS = 250
local intervalChannelCount = 0 -- running count of interval channels; drives the per-channel stagger

-- Floor for a user-configured interval (ms). The channels walk placeables/animals/vehicles, so an
-- interval near frame time would reintroduce the per-frame cost this module exists to spread out.
VDT.ExportChannels.MIN_INTERVAL_MS = 100

-- Performance profiles: a preset that scales every interval channel's registered default cadence. A
-- higher profile means more frequent updates (and more load); "custom" instead honours the per-channel
-- interval overrides (see configure). Order is the in-game selector order. See setProfile /
-- channelInterval.
--
-- A preset can also switch a channel off outright, for work too expensive to scale into acceptability
-- on a weak machine: a channel registered with `minProfile` is off under any preset below it (see
-- profileAllows). The user's own enable toggle is independent of the profile in both directions -- a
-- preset can disable a channel the user enabled, and "custom" honours the user's toggles alone.
VDT.ExportChannels.PROFILES = { "low", "medium", "high", "veryHigh", "custom" }
VDT.ExportChannels.DEFAULT_PROFILE = "high" -- scale 1.0 == the registered defaults
local PROFILE_SCALE = { low = 4.0, medium = 2.0, high = 1.0, veryHigh = 0.5 }
-- Ranking for minProfile comparisons; "custom" has no rank (it opts out of profile gating entirely).
local PROFILE_RANK = { low = 1, medium = 2, high = 3, veryHigh = 4 }
local profile = VDT.ExportChannels.DEFAULT_PROFILE

---Register an export channel. Registration order is the write order.
---@param channel table { name, fileName, isAvailable, collect, intervalMs?, tick?, latencyCritical? }
function VDT.ExportChannels.register(channel)
  channels[channel.name] = channel
  order[#order + 1] = channel.name
  if channel.intervalMs ~= nil then
    intervalChannelCount = intervalChannelCount + 1
    -- Seed the timer with a stagger phase so this channel's first (and hence every) fire is offset
    -- from the other interval channels; % intervalMs keeps the phase inside a single period.
    timers[channel.name] = (intervalChannelCount * STAGGER_STEP_MS) % channel.intervalMs
  end
end

---Mark a channel for writing on the next flush. Called for interval channels by tick(), and from the
---mods' messageCenter subscriptions for the event-driven channels. Records the clean->dirty moment
---(once, until cleared) so writeDirty can drain the longest-waiting channel first.
---@param name string
function VDT.ExportChannels.markDirty(name)
  if not dirty[name] then
    dirty[name] = true
    dirtyAt[name] = nowMs
  end
end

-- The STORED enable toggle: on unless the user turned it off in the settings XML (default true). This
-- is the value that persists (see configurableChannels) -- deliberately NOT the profile's verdict, for
-- the same reason channelStoredInterval isn't the scaled one: saving settings under a preset that
-- disables a channel would otherwise clobber the user's toggle and lose it on the way back up.
local function channelStoredEnabled(name)
  local v = enabledOverride[name]
  return v == nil or v
end

-- Whether the active profile permits this channel at all -- true unless it registered a minProfile the
-- current preset ranks below. "custom" opts out of profile gating: there the user's toggles decide.
local function profileAllows(ch)
  if ch.minProfile == nil or profile == "custom" then
    return true
  end
  return (PROFILE_RANK[profile] or 0) >= (PROFILE_RANK[ch.minProfile] or 0)
end

-- The EFFECTIVE enable state -- what the scheduler and the writer actually gate on: the user's toggle
-- AND the profile's verdict. Both must say yes.
local function channelEnabled(name)
  local ch = channels[name]
  return ch ~= nil and channelStoredEnabled(name) and profileAllows(ch)
end

-- The STORED interval for a channel: the user's override, else the registered default. This is the
-- value that persists to the settings XML and round-trips through "custom" -- it's independent of the
-- active profile, so saving under a preset never clobbers a custom override (see configurableChannels).
local function channelStoredInterval(ch)
  return intervalOverride[ch.name] or ch.intervalMs
end

-- The EFFECTIVE cadence for an interval channel -- what the tick scheduler actually uses. Under "custom"
-- it's the stored interval; under a preset it's the registered default scaled by the profile (clamped to
-- the floor). Deliberately scales the *default*, not the stored override, so a preset ignores custom
-- values (they're preserved for when the user switches back). Only called where ch.intervalMs ~= nil.
local function channelInterval(ch)
  if profile == "custom" then
    return channelStoredInterval(ch)
  end
  local scaled = math.floor(ch.intervalMs * PROFILE_SCALE[profile] + 0.5)
  return math.max(scaled, VDT.ExportChannels.MIN_INTERVAL_MS)
end

-- A profile id is valid if it's a known preset or the "custom" sentinel.
local function isValidProfile(name)
  return name == "custom" or PROFILE_SCALE[name] ~= nil
end

---Set the active performance profile. Unknown ids are rejected (returns false) so a stale settings
---value falls back to whatever's current. Takes effect immediately -- channelInterval reads it live,
---so the tick scheduler picks up the new cadence on the next frame.
---@param name string one of PROFILES
---@return boolean applied
function VDT.ExportChannels.setProfile(name)
  if not isValidProfile(name) then
    return false
  end
  profile = name
  return true
end

---@return string the active profile id
function VDT.ExportChannels.getProfile()
  return profile
end

---The effective cadence (ms) a registered interval channel currently runs at -- the active profile's
---scaling of its default, or its stored override under "custom". This is what the tick scheduler uses;
---configurableChannels() by contrast reports the *stored* interval that persists to the settings XML.
---nil for an event-driven channel (no interval) or an unknown name.
---@param name string
---@return number|nil
function VDT.ExportChannels.effectiveInterval(name)
  local ch = channels[name]
  if ch == nil or ch.intervalMs == nil then
    return nil
  end
  return channelInterval(ch)
end

-- Writable = registered, user-enabled, and its data source is available. selectDirty and the stale-
-- file cleanup both key off this, so a user-disabled channel is neither written nor left on disk.
local function isWritable(name)
  local ch = channels[name]
  return ch ~= nil and channelEnabled(name) and ch.isAvailable()
end

---Apply user config (from the settings XML) to a registered channel: an enable toggle and, for
---interval-driven channels, an interval override (clamped to MIN_INTERVAL_MS). A nil field leaves that
---setting at its default; an unknown channel id (a stale entry from a since-removed mod) is ignored.
---@param name string channel id
---@param opts table { enabled: boolean?, intervalMs: number? }
function VDT.ExportChannels.configure(name, opts)
  local ch = channels[name]
  if ch == nil then
    return
  end
  if opts.enabled ~= nil then
    enabledOverride[name] = opts.enabled and true or false
  end
  if opts.intervalMs ~= nil and ch.intervalMs ~= nil then
    intervalOverride[name] = math.max(opts.intervalMs, VDT.ExportChannels.MIN_INTERVAL_MS)
  end
end

---Enumerate the user-configurable channels — everything except the latency-critical live-telemetry
---channel — in registration order, for the settings XML read/write (so VDTelemetry never hardcodes the
---channel list). Both fields are the STORED user config, NOT the profile's verdict: `intervalMs` is the
---override-or-default rather than the scaled cadence, and `enabled` is the user's toggle rather than
---the effective state. Persisting the profile's view would overwrite the user's settings whenever
---settings are saved under a preset -- switching back to "custom" would restore the wrong cadence, and
---a preset that disables a channel via minProfile would permanently turn it off. `intervalMs` is nil
---for event-driven channels (no cadence to tune).
---@return table[] { name, enabled, intervalMs } per channel
function VDT.ExportChannels.configurableChannels()
  local out = {}
  for _, name in ipairs(order) do
    local ch = channels[name]
    if not ch.latencyCritical then
      out[#out + 1] = {
        name = name,
        enabled = channelStoredEnabled(name),
        intervalMs = ch.intervalMs ~= nil and channelStoredInterval(ch) or nil,
      }
    end
  end
  return out
end

-- Test seam: drop all registrations + dirty state (busted insulates _G per block, but the module
-- upvalues persist within a block, so specs call this between cases).
function VDT.ExportChannels.reset()
  channels = {}
  order = {}
  dirty = {}
  dirtyAt = {}
  timers = {}
  nowMs = 0
  intervalChannelCount = 0
  enabledOverride = {}
  intervalOverride = {}
  profile = VDT.ExportChannels.DEFAULT_PROFILE
end

---Mark every registered channel dirty — used when export is re-enabled to repopulate all files at
---once (each channel is still gated by its own isAvailable() at write time).
function VDT.ExportChannels.markAllDirty()
  for _, name in ipairs(order) do
    VDT.ExportChannels.markDirty(name)
  end
end

---File names of every registered channel, for bulk cleanup when export is disabled.
---@return string[]
function VDT.ExportChannels.fileNames()
  local names = {}
  for _, name in ipairs(order) do
    names[#names + 1] = channels[name].fileName
  end
  return names
end

---File names of the channels that will never be written this session — either their data source is
---unavailable (mod not installed) OR the user disabled the channel in the settings. The app reads a
---channel file's *absence* as "off / not installed", so a file left behind by a session where the
---channel WAS written keeps it showing stale data — the caller deletes these once at startup. Only
---meaningful once every mod has loaded (isAvailable() is false until its mod is up).
---@return string[]
function VDT.ExportChannels.unavailableFileNames()
  local names = {}
  for _, name in ipairs(order) do
    if not isWritable(name) then
      names[#names + 1] = channels[name].fileName
    end
  end
  return names
end

---Per-tick hook. Two responsibilities:
---  * interval-driven channels: the registry owns the cadence (was per-exporter) -- accumulate the
---    frame delta and markDirty each intervalMs. Reset-to-0 on fire preserves the registration-time
---    stagger phase, so channels sharing an interval keep firing on different frames.
---  * event/poll-driven channels: run their custom tick() (lazy messageCenter subscribe, change-diff).
---A channel may have either, both, or neither.
---@param debugger GrisuDebug
---@param dt number? frame delta in ms (from the engine's update)
function VDT.ExportChannels.tick(debugger, dt)
  if type(dt) == "number" then
    nowMs = nowMs + dt
  end
  for _, name in ipairs(order) do
    local ch = channels[name]
    -- A disabled channel (user toggle or profile) does nothing at all: it neither accumulates -- which
    -- would queue a write selectDirty only drops again, leaving a dangling dirty flag -- nor ticks. The
    -- tick is where the expensive work lives (mapLayers grid-samples the map from its tick), so running
    -- it for a channel that will never be written is pure waste; "off" has to mean off.
    if channelEnabled(name) then
      if ch.intervalMs ~= nil and type(dt) == "number" then
        timers[name] = (timers[name] or 0) + dt
        if timers[name] >= channelInterval(ch) then
          timers[name] = 0
          VDT.ExportChannels.markDirty(name)
        end
      end
      if ch.tick ~= nil then
        ch.tick(debugger, dt)
      end
    end
  end
end

---Channels that are dirty and writable (registered, user-enabled, data available), in registration
---order. Pure (no IO) so it's unit-testable; writeDirty() is the thin IO wrapper around it.
---@return table[] channels to write
function VDT.ExportChannels.selectDirty()
  local out = {}
  for _, name in ipairs(order) do
    if dirty[name] and isWritable(name) then
      out[#out + 1] = channels[name]
    end
  end
  return out
end

---Write one channel's file. May raise (collect / encode read and walk third-party data); writeDirty
---pcalls it. The open handle is closed on the way out either way -- an escaping error would otherwise
---leak the file descriptor, and the engine's Lua has no `finally`.
---@param ch table the channel
---@param dir string telemetry directory with trailing slash
---@param encode fun(model: table): string
---@param debugger GrisuDebug
local function writeChannel(ch, dir, encode, debugger)
  local model = ch.collect()
  if model == nil then
    debugger:trace("channel %s: collect returned nil, skipping", ch.name)
    return
  end

  local path = dir .. ch.fileName
  local file = io.open(path, "w")
  if file == nil then
    debugger:error("channel %s: could not open %s", ch.name, tostring(path))
    return
  end

  local ok, err = pcall(function()
    file:write(encode(model))
  end)
  file:close()
  if not ok then
    error(err, 0) -- rethrow to writeDirty's pcall, now that the handle is closed
  end
  debugger:trace("channel %s: wrote %s", ch.name, ch.fileName)
end

---Clear a channel's dirty state and write it, containing any throw. Extracted so writeDirty can call
---it from both the latency-critical and the spread paths.
---Contain each channel: collect() on an integration reads a third-party mod's internals, which a mod
---update is free to rename (see the fail-soft contract in src/integrations/), and encode() then walks
---whatever it returned. Uncontained, one bad channel aborts the whole flush -- taking the core
---telemetry write down with it, every tick, for as long as the mod stays installed. The dirty flag is
---cleared first, so a throwing channel waits for its next change rather than spinning on the failure.
---@param ch table the channel
---@param dir string telemetry directory with trailing slash
---@param encode fun(model: table): string
---@param debugger GrisuDebug
local function flushChannel(ch, dir, encode, debugger)
  dirty[ch.name] = nil
  dirtyAt[ch.name] = nil
  local ok, err = pcall(writeChannel, ch, dir, encode, debugger)
  if not ok then
    debugger:error("channel %s: write failed (%s)", ch.name, tostring(err))
  end
end

---Flush dirty + available channels, spreading the heavy ones across frames. A `latencyCritical`
---channel (the live-dashboard telemetry) always writes -- it's cheap and can't tolerate the backlog
---latency. Every other channel is subject to a one-per-frame budget: only the single longest-waiting
---one writes this call, the rest stay dirty and drain on following frames. This keeps several
---channels coming due on the same frame from collecting + serializing + writing all at once (the
---per-frame cost the game feels). A channel whose collect() returns nil is skipped (still cleared, so
---a transient nil doesn't spin). Writing is io.open write-mode (sandbox-permitted); the model is
---serialized by the injected `encode` so this module stays decoupled from Json.
---@param dir string telemetry directory with trailing slash; files land at dir .. fileName
---@param encode fun(model: table): string serializer, e.g. Json.encode bound to prettyJson
---@param debugger GrisuDebug
function VDT.ExportChannels.writeDirty(dir, encode, debugger)
  local oldest -- the single longest-waiting non-critical channel to flush this frame
  for _, ch in ipairs(VDT.ExportChannels.selectDirty()) do
    if ch.latencyCritical then
      flushChannel(ch, dir, encode, debugger)
    elseif oldest == nil or (dirtyAt[ch.name] or 0) < (dirtyAt[oldest.name] or 0) then
      -- selectDirty is in registration order, so a strict < makes the earliest-registered channel win
      -- ties -- a deterministic drain order.
      oldest = ch
    end
  end
  if oldest ~= nil then
    flushChannel(oldest, dir, encode, debugger)
  end
end
