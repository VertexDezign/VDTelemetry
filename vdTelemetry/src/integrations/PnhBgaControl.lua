-- Optional integration: PnH BGA Control (FS25_PnH_BgaControl, by Kastor/T4xs), which turns a Pumps &
-- Hoses biogas plant into a process you run — tank temperature, bacterial activity, dry matter and
-- pH, an agitator, digestate recirculation, the feed rates out of the bunker and the pit, and the
-- separator's throughput. This file reads all of that for one plant; PumpsAndHoses.lua hangs the
-- result on the plant's construction entry as `control`. Written against BGA Control 0.0.9.0.
--
-- WHERE THE STATE LIVES. On the plant's ROOT placeable — the same root PumpsAndHoses.lua groups by —
-- as plain fields: `bgaControlData` (setpoints, temperature, bacterial activity),
-- `bgaTarget{Silage,Liquid}FlowLph` (the feed rates the mod actually applies) and `bgaStressTimer`.
-- Those are readable from our environment like any table field.
--
-- WHAT IT NEVER STORES. The process MODE ("multi-substrate"), the STATUS line ("mesophilic optimal",
-- "acid crash"), the biology TREND ("growing", "dying off"), dry matter, pH and the operating costs
-- are all worked out by the mod's control dialog each time it draws
-- (BgaControlDialog:calculateFermenterDiagnostics, :updateDisplayValues, :updateSeparatorDisplay),
-- and the words come back as the mod's l10n — text our environment would resolve to the bare key. So
-- the decision trees are PORTED here, branch for branch, and export TOKENS the app words itself. The
-- numbers those trees run on come from the mod's own helpers, called rather than copied: they know
-- its fifteen substrates and their dry matter and acid load, the table most likely to change between
-- versions.
--
-- MOD-ENVIRONMENT ISOLATION (see Invoices.lua). BgaExtensionMain, BgaAdditionalSubstrates and
-- BgaRuntimeFixes are globals in the mod's own environment and nil from ours; they are reached through
-- `_G[MOD_NAME]`. The helpers run in the mod's environment wherever they are called from, so calling
-- them from here sees exactly what they see from the dialog.
--
-- MULTIPLAYER. The mod simulates on the server alone and broadcasts its state to every client every
-- 5 s (BgaSetSettingsEvent.broadcastAuthoritativeState), including four numbers a client cannot work
-- out — methane and digestate flow, cogeneration and torch load — which it keeps as
-- `bgaControlData.live*`. A client reads those; the server computes them the way that broadcast does.
-- Until a client has received its first broadcast (`root.bgaStateReceived`) its copy is the dialog's
-- defaults or nothing at all, so the block is withheld rather than reported wrong.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

---@class BgaControlLevelModel a level against a capacity, liters
---@field level number
---@field capacity number

---@class BgaControlModel the BGA Control state of one biogas plant
---@field temperature { target: number, actual: number } degrees C; the setpoint is 30..55
---@field feed { silage: number, silageMax: number, liquid: number, liquidMax: number } L/h; the maximum
---  scales with the plant's fermenter count
---@field agitator number percent, 0..100
---@field recirculation number percent of the digestate fed back, 0..50
---@field separator { flow: number, state: string }? absent when the plant has no separator; state is
---  INACTIVE|OPERATING|FULL|NO_SILO|STANDBY, absent where this machine cannot tell
---@field biology { activity: number, trend: string, stress: number, stressLimit: number } activity in
---  percent (10..100); trend DYING|STRESSED|DECAY|DORMANT|STABLE|GROWING; stress in in-game seconds,
---  the bacteria start dying once it passes stressLimit
---@field dryMatter number percent (the mod's TS content)
---@field ph number
---@field efficiency number percent
---@field mode string INACTIVE|LIQUID_MONO|SOLID_MONO|SOLID_MULTI|RECIRCULATION|MULTI_SUBSTRATE|
---  CO_FERMENTATION|MANURE_LIQUID|BEET_FERMENTATION
---@field status string STOPPED|STARTUP|COLD|ECONOMICAL_STABLE|RECIRC_STABLE|DRY_OK|THERMOPHILIC|
---  MESOPHILIC_OPTIMAL|BUFFERING|NO_FEED|SCUM_DRY|HIGH_VISCOSITY|HIGH_SHEAR|ACID_CRASH|AMMONIA_INHIBITION
---@field methane number L/h
---@field digestate number L/h
---@field chp { load: number, income: number } load percent, income per in-game hour
---@field torch number percent
---@field stock { solid: BgaControlLevelModel, liquid: BgaControlLevelModel, fermenter: BgaControlLevelModel, digestate: BgaControlLevelModel }
---@field costs { operating: number, upkeep: number } operating per in-game hour, upkeep per day

VDT = VDT or {}
VDT.PnhBgaControl = {}

VDT.PnhBgaControl.MOD_NAME = "FS25_PnH_BgaControl"

-- The mod's own limits (BgaSetSettingsEvent's clampSettings / clampSettingsForRoot), in the units the
-- mod stores: degrees, L/h per fermenter, fractions. The command side clamps to the same.
VDT.PnhBgaControl.LIMITS = {
  temperatureMin = 30,
  temperatureMax = 55,
  silagePerFermenter = 18000,
  liquidPerFermenter = 24000,
  recirculationMax = 0.5,
  separatorMax = 3000,
}

