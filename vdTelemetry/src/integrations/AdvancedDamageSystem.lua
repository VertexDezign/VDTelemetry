-- Optional integration: FS25_AdvancedDamageSystem ("ADS", by id577) — the maintenance mod the
-- cluster's warning lamps were drawn for (see VDTerminal panels/Telltales.kt, issue #79).
--
-- ADS replaces the vanilla damage model outright: every motorized vehicle is split into eight systems
-- (engine, transmission, hydraulics, cooling, electrical, chassis, work process, fuel), each wearing
-- at its own rate, each able to break down, and the whole thing serviced in a workshop that takes
-- game time. It attaches to motorized vehicles only, so an implement keeps its vanilla wear.
--
-- WHAT IT MEANS FOR DATA WE ALREADY EXPORT. Two things quietly change under ADS:
--
--   * `wearable.damage` is pinned to 0. ADS overwrites `updateDamageAmount` to return 0 and the
--     server re-zeroes it every tick, so the vanilla damage figure stops meaning anything on a
--     vehicle (an implement's is still real). Condition lives in ADS's own systems instead — and is
--     deliberately not readable at a glance, see WHAT WE DELIBERATELY DO NOT EXPORT below.
--   * engine temperature becomes ADS's, and is the only one worth having. ADS mirrors its thermal
--     model into `spec_motorized.motorTemperature.value`, but only through `updateMotorTemperature`,
--     which the engine calls **on the server only, and only while the motor runs**. FS25 never syncs
--     that field either (`motorTemperature.valueSend` is dead code in Motorized.lua) — so on a
--     multiplayer client the vanilla figure sits at its initial 20 °C forever, with or without ADS.
--     ADS *does* replicate its own thermal state, and smooths it client-side every frame, so reading
--     `spec.engineTemperature` here is both the ADS answer and the first correct engine temperature
--     this mod has ever exported to an MP client.
--
-- WHAT WE DELIBERATELY DO NOT EXPORT. ADS hides exact numbers on purpose: condition, per-system
-- condition/stress and service level are known to the player only as a coarse status from a workshop
-- inspection, and as percentages only after an expensive full defectoscopy. Exporting
-- `spec.conditionLevel` to a dashboard would hand out a permanent free diagnostic and delete that
-- mechanic. So `inspected` below carries what an inspection actually told the player
-- (getLastInspectedCondition / getLastInspectedService) and nothing else.
--
-- The pre-shift chores -- radiator and air-intake clogging, lubrication -- are out for the same
-- reason, and coarse bands were not enough to save them: they are learned by getting out and walking
-- round the machine, so a dashboard that printed them would hand the player that walk for free.
-- Having to go and look is what they are for. The dashboard never knows more than the driver does.
--
-- Mod-environment isolation: ADS's `ADS_Breakdowns` / `ADS_Config` are globals in *its own* Lua
-- environment, so from ours they are reachable only as `FS25_AdvancedDamageSystem.ADS_Breakdowns`
-- (see EnhancedLoanSystem.lua, where the same trap already bit). The per-vehicle spec table and the
-- functions ADS registers on the vehicle type are on the vehicle itself, so those are called
-- directly.
--
-- **Written against FS25_AdvancedDamageSystem 0.9.2.8-beta** — everything here reads that mod's
-- internals, which it is free to rename in any release, and 0.9.2.7 already proves it does: the
-- exclusion flag `spec.isExcludedVehicle` became the method `getIsADSExcluded()` (see spec below).
-- One version is tracked rather than several, as with every other integration here; a player on an
-- older ADS gets no `vehicle.ads` block rather than a plausible wrong one.
--
-- So fail soft, never throw: a missing field means "no ADS data", because a throw in a collector takes
-- the whole telemetry write down with it.
--
-- Two more 0.9.2.8 changes, neither of which needs anything from us. Its other renames
-- (`setADSUserExcluded` -> `setADSPlayerExcluded`, the savegame's `isExcludedByUser` ->
-- `isExcludedByPlayer`) are on the write side, which we never touch. And contract vehicles stopped
-- accruing service hours (its new `isMissionVehicle` gate zeroes getHoursSinceLastMaintenance), so
-- `service.hours` reads 0 on one -- ADS's own answer, and its own menu shows the same, so it travels
-- unaltered.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.AdvancedDamageSystem = {}

-- Fields this integration adds to the model, declared here next to the code that sets them (see
-- EnhancedVehicle.lua for why). Mirrored on the shared Kotlin `Ads`.

-- One dashboard lamp: "OFF", "COLD", "WARN" or "CRIT" — ADS's own four indicator colours (its
-- DEFAULT / COOL / WARNING / CRITICAL), by name rather than by hue. A lamp this machine does not
-- have is absent, not "OFF" (see lampYears).
---@alias AdsLampModel string

