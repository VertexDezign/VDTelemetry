-- Unit tests for src/command/ProductionControl.lua (productions write-back commands).
--
-- Run with `busted` from the vdTelemetry/ directory. The control self-registers its command types
-- into VDT.CommandRegistry at load, so CommandRegistry loads first; it also takes its own-farm + id
-- helpers from the read side (ProductionExporter), which registers a channel at load, so
-- ExportChannels loads ahead of that — mirroring VDTelemetry.lua's sourceFiles order. We stub a
-- ProductionPoint that records the setter calls it receives, plus the FS globals the control reads.

if VDT == nil or VDT.CommandRegistry == nil then
  dofile("src/command/CommandRegistry.lua")
end
if VDT.ExportChannels == nil then
  dofile("src/export/ExportChannels.lua")
end
if VDT.ProductionExporter == nil then
  dofile("src/collect/ProductionExporter.lua")
end
dofile("src/command/ProductionControl.lua")

local debugger = { warn = function() end, debug = function() end }

local OUTPUT_MODE = { KEEP = 0, DIRECT_SELL = 1, AUTO_DELIVER = 2 }
local FILL_INDEX = { MANURE = 10, FERMENTERMANURE = 11 }

local function makePoint(opts)
  local pp = {
    productionsIdToObj = opts.productionsIdToObj or {},
    outputFillTypeIds = opts.outputFillTypeIds or {},
    owningPlaceable = opts.owningPlaceable,
    _owner = opts.owner,
    calls = {},
  }
  function pp:getOwnerFarmId()
    return self._owner
  end
  function pp:setProductionState(id, state)
    self.calls[#self.calls + 1] = { "setProductionState", id, state }
  end
  function pp:setOutputDistributionMode(ft, mode)
    self.calls[#self.calls + 1] = { "setOutputDistributionMode", ft, mode }
  end
  return pp
end

local function installWorld(points, farmId)
  _G.ProductionPoint = { OUTPUT_MODE = OUTPUT_MODE }
  _G.g_localPlayer = farmId ~= nil and { farmId = farmId } or nil
  _G.g_fillTypeManager = {
    getFillTypeIndexByName = function(_, name)
      return FILL_INDEX[name]
    end,
  }
  _G.g_currentMission = { productionChainManager = { productionPoints = points } }
end

local function run(cmdType, params)
  VDT.CommandRegistry.get(cmdType).execute(nil, params, debugger)
end

after_each(function()
  _G.g_currentMission = nil
  _G.g_localPlayer = nil
  _G.g_fillTypeManager = nil
  _G.ProductionPoint = nil
end)

describe("setProductionEnabled", function()
  it("drives setProductionState on the addressed line", function()
    local pp = makePoint({ owner = 1, owningPlaceable = { uniqueId = "biogas-1" }, productionsIdToObj = { mist = {} } })
    installWorld({ pp }, 1)
    run("setProductionEnabled", { pointId = "biogas-1", productionId = "mist", enabled = true })
    assert.are.same({ "setProductionState", "mist", true }, pp.calls[1])
  end)

  it("ignores a point owned by another farm", function()
    local pp = makePoint({ owner = 2, owningPlaceable = { uniqueId = "b" }, productionsIdToObj = { mist = {} } })
    installWorld({ pp }, 1)
    run("setProductionEnabled", { pointId = "b", productionId = "mist", enabled = false })
    assert.are.equal(0, #pp.calls)
  end)

  it("ignores an unknown line", function()
    local pp = makePoint({ owner = 1, owningPlaceable = { uniqueId = "biogas-1" }, productionsIdToObj = { mist = {} } })
    installWorld({ pp }, 1)
    run("setProductionEnabled", { pointId = "biogas-1", productionId = "ghost", enabled = true })
    assert.are.equal(0, #pp.calls)
  end)

  it("ignores an unknown point id", function()
    local pp = makePoint({ owner = 1, owningPlaceable = { uniqueId = "biogas-1" }, productionsIdToObj = { mist = {} } })
    installWorld({ pp }, 1)
    run("setProductionEnabled", { pointId = "nope", productionId = "mist", enabled = true })
    assert.are.equal(0, #pp.calls)
  end)
end)

describe("setProductionOutputMode", function()
  it("drives setOutputDistributionMode for a buffered output", function()
    local pp =
      makePoint({ owner = 1, owningPlaceable = { uniqueId = "biogas-1" }, outputFillTypeIds = { [11] = true } })
    installWorld({ pp }, 1)
    run("setProductionOutputMode", { pointId = "biogas-1", fillType = "FERMENTERMANURE", mode = "autoDeliver" })
    assert.are.same({ "setOutputDistributionMode", 11, OUTPUT_MODE.AUTO_DELIVER }, pp.calls[1])
  end)

  it("ignores a fill type that is not a buffered output (e.g. direct-sell)", function()
    local pp = makePoint({ owner = 1, owningPlaceable = { uniqueId = "biogas-1" }, outputFillTypeIds = {} })
    installWorld({ pp }, 1)
    run("setProductionOutputMode", { pointId = "biogas-1", fillType = "MANURE", mode = "keep" })
    assert.are.equal(0, #pp.calls)
  end)

  it("ignores an unknown mode token", function()
    local pp =
      makePoint({ owner = 1, owningPlaceable = { uniqueId = "biogas-1" }, outputFillTypeIds = { [11] = true } })
    installWorld({ pp }, 1)
    run("setProductionOutputMode", { pointId = "biogas-1", fillType = "FERMENTERMANURE", mode = "bogus" })
    assert.are.equal(0, #pp.calls)
  end)

  it("refuses to mutate while spectating (no local farm)", function()
    local pp =
      makePoint({ owner = 1, owningPlaceable = { uniqueId = "biogas-1" }, outputFillTypeIds = { [11] = true } })
    installWorld({ pp }, nil)
    run("setProductionOutputMode", { pointId = "biogas-1", fillType = "FERMENTERMANURE", mode = "keep" })
    assert.are.equal(0, #pp.calls)
  end)
end)