-- Stress the bacteria absorb before they start dying: 1.5 in-game hours (updateBgaBiology).
VDT.PnhBgaControl.STRESS_LIMIT = 5400

local function env()
  local e = _G[VDT.PnhBgaControl.MOD_NAME]
  return type(e) == "table" and e or nil
end

---The mod's tables, or nil when it is not installed (or a version without them).
---@return table|nil main BgaExtensionMain
---@return table|nil substrates BgaAdditionalSubstrates
---@return table|nil fixes BgaRuntimeFixes (optional even when the mod is there)
---@return table|nil settingsEvent BgaSetSettingsEvent, the write side's network event (optional)
function VDT.PnhBgaControl.tables()
  local e = env()
  if e == nil then
    return nil
  end
  local main, substrates = e.BgaExtensionMain, e.BgaAdditionalSubstrates
  if type(main) ~= "table" or type(substrates) ~= "table" then
    return nil
  end
  local fixes = type(e.BgaRuntimeFixes) == "table" and e.BgaRuntimeFixes or nil
  local settingsEvent = type(e.BgaSetSettingsEvent) == "table" and e.BgaSetSettingsEvent or nil
  return main, substrates, fixes, settingsEvent
end

---True when BGA Control is loaded.
---@return boolean
function VDT.PnhBgaControl.isAvailable()
  return VDT.PnhBgaControl.tables() ~= nil
end

local function num(v, default)
  v = tonumber(v)
  if v == nil or v ~= v then
    return default
  end
  return v
end

local function round(v, decimals)
  local m = 10 ^ (decimals or 0)
  return math.floor(v * m + 0.5) / m
end

local function clamp(v, lo, hi)
  return math.max(lo, math.min(hi, v))
end

---The fermenter count, as the mod counts it — the feed limits scale with it.
---@param main table BgaExtensionMain
---@param root table
---@return number
function VDT.PnhBgaControl.fermenterCount(main, root)
  local _, count = main.getFermenterClusterData(root)
  return math.max(1, num(count, 1))
end

-- Is any production line of the plant's fermenters switched on? (BgaControlDialog:getIsBgaActive)
local function isRunning(main, cluster)
  for placeable in pairs(cluster) do
    if main.isFermenterPlaceable(placeable) then
      local pp = main.getProductionPointFromPlaceable(placeable)
      if pp ~= nil and type(pp.productions) == "table" then
        for _, production in ipairs(pp.productions) do
          local enabled = (pp.getIsProductionEnabled ~= nil and pp:getIsProductionEnabled(production.id))
            or (type(pp.activeProductions) == "table" and table.hasElement(pp.activeProductions, production))
          if enabled then
            return true
          end
        end
      end
    end
  end
  return false
end

