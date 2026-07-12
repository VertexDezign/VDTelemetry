-- Unit tests for the command back-channel reader (src/command/CommandChannel.lua).
--
-- Run with `busted` from the vdTelemetry/ directory (see .github/workflows/ci.yml). CommandChannel
-- sets a global `VDT.CommandChannel` when executed. `poll` reads via the engine `XMLFile.load`, which
-- doesn't exist off-engine, so we stub a minimal XMLFile that parses our flat command XML — enough
-- to exercise poll's iterate + attribute reads. `selectNew` is pure and tested directly.
--
-- poll() delegates payload parsing to the command registry, so we also source CommandRegistry and a
-- real control (LightControl) — its execute() closures reference engine setters but are never invoked
-- here (poll only parses + dispatches), so no engine stubs are needed for them. CommandRegistry is
-- loaded only if it isn't already, so we don't reset a registry another spec populated (see the
-- sibling *Control specs).

if VDT == nil or VDT.CommandRegistry == nil then
  dofile("src/command/CommandRegistry.lua")
end
dofile("src/command/CommandChannel.lua")
dofile("src/command/LightControl.lua")
-- Sourced for the requiresVehicle-threading tests below: it registers a handler with
-- requiresVehicle = false. poll() only parses + dispatches (never executes), so its execute()'s
-- engine references are never touched here.
dofile("src/command/GpsControl.lua")

