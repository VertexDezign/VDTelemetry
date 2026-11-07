-- Unit tests for the PnH BGA Control integration (src/integrations/PnhBgaControl.lua).
--
-- Run with `busted` from the vdTelemetry/ directory. The mod is not here, so its three tables are
-- stubbed in the mod environment the integration reaches through `_G[MOD_NAME]` — and only with the
-- functions the integration calls, so a new dependency on the mod shows up as a failing test.

if VDT == nil or VDT.PnhBgaControl == nil then
  dofile("src/integrations/PnhBgaControl.lua")
end

local P = VDT.PnhBgaControl

local FILL = { SILAGE = 22, MANURE = 23, SUGARBEET_CUT = 24, LIQUIDMANURE = 25, DIGESTATE = 40, METHANE = 41 }

local function base(overrides)
  local s = {
    running = true,
    hasSilage = true,
    hasManure = false,
    hasBeet = false,
    hasLiquid = true,
    activity = 1.0,
    actual = 40.0,
    recirculation = 0.0,
    ph = 7.4,
    feeding = true,
    silageFeed = 5400,
    liquidFeed = 4000,
    total = 300000,
    agitatorStatus = nil,
  }
  for k, v in pairs(overrides or {}) do
    s[k] = v
  end
  return s
end

describe("PnhBgaControl.diagnose", function()
  it("is stopped, whatever else is true, while the fermenters are off", function()
    local mode, status = P.diagnose(base({ running = false, ph = 6.0 }))
    assert.are.equal("INACTIVE", mode)
    assert.are.equal("STOPPED", status)
  end)

  it("names a silage + slurry plant co-fermentation, mesophilic at 40 degrees", function()
    local mode, status = P.diagnose(base())
    assert.are.equal("CO_FERMENTATION", mode)
    assert.are.equal("MESOPHILIC_OPTIMAL", status)
  end)

  it("names two solids plus slurry multi-substrate, thermophilic from 49 degrees", function()
    local mode, status = P.diagnose(base({ hasManure = true, actual = 50.0 }))
    assert.are.equal("MULTI_SUBSTRATE", mode)
    assert.are.equal("THERMOPHILIC", status)
  end)

  it("lets recirculation name the mode once it reaches 20 percent", function()
    local mode = P.diagnose(base({ recirculation = 0.2 }))
    assert.are.equal("RECIRCULATION", mode)
  end)

  it("reports a culture below 95 percent as starting up", function()
    local _, status = P.diagnose(base({ activity = 0.5 }))
    assert.are.equal("STARTUP", status)
  end)

  it("tells slurry-only and solids-only plants apart", function()
    local liquidMode, liquidStatus = P.diagnose(base({ hasSilage = false }))
    assert.are.equal("LIQUID_MONO", liquidMode)
    assert.are.equal("ECONOMICAL_STABLE", liquidStatus)
    local solidMode, solidStatus = P.diagnose(base({ hasLiquid = false }))
    assert.are.equal("SOLID_MONO", solidMode)
    assert.are.equal("DRY_OK", solidStatus)
    local recircMode, recircStatus = P.diagnose(base({ hasLiquid = false, recirculation = 0.3, ph = 7.3 }))
    assert.are.equal("RECIRCULATION", recircMode)
    assert.are.equal("RECIRC_STABLE", recircStatus)
  end)

  it("is buffering while fed but nothing in the tank counts yet, and idle when not fed at all", function()
    local mode, status = P.diagnose(base({ hasSilage = false, hasLiquid = false, liquidFeed = 0 }))
    assert.are.equal("SOLID_MONO", mode)
    assert.are.equal("BUFFERING", status)
    local idleMode, idleStatus = P.diagnose(
      base({ hasSilage = false, hasLiquid = false, feeding = false, silageFeed = 0, liquidFeed = 0, total = 0 })
    )
    assert.are.equal("INACTIVE", idleMode)
    assert.are.equal("NO_FEED", idleStatus)
  end)

  it("lets agitator trouble override the process word, and the pH override that", function()
    local _, agitated = P.diagnose(base({ agitatorStatus = "SCUM_DRY" }))
    assert.are.equal("SCUM_DRY", agitated)
    local _, acid = P.diagnose(base({ agitatorStatus = "SCUM_DRY", ph = 6.9 }))
    assert.are.equal("ACID_CRASH", acid)
    local _, ammonia = P.diagnose(base({ ph = 8.1 }))
    assert.are.equal("AMMONIA_INHIBITION", ammonia)
  end)
end)

