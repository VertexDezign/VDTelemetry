-- Unit tests for src/command/BgaControl.lua (the BGA Control setpoints write-back).
--
-- Run with `busted` from the vdTelemetry/ directory. The plant is resolved through
-- VDT.PumpsAndHoses.rootById and the mod through VDT.PnhBgaControl.tables, so both are stubbed at
-- that seam; the mod's own functions record what they were asked to do.

if VDT == nil or VDT.Farm == nil then
  dofile("src/utils/Farm.lua")
end
if VDT.CommandRegistry == nil then
  dofile("src/command/CommandRegistry.lua")
end
if VDT.ExportChannels == nil then
  dofile("src/export/ExportChannels.lua")
end
if VDT.ProductionExporter == nil then
  dofile("src/collect/ProductionExporter.lua")
end
if VDT.PumpsAndHoses == nil then
  dofile("src/integrations/PumpsAndHoses.lua")
end
if VDT.PnhBgaControl == nil then
  dofile("src/integrations/PnhBgaControl.lua")
end
dofile("src/command/BgaControl.lua")

local warnings = {}
local debugger = {
  warn = function(_, fmt, ...)
    warnings[#warnings + 1] = string.format(fmt, ...)
  end,
  debug = function() end,
}

local command = VDT.CommandRegistry.get("setBgaSettings")

local function params(overrides)
  local p = {
    constructionId = "bga-1",
    temperature = 42,
    silage = 6000,
    liquid = 3000,
    agitator = 80,
    recirculation = 20,
    separator = 1500,
  }
  for k, v in pairs(overrides or {}) do
    p[k] = v
  end
  return p
end

-- A world with one plant (root + fermenter production point) and the mod's tables, recording calls.
local function setup(opts)
  opts = opts or {}
  local calls = { recipe = 0 }
  local pp = {}
  local root = {
    bgaControlData = { separatorFlow = 1000 },
    bgaStateReceived = opts.received,
  }
  if opts.noData then
    root.bgaControlData = nil
  end
  local main = {
    getFermenterClusterData = function()
      return 1.0, opts.fermenters or 1
    end,
    applyFlowSettingsToCluster = function(r)
      calls.flows = { silage = r.bgaTargetSilageFlowLph, liquid = r.bgaTargetLiquidFlowLph }
    end,
    getSeparatorFlowLph = function(r)
      return r.bgaControlData.separatorFlow
    end,
    applySeparatorFlowToCluster = function(r, flow)
      calls.separator = flow
      r.bgaControlData.separatorFlow = flow
    end,
    getAllClusterPlaceables = function()
      return { [root] = true }
    end,
    isFermenterPlaceable = function(pl)
      return pl == root
    end,
    getProductionPointFromPlaceable = function()
      return pp
    end,
    updateBgaProductionRecipe = function(p)
      assert.are.equal(pp, p)
      calls.recipe = calls.recipe + 1
    end,
  }
  local event = {
    sendEvent = function(r, t, s, l, a, rc, sep)
      calls.event =
        { root = r, temperature = t, silage = s, liquid = l, agitator = a, recirculation = rc, separator = sep }
    end,
  }
  local originalTables, originalRoot, originalFarm =
    VDT.PnhBgaControl.tables, VDT.PumpsAndHoses.rootById, VDT.Farm.ownFarmId
  VDT.PnhBgaControl.tables = function()
    if opts.noMod then
      return nil
    end
    return main, {}, nil, event
  end
  VDT.PumpsAndHoses.rootById = function(id, farmId)
    return (id == "bga-1" and farmId == 1) and root or nil
  end
  VDT.Farm.ownFarmId = function()
    return 1
  end
  if opts.client then
    _G.g_server = nil
  else
    _G.g_server = {}
  end
  local function restore()
    VDT.PnhBgaControl.tables, VDT.PumpsAndHoses.rootById, VDT.Farm.ownFarmId =
      originalTables, originalRoot, originalFarm
    _G.g_server = nil
  end
  return root, calls, restore
end

describe("setBgaSettings", function()
  local restore
  before_each(function()
    warnings = {}
  end)
  after_each(function()
    if restore then
      restore()
    end
  end)

  it("writes all six onto the plant, rebuilds the recipe and sends the mod's event", function()
    local root, calls
    root, calls, restore = setup()
    command.execute(nil, params(), debugger)
    local data = root.bgaControlData
    assert.are.equal(42, data.targetTemperature)
    assert.are.equal(6000, data.silageFlow)
    assert.are.equal(3000, data.liquidFlow)
    assert.are.equal(0.8, data.agitatorIntensity)
    assert.are.equal(0.2, data.recirculationRate)
    assert.are.same({ silage = 6000, liquid = 3000 }, calls.flows)
    assert.are.equal(1500, calls.separator)
    assert.are.equal(1, calls.recipe)
    assert.are.same({
      root = root,
      temperature = 42,
      silage = 6000,
      liquid = 3000,
      agitator = 0.8,
      recirculation = 0.2,
      separator = 1500,
    }, calls.event)
  end)

  it("leaves the separator alone when its value did not change", function()
    local calls
    _, calls, restore = setup()
    command.execute(nil, params({ separator = 1000 }), debugger)
    assert.is_nil(calls.separator)
    assert.are.equal(1000, calls.event.separator)
  end)

  it("clamps to the mod's limits, the feed ones scaled by the fermenter count", function()
    local root
    root, _, restore = setup({ fermenters = 2 })
    command.execute(
      nil,
      params({ temperature = 70, silage = 99999, liquid = -5, agitator = 150, recirculation = 90, separator = 5000 }),
      debugger
    )
    local data = root.bgaControlData
    assert.are.equal(55, data.targetTemperature)
    assert.are.equal(36000, data.silageFlow)
    assert.are.equal(0, data.liquidFlow)
    assert.are.equal(1, data.agitatorIntensity)
    assert.are.equal(0.5, data.recirculationRate)
    assert.are.equal(3000, data.separatorFlow)
  end)

  it("does not rebuild the recipe on an MP client — the server does, from the event", function()
    local root, calls
    root, calls, restore = setup({ client = true, received = true })
    command.execute(nil, params(), debugger)
    assert.are.equal(0, calls.recipe)
    assert.are.equal(root, calls.event.root)
  end)

  it("refuses on an MP client that has not heard from the server yet", function()
    local root, calls
    root, calls, restore = setup({ client = true })
    command.execute(nil, params(), debugger)
    assert.is_nil(calls.event)
    assert.is_nil(root.bgaControlData.targetTemperature)
    assert.are.equal(1, #warnings)
  end)

  it("refuses a plant it cannot find, one with no state, and a game without the mod", function()
    local calls
    _, calls, restore = setup()
    command.execute(nil, params({ constructionId = "someone-else" }), debugger)
    assert.is_nil(calls.event)
    restore()

    _, calls, restore = setup({ noData = true })
    command.execute(nil, params(), debugger)
    assert.is_nil(calls.event)
    restore()

    _, calls, restore = setup({ noMod = true })
    command.execute(nil, params(), debugger)
    assert.is_nil(calls.event)
    assert.are.equal(3, #warnings)
  end)
end)