-- Install a minimal XMLFile stub as a global: parses <command .../> elements and answers
-- getInt/getString/getBool for keys shaped like "cmd:<index>#<attr>" (what our fake iterate hands
-- back). Installed via before_each because busted's per-block insulation restores _G between blocks,
-- which would otherwise wipe a file-level global.
local function installXmlStub()
  local stub = {}
  stub.__index = stub
  function stub.loadIfExists(_, path)
    local f = io.open(path, "r")
    if f == nil then
      return nil
    end
    local content = f:read("*a")
    f:close()
    local cmds = {}
    for tag in content:gmatch("<command%s+([^/>]-)%s*/>") do
      local attrs = {}
      for k, v in tag:gmatch('([%w_]+)="([^"]*)"') do
        attrs[k] = v
      end
      cmds[#cmds + 1] = attrs
    end
    return setmetatable({ cmds = cmds }, stub)
  end
  function stub:iterate(_, func)
    for i = 1, #self.cmds do
      func(i, "cmd:" .. i)
    end
  end
  local function attrFor(self, fullpath)
    local idx, attr = fullpath:match("^cmd:(%d+)#([%w_]+)$")
    return self.cmds[tonumber(idx)][attr]
  end
  function stub:getInt(fullpath)
    local v = attrFor(self, fullpath)
    return v and tonumber(v) or nil
  end
  function stub:getString(fullpath)
    return attrFor(self, fullpath)
  end
  function stub:getBool(fullpath, default)
    local v = attrFor(self, fullpath)
    if v == nil then
      return default
    end
    return v == "true"
  end
  function stub:delete() end
  -- Bind on the real global table: busted insulates each block with setfenv, so a plain `XMLFile =`
  -- can land in a sandbox env that the dofile'd CommandChannel doesn't read from.
  rawset(_G, "XMLFile", stub)
end

-- swallow the debug/warn logging poll emits
local debugger = { debug = function() end, warn = function() end }

-- Write XML to a temp file and poll it, collecting dispatched commands. Installs the XMLFile stub
-- right before use (busted's per-block insulation restores _G between blocks, wiping file-level
-- globals, so we (re)install imperatively rather than relying on a before_each).
local function pollXml(xml, lastId)
  installXmlStub()
  local path = os.tmpname()
  local f = assert(io.open(path, "w"))
  f:write(xml)
  f:close()
  local got = {}
  local newLast = VDT.CommandChannel.poll(path, lastId, VDT.CommandRegistry, function(cmd)
    got[#got + 1] = cmd
  end, debugger)
  os.remove(path)
  return newLast, got
end

describe("CommandChannel.selectNew", function()
  it("keeps only ids greater than lastCommandId", function()
    local pending = VDT.CommandChannel.selectNew({ { id = 1 }, { id = 2 }, { id = 3 } }, 2)
    assert.are.equal(1, #pending)
    assert.are.equal(3, pending[1].id)
  end)

  it("sorts ascending by id regardless of input order", function()
    local pending = VDT.CommandChannel.selectNew({ { id = 3 }, { id = 1 }, { id = 2 } }, 0)
    assert.are.same({ 1, 2, 3 }, { pending[1].id, pending[2].id, pending[3].id })
  end)

  it("skips malformed entries (no numeric id / not a table)", function()
    local pending = VDT.CommandChannel.selectNew({ { type = "noid" }, "junk", { id = 5, type = "ok" } }, 0)
    assert.are.equal(1, #pending)
    assert.are.equal(5, pending[1].id)
  end)
end)

describe("CommandChannel.poll", function()
  it("returns lastCommandId unchanged when the file is absent", function()
    installXmlStub()
    local dispatched = 0
    local newLast = VDT.CommandChannel.poll("/no/such/file.xml", 7, VDT.CommandRegistry, function()
      dispatched = dispatched + 1
    end, debugger)
    assert.are.equal(7, newLast)
    assert.are.equal(0, dispatched)
  end)

  it("dispatches new commands in id order and advances the watermark", function()
    local newLast, got = pollXml(
      [[<commands>
        <command id="3" type="setLight" light="beacon" on="true"/>
        <command id="1" type="setLight" light="workFront" on="false"/>
        <command id="2" type="setTurnLight" state="left"/>
      </commands>]],
      0
    )
    assert.are.equal(3, newLast)
    assert.are.equal(3, #got)
    assert.are.equal(1, got[1].id)
    assert.are.equal(2, got[2].id)
    assert.are.equal(3, got[3].id)
  end)

  it("delegates setLight parsing to its control (light + boolean on)", function()
    local _, got = pollXml([[<commands><command id="1" type="setLight" light="highBeam" on="true"/></commands>]], 0)
    assert.are.equal("setLight", got[1].type)
    assert.are.equal("highBeam", got[1].params.light)
    assert.is_true(got[1].params.on)
  end)

  it("delegates setTurnLight parsing to its control (state)", function()
    local _, got = pollXml([[<commands><command id="1" type="setTurnLight" state="hazard"/></commands>]], 0)
    assert.are.equal("setTurnLight", got[1].type)
    assert.are.equal("hazard", got[1].params.state)
  end)

  it("skips unknown command types but still advances the watermark", function()
    local newLast, got = pollXml([[<commands><command id="4" type="bogusCommand"/></commands>]], 0)
    assert.are.equal(4, newLast)
    assert.are.equal(0, #got)
  end)

  -- The dispatcher (VDTelemetry:onCommand) drops vehicle commands when on foot but runs the ones
  -- that don't need a vehicle. poll() surfaces the owning handler's flag on the dispatched command;
  -- it defaults to true when the handler doesn't declare it (LightControl).
  it("marks a vehicle-driving command requiresVehicle = true by default", function()
    local _, got = pollXml([[<commands><command id="1" type="setLight" light="beacon" on="true"/></commands>]], 0)
    assert.is_true(got[1].requiresVehicle)
  end)

  it("propagates requiresVehicle = false from an opted-out handler (setGpsLinesVisible)", function()
    local _, got = pollXml([[<commands><command id="1" type="setGpsLinesVisible" on="true"/></commands>]], 0)
    assert.are.equal(false, got[1].requiresVehicle)
  end)

  it("dedups: re-polling the same file dispatches nothing", function()
    local xml = [[<commands><command id="5" type="setLight" light="beacon" on="true"/></commands>]]
    local last1 = pollXml(xml, 0)
    assert.are.equal(5, last1)
    local last2, got2 = pollXml(xml, last1)
    assert.are.equal(5, last2)
    assert.are.equal(0, #got2)
  end)
end)