describe("PnhBgaControl.biologyTrend", function()
  local function trend(o)
    local s = { dying = false, stressed = false, producing = true, actual = 40.0, activity = 1.0 }
    for k, v in pairs(o or {}) do
      s[k] = v
    end
    return P.biologyTrend(s)
  end

  it("ranks dying over stressed over decaying", function()
    assert.are.equal("DYING", trend({ dying = true, stressed = true, producing = false }))
    assert.are.equal("STRESSED", trend({ stressed = true }))
    assert.are.equal("DECAY", trend({ producing = false }))
  end)

  it("is only stressed while producing — an idle plant decays instead", function()
    assert.are.equal("DECAY", trend({ stressed = true, producing = false }))
  end)

  it("is dormant below 35 degrees, stable from 99 percent, growing otherwise", function()
    assert.are.equal("DORMANT", trend({ actual = 33.0 }))
    assert.are.equal("STABLE", trend({ activity = 0.99 }))
    assert.are.equal("GROWING", trend({ activity = 0.6 }))
  end)
end)

describe("PnhBgaControl.chemistry", function()
  it("answers the mod's defaults for a near-empty tank", function()
    local ts, ph =
      P.chemistry({ total = 10, rawDryMatter = 30, acidLoad = 2, liquidLevel = 0, recirculation = 0, agitator = 0 })
    assert.are.equal(8.5, ts)
    assert.are.equal(7.5, ph)
  end)

  it("dilutes dry matter with recirculation and hard stirring, and buffers acid with slurry", function()
    local ts, ph = P.chemistry({
      total = 1000,
      rawDryMatter = 12,
      acidLoad = 0.5,
      liquidLevel = 200,
      recirculation = 0.1,
      agitator = 1.0,
    })
    -- 12 * 0.9 + 6 * 0.1 - 1.8 = 9.6
    assert.are.equal(9.6, ts)
    -- neutralization = 0.2 * 1.5 + 0.1 * 2 = 0.5; 7.55 - 0.25 + 0.07 = 7.37 -> 7.4
    assert.are.equal(7.4, ph)
  end)

  it("clamps to the mod's ranges", function()
    local ts, ph =
      P.chemistry({ total = 1000, rawDryMatter = 40, acidLoad = 5, liquidLevel = 0, recirculation = 0, agitator = 0 })
    assert.are.equal(22.0, ts)
    assert.are.equal(5.8, ph)
  end)
end)

-- ---- control(): the whole block against a stubbed mod -------------------------------------------

local function makeStorage(levels, caps)
  return {
    getIsFillTypeSupported = function(_, ft)
      return levels[ft] ~= nil
    end,
    getFillLevel = function(_, ft)
      return levels[ft] or 0
    end,
    getCapacity = function(_, ft)
      return caps[ft] or 0
    end,
  }
end

local function makeProductionPoint(enabled, outputs, cyclesPerMinute)
  local production = { id = "FERMENTER", cyclesPerMinute = cyclesPerMinute, outputs = outputs }
  return {
    productions = { production },
    getIsProductionEnabled = function()
      return enabled
    end,
  }
end