local AGITATOR_STATUS = {
  bga_status_scum_dry = "SCUM_DRY",
  bga_status_high_viscosity = "HIGH_VISCOSITY",
  bga_status_high_shear = "HIGH_SHEAR",
}

-- The pure arithmetic half of BgaControlDialog:calculateFermenterDiagnostics: dry matter and pH out
-- of the substrate mix. Split out so it can be tested without the mod.
---@param input table { total, rawDryMatter, acidLoad, liquidLevel, recirculation, agitator }
---@return number dryMatter percent
---@return number ph
function VDT.PnhBgaControl.chemistry(input)
  local ts, ph = 8.5, 7.5
  if input.total > 50 then
    local agit = input.agitator
    local agitReduction = (agit > 0.70) and (((agit - 0.70) / 0.30) * 1.8) or 0.0
    local recirc = input.recirculation
    ts = math.max(4.0, (input.rawDryMatter * (1.0 - recirc)) + (6.0 * recirc) - agitReduction)
    local wLiq = input.liquidLevel / input.total
    local neutralization = math.min(1.0, math.max(0.0, (wLiq * 1.5) + (recirc * 2.0)))
    ph = 7.55 - math.max(0.0, input.acidLoad * (1.0 - neutralization)) + (wLiq * 0.35)
  end
  ts = clamp(round(ts, 1), 4.0, 22.0)
  ph = clamp(round(ph, 1), 5.8, 8.5)
  return ts, ph
end

-- The temperature factor of the mod's efficiency formula (identical in the dialog and the simulation).
function VDT.PnhBgaControl.temperatureFactor(t)
  if t < 38.0 then
    return 0.60 + ((t - 30.0) / 8.0) * 0.40
  elseif t <= 42.0 then
    return 1.00
  elseif t < 48.0 then
    return 0.95 - ((t - 42.0) / 6.0) * 0.03
  end
  return 0.98 + ((t - 48.0) / 7.0) * 0.20
end

---The mode and status decision tree of BgaControlDialog:calculateFermenterDiagnostics, as tokens.
---Kept free of the mod so every branch can be tested: the caller resolves which substrate groups are
---"active" (the mod's 500 L-per-fermenter rule, or the recipe's own set when the server has one).
---@param s table { running, hasSilage, hasManure, hasBeet, hasLiquid, activity (0..1), actual,
---  recirculation (0..0.5), ph, feeding, total, agitatorStatus (token|nil) }
---@return string mode
---@return string status
function VDT.PnhBgaControl.diagnose(s)
  if not s.running then
    return "INACTIVE", "STOPPED"
  end
  local solidCount = (s.hasSilage and 1 or 0) + (s.hasManure and 1 or 0) + (s.hasBeet and 1 or 0)
  local hasSolid = solidCount > 0
  local starting = s.activity < 0.95
  local mode, status = "INACTIVE", "MESOPHILIC_OPTIMAL"

  if not hasSolid and s.hasLiquid then
    mode = "LIQUID_MONO"
    if starting then
      status = "STARTUP"
    elseif s.actual < 34.0 then
      status = "COLD"
    else
      status = "ECONOMICAL_STABLE"
    end
  elseif hasSolid and not s.hasLiquid then
    if s.recirculation >= 0.20 then
      mode = "RECIRCULATION"
    else
      mode = (solidCount >= 2) and "SOLID_MULTI" or "SOLID_MONO"
    end
    if starting then
      status = "STARTUP"
    elseif s.recirculation >= 0.20 and s.ph >= 7.3 then
      status = "RECIRC_STABLE"
    elseif s.actual < 34.0 then
      status = "COLD"
    else
      status = "DRY_OK"
    end
  elseif hasSolid and s.hasLiquid then
    if s.recirculation >= 0.20 then
      mode = "RECIRCULATION"
    elseif solidCount >= 2 then
      mode = "MULTI_SUBSTRATE"
    elseif s.hasSilage then
      mode = "CO_FERMENTATION"
    elseif s.hasManure then
      mode = "MANURE_LIQUID"
    else
      mode = "BEET_FERMENTATION"
    end
    if starting then
      status = "STARTUP"
    elseif s.actual < 34.0 then
      status = "COLD"
    elseif s.actual >= 49.0 then
      status = "THERMOPHILIC"
    else
      status = "MESOPHILIC_OPTIMAL"
    end
  else
    -- nothing active in the tank: the mode follows the feed that is set, if any
    if s.feeding or s.total > 50 then
      if s.silageFeed > 0 and s.liquidFeed > 0 then
        mode = "CO_FERMENTATION"
      elseif s.silageFeed > 0 then
        mode = "SOLID_MONO"
      elseif s.liquidFeed > 0 then
        mode = "LIQUID_MONO"
      end
      status = "BUFFERING"
    else
      status = "NO_FEED"
    end
  end

  -- agitator trouble overrides the process word, and the pH overrides everything
  if s.agitatorStatus ~= nil then
    status = s.agitatorStatus
  end
  if s.ph < 7.0 then
    status = "ACID_CRASH"
  elseif s.ph > 8.05 then
    status = "AMMONIA_INHIBITION"
  end
  return mode, status
