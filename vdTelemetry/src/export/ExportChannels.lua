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
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.ExportChannels = {}

local channels = {} -- name -> channel
local order = {} -- registration order, so writes are deterministic
local dirty = {} -- name -> true while awaiting a write

---Register an export channel. Registration order is the write order.
---@param channel table { name, fileName, isAvailable, collect }
function VDT.ExportChannels.register(channel)
  channels[channel.name] = channel
  order[#order + 1] = channel.name
end

---Mark a channel for writing on the next flush. Called every interval for telemetry, and from the
---mods' messageCenter subscriptions for the event-driven channels.
---@param name string
function VDT.ExportChannels.markDirty(name)
  dirty[name] = true
end

-- Test seam: drop all registrations + dirty state (busted insulates _G per block, but the module
-- upvalues persist within a block, so specs call this between cases).
function VDT.ExportChannels.reset()
  channels = {}
  order = {}
  dirty = {}
end

---Mark every registered channel dirty — used when export is re-enabled to repopulate all files at
---once (each channel is still gated by its own isAvailable() at write time).
function VDT.ExportChannels.markAllDirty()
  for _, name in ipairs(order) do
    dirty[name] = true
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

---File names of the channels that are currently unavailable, i.e. that will never be written. The
---app reads a channel file's *absence* as "that mod isn't installed", so a file left behind by a
---session where the mod WAS installed keeps it showing stale data — the caller deletes these once at
---startup. Only meaningful once every mod has loaded (isAvailable() is false until its mod is up).
---@return string[]
function VDT.ExportChannels.unavailableFileNames()
  local names = {}
  for _, name in ipairs(order) do
    local ch = channels[name]
    if not ch.isAvailable() then
      names[#names + 1] = ch.fileName
    end
  end
  return names
end

---Per-tick hook: lets each channel do cheap setup (event-driven channels subscribe lazily here,
---since a third-party mod's message ids only exist once it has loaded) or keep its own cadence
---(interval-driven channels accumulate dt and markDirty themselves). Channels without tick() are
---skipped.
---@param debugger GrisuDebug
---@param dt number? frame delta in ms (from the engine's update)
function VDT.ExportChannels.tick(debugger, dt)
  for _, name in ipairs(order) do
    local ch = channels[name]
    if ch.tick ~= nil then
      ch.tick(debugger, dt)
    end
  end
end

---Channels that are both dirty and available, in registration order. Pure (no IO) so it's
---unit-testable; writeDirty() is the thin IO wrapper around it.
---@return table[] channels to write
function VDT.ExportChannels.selectDirty()
  local out = {}
  for _, name in ipairs(order) do
    local ch = channels[name]
    if dirty[name] and ch ~= nil and ch.isAvailable() then
      out[#out + 1] = ch
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

---Write every dirty + available channel and clear its dirty flag. A channel whose collect() returns
---nil is skipped (still cleared, so a transient nil doesn't spin). Writing is io.open write-mode
---(permitted by the sandbox); the model is serialized by the injected `encode` so this module stays
---decoupled from Json. A channel that throws is contained: it is logged and the rest still flush.
---@param dir string telemetry directory with trailing slash; files land at dir .. fileName
---@param encode fun(model: table): string serializer, e.g. Json.encode bound to prettyJson
---@param debugger GrisuDebug
function VDT.ExportChannels.writeDirty(dir, encode, debugger)
  for _, ch in ipairs(VDT.ExportChannels.selectDirty()) do
    dirty[ch.name] = nil
    -- Contain each channel: collect() on an integration reads a third-party mod's internals, which a
    -- mod update is free to rename (see the fail-soft contract in src/integrations/), and encode()
    -- then walks whatever it returned. Uncontained, one bad channel aborts the whole flush -- taking
    -- the core telemetry write down with it, every tick, for as long as the mod stays installed. The
    -- dirty flag is already cleared, so a throwing channel waits for its next change rather than
    -- spinning on the failure.
    local ok, err = pcall(writeChannel, ch, dir, encode, debugger)
    if not ok then
      debugger:error("channel %s: write failed (%s)", ch.name, tostring(err))
    end
  end
end