---@class AdsLampsModel
---@field engine AdsLampModel?
---@field warning AdsLampModel?
---@field brakes AdsLampModel?
---@field battery AdsLampModel?
---@field coolant AdsLampModel?
---@field service AdsLampModel?

-- Where this machine is in its service interval. Both in operating hours, both player-visible in
-- game (the shop, the vehicle info panel and ADS's fleet menu all print them).
---@class AdsServiceModel
---@field hours number hours since the last maintenance
---@field interval number hours the manufacturer recommends between them

-- What the last workshop inspection told the player — percentages, because that is the form the
-- report is in. `complete` is ADS's own flag for a full defectoscopy as opposed to a routine check;
-- absent entirely until the machine has been inspected at least once.
---@class AdsInspectedModel
---@field condition number?
---@field service number?
---@field complete boolean?

---@class AdsElectricalModel
---@field systemVoltage number
---@field unit string

-- The load ADS wears the engine on, as a percentage. NOT the same quantity as `motor.load`, which is
-- the plain engine load and stays exported unchanged: this is that load plus what the driveline is
-- doing under it, so it can read past 100 (see collectLoad). `overloadAt` is where ADS starts
-- charging wear for it, and is configurable, so it travels with the value rather than being a number
-- the terminal knows.
---@class AdsLoadModel
---@field value number
---@field overloadAt number
---@field unit string

---@class AdsModel
---@field lamps AdsLampsModel?
---@field service AdsServiceModel?
---@field inspected AdsInspectedModel?
---@field electrical AdsElectricalModel?
---@field load AdsLoadModel?
---@field transmissionTemperatur TemperaturModel?

---@class VehicleModel
---@field ads AdsModel?

-- The fleet-channel counterpart of AdsModel: the maintenance side of a machine NOBODY is sitting in,
-- which is what ADS's own fleet menu lists and what the fleet channel asks for (see
-- src/collect/FleetExporter.lua). Shapes live in src/model/FleetModel.lua; `inspected` and `service`
-- are the very same blocks the driven vehicle carries, on purpose -- a machine must not read
-- differently depending on which screen is asking.
---@class FleetVehicleModel
---@field ads FleetAdsModel?

-- ADS's mod name, which is also its env global's key.
VDT.AdvancedDamageSystem.MOD_NAME = "FS25_AdvancedDamageSystem"

-- The lamps we carry, as ids of ADS_Breakdowns.DASHBOARD (which is also how spec.activeIndicators is
-- keyed), in the order they read on the band.
--
-- ADS defines eight; two of them are not worth carrying, and that is an editorial call rather than
-- something to read off the mod. `transmission` is declared and then never referenced by a single
-- breakdown — dead in the mod itself. `oil` is alive (it lights below 20% service) but ADS
-- deliberately does not draw it, and drawing it here would be telling the player something the mod
-- chose to withhold. Their glyphs are also the only two we would have to invent, which is a fair sign
-- they are not part of what a driver sees.
local LAMPS = { "engine", "warning", "brakes", "battery", "coolant", "service" }