end

---The biology trend of BgaControlDialog:updateDisplayValues, as a token.
---@param s table { dying, stressed, producing, actual, activity (0..1) }
---@return string
function VDT.PnhBgaControl.biologyTrend(s)
  if s.dying then
    return "DYING"
  elseif s.stressed and s.producing then
    return "STRESSED"
  elseif not s.producing then
    return "DECAY"
  elseif s.actual < 35.0 then
    return "DORMANT"
  elseif math.floor(s.activity * 100 + 0.5) >= 99 then
    return "STABLE"
  end
  return "GROWING"
end

-- Methane and digestate L/h the way the server broadcasts them (BgaSetSettingsEvent's
-- getLiveTelemetry): the enabled fermenter lines' outputs times their cycle rate. The mod writes its
-- efficiency into that cycle rate, so this is what the plant is really making.
local function liveFlows(main, cluster)
  local methaneType = g_fillTypeManager:getFillTypeIndexByName("METHANE")
  local digestateType = g_fillTypeManager:getFillTypeIndexByName("DIGESTATE")
  local timeAdjustment = (g_currentMission.environment ~= nil and g_currentMission.environment.timeAdjustment) or 1
  local methane, digestate = 0, 0
  for placeable in pairs(cluster) do
    if main.isFermenterPlaceable(placeable) then
      local pp = main.getProductionPointFromPlaceable(placeable)
      if pp ~= nil and type(pp.productions) == "table" then
        for _, production in ipairs(pp.productions) do
          local enabled = (pp.getIsProductionEnabled ~= nil and pp:getIsProductionEnabled(production.id))
            or (type(pp.activeProductions) == "table" and table.hasElement(pp.activeProductions, production))
          if enabled and (production.id == "FERMENTER" or production.id == "BIOGAS") then
            local cycles = num(production.cyclesPerMinute, 0) * 60 * timeAdjustment
            for _, output in ipairs(production.outputs or {}) do
              local amount = num(output.amount, 0) * cycles
              if output.type == methaneType then
                methane = methane + amount
              elseif output.type == digestateType then
                digestate = digestate + amount
              end
            end
          end
        end
      end
    end
  end
  return math.floor(methane + 0.5), math.floor(digestate + 0.5)
end

