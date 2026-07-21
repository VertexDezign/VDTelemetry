-- Unit tests for the export-channel registry (src/export/ExportChannels.lua).
--
-- Run with `busted` from the vdTelemetry/ directory. ExportChannels sets a global `VDT.ExportChannels`
-- when executed and has no engine dependency (writes go through plain io.open, and serialization is an
-- injected function), so we load it directly and reset() its state before each test.

if VDT == nil or VDT.ExportChannels == nil then
  dofile("src/export/ExportChannels.lua")
end

-- swallow the trace/error logging writeDirty emits
local debugger = { trace = function() end, error = function() end }

-- Serializer stub: each channel's collect() returns { body = <string> }, so the written file content
-- is exactly that string — lets us assert which model reached disk without pulling in Json.
local function encode(model)
  return model.body
end

-- A channel whose availability + collected body are fixed at construction. `collect` counts its calls
-- so we can assert a channel is (not) collected.
local function channel(name, available, body)
  local ch = { name = name, fileName = name .. ".json", collects = 0 }
  ch.isAvailable = function()
    return available
  end
  ch.collect = function()
    ch.collects = ch.collects + 1
    return body
  end
  return ch
end

-- Read a file written by writeDirty (dir is a plain prefix, so path = dir .. name .. ".json").
local function readFile(path)
  local f = io.open(path, "r")
  if f == nil then
    return nil
  end
  local content = f:read("*a")
  f:close()
  return content
end