-- MotorState (the engine's own enum, values from vehicles/specializations/enums/MotorState.lua):
-- OFF = 1, IGNITION = 2, STARTING = 3, ON = 4. Named locally because the enum is a base-game global
-- the specs do not stand up.
local MOTOR_OFF = 1
local MOTOR_IGNITION = 2
local MOTOR_STARTING = 3

-- Coolant-lamp temperatures, in °C, as ADS's own HUD applies them: warm-but-watch, then trouble.
-- Literals in the mod too — unlike the cold threshold, which is configurable and read from its config.
local COOLANT_WARN_C = 99
local COOLANT_CRIT_C = 110

-- Fallbacks for the two cold thresholds when ADS's config is out of reach; its shipped defaults.
local COLD_ENGINE_C = 50
local COLD_TRANSMISSION_C = 45

-- ... and for the load above which ADS starts charging the engine wear for being overloaded.
local MOTOR_OVERLOADED = 0.85

-- Below this much service left, ADS treats the machine as running on spent consumables.
local SERVICE_OVERDUE_RATIO = 1.0

-- The gauge frame the transmission temperature is reported against — the same 20..120 °C the base
-- game gives the coolant gauge, so a second temperature bar reads on the same scale as the first.
local TEMP_GAUGE_MIN_C = 20
local TEMP_GAUGE_MAX_C = 120

-- At or below this, ADS's transmission temperature is not a reading. Its "no such temperature"
-- sentinel is -99, but the value DRIFTS off it once the thermal smoothing has touched it -- it turns
-- up in the -80s on machines that have no CVT at all. ADS's own syncBlinkingWarning tests `> -90`
-- rather than the sentinel for exactly that reason, so this is its number, not one we picked.
--
-- It is only a sanity floor. Whether a machine HAS a transmission temperature is a question about the
-- machine (see hasCVT), never about the value: a sentinel that drifts cannot be a presence test.
local NO_TRANSMISSION_TEMP_C = -90

-- Our own latch for the indicator lamps, per vehicle. ADS's `activeIndicators[id].isActive` is the
-- same latch, but it is driven from ADS's HUD draw and only for the vehicle the local player is
-- sitting in — so it stands still whenever that HUD is not running. The lamps are a state machine
-- (switchOn latches them, only switchOff clears them), so we keep our own and seed it from theirs.
--
-- Weak-keyed: a vehicle that has been sold or unloaded must not be held alive by this table.
local latches = setmetatable({}, { __mode = "k" })

-- The mod's env global (keyed by the exact mod name); nil when ADS isn't installed.
local function env()
  return type(FS25_AdvancedDamageSystem) == "table" and FS25_AdvancedDamageSystem or nil
end

---ADS's spec on an object, or nil when there is nothing to read: no ADS, not a motorized vehicle
---(ADS attaches to those only), or a vehicle ADS excludes (electric machines and the bikes by
---default, plus anything the player has switched off).
---
---The exclusion question goes to ADS's own `getIsADSExcluded`, and a machine that cannot answer it is
---one we report nothing for. That is stricter than it looks, and deliberately so:
---
---  * An excluded machine still CARRIES the spec table -- ADS's `onPostLoad` returns before populating
---    it -- so its fields sit at their `onLoad` defaults rather than at nothing: `engineTemperature`
---    is -99 and `year` is 2000. A gate that lets one through does not export a gap, it exports
---    fiction: -99 °C on the cluster's temperature gauge, a coolant lamp latched cold, and the full
---    lamp band lit on an electric loader.
---  * The answer stopped being a stored flag in 0.9.2.8, so there is nothing to fall back TO. A
---    machine excluded by default can be brought back in by a Used Equipment Yard item, which only ADS
---    can see; the method is the only place that composition happens.
---  * A missing method means an ADS older than the one above, whose fields we would be reading by
---    their old names anyway. Silence is the honest answer to a version we have not read.
---@param object table a vehicle or implement
---@return table|nil
function VDT.AdvancedDamageSystem.spec(object)
  local spec = object ~= nil and object.spec_AdvancedDamageSystem or nil
  if spec == nil or type(object.getIsADSExcluded) ~= "function" then
    return nil
  end
  -- Third-party code, so contained like every other ADS call (see [call]); a throw is not a "no".
  local ok, excluded = pcall(object.getIsADSExcluded, object)
  if not ok or excluded ~= false then
    return nil
  end
  return spec
end

---Whether ADS is installed and up.
---@return boolean
function VDT.AdvancedDamageSystem.isAvailable()
  return env() ~= nil
end

---Whether this machine has a continuously variable transmission, and so a transmission oil
---temperature to report at all.
---
---ADS's own test, used everywhere it decides whether that half of its thermal model applies (its
---local `hasCVTTransmission`): a CVT motor is the one that carries a `minForwardGearRatio`, because a
---geared transmission has discrete ratios instead. Asking the machine rather than reading a sentinel
---out of the temperature is the whole point -- see NO_TRANSMISSION_TEMP_C.
---@param vehicle table
---@return boolean
local function hasCVT(vehicle)
  if type(vehicle.getMotor) ~= "function" then
    return false
  end
  local ok, motor = pcall(vehicle.getMotor, vehicle)
  return ok and type(motor) == "table" and motor.minForwardGearRatio ~= nil
end

-- ADS's four indicator colours, as the table it compares against by identity. nil when unreachable,
-- which turns every lit lamp into a plain "WARN" rather than losing the lamp.
local function colours()
  local e = env()
  local breakdowns = e ~= nil and e.ADS_Breakdowns or nil
  return type(breakdowns) == "table" and breakdowns.COLORS or nil
end

-- Resolved once from ADS's HUD and then held: the indicator table is built at mission start and never
-- changes after. nil until a live read succeeds, so we keep retrying until one does (a savegame
-- reload rebuilds ADS's HUD, and this file's state outlives it).
local liveLampYears = nil