-- The four tanks the mod's screen shows (BgaControlDialog:updateStorageTelemetry): solid feed stock
-- in the bunkers, liquid feed stock, the fermenters' pooled fill and the digestate store. The
-- fermenter capacity is the mod's POOLED one — it shares one volume across all substrates — which
-- the DLC's own per-fill-type capacities overstate.
local function stock(main, substrates, cluster)
  local silageType = g_fillTypeManager:getFillTypeIndexByName("SILAGE")
  local manureType = g_fillTypeManager:getFillTypeIndexByName("MANURE")
  local beetType = g_fillTypeManager:getFillTypeIndexByName("SUGARBEET_CUT")
  local liquidType = g_fillTypeManager:getFillTypeIndexByName("LIQUIDMANURE")
  local digestateType = g_fillTypeManager:getFillTypeIndexByName("DIGESTATE")

  local out = {
    solid = { level = 0, capacity = 0 },
    liquid = { level = 0, capacity = 0 },
    fermenter = { level = 0, capacity = 0 },
    digestate = { level = 0, capacity = 0 },
  }
  local visited = {}

  local function supports(storage, fillType)
    return fillType ~= nil and storage.getIsFillTypeSupported ~= nil and storage:getIsFillTypeSupported(fillType)
  end

  local function read(storage, isFermenter, isBunker)
    if storage == nil or visited[storage] then
      return
    end
    visited[storage] = true
    if isFermenter then
      out.fermenter.level = out.fermenter.level + num(substrates.getStorageSubstrateLevel(storage), 0)
      out.fermenter.capacity = out.fermenter.capacity + num(substrates.getFermenterPoolCapacity(storage), 0)
    end
    if isBunker then
      out.solid.level = out.solid.level + num(substrates.getBunkerStorageLevel(storage), 0)
      out.solid.capacity = out.solid.capacity + num(substrates.getBunkerStorageCapacity(storage), 0)
    elseif not isFermenter then
      if supports(storage, silageType) then
        out.solid.level = out.solid.level + num(storage:getFillLevel(silageType), 0)
        out.solid.capacity = out.solid.capacity + num(storage:getCapacity(silageType), 0)
      end
      if supports(storage, manureType) then
        out.solid.level = out.solid.level + num(storage:getFillLevel(manureType), 0)
      end
      if supports(storage, beetType) then
        out.solid.level = out.solid.level + num(storage:getFillLevel(beetType), 0)
      end
    end
    if supports(storage, liquidType) and (isBunker or not isFermenter) then
      out.liquid.level = out.liquid.level + num(storage:getFillLevel(liquidType), 0)
      out.liquid.capacity = out.liquid.capacity + num(storage:getCapacity(liquidType), 0)
    end
    if supports(storage, digestateType) then
      out.digestate.level = out.digestate.level + num(storage:getFillLevel(digestateType), 0)
      out.digestate.capacity = out.digestate.capacity + num(storage:getCapacity(digestateType), 0)
    end
  end

  for placeable in pairs(cluster) do
    local isFermenter = main.isFermenterPlaceable(placeable)
    -- the role by name: the DLC's numeric SANDBOX_TYPE_BUNKER is nil from here (PumpsAndHoses.lua)
    local isBunker = false
    if placeable.getSandboxTypeName ~= nil then
      local ok, role = pcall(placeable.getSandboxTypeName, placeable)
      isBunker = ok and role == "BUNKER"
    end
    if placeable.storage ~= nil then
      read(placeable.storage, isFermenter, isBunker)
    end
    if placeable.spec_silo ~= nil and type(placeable.spec_silo.storages) == "table" then
      for _, storage in ipairs(placeable.spec_silo.storages) do
        read(storage, isFermenter, isBunker)
      end
    end
    local ppSpec = placeable.spec_sandboxPlaceableProductionPoint
    if ppSpec ~= nil and ppSpec.productionPoint ~= nil and ppSpec.productionPoint.storage ~= nil then
      read(ppSpec.productionPoint.storage, isFermenter, isBunker)
    end
  end

  for _, row in pairs(out) do
    row.level = math.floor(row.level + 0.5)
    row.capacity = math.floor(row.capacity + 0.5)
  end
  return out
end

-- The separator's state word (BgaControlDialog:updateSeparatorDisplay), or nil when this machine
-- cannot tell — the processing flags live on the server's separator object.
local function separatorState(separator, flow)
  if separator.isProcessing == nil then
    return nil
  end
  if not separator.isProcessing or flow <= 0 then
    return "INACTIVE"
  elseif separator.isOperating then
    return "OPERATING"
  end
  if separator.getFreeCapacity ~= nil and separator.fillTypeIndex ~= nil then
    local free = separator:getFreeCapacity(separator.fillTypeIndex)
    if free ~= nil and free <= 0 then
      return "FULL"
    end
  end
  if separator.sourceStorage == nil then
    return "NO_SILO"
  end
  return "STANDBY"
end