-- A plant: a root fermenter, a bunker and a digestate tank, plus the mod's tables.
local function setup(opts)
  opts = opts or {}
  local fermenterPp = makeProductionPoint(opts.running ~= false, {
    { type = FILL.METHANE, amount = 2 },
    { type = FILL.DIGESTATE, amount = 10 },
  }, 1)
  local root = {
    bgaControlData = opts.data or {
      targetTemperature = 40,
      actualTemperature = 39.96,
      silageFlow = 5400,
      liquidFlow = 4000,
      agitatorIntensity = 0.9,
      recirculationRate = 0.1,
      bacterialActivity = 1.0,
      liveMethaneLph = 889,
      liveDigestateLph = 4285,
      liveBhkwUtil = 42,
      liveTorchUtil = 0,
    },
    bgaTargetSilageFlowLph = 5400,
    bgaTargetLiquidFlowLph = 4000,
    bgaStressTimer = opts.stress or 0,
    bgaStateReceived = opts.received,
    dailyUpkeep = 500,
    spec_sandboxPlaceableProductionPoint = {
      productionPoint = { storage = makeStorage({ [FILL.SILAGE] = 200000, [FILL.LIQUIDMANURE] = 100000 }, {}) },
    },
    getSandboxTypeName = function()
      return "FERMENTER"
    end,
  }
  local bunker = {
    dailyUpkeep = 100,
    spec_silo = {
      storages = {
        makeStorage({ [FILL.SILAGE] = 150000, [FILL.LIQUIDMANURE] = 40000 }, { [FILL.LIQUIDMANURE] = 80000 }),
      },
    },
    getSandboxTypeName = function()
      return "BUNKER"
    end,
  }
  local tank = {
    spec_silo = { storages = { makeStorage({ [FILL.DIGESTATE] = 7000 }, { [FILL.DIGESTATE] = 1000000 }) } },
    getSandboxTypeName = function()
      return "SILO"
    end,
  }
  local cluster = { [root] = true, [bunker] = true, [tank] = true }

  local substrateByName = {
    SILAGE = { fillType = FILL.SILAGE },
    MANURE = { fillType = FILL.MANURE },
    SUGARBEET_CUT = { fillType = FILL.SUGARBEET_CUT },
    LIQUIDMANURE = { fillType = FILL.LIQUIDMANURE },
  }
  local separator = opts.separator
  _G.FS25_PnH_BgaControl = {
    BgaExtensionMain = {
      getAllClusterPlaceables = function()
        return cluster, root
      end,
      getFermenterClusterData = function()
        return 1.0, opts.fermenters or 1
      end,
      isFermenterPlaceable = function(pl)
        return pl == root
      end,
      getProductionPointFromPlaceable = function(pl)
        return pl == root and fermenterPp or nil
      end,
      getFermenterSubstrateLevels = function()
        return { [FILL.SILAGE] = 200000, [FILL.LIQUIDMANURE] = 100000 }
      end,
      calculateAgitatorRheology = function()
        return 1.0, 20, 1.0, nil, false
      end,
      getSandboxUtilization = function()
        return 64, 0, true, true
      end,
      getLiveBhkwIncome = function(_, load)
        return load * 10
      end,
      getSeparatorForBga = function()
        return separator
      end,
      getSeparatorFlowLph = function()
        return 1200
      end,
    },
    BgaAdditionalSubstrates = {
      getTotalLevel = function(levels)
        local t = 0
        for _, v in pairs(levels) do
          t = t + v
        end
        return t
      end,
      getDisplayGroupLevelFromLevels = function(levels, name)
        return levels[FILL[name]] or 0
      end,
      getWeightedDryMatter = function()
        return 11.0
      end,
      getWeightedAcidLoad = function()
        return 0.1
      end,
      getSubstrateByName = function(name)
        return substrateByName[name]
      end,
      getStorageSubstrateLevel = function()
        return 300000
      end,
      getFermenterPoolCapacity = function()
        return 326100
      end,
      getBunkerStorageLevel = function()
        return 150000
      end,
      getBunkerStorageCapacity = function()
        return 210000
      end,
    },
    BgaRuntimeFixes = {
      getEfficiency = function()
        return 0.99
      end,
    },
  }
  _G.g_fillTypeManager = {
    getFillTypeIndexByName = function(_, name)
      return FILL[name]
    end,
  }
  _G.g_currentMission = { environment = { timeAdjustment = 1 } }
  if opts.client then
    _G.g_server = nil
  else
    _G.g_server = {}
  end
  return root
end