---The per-lamp year gate, straight out of the table ADS's own dashboard draws from
---(`ADS_Main.hud.indicators`, each entry carrying the id it uses and the year it needs).
---
---Read live rather than mirrored because it is the one part of ADS's lamp behaviour that IS a table
---we can reach: the thresholds around it are literals inside the mod's function bodies, and those we
---have no choice but to copy.
---
---nil when the HUD has not been built, and the lamps then go silent rather than falling back to a
---mirrored copy of the years: which lamps a machine has is ADS's answer to give, and a machine with a
---driver in it always has a HUD built.
---@return table<string, number>|nil lamp id -> year
local function lampYears()
  if liveLampYears ~= nil then
    return liveLampYears
  end
  local e = env()
  local hud = e ~= nil and type(e.ADS_Main) == "table" and e.ADS_Main.hud or nil
  local indicators = type(hud) == "table" and hud.indicators or nil
  if type(indicators) ~= "table" then
    return nil
  end
  local years = {}
  for _, data in pairs(indicators) do
    -- `name` is the id ADS itself indexes activeIndicators by, so taking it from here means a lamp
    -- ADS renames is followed rather than lost.
    if type(data) == "table" and type(data.name) == "string" and tonumber(data.year) ~= nil then
      years[data.name] = tonumber(data.year)
    end
  end
  if next(years) == nil then
    return nil
  end
  liveLampYears = years
  return years
end

-- ADS's cold-engine / cold-transmission thresholds, which are user-configurable, with its shipped
-- defaults as the fallback.
local function coldThresholds()
  local e = env()
  local core = e ~= nil and type(e.ADS_Config) == "table" and e.ADS_Config.CORE or nil
  local engine = core ~= nil and core.ENGINE_FACTOR_DATA or nil
  local transmission = core ~= nil and core.TRANSMISSION_FACTOR_DATA or nil
  return tonumber(engine ~= nil and engine.COLD_MOTOR_TEMP_THRESHOLD or nil) or COLD_ENGINE_C,
    tonumber(transmission ~= nil and transmission.COLD_TRANSMISSION_THRESHOLD or nil) or COLD_TRANSMISSION_C
end

-- ADS's engine-overload threshold, also user-configurable, with its shipped default as the fallback.
local function overloadThreshold()
  local e = env()
  local core = e ~= nil and type(e.ADS_Config) == "table" and e.ADS_Config.CORE or nil
  local engine = core ~= nil and core.ENGINE_FACTOR_DATA or nil
  return tonumber(engine ~= nil and engine.MOTOR_OVERLOADED_THRESHOLD or nil) or MOTOR_OVERLOADED
end

-- One of ADS's colour tables -> our severity name. Compared by identity, which is how ADS's own
-- COLOR_PRIORITY keys them; an unrecognized colour is still a lamp that is on, so it reads as WARN.
local function severityOf(colour, palette)
  if palette == nil or colour == nil then
    return "WARN"
  end
  if colour == palette.CRITICAL then
    return "CRIT"
  elseif colour == palette.WARNING then
    return "WARN"
  elseif colour == palette.COOL then
    return "COLD"
  end
  return nil -- DEFAULT: the lamp is off
end

-- Call one of ADS's own methods on a vehicle, containing anything it throws. Its getters walk the
-- machine's maintenance log and do arithmetic over it, which is third-party code over third-party
-- state; this collector runs inside the LATENCY-CRITICAL telemetry write, so a throw here would cost
-- the whole dashboard its tick for as long as the machine stayed in that state.
---@return any|nil, any|nil the method's first two results, or nil when it is missing or threw
local function call(vehicle, name, ...)
  if type(vehicle[name]) ~= "function" then
    return nil
  end
  local ok, first, second = pcall(vehicle[name], vehicle, ...)
  if not ok then
    return nil
  end
  return first, second
end

-- Evaluate one of ADS's switchOn/switchOff conditions, which the mod aggregates into a function but
-- may leave as a plain boolean on an older stage definition. Contained for the same reason as [call].
local function condition(fn, vehicle)
  if type(fn) == "boolean" then
    return fn
  end
  if type(fn) ~= "function" then
    return false
  end
  local ok, result = pcall(fn, vehicle)
  return ok and result == true
end

-- The severity of the breakdown-driven part of one lamp, or nil when no active breakdown lights it.
-- Mirrors ADS's own HUD state machine: an indicator latches on when any of its switchOn conditions
-- holds and only lets go when a switchOff one does.
local function breakdownSeverity(vehicle, spec, latch, palette, id)
  local indicators = spec.activeIndicators
  local indicator = type(indicators) == "table" and indicators[id] or nil
  if indicator == nil then
    latch[id] = nil
    return nil
  end
  local on = latch[id]
  if on == nil then
    on = indicator.isActive == true -- seed from ADS's own latch the first time we see this lamp
  end
  if not on then
    on = condition(indicator.switchOn, vehicle)
  end
  if on and condition(indicator.switchOff, vehicle) then
    on = false
  end
  latch[id] = on
  if not on then
    return nil
  end
  return severityOf(indicator.color, palette)
end