describe("ExportChannels.selectDirty", function()
  before_each(function()
    VDT.ExportChannels.reset()
  end)

  it("returns nothing when nothing is dirty", function()
    VDT.ExportChannels.register(channel("a", true, { body = "A" }))
    assert.are.equal(0, #VDT.ExportChannels.selectDirty())
  end)

  it("returns only channels that are dirty AND available, in registration order", function()
    VDT.ExportChannels.register(channel("first", true, { body = "1" }))
    VDT.ExportChannels.register(channel("second", false, { body = "2" }))
    VDT.ExportChannels.register(channel("third", true, { body = "3" }))
    VDT.ExportChannels.markDirty("first")
    VDT.ExportChannels.markDirty("second") -- dirty but unavailable -> excluded
    VDT.ExportChannels.markDirty("third")

    local dirty = VDT.ExportChannels.selectDirty()
    assert.are.equal(2, #dirty)
    assert.are.equal("first", dirty[1].name)
    assert.are.equal("third", dirty[2].name)
  end)
end)

describe("ExportChannels.writeDirty", function()
  local dir

  before_each(function()
    VDT.ExportChannels.reset()
    -- os.tmpname gives a unique path; remove it and reuse it as a filename prefix (dir is concatenated
    -- with fileName, so it need not be a real directory).
    dir = os.tmpname()
    os.remove(dir)
    dir = dir .. "_"
  end)

  it("writes each dirty channel's collected body and clears its dirty flag", function()
    local a = channel("alpha", true, { body = "hello" })
    VDT.ExportChannels.register(a)
    VDT.ExportChannels.markDirty("alpha")

    VDT.ExportChannels.writeDirty(dir, encode, debugger)
    assert.are.equal("hello", readFile(dir .. "alpha.json"))
    assert.are.equal(1, a.collects)

    -- flag cleared: a second flush without re-marking writes nothing new
    os.remove(dir .. "alpha.json")
    VDT.ExportChannels.writeDirty(dir, encode, debugger)
    assert.is_nil(readFile(dir .. "alpha.json"))
    assert.are.equal(1, a.collects)
  end)

  it("does not write or collect an unavailable channel", function()
    local off = channel("off", false, { body = "x" })
    VDT.ExportChannels.register(off)
    VDT.ExportChannels.markDirty("off")

    VDT.ExportChannels.writeDirty(dir, encode, debugger)
    assert.is_nil(readFile(dir .. "off.json"))
    assert.are.equal(0, off.collects)
  end)

  it("contains a throwing channel: the others still flush, and it isn't retried", function()
    -- A collector reading a third-party mod's internals can throw when that mod changes. Uncontained
    -- it would abort the whole flush, so the CORE telemetry write must survive a broken integration.
    local boom = channel("boom", true, { body = "x" })
    boom.collect = function()
      boom.collects = boom.collects + 1
      error("mod internals changed")
    end
    local errors = 0
    local loud = {
      trace = function() end,
      error = function()
        errors = errors + 1
      end,
    }
    local core = channel("core", true, { body = "telemetry" })
    core.latencyCritical = true -- always flushes, so a throwing heavy channel can't starve it
    VDT.ExportChannels.register(boom)
    VDT.ExportChannels.register(core)
    VDT.ExportChannels.markAllDirty()

    VDT.ExportChannels.writeDirty(dir, encode, loud)
    assert.are.equal("telemetry", readFile(dir .. "core.json")) -- the good channel still wrote
    assert.are.equal(1, errors) -- and the failure was reported, not swallowed

    -- dirty cleared despite the throw, so it waits for its next change instead of spinning
    assert.are.equal(0, #VDT.ExportChannels.selectDirty())
    VDT.ExportChannels.writeDirty(dir, encode, loud)
    assert.are.equal(1, boom.collects)
  end)

  it("closes the file when encode throws (and reports it)", function()
    local ch = channel("bad", true, { body = "x" })
    local errors = 0
    local loud = {
      trace = function() end,
      error = function()
        errors = errors + 1
      end,
    }
    VDT.ExportChannels.register(ch)
    VDT.ExportChannels.markDirty("bad")

    VDT.ExportChannels.writeDirty(dir, function()
      error("unserializable model")
    end, loud)
    assert.are.equal(1, errors)
  end)

  it("skips (but clears) a channel whose collect returns nil", function()
    local nilCh = channel("empty", true, nil)
    VDT.ExportChannels.register(nilCh)
    VDT.ExportChannels.markDirty("empty")

    VDT.ExportChannels.writeDirty(dir, encode, debugger)
    assert.is_nil(readFile(dir .. "empty.json"))
    assert.are.equal(1, nilCh.collects)
    -- dirty cleared, so it isn't retried until re-marked
    assert.are.equal(0, #VDT.ExportChannels.selectDirty())
  end)
end)

describe("ExportChannels.markAllDirty / fileNames / unavailableFileNames / tick", function()
  before_each(function()
    VDT.ExportChannels.reset()
  end)

  it("markAllDirty marks every available channel", function()
    VDT.ExportChannels.register(channel("a", true, { body = "A" }))
    VDT.ExportChannels.register(channel("b", true, { body = "B" }))
    VDT.ExportChannels.markAllDirty()
    assert.are.equal(2, #VDT.ExportChannels.selectDirty())
  end)

  it("fileNames returns each channel's file in registration order", function()
    VDT.ExportChannels.register(channel("first", true, { body = "1" }))
    VDT.ExportChannels.register(channel("second", true, { body = "2" }))
    assert.are.same({ "first.json", "second.json" }, VDT.ExportChannels.fileNames())
  end)

  it("unavailableFileNames returns only the files of channels that will never be written", function()
    VDT.ExportChannels.register(channel("installed", true, { body = "1" }))
    VDT.ExportChannels.register(channel("missing", false, { body = "2" }))
    VDT.ExportChannels.register(channel("alsoMissing", false, { body = "3" }))
    assert.are.same({ "missing.json", "alsoMissing.json" }, VDT.ExportChannels.unavailableFileNames())
  end)

  it("unavailableFileNames returns nothing when every channel is available", function()
    VDT.ExportChannels.register(channel("a", true, { body = "A" }))
    assert.are.equal(0, #VDT.ExportChannels.unavailableFileNames())
  end)

  it("tick invokes the per-channel tick hook (and skips channels without one)", function()
    local ticked = 0
    local withTick = channel("evented", true, { body = "x" })
    withTick.tick = function()
      ticked = ticked + 1
    end
    VDT.ExportChannels.register(channel("plain", true, { body = "y" })) -- no tick, must be skipped
    VDT.ExportChannels.register(withTick)

    VDT.ExportChannels.tick(debugger)
    assert.are.equal(1, ticked)
  end)
end)

-- An interval-driven channel: the registry accumulates dt and marks it dirty every intervalMs.
local function intervalChannel(name, interval)
  local ch = channel(name, true, { body = name })
  ch.intervalMs = interval
  return ch
end

describe("ExportChannels interval cadence + stagger", function()
  local dir

  before_each(function()
    VDT.ExportChannels.reset()
    dir = os.tmpname()
    os.remove(dir)
    dir = dir .. "_"
  end)

  it("marks an interval channel dirty once its intervalMs of frame time has accumulated", function()
    -- First interval channel is seeded with a 250 ms stagger phase, so it fires 250 ms early.
    VDT.ExportChannels.register(intervalChannel("prod", 1000))

    VDT.ExportChannels.tick(debugger, 500)
    assert.are.equal(0, #VDT.ExportChannels.selectDirty()) -- 500 + 250 phase = 750 < 1000
    VDT.ExportChannels.tick(debugger, 500)
    assert.are.equal(1, #VDT.ExportChannels.selectDirty()) -- 1250 >= 1000 -> queued
  end)

  it("ignores a tick without dt (older framework caller)", function()
    VDT.ExportChannels.register(intervalChannel("prod", 1000))
    VDT.ExportChannels.tick(debugger, nil)
    assert.are.equal(0, #VDT.ExportChannels.selectDirty())
  end)

  it("staggers channels sharing an interval so they don't fire on the same frame", function()
    -- phases: prod = 1*250 = 250, storage = 2*250 = 500 (both mod 1000).
    VDT.ExportChannels.register(intervalChannel("prod", 1000))
    VDT.ExportChannels.register(intervalChannel("storage", 1000))

    VDT.ExportChannels.tick(debugger, 500) -- prod 750 (<1000), storage 1000 (fires)
    local dirty = VDT.ExportChannels.selectDirty()
    assert.are.equal(1, #dirty)
    assert.are.equal("storage", dirty[1].name) -- prod did NOT fire on this frame
    VDT.ExportChannels.writeDirty(dir, encode, debugger) -- drain storage

    VDT.ExportChannels.tick(debugger, 500) -- prod 1250 (fires), storage reset -> 500 (<1000)
    dirty = VDT.ExportChannels.selectDirty()
    assert.are.equal(1, #dirty)
    assert.are.equal("prod", dirty[1].name) -- prod fires on its own later frame
  end)
end)

describe("ExportChannels.writeDirty spreading", function()
  local dir

  before_each(function()
    VDT.ExportChannels.reset()
    dir = os.tmpname()
    os.remove(dir)
    dir = dir .. "_"
  end)

  it("writes at most one non-critical channel per flush, draining the rest on later frames", function()
    local a = channel("a", true, { body = "A" })
    local b = channel("b", true, { body = "B" })
    local c = channel("c", true, { body = "C" })
    VDT.ExportChannels.register(a)
    VDT.ExportChannels.register(b)
    VDT.ExportChannels.register(c)
    VDT.ExportChannels.markAllDirty() -- all dirty at the same nowMs -> registration order breaks the tie

    VDT.ExportChannels.writeDirty(dir, encode, debugger)
    assert.are.equal("A", readFile(dir .. "a.json"))
    assert.is_nil(readFile(dir .. "b.json"))
    assert.is_nil(readFile(dir .. "c.json"))
    assert.are.equal(1, a.collects)
    assert.are.equal(0, b.collects) -- not even collected: spread caps the per-frame work
    assert.are.equal(0, c.collects)

    VDT.ExportChannels.writeDirty(dir, encode, debugger)
    assert.are.equal("B", readFile(dir .. "b.json"))
    assert.is_nil(readFile(dir .. "c.json"))

    VDT.ExportChannels.writeDirty(dir, encode, debugger)
    assert.are.equal("C", readFile(dir .. "c.json"))
    assert.are.equal(0, #VDT.ExportChannels.selectDirty()) -- fully drained
  end)

  it("drains the longest-waiting channel first, regardless of registration order", function()
    local x = channel("x", true, { body = "X" })
    local y = channel("y", true, { body = "Y" })
    VDT.ExportChannels.register(x)
    VDT.ExportChannels.register(y)

    VDT.ExportChannels.markDirty("y") -- y goes dirty at nowMs = 0
    VDT.ExportChannels.tick(debugger, 100) -- clock advances to 100
    VDT.ExportChannels.markDirty("x") -- x goes dirty later, at nowMs = 100

    VDT.ExportChannels.writeDirty(dir, encode, debugger)
    assert.are.equal("Y", readFile(dir .. "y.json")) -- older wins over earlier-registered x
    assert.is_nil(readFile(dir .. "x.json"))
  end)

  it("always flushes a latencyCritical channel alongside the single spread pick", function()
    local core = channel("core", true, { body = "T" })
    core.latencyCritical = true
    local h1 = channel("h1", true, { body = "H1" })
    local h2 = channel("h2", true, { body = "H2" })
    VDT.ExportChannels.register(core)
    VDT.ExportChannels.register(h1)
    VDT.ExportChannels.register(h2)
    VDT.ExportChannels.markAllDirty()

    VDT.ExportChannels.writeDirty(dir, encode, debugger)
    assert.are.equal("T", readFile(dir .. "core.json")) -- exempt: always written
    assert.are.equal("H1", readFile(dir .. "h1.json")) -- one heavy channel
    assert.is_nil(readFile(dir .. "h2.json")) -- the other waits a frame

    VDT.ExportChannels.writeDirty(dir, encode, debugger)
    assert.are.equal("H2", readFile(dir .. "h2.json"))
  end)
end)

describe("ExportChannels per-channel config", function()
  before_each(function()
    VDT.ExportChannels.reset()
  end)

  it("a user-disabled channel is neither written nor listed as writable", function()
    VDT.ExportChannels.register(channel("prod", true, { body = "P" }))
    VDT.ExportChannels.markDirty("prod")
    assert.are.equal(1, #VDT.ExportChannels.selectDirty()) -- enabled by default

    VDT.ExportChannels.configure("prod", { enabled = false })
    assert.are.equal(0, #VDT.ExportChannels.selectDirty()) -- disabled -> excluded even though dirty
  end)

  it("a disabled channel's file is cleaned (appears in unavailableFileNames)", function()
    VDT.ExportChannels.register(channel("on", true, { body = "1" }))
    VDT.ExportChannels.register(channel("off", true, { body = "2" }))
    VDT.ExportChannels.configure("off", { enabled = false })
    -- 'on' is available + enabled -> not listed; 'off' is disabled -> listed for deletion
    assert.are.same({ "off.json" }, VDT.ExportChannels.unavailableFileNames())
  end)

  it("re-enabling restores writability", function()
    VDT.ExportChannels.register(channel("prod", true, { body = "P" }))
    VDT.ExportChannels.configure("prod", { enabled = false })
    VDT.ExportChannels.markDirty("prod")
    assert.are.equal(0, #VDT.ExportChannels.selectDirty())
    VDT.ExportChannels.configure("prod", { enabled = true })
    assert.are.equal(1, #VDT.ExportChannels.selectDirty())
  end)

  it("an interval override changes the cadence used by tick (under custom)", function()
    VDT.ExportChannels.register(intervalChannel("prod", 2000)) -- default: ~1750 ms to first fire
    VDT.ExportChannels.setProfile("custom")
    VDT.ExportChannels.configure("prod", { intervalMs = 500 })

    VDT.ExportChannels.tick(debugger, 300) -- 250 stagger seed + 300 = 550 >= 500 -> fires early
    assert.are.equal(1, #VDT.ExportChannels.selectDirty())
  end)

  it("clamps an interval override to MIN_INTERVAL_MS", function()
    VDT.ExportChannels.register(intervalChannel("prod", 2000))
    VDT.ExportChannels.setProfile("custom")
    VDT.ExportChannels.configure("prod", { intervalMs = 5 }) -- absurdly low

    local cfg = VDT.ExportChannels.configurableChannels()[1]
    assert.are.equal(VDT.ExportChannels.MIN_INTERVAL_MS, cfg.intervalMs)
  end)

  it("ignores an interval override on an event-driven channel (no intervalMs)", function()
    VDT.ExportChannels.register(channel("map", true, { body = "M" })) -- no intervalMs
    VDT.ExportChannels.setProfile("custom")
    VDT.ExportChannels.configure("map", { intervalMs = 500 })
    local cfg = VDT.ExportChannels.configurableChannels()[1]
    assert.is_nil(cfg.intervalMs) -- event-driven: no cadence to tune
  end)

  it("ignores config for an unknown channel id without erroring", function()
    VDT.ExportChannels.register(channel("prod", true, { body = "P" }))
    assert.has_no.errors(function()
      VDT.ExportChannels.configure("ghost", { enabled = false, intervalMs = 500 })
    end)
    assert.are.equal(1, #VDT.ExportChannels.configurableChannels()) -- only the real channel
  end)

  it("configurableChannels excludes the latencyCritical channel and reflects overrides", function()
    local core = channel("telemetry", true, { body = "T" })
    core.latencyCritical = true
    VDT.ExportChannels.register(core)
    VDT.ExportChannels.register(intervalChannel("prod", 2000))
    VDT.ExportChannels.register(channel("map", true, { body = "M" }))
    VDT.ExportChannels.configure("prod", { enabled = false, intervalMs = 1500 })

    -- configurableChannels reports the STORED interval (the override), independent of the active profile
    local list = VDT.ExportChannels.configurableChannels()
    assert.are.equal(2, #list) -- telemetry excluded
    assert.are.same({ name = "prod", enabled = false, intervalMs = 1500 }, list[1])
    assert.are.same({ name = "map", enabled = true, intervalMs = nil }, list[2])
  end)
end)

describe("ExportChannels performance profiles", function()
  before_each(function()
    VDT.ExportChannels.reset()
  end)

  it("defaults to the high profile (registered intervals unchanged)", function()
    assert.are.equal("high", VDT.ExportChannels.getProfile())
    VDT.ExportChannels.register(intervalChannel("prod", 2000))
    assert.are.equal(2000, VDT.ExportChannels.configurableChannels()[1].intervalMs)
  end)

  it("scales the effective cadence by the active profile", function()
    VDT.ExportChannels.register(intervalChannel("prod", 2000))

    VDT.ExportChannels.setProfile("low") -- 4x
    assert.are.equal(8000, VDT.ExportChannels.effectiveInterval("prod"))
    VDT.ExportChannels.setProfile("medium") -- 2x
    assert.are.equal(4000, VDT.ExportChannels.effectiveInterval("prod"))
    VDT.ExportChannels.setProfile("veryHigh") -- 0.5x
    assert.are.equal(1000, VDT.ExportChannels.effectiveInterval("prod"))
  end)

  it("clamps a scaled effective interval to MIN_INTERVAL_MS", function()
    VDT.ExportChannels.register(intervalChannel("fast", 150)) -- 150 * 0.5 = 75 < 100
    VDT.ExportChannels.setProfile("veryHigh")
    assert.are.equal(VDT.ExportChannels.MIN_INTERVAL_MS, VDT.ExportChannels.effectiveInterval("fast"))
  end)

  it("effectiveInterval is nil for an event-driven or unknown channel", function()
    VDT.ExportChannels.register(channel("map", true, { body = "M" })) -- no intervalMs
    assert.is_nil(VDT.ExportChannels.effectiveInterval("map"))
    assert.is_nil(VDT.ExportChannels.effectiveInterval("ghost"))
  end)

  it("the scaled cadence drives tick", function()
    VDT.ExportChannels.register(intervalChannel("prod", 2000))
    VDT.ExportChannels.setProfile("veryHigh") -- 2000 -> 1000 effective

    VDT.ExportChannels.tick(debugger, 800) -- 250 stagger seed + 800 = 1050 >= 1000 -> fires
    assert.are.equal(1, #VDT.ExportChannels.selectDirty())
  end)

  it("custom honours the per-channel override instead of scaling", function()
    VDT.ExportChannels.register(intervalChannel("prod", 2000))
    VDT.ExportChannels.configure("prod", { intervalMs = 750 })

    VDT.ExportChannels.setProfile("low") -- scaling ignored under custom
    VDT.ExportChannels.setProfile("custom")
    assert.are.equal(750, VDT.ExportChannels.effectiveInterval("prod"))
  end)

  it("persists the stored override, not the profile-scaled interval", function()
    -- Regression: saving under a preset must not clobber a custom override. Switching to a preset
    -- scales the *effective* cadence, but configurableChannels (what gets written to the XML) must
    -- still report the stored 750 so switching back to custom restores the right cadence.
    VDT.ExportChannels.register(intervalChannel("prod", 2000))
    VDT.ExportChannels.setProfile("custom")
    VDT.ExportChannels.configure("prod", { intervalMs = 750 })

    VDT.ExportChannels.setProfile("low")
    assert.are.equal(8000, VDT.ExportChannels.effectiveInterval("prod")) -- runs scaled
    assert.are.equal(750, VDT.ExportChannels.configurableChannels()[1].intervalMs) -- persists override
  end)

  it("rejects an unknown profile and keeps the current one", function()
    assert.is_true(VDT.ExportChannels.setProfile("low"))
    assert.is_false(VDT.ExportChannels.setProfile("ludicrous"))
    assert.are.equal("low", VDT.ExportChannels.getProfile())
  end)
end)

-- A channel that counts its tick() calls, so we can assert that a disabled channel does no work —
-- for the expensive channels (mapLayers) the tick IS the cost, long before anything is written.
local function tickingChannel(name)
  local ch = channel(name, true, { body = name })
  ch.ticks = 0
  ch.tick = function()
    ch.ticks = ch.ticks + 1
  end
  return ch
end

describe("ExportChannels profile gating (minProfile)", function()
  before_each(function()
    VDT.ExportChannels.reset()
  end)

  it("is off below its minProfile and on at or above it", function()
    local ch = tickingChannel("mapLayers")
    ch.minProfile = "medium"
    VDT.ExportChannels.register(ch)
    VDT.ExportChannels.markDirty("mapLayers")

    VDT.ExportChannels.setProfile("low")
    assert.are.equal(0, #VDT.ExportChannels.selectDirty())
    VDT.ExportChannels.setProfile("medium") -- exactly at the floor -> on
    assert.are.equal(1, #VDT.ExportChannels.selectDirty())
    VDT.ExportChannels.setProfile("veryHigh")
    assert.are.equal(1, #VDT.ExportChannels.selectDirty())
  end)

  it("a channel without a minProfile runs at every profile", function()
    VDT.ExportChannels.register(channel("prod", true, { body = "P" }))
    VDT.ExportChannels.markDirty("prod")
    for _, name in ipairs({ "low", "medium", "high", "veryHigh", "custom" }) do
      VDT.ExportChannels.setProfile(name)
      assert.are.equal(1, #VDT.ExportChannels.selectDirty(), "expected prod writable under " .. name)
    end
  end)

  it("does not tick a channel the profile disabled", function()
    -- The regression that motivated the gate: mapLayers grid-samples the whole map from its tick, so a
    -- channel left ticking while its writes are dropped burns exactly the CPU the low preset exists to
    -- save.
    local ch = tickingChannel("mapLayers")
    ch.minProfile = "medium"
    VDT.ExportChannels.register(ch)

    VDT.ExportChannels.setProfile("low")
    VDT.ExportChannels.tick(debugger, 100)
    assert.are.equal(0, ch.ticks)

    VDT.ExportChannels.setProfile("high")
    VDT.ExportChannels.tick(debugger, 100)
    assert.are.equal(1, ch.ticks)
  end)

  it("does not tick a user-disabled channel either", function()
    local ch = tickingChannel("map")
    VDT.ExportChannels.register(ch)
    VDT.ExportChannels.configure("map", { enabled = false })

    VDT.ExportChannels.tick(debugger, 100)
    assert.are.equal(0, ch.ticks)
  end)

  it("lists a profile-disabled channel's file for cleanup", function()
    local ch = tickingChannel("mapLayers")
    ch.minProfile = "medium"
    VDT.ExportChannels.register(ch)
    VDT.ExportChannels.register(channel("prod", true, { body = "P" }))

    VDT.ExportChannels.setProfile("low")
    -- the app reads a channel file's absence as "off", so the low preset must drop mapLayers.json
    assert.are.same({ "mapLayers.json" }, VDT.ExportChannels.unavailableFileNames())
  end)

  it("custom ignores minProfile and honours the user toggle", function()
    local ch = tickingChannel("mapLayers")
    ch.minProfile = "veryHigh"
    VDT.ExportChannels.register(ch)
    VDT.ExportChannels.markDirty("mapLayers")

    VDT.ExportChannels.setProfile("custom")
    assert.are.equal(1, #VDT.ExportChannels.selectDirty()) -- gating opted out of

    VDT.ExportChannels.configure("mapLayers", { enabled = false })
    assert.are.equal(0, #VDT.ExportChannels.selectDirty()) -- the user's toggle still decides
  end)

  it("persists the user's toggle, not the profile's verdict", function()
    -- Same regression as the interval overrides: saving settings under a preset that disables the
    -- channel must not write enabled=false, or the channel stays off after switching back up.
    local ch = tickingChannel("mapLayers")
    ch.minProfile = "medium"
    VDT.ExportChannels.register(ch)

    VDT.ExportChannels.setProfile("low")
    assert.are.equal(0, #VDT.ExportChannels.selectDirty()) -- off right now
    assert.is_true(VDT.ExportChannels.configurableChannels()[1].enabled) -- but stored as the user left it

    VDT.ExportChannels.setProfile("high")
    VDT.ExportChannels.markDirty("mapLayers")
    assert.are.equal(1, #VDT.ExportChannels.selectDirty()) -- back on, toggle intact
  end)
end)
