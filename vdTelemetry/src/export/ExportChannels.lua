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
--   tick        fun(debugger)?    optional per-tick hook; event-driven channels subscribe lazily here
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

---Per-tick hook: lets each channel do cheap setup (event-driven channels subscribe lazily here,
---since a third-party mod's message ids only exist once it has loaded). Channels without tick() are
---skipped.
---@param debugger GrisuDebug
function VDT.ExportChannels.tick(debugger)
  for _, name in ipairs(order) do
    local ch = channels[name]
    if ch.tick ~= nil then
      ch.tick(debugger)
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

---Write every dirty + available channel and clear its dirty flag. A channel whose collect() returns
---nil is skipped (still cleared, so a transient nil doesn't spin). Writing is io.open write-mode
---(permitted by the sandbox); the model is serialized by the injected `encode` so this module stays
---decoupled from Json.
---@param dir string telemetry directory with trailing slash; files land at dir .. fileName
---@param encode fun(model: table): string serializer, e.g. Json.encode bound to prettyJson
---@param debugger GrisuDebug
function VDT.ExportChannels.writeDirty(dir, encode, debugger)
  for _, ch in ipairs(VDT.ExportChannels.selectDirty()) do
    dirty[ch.name] = nil
    local model = ch.collect()
    if model == nil then
      debugger:trace("channel %s: collect returned nil, skipping", ch.name)
    else
      local path = dir .. ch.fileName
      local file = io.open(path, "w")
      if file == nil then
        debugger:error("channel %s: could not open %s", ch.name, tostring(path))
      else
        file:write(encode(model))
        file:close()
        debugger:trace("channel %s: wrote %s", ch.name, ch.fileName)
      end
    end
  end
end