-- The coolant lamp's own rules, on top of any breakdown lighting it: blue while the engine (or, on a
-- CVT, the transmission) is still cold, then warm and then hot. Only applied while the lamp is
-- otherwise off, as in ADS's HUD — a breakdown's own colour outranks a temperature reading.
--
-- `cvt` is load-bearing rather than decorative: a machine without one carries a non-reading in that
-- field which drifts up out of the -90s, and taken at face value it is below every cold threshold
-- there is — so the lamp would sit blue for the whole session on most of the fleet.
local function coolantSeverity(spec, severity, cvt)
  if severity ~= nil then
    return severity
  end
  local engine = tonumber(spec.engineTemperature)
  local transmission = cvt and tonumber(spec.transmissionTemperature) or nil
  local coldEngine, coldTransmission = coldThresholds()

  if transmission ~= nil and transmission <= NO_TRANSMISSION_TEMP_C then
    transmission = nil
  end

  if (engine ~= nil and engine < coldEngine) or (transmission ~= nil and transmission < coldTransmission) then
    return "COLD"
  end
  -- Either temperature can put the lamp up, and the hotter verdict wins.
  local hottest = math.max(engine or -math.huge, transmission or -math.huge)
  if hottest > COOLANT_CRIT_C then
    return "CRIT"
  elseif hottest > COOLANT_WARN_C then
    return "WARN"
  end
  return nil
end

-- One decimal, without MathUtil: the specs stand up ValueMapper but not the engine's math globals,
-- and both figures below want tenths rather than the mapper's default hundredths.
local function round1(value)
  return math.floor(value * 10 + 0.5) / 10
end

---How far into its service interval this machine is, as hours-since and hours-recommended. Both
---come from ADS's own getters, which fold in the maintenance it has actually had.
---@param vehicle table
---@return AdsServiceModel|nil nil when either getter is missing or the interval is degenerate
local function collectService(vehicle)
  -- Parenthesised: `call` returns two values, and tonumber's second argument is a *base*.
  local hours = tonumber((call(vehicle, "getHoursSinceLastMaintenance")))
  local interval = tonumber((call(vehicle, "getMaintenanceInterval")))
  if hours == nil or interval == nil or interval <= 0 then
    return nil
  end
  return { hours = round1(hours), interval = round1(interval) }
end

-- Whether the service lamp is due to come on: past the interval the manufacturer recommends.
local function serviceOverdue(service)
  return service ~= nil and (service.hours / service.interval) > SERVICE_OVERDUE_RATIO
end

---The dashboard lamps, exactly as ADS drives its own: dark with the key out, every lamp lit while
---the starter turns (a real bulb check), and otherwise whatever the machine's breakdowns and
---temperatures say. Only the lamps a machine of this age actually has are reported.
---@param vehicle table
---@param spec table ADS's spec
---@param service AdsServiceModel|nil
---@param cvt boolean whether this machine has a transmission temperature at all
---@return AdsLampsModel|nil
local function collectLamps(vehicle, spec, service, cvt)
  local motorState = call(vehicle, "getMotorState")
  local years = lampYears()
  if motorState == nil or years == nil then
    return nil
  end
  local latch = latches[vehicle]
  if latch == nil then
    latch = {}
    latches[vehicle] = latch
  end

  local year = tonumber(spec.year) or 0
  local palette = colours()
  -- With the key out ADS's dashboard is dark and its latches are released; ours follow, so a lamp
  -- does not come back lit from before the machine was shut down.
  local off = motorState == MOTOR_OFF
  -- Ignition and cranking light everything the machine has, which is what a real cluster does while
  -- the starter turns and the one moment a driver can see that the lamps still work.
  local bulbCheck = motorState == MOTOR_IGNITION or motorState == MOTOR_STARTING

  local lamps = {}
  local any = false
  for _, id in ipairs(LAMPS) do
    -- A lamp ADS reports no year for is one it no longer has: absent, not off.
    if (years[id] or math.huge) < year then
      local severity
      if off then
        latch[id] = nil
      elseif bulbCheck then
        severity = "WARN"
      else
        severity = breakdownSeverity(vehicle, spec, latch, palette, id)
        if id == "coolant" then
          severity = coolantSeverity(spec, severity, cvt)
        elseif id == "service" and severity == nil and serviceOverdue(service) then
          severity = "WARN"
        end
      end
      lamps[id] = severity or "OFF"
      any = true
    end
  end
  if not any then
    return nil
  end
  return lamps
end