local function dailyUpkeep(cluster)
  local total = 0
  for placeable in pairs(cluster) do
    local upkeep = tonumber(placeable.dailyUpkeep)
    if upkeep == nil and type(placeable.getDailyUpkeep) == "function" then
      upkeep = tonumber(placeable:getDailyUpkeep())
    end
    total = total + (upkeep or 0)
  end
  return math.floor(total + 0.5)
end

local function collectControl(main, substrates, fixes, root)
  local data = root.bgaControlData
  local isClient = g_server == nil
  if type(data) ~= "table" or (isClient and root.bgaStateReceived ~= true) then
    return nil
  end

  local cluster = main.getAllClusterPlaceables(root)
  local fermenters = VDT.PnhBgaControl.fermenterCount(main, root)
  local running = isRunning(main, cluster)

  local target = num(data.targetTemperature, 30.0)
  local actual = num(data.actualTemperature, 15.0)
  local silageFeed = num(root.bgaTargetSilageFlowLph or data.silageFlow, 0)
  local liquidFeed = num(root.bgaTargetLiquidFlowLph or data.liquidFlow, 0)
  local agitator = clamp(num(data.agitatorIntensity, 0.0), 0, 1)
  local recirculation = clamp(num(data.recirculationRate, 0.0), 0, VDT.PnhBgaControl.LIMITS.recirculationMax)
  local activity = clamp(num(data.bacterialActivity, 0.10), 0.10, 1.0)
  local stress = clamp(num(root.bgaStressTimer, 0), 0, 2 * VDT.PnhBgaControl.STRESS_LIMIT)

  -- the substrate mix in the fermenters
  local pp = main.getProductionPointFromPlaceable(root)
  local levels = main.getFermenterSubstrateLevels(root, pp)
  local total = num(substrates.getTotalLevel(levels), 0)
  local function group(name)
    return num(substrates.getDisplayGroupLevelFromLevels(levels, name), 0)
  end
  local liquidLevel = group("LIQUIDMANURE")
  local ts, ph = VDT.PnhBgaControl.chemistry({
    total = total,
    rawDryMatter = total > 50 and num(substrates.getWeightedDryMatter(levels), 0) or 0,
    acidLoad = total > 50 and num(substrates.getWeightedAcidLoad(levels), 0) or 0,
    liquidLevel = liquidLevel,
    recirculation = recirculation,
    agitator = agitator,
  })

  -- which groups count as fed: the recipe's own set where the server has built one, else 500 L per
  -- fermenter (the dialog's checkSubstrateActive)
  local inRecipe = type(root.bgaActiveRecipeSubstrates) == "table" and root.bgaActiveRecipeSubstrates or {}
  local function active(name, level)
    local substrate = substrates.getSubstrateByName(name)
    if substrate ~= nil and inRecipe[substrate.fillType] ~= nil then
      return inRecipe[substrate.fillType] == true
    end
    return level >= 500.0 * fermenters
  end

  local agitatorFactor, agitatorCost, _, agitatorKey, agitatorCritical =
    main.calculateAgitatorRheology(agitator, ts, fermenters)
  local agitatorStatus = AGITATOR_STATUS[agitatorKey]

  local mode, status = VDT.PnhBgaControl.diagnose({
    running = running,
    hasSilage = active("SILAGE", group("SILAGE")),
    hasManure = active("MANURE", group("MANURE")),
    hasBeet = active("SUGARBEET_CUT", group("SUGARBEET_CUT")),
    hasLiquid = active("LIQUIDMANURE", liquidLevel),
    activity = activity,
    actual = actual,
    recirculation = recirculation,
    ph = ph,
    feeding = silageFeed > 0 or liquidFeed > 0,
    silageFeed = silageFeed,
    liquidFeed = liquidFeed,
    total = total,
    agitatorStatus = agitatorStatus,
  })

  local producing = total > 50 and running
  local stressed = ph < 7.0
    or ph > 8.05
    or ts > 16.5
    or agitatorCritical == true
    or (producing and actual < 32.0)
    or actual > 56.0
  local trend = VDT.PnhBgaControl.biologyTrend({
    dying = stress > VDT.PnhBgaControl.STRESS_LIMIT or root.bgaBioIsDying == true,
    stressed = stressed,
    producing = producing,
    actual = actual,
    activity = activity,
  })

  -- efficiency: the server's own figure (written into the recipe, synced to clients), else the formula
  local efficiency = fixes ~= nil and fixes.getEfficiency ~= nil and tonumber(fixes.getEfficiency(root)) or nil
  if not running then
    efficiency = 0
  elseif efficiency == nil then
    efficiency = clamp(VDT.PnhBgaControl.temperatureFactor(actual) * num(agitatorFactor, 1), 0.20, 1.30) * activity
  end

  local heating = 0
  if target > 32.0 then
    heating = (target - 32.0) * 1.4 + (math.max(0, target - 40.0) ^ 1.75) * 2.5
  end
  local operating = running and math.floor(10.0 * fermenters + heating * fermenters + num(agitatorCost, 0)) or 0

  local methane, digestate, chpLoad, torch
  if isClient then
    methane = num(data.liveMethaneLph, 0)
    digestate = num(data.liveDigestateLph, 0)
    chpLoad = num(data.liveBhkwUtil, 0)
    torch = num(data.liveTorchUtil, 0)
  else
    methane, digestate = liveFlows(main, cluster)
    local rawChp, rawTorch = main.getSandboxUtilization(root)
    chpLoad, torch = num(rawChp, 0), num(rawTorch, 0)
  end
  chpLoad = clamp(math.floor(chpLoad + 0.5), 0, 100)
  torch = clamp(math.floor(torch + 0.5), 0, 100)
  local income = 0
  if chpLoad > 0 and main.getLiveBhkwIncome ~= nil then
    income = num(main.getLiveBhkwIncome(root, chpLoad), 0)
  end

  local separatorBlock
  local separator = main.getSeparatorForBga(root)
  if separator ~= nil then
    local flow = num(main.getSeparatorFlowLph(root), 0)
    separatorBlock = { flow = math.floor(flow + 0.5), state = separatorState(separator, flow) }
  end

  return {
    temperature = { target = round(target, 1), actual = round(actual, 1) },
    feed = {
      silage = math.floor(silageFeed + 0.5),
      silageMax = VDT.PnhBgaControl.LIMITS.silagePerFermenter * fermenters,
      liquid = math.floor(liquidFeed + 0.5),
      liquidMax = VDT.PnhBgaControl.LIMITS.liquidPerFermenter * fermenters,
    },
    agitator = math.floor(agitator * 100 + 0.5),
    recirculation = math.floor(recirculation * 100 + 0.5),
    separator = separatorBlock,
    biology = {
      activity = math.floor(activity * 100 + 0.5),
      trend = trend,
      stress = math.floor(stress + 0.5),
      stressLimit = VDT.PnhBgaControl.STRESS_LIMIT,
    },
    dryMatter = ts,
    ph = ph,
    efficiency = math.floor(efficiency * 100 + 0.5),
    mode = mode,
    status = status,
    methane = methane,
    digestate = digestate,
    chp = { load = chpLoad, income = income },
    torch = torch,
    stock = stock(main, substrates, cluster),
    costs = { operating = operating, upkeep = dailyUpkeep(cluster) },
  }
end

local reported = false

---The BGA Control block for one plant, or nil when the mod is absent, the plant has no state yet, or
---reading it failed (logged once — another mod's internals must never take the channel down).
---@param root table the plant's root placeable
---@return BgaControlModel|nil
function VDT.PnhBgaControl.control(root)
  local main, substrates, fixes = VDT.PnhBgaControl.tables()
  if main == nil or root == nil then
    return nil
  end
  local ok, result = pcall(collectControl, main, substrates, fixes, root)
  if ok then
    return result
  end
  if not reported then
    reported = true
    local debugger = g_vdTelemetry ~= nil and g_vdTelemetry.debugger or nil
    if debugger ~= nil then
      debugger:error("integration PnhBgaControl: control failed (" .. tostring(result) .. ")")
    end
  end
  return nil
end
