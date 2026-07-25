-- Unit tests for the pipe and cover aspect collectors (src/collect/aspects/{Pipe,Cover}.lua).
--
-- Run with `busted` from the vdTelemetry/ directory. Both collectors are pure reads of their spec
-- table plus a ValueMapper call, so nothing needs stubbing beyond the spec itself.
--
-- Both used to collapse their state into a single string, which lost information the game really
-- has: a pipe can have more than two positions (spec.numStates), and a vehicle can have more than
-- one cover (spec.state is *which* is open, not a boolean).

if ValueMapper == nil then
  dofile("src/mapper/ValueMapper.lua")
end
if VDT == nil or VDT.Pipe == nil then
  dofile("src/collect/aspects/Pipe.lua")
end
if VDT.Cover == nil then
  dofile("src/collect/aspects/Cover.lua")
end

local function pipe(current, target, numStates)
  return { spec_pipe = { currentState = current, targetState = target, numStates = numStates } }
end

local function cover(state, numCovers)
  local covers = {}
  for i = 1, numCovers do
    covers[i] = { index = i }
  end
  return { spec_cover = { hasCovers = numCovers > 0, state = state, covers = covers } }
end

describe("Pipe.collect", function()
  it("returns nil when the object has no pipe", function()
    assert.is_nil(VDT.Pipe.collect({}))
  end)

  it("reports state 1 as retracted", function()
    local p = VDT.Pipe.collect(pipe(1, 1, 2))
    assert.are.equal("RETRACTED", p.state)
    assert.are.equal(1, p.current)
    assert.are.equal(1, p.target)
    assert.are.equal(2, p.numStates)
  end)

  it("reports state 0 as moving, and keeps the target it is heading for", function()
    local p = VDT.Pipe.collect(pipe(0, 3, 3))
    assert.are.equal("MOVING", p.state)
    assert.are.equal(0, p.current)
    assert.are.equal(3, p.target)
  end)

  it("keeps intermediate positions of a multi-state pipe distinguishable", function()
    -- Both are EXTENDED at the coarse level; only `current` tells them apart.
    local middle = VDT.Pipe.collect(pipe(2, 2, 3))
    local out = VDT.Pipe.collect(pipe(3, 3, 3))
    assert.are.equal("EXTENDED", middle.state)
    assert.are.equal("EXTENDED", out.state)
    assert.are.equal(2, middle.current)
    assert.are.equal(3, out.current)
  end)
end)

describe("Cover.collect", function()
  it("returns nil when the object has no covers", function()
    assert.is_nil(VDT.Cover.collect({}))
    assert.is_nil(VDT.Cover.collect(cover(0, 0)))
  end)

  it("reports state 0 as closed", function()
    local c = VDT.Cover.collect(cover(0, 1))
    assert.are.equal("CLOSED", c.state)
    assert.are.equal(0, c.index)
    assert.are.equal(1, c.count)
  end)

  it("reports any open cover as open, not just the first", function()
    -- The old mapper keyed on state == 1 and returned UNKNOWN for 2..n.
    for index = 1, 3 do
      local c = VDT.Cover.collect(cover(index, 3))
      assert.are.equal("OPEN", c.state)
      assert.are.equal(index, c.index)
      assert.are.equal(3, c.count)
    end
  end)
end)