---What the last workshop inspection told the player, as percentages. ADS returns the figure plus
---whether the report was a full defectoscopy; a machine never inspected reports nothing.
---@param vehicle table
---@return AdsInspectedModel|nil
local function collectInspected(vehicle)
  local rawCondition, conditionComplete = call(vehicle, "getLastInspectedCondition")
  -- Coerced up front and used coerced from here on: a getter that answers with a numeric string
  -- would otherwise pass the tonumber guard and then throw on the comparison right after it.
  local conditionLevel = tonumber(rawCondition)
  local serviceLevel = tonumber((call(vehicle, "getLastInspectedService")))
  -- ADS returns 0 for "no report in the log at all", which is not a reading of zero condition.
  if conditionLevel == nil or conditionLevel <= 0 then
    return nil
  end
  local model = {
    condition = tonumber(ValueMapper.mapPercentage(conditionLevel, 0)),
    complete = conditionComplete == true,
  }
  if serviceLevel ~= nil and serviceLevel > 0 then
    model.service = tonumber(ValueMapper.mapPercentage(serviceLevel, 0))
  end
  return model
end

---The load ADS wears the engine on, and where it starts charging for it.
---
---This is `dynamicMotorLoad`, which is the plain engine load everywhere except on a field with an
---implement down and working -- there ADS adds what the driveline is doing under the draft, and the
---sum is allowed past 100%. It is the figure ADS puts on its own dashboard and the one its overload
---wear keys off, so under ADS it is the load that means something; `motor.load` stays exported
---beside it, unchanged, because the plain engine load is still true and is not what this replaces.
---
---Reported uncapped. ADS's HUD clips its own readout at 100%, but the amount by which a machine is
---over is exactly what a driver would change their driving for, and it is not a number ADS hides --
---it colours the same readout to say so.
---@param spec table
---@return AdsLoadModel|nil
local function collectLoad(spec)
  local load = tonumber(spec.dynamicMotorLoad)
  if load == nil then
    return nil
  end
  return {
    value = tonumber(ValueMapper.mapPercentage(math.max(load, 0), 0)),
    overloadAt = tonumber(ValueMapper.mapPercentage(overloadThreshold(), 0)),
    unit = "%",
  }
end