describe("PnhBgaControl.control", function()
  after_each(function()
    _G.FS25_PnH_BgaControl, _G.g_fillTypeManager, _G.g_currentMission, _G.g_server = nil, nil, nil, nil
  end)

  it("is nil without the mod", function()
    assert.is_false(P.isAvailable())
    assert.is_nil(P.control({ bgaControlData = {} }))
  end)

  it("is nil until the mod has made state for the plant", function()
    local root = setup()
    root.bgaControlData = nil
    assert.is_nil(P.control(root))
  end)

  it("reports setpoints, readings and tokens on the server", function()
    local root = setup()
    local c = P.control(root)
    assert.are.same({ target = 40, actual = 40.0 }, c.temperature)
    assert.are.same({ silage = 5400, silageMax = 18000, liquid = 4000, liquidMax = 24000 }, c.feed)
    assert.are.equal(90, c.agitator)
    assert.are.equal(10, c.recirculation)
    assert.are.equal(99, c.efficiency)
    assert.are.equal("CO_FERMENTATION", c.mode)
    assert.are.equal("MESOPHILIC_OPTIMAL", c.status)
    assert.are.same({ activity = 100, trend = "STABLE", stress = 0, stressLimit = 5400 }, c.biology)
    -- the server works the flows out of the running recipe: 2 + 10 L per cycle, 60 cycles an hour
    assert.are.equal(120, c.methane)
    assert.are.equal(600, c.digestate)
    assert.are.same({ load = 64, income = 640 }, c.chp)
    assert.are.equal(0, c.torch)
    -- 10 per fermenter + heating to 40 degrees (8 * 1.4) + the agitator's 20
    assert.are.same({ operating = 41, upkeep = 600 }, c.costs)
    assert.is_nil(c.separator)
  end)

  it("reads the four tanks the way the mod's screen adds them up", function()
    local c = P.control(setup())
    assert.are.same({ level = 150000, capacity = 210000 }, c.stock.solid)
    assert.are.same({ level = 40000, capacity = 80000 }, c.stock.liquid)
    assert.are.same({ level = 300000, capacity = 326100 }, c.stock.fermenter)
    assert.are.same({ level = 7000, capacity = 1000000 }, c.stock.digestate)
  end)

  it("scales the feed limits with the fermenter count", function()
    local c = P.control(setup({ fermenters = 3 }))
    assert.are.equal(54000, c.feed.silageMax)
    assert.are.equal(72000, c.feed.liquidMax)
  end)

  it("takes the server's broadcast numbers on an MP client, and waits for the first one", function()
    local root = setup({ client = true })
    assert.is_nil(P.control(root))
    root.bgaStateReceived = true
    local c = P.control(root)
    assert.are.equal(889, c.methane)
    assert.are.equal(4285, c.digestate)
    assert.are.same({ load = 42, income = 420 }, c.chp)
  end)

  it("reads a stopped plant as stopped, with no efficiency and no operating cost", function()
    local c = P.control(setup({ running = false }))
    assert.are.equal("STOPPED", c.status)
    assert.are.equal(0, c.efficiency)
    assert.are.equal(0, c.costs.operating)
    assert.are.equal("DECAY", c.biology.trend)
  end)

  it("reports the bacteria dying once the stress passes 1.5 hours", function()
    local c = P.control(setup({ stress = 6000 }))
    assert.are.equal("DYING", c.biology.trend)
    assert.are.equal(6000, c.biology.stress)
  end)

  it("reports a separator's flow, and its state only where the machine knows it", function()
    local c = P.control(setup({ separator = { isProcessing = true, isOperating = true } }))
    assert.are.same({ flow = 1200, state = "OPERATING" }, c.separator)
    local blind = P.control(setup({ separator = {} }))
    assert.are.same({ flow = 1200 }, blind.separator)
  end)

  it("swallows a failing mod call rather than taking the channel down", function()
    local root = setup()
    _G.FS25_PnH_BgaControl.BgaExtensionMain.getAllClusterPlaceables = function()
      error("boom")
    end
    assert.is_nil(P.control(root))
  end)
end)
