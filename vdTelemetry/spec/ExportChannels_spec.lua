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