---Object stage: runs per vehicle/implement during the walk (see registry.lua).
---
---Everything here is additive except the engine temperature, which is a *correction*: the vanilla
---figure the core collector read is stale under ADS (see the header), so it is overwritten in place
---rather than added alongside, and every existing consumer of `motor.temperatur` is fixed for free.
---@param object table a vehicle or implement (only motorized vehicles carry ADS's spec)
---@param model table the object's already core-collected model
function VDT.AdvancedDamageSystem.contributeObject(object, model)
  local spec = VDT.AdvancedDamageSystem.spec(object)
  if spec == nil then
    return
  end

  local engineTemp = tonumber(spec.engineTemperature)
  if engineTemp ~= nil and model.motor ~= nil and model.motor.temperatur ~= nil then
    model.motor.temperatur.value = math.floor(engineTemp)
  end

  ---@type AdsModel
  local ads = {}

  -- Asked once and handed down: both the coolant lamp and the transmission field turn on it, and
  -- neither may fall back to reading it out of the temperature (see NO_TRANSMISSION_TEMP_C).
  local cvt = hasCVT(object)

  local service = collectService(object)
  ads.service = service
  ads.lamps = collectLamps(object, spec, service, cvt)
  ads.inspected = collectInspected(object)
  ads.load = collectLoad(spec)

  -- A CVT runs its own thermal model and its own gauge. Only a machine that HAS one gets the field:
  -- the terminal draws a second temperature bar off its presence, and a bar for oil that does not
  -- exist is worse than no bar at all.
  local transmissionTemp = tonumber(spec.transmissionTemperature)
  if cvt and transmissionTemp ~= nil and transmissionTemp > NO_TRANSMISSION_TEMP_C then
    ---@type TemperaturModel
    ads.transmissionTemperatur = {
      value = math.floor(transmissionTemp),
      min = TEMP_GAUGE_MIN_C,
      max = TEMP_GAUGE_MAX_C,
      unit = "°C",
    }
  end

  -- System voltage rather than the battery's own terminal voltage: it is what the machine's
  -- electrics actually see, and the figure ADS puts on its dashboard.
  local voltage = tonumber(spec.systemVoltageV)
  if voltage ~= nil then
    ads.electrical = { systemVoltage = round1(voltage), unit = "V" }
  end

  if next(ads) ~= nil then
    model.ads = ads
  end
end

---ADS's own STATUS values are i18n keys ("ads_spec_state_ready"), which is not a token to put on the
---wire. Resolved against ADS's table so a status it renames is followed rather than mistranslated,
---with the key's own shape as the fallback for when that table is out of reach.
---
---A string neither of those resolves is reported as UNKNOWN rather than dropped: whatever a future
---ADS calls it, the one thing known about it is that it is not the state that maps to READY, and the
---terminal must not print "ready to work" over a state nobody has read (see AdsState).
---@param currentState any
---@return string|nil the state token, or nil when the machine has no ADS state at all
local function stateToken(currentState)
  if type(currentState) ~= "string" then
    return nil
  end
  local e = env()
  local class = e ~= nil and e.AdvancedDamageSystem or nil
  local statuses = type(class) == "table" and class.STATUS or nil
  if type(statuses) == "table" then
    for name, value in pairs(statuses) do
      if value == currentState and type(name) == "string" then
        return name
      end
    end
  end
  local suffix = string.match(currentState, "^ads_spec_state_(%a+)$")
  return suffix ~= nil and string.upper(suffix) or "UNKNOWN"
end

---One of ADS's OWN localized texts -- a breakdown's part, severity and description are all its keys,
---and the fleet rows carry the text rather than the key because the terminal has no language files.
---
---The mod-environment isolation again, in its i18n form: `I18N:addModI18N` gives every mod its own
---`texts` table, so an `ads_*` key is not in ours and asking for it plainly answers with the literal
---string "Missing '<key>' in l10n_xx.xml". The customEnv argument is the way in, and `hasText` is what
---keeps that literal off the screen -- the same lookup the Invoices integration does (see
---VDT.Invoices.modText).
---
---g_i18n is checked before it is indexed rather than merely pcall'd: `pcall(g_i18n.getText, ...)`
---evaluates the field access first, so a nil g_i18n would throw outside the pcall's protection.
---@param key any
---@return string|nil
local function text(key)
  if type(key) ~= "string" or key == "" or g_i18n == nil then
    return nil
  end
  if type(g_i18n.hasText) ~= "function" or type(g_i18n.getText) ~= "function" then
    return nil
  end
  local okHas, has = pcall(g_i18n.hasText, g_i18n, key, VDT.AdvancedDamageSystem.MOD_NAME)
  if not okHas or has ~= true then
    return nil
  end
  local ok, value = pcall(g_i18n.getText, g_i18n, key, VDT.AdvancedDamageSystem.MOD_NAME)
  if not ok or type(value) ~= "string" or value == "" then
    return nil
  end
  return value
end

---One of ADS's log dates ({day, month, year}, where month is the game period) as our own shape.
---@param value any
---@return FleetDateModel|nil
local function dateOf(value)
  local year = tonumber(type(value) == "table" and value.year or nil)
  local month = tonumber(type(value) == "table" and value.month or nil)
  if year == nil or month == nil then
    return nil
  end
  return { year = math.floor(year), month = math.floor(month), day = math.floor(tonumber(value.day) or 1) }
end

---One visible breakdown, rendered the way ADS's workshop dialog renders it: the part it is on (its
---`part`, falling back to the system), and the severity/description of the stage it has reached --
---except for a breakdown ADS has SUSPENDED, where what was done to it (a quick fix, a bad part)
---replaces both, because that is what the player is looking at.
---@param id string
---@param data table ADS's activeBreakdowns entry
---@param registry table|nil ADS_Breakdowns.BreakdownRegistry
---@param sources table|nil AdvancedDamageSystem.BREAKDOWN_SOURCES
---@return FleetBreakdownModel
local function breakdownRow(id, data, registry, sources)
  local stage = math.max(math.floor(tonumber(data.stage) or 1), 1)
  ---@type FleetBreakdownModel
  local row = { id = tostring(id), stage = stage }

  local entry = type(registry) == "table" and registry[id] or nil
  if type(entry) == "table" then
    row.part = text(entry.part) or text(entry.system)
    local stageData = type(entry.stages) == "table" and entry.stages[stage] or nil
    if type(stageData) == "table" then
      row.severity = text(stageData.severity)
      row.description = text(stageData.description)
    end
  end

  if data.isActive == false and type(sources) == "table" then
    if data.source == sources.QUICK_FIX then
      row.severity = text("ads_breakdowns_quick_fix_stage") or row.severity
      row.description = text("ads_breakdowns_temporarily_repaired_description") or row.description
    elseif data.source == sources.POOR_PARTS then
      row.severity = text("ads_breakdowns_defected_parts_stage") or row.severity
      row.description = text("ads_breakdowns_defected_parts_detected_description") or row.description
    end
  end

  return row
end

