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