---The breakdowns the player KNOWS about -- ADS's `isVisible` flag, the same filter its workshop
---dialog applies. An undiscovered breakdown is precisely what the inspection mechanic is for, so it
---is not on the wire in any form, not even as a count.
---@param spec table
---@return FleetBreakdownModel[]|nil
local function collectBreakdowns(spec)
  local active = spec.activeBreakdowns
  if type(active) ~= "table" then
    return nil
  end
  local e = env()
  local breakdowns = e ~= nil and e.ADS_Breakdowns or nil
  local registry = type(breakdowns) == "table" and breakdowns.BreakdownRegistry or nil
  local class = e ~= nil and e.AdvancedDamageSystem or nil
  local sources = type(class) == "table" and class.BREAKDOWN_SOURCES or nil

  local rows = {}
  for id, data in pairs(active) do
    if type(id) == "string" and type(data) == "table" and data.isVisible == true then
      rows[#rows + 1] = breakdownRow(id, data, registry, sources)
    end
  end
  if #rows == 0 then
    return nil
  end
  -- Sorted because `pairs` is not ordered: an unsorted list would reshuffle itself between writes and
  -- push a "changed" document at every tick that changed nothing.
  table.sort(rows, function(a, b)
    return a.id < b.id
  end)
  return rows
end

---What the workshop is doing to this machine, while it is doing anything. Times are in-game hours,
---straight out of ADS's own getters: how much work is left, and the hour (plus day rollovers) it
---comes back. The price is the pending service's, falling back to asking ADS what the service it is
---performing costs -- exactly what its fleet menu's service section does.
---@param vehicle table
---@param spec table
---@return FleetWorkshopModel|nil
local function collectWorkshop(vehicle, spec)
  ---@type FleetWorkshopModel
  local workshop = {}

  local remaining = tonumber((call(vehicle, "getServiceDuration")))
  if remaining ~= nil and remaining > 0 then
    workshop.remaining = round1(remaining)
  end

  local finishHour, finishInDays = call(vehicle, "getServiceFinishTime")
  if tonumber(finishHour) ~= nil then
    workshop.finishHour = round1(tonumber(finishHour))
    workshop.finishInDays = math.floor(tonumber(finishInDays) or 0)
  end

  local price = tonumber(spec.pendingServicePrice)
  if price == nil or price <= 0 then
    price = tonumber(
      (
        call(
          vehicle,
          "getServicePrice",
          spec.currentState,
          spec.serviceOptionOne,
          spec.serviceOptionTwo,
          spec.serviceOptionThree == true
        )
      )
    )
  end
  if price ~= nil and price > 0 then
    workshop.price = math.floor(price)
  end

  if next(workshop) == nil then
    return nil
  end
  return workshop
end

---What this machine has cost in maintenance so far -- the sum of its log, which is the "cost" column
---of ADS's fleet menu.
---@param spec table
---@return number|nil
local function maintenanceCost(spec)
  local log = spec.maintenanceLog
  if type(log) ~= "table" then
    return nil
  end
  local total = 0
  for _, entry in ipairs(log) do
    total = total + (tonumber(type(entry) == "table" and entry.price or nil) or 0)
  end
  if total <= 0 then
    return nil
  end
  return math.floor(total)
end

---Fleet stage: runs per machine of the fleet channel (see registry.lua and collect/FleetExporter).
---
---A different block from [contributeObject]'s, deliberately: that one is the dashboard of the machine
---you are IN -- lamps, load, temperatures, all of which are live readings that mean nothing for a
---machine parked in a shed. This one is the maintenance record ADS's own fleet menu lists, which is
---the question a fleet list exists to answer.
---
---It reads the per-vehicle spec rather than ADS's `ADS_Main.vehicles` table, which is keyed by
---`uniqueId` and therefore empty of anything useful on a multiplayer client. The spec itself is fully
---synced (state, breakdowns and the maintenance log all cross in onReadStream/onReadUpdateStream), so
---a client sees the same fleet the server does.
---@param vehicle table
---@param row table the machine's already core-collected fleet row
function VDT.AdvancedDamageSystem.contributeFleetVehicle(vehicle, row)
  local spec = VDT.AdvancedDamageSystem.spec(vehicle)
  if spec == nil then
    return
  end

  local state = stateToken(spec.currentState)
  if state == nil then
    return -- no state at all means no ADS record worth reporting; the row keeps its vanilla condition
  end

  ---@type FleetAdsModel
  local ads = {
    state = state,
    inspected = collectInspected(vehicle),
    service = collectService(vehicle),
    lastInspection = dateOf(call(vehicle, "getLastInspectionDate")),
    lastMaintenance = dateOf(call(vehicle, "getLastMaintenanceDate")),
    breakdowns = collectBreakdowns(spec),
    maintenanceCost = maintenanceCost(spec),
  }
  if state ~= "READY" then
    ads.workshop = collectWorkshop(vehicle, spec)
  end

  row.ads = ads
end

-- Test seam: drop the per-vehicle lamp latches and the resolved year table between spec cases.
function VDT.AdvancedDamageSystem.reset()
  latches = setmetatable({}, { __mode = "k" })
  liveLampYears = nil
end
