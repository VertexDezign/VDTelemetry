-- Unit tests for the FS25_AdvancedDamageSystem integration (src/integrations/AdvancedDamageSystem.lua).
--
-- Run with `busted` from the vdTelemetry/ directory. ADS's constants live in its own Lua environment,
-- so the stubs plant `FS25_AdvancedDamageSystem.*` rather than bare globals -- the isolation trap the
-- integration is written around.
--
-- What is worth pinning down: that the lamps follow ADS's own state machine rather than a snapshot
-- (a latched indicator stays lit after its switchOn stops holding), that the key-out and cranking
-- states are what ADS says they are, that a machine only gets the lamps its production year gives it,
-- that the engine temperature is a correction of the core-collected value rather than an addition,
-- and that nothing leaks out of what ADS hides -- neither the exact values behind its workshop
-- diagnostic nor the chores it makes you get out of the cab to look at.

if ValueMapper == nil then
  dofile("src/mapper/ValueMapper.lua")
end
if VDT == nil or VDT.AdvancedDamageSystem == nil then
  dofile("src/integrations/AdvancedDamageSystem.lua")
end
-- The fleet cases encode a row to check that nothing ADS hides is anywhere in it.
if Json == nil then
  dofile("src/utils/Json.lua")
end

-- ADS's four indicator colours are compared by table identity, so the stub must hand out the *same*
-- tables the integration will see -- which is exactly how ADS keys its own COLOR_PRIORITY.
local COLORS = {
  DEFAULT = { 1, 1, 1, 0.03 },
  COOL = { 0.0097, 0.4287, 0.6445, 1 },
  WARNING = { 1, 0.4287, 0.0006, 1 },
  CRITICAL = { 0.8069, 0.0097, 0.0097, 1 },
}

-- ADS's breakdown registry, cut down to the two entries the fleet cases use. `part` is what its
-- workshop dialog prints as the breakdown's name, falling back to `system`; each stage carries its own
-- severity and description, both i18n keys.
local BREAKDOWNS = {
  ENGINE_OIL_LEAK = {
    part = "ads_part_engine",
    system = "ads_spec_system_engine",
    stages = {
      { severity = "ads_severity_minor", description = "ads_desc_seeping" },
      { severity = "ads_severity_major", description = "ads_desc_dripping" },
    },
  },
  -- No `part`: the name falls back to the system, as ADS's own dialog does.
  BRAKE_WEAR = {
    system = "ads_spec_system_chassis",
    stages = { { severity = "ads_severity_minor", description = "ads_desc_soft_pedal" } },
  },
}

---One entry of ADS_Hud's indicator table: the id it drives the lamp by, and the production year a
---machine must beat to have that lamp at all.
local function hudIndicator(name, year)
  return { name = name, year = year, icon = {} }
end

local function stubMod(over)
  over = over or {}
  _G.FS25_AdvancedDamageSystem = {
    ADS_Breakdowns = over.noColours and {} or { COLORS = COLORS, BreakdownRegistry = BREAKDOWNS },
    -- ADS's specialization class, which is where its STATUS values and breakdown sources live.
    AdvancedDamageSystem = {
      STATUS = {
        READY = "ads_spec_state_ready",
        INSPECTION = "ads_spec_state_inspection",
        MAINTENANCE = "ads_spec_state_maintenance",
        REPAIR = "ads_spec_state_repair",
        OVERHAUL = "ads_spec_state_overhaul",
        BROKEN = "ads_spec_state_broken",
      },
      BREAKDOWN_SOURCES = { RANDOM = 1, POOR_PARTS = 2, QUICK_FIX = 3 },
    },
    ADS_Config = {
      CORE = {
        ENGINE_FACTOR_DATA = { COLD_MOTOR_TEMP_THRESHOLD = 50 },
        TRANSMISSION_FACTOR_DATA = { COLD_TRANSMISSION_THRESHOLD = 45 },
      },
    },
    -- The HUD instance ADS_Main builds at mission start; its indicator table is where the year gate
    -- is read from at runtime rather than mirrored.
    ADS_Main = over.noHud and {} or {
      hud = {
        indicators = over.indicators or {
          engine = hudIndicator("engine", 1990),
          transmission = hudIndicator("transmission", 2000),
          brakes = hudIndicator("brakes", 1980),
          battery = hudIndicator("battery", 1950),
          coolant = hudIndicator("coolant", 1950),
          warning = hudIndicator("warning", 1990),
          service = hudIndicator("service", 1970),
          oil = hudIndicator("oil", 1950),
        },
      },
    },
  }
end

---One ADS indicator, as `recalculateAndApplyIndicators` leaves it: a colour, a latch, and the two
---predicates the HUD drives it with.
local function indicator(colour, on, off, isActive)
  return {
    color = colour,
    isActive = isActive or false,
    switchOn = function()
      return on == true
    end,
    switchOff = function()
      return off == true
    end,
  }
end

---A motorized vehicle carrying ADS's spec. `motorState` is the engine's own enum (OFF 1, IGNITION 2,
---STARTING 3, ON 4).
---
---`excluded` is asked through `getIsADSExcluded`, which is how 0.9.2.8 answers it. `noExclusionMethod`
---builds a machine from an ADS too old to have that method, and `exclusionThrows` one whose method is
---there but blows up in third-party code.
local function makeVehicle(over)
  over = over or {}
  local spec = {
    -- 0.9.2.7's flag, planted on the old shape only. The integration must not read it even there: it
    -- is here to show that a spec carrying the old field and nothing else still reports nothing.
    isExcludedVehicle = over.noExclusionMethod and (over.excluded or false) or nil,
    year = over.year or 2020,
    engineTemperature = over.engineTemperature or 88,
    transmissionTemperature = over.transmissionTemperature or -99,
    systemVoltageV = over.systemVoltageV,
    radiatorClogging = over.radiatorClogging,
    airIntakeClogging = over.airIntakeClogging,
    lubricationLevel = over.lubricationLevel,
    dynamicMotorLoad = over.dynamicMotorLoad,
    activeIndicators = over.activeIndicators or {},
  }
  local vehicle = { spec_AdvancedDamageSystem = spec }
  if not over.noExclusionMethod then
    function vehicle:getIsADSExcluded()
      if over.exclusionThrows then
        error("ADS threw")
      end
      return over.excluded or false
    end
  end
  function vehicle:getMotorState()
    return over.motorState or 4
  end
  -- ADS asks the motor whether this is a CVT, and a CVT is the one that carries a
  -- `minForwardGearRatio` -- a geared transmission has discrete ratios instead.
  function vehicle:getMotor()
    return { minForwardGearRatio = over.cvt and 0.1 or nil }
  end
  if over.serviceHours ~= nil then
    function vehicle:getHoursSinceLastMaintenance()
      return over.serviceHours
    end
    function vehicle:getMaintenanceInterval()
      return over.serviceInterval or 5
    end
  end
  if over.inspectedCondition ~= nil then
    function vehicle:getLastInspectedCondition()
      return over.inspectedCondition, over.inspectedComplete == true
    end
    function vehicle:getLastInspectedService()
      return over.inspectedService or 0
    end
  end
  return vehicle, spec
end

---Run the object hook over a freshly core-collected model with a motor in it.
local function contribute(vehicle, model)
  model = model or { motor = { temperatur = { value = 20, min = 20, max = 120, unit = "°C" } } }
  VDT.AdvancedDamageSystem.contributeObject(vehicle, model)
  return model
end

describe("AdvancedDamageSystem integration", function()
  before_each(function()
    stubMod()
    VDT.AdvancedDamageSystem.reset()
  end)

  after_each(function()
    _G.FS25_AdvancedDamageSystem = nil
  end)

  describe("presence", function()
    it("is unavailable without the mod's env table", function()
      _G.FS25_AdvancedDamageSystem = nil
      assert.is_false(VDT.AdvancedDamageSystem.isAvailable())
    end)

    it("contributes nothing to an implement, which never carries the spec", function()
      local model = contribute({})
      assert.is_nil(model.ads)
      assert.equals(20, model.motor.temperatur.value)
    end)

    it("contributes nothing to a vehicle ADS excludes", function()
      local vehicle = makeVehicle({ excluded = true })
      assert.is_nil(contribute(vehicle).ads)
    end)

    it("asks ADS whether the machine is excluded rather than reading a flag off the spec", function()
      -- 0.9.2.8 dropped `spec.isExcludedVehicle` for `getIsADSExcluded()`. A reader still looking for
      -- the field sees nothing there and lets an electric machine through -- one whose spec ADS never
      -- populated, so `engineTemperature` is still the -99 its onLoad left, and that would land on the
      -- cluster's temperature gauge as a reading.
      local vehicle = makeVehicle({ excluded = true, engineTemperature = -99 })
      local model = contribute(vehicle)
      assert.is_nil(model.ads)
      assert.equals(20, model.motor.temperatur.value)
    end)

    it("reports nothing at all on an ADS too old to answer the exclusion question", function()
      -- Pre-0.9.2.8, which had the field and no method. One version is tracked, so the answer to an
      -- ADS we have not read is silence rather than a guess -- for the INCLUDED machine too, which is
      -- the half that costs something and the half that keeps the fiction out.
      assert.is_nil(contribute(makeVehicle({ excluded = true, noExclusionMethod = true })).ads)
      assert.is_nil(contribute(makeVehicle({ noExclusionMethod = true })).ads)
    end)

    it("reports nothing when ADS's own method throws", function()
      -- Third-party code inside a pcall, like every other ADS call here: a throw is not a "no".
      assert.is_nil(contribute(makeVehicle({ exclusionThrows = true })).ads)
    end)
  end)

  describe("engine temperature", function()
    it("overwrites the core-collected value, which is stale under ADS", function()
      local vehicle = makeVehicle({ engineTemperature = 93.7 })
      local model = contribute(vehicle)
      assert.equals(93, model.motor.temperatur.value)
      -- The gauge frame is the base game's and stays put: the bar has to read on one scale.
      assert.equals(20, model.motor.temperatur.min)
      assert.equals(120, model.motor.temperatur.max)
    end)

    it("reports a CVT's own temperature separately", function()
      local vehicle = makeVehicle({ cvt = true, transmissionTemperature = 71.2 })
      local trans = contribute(vehicle).ads.transmissionTemperatur
      assert.equals(71, trans.value)
      assert.equals("°C", trans.unit)
    end)

    it("says nothing about the transmission of a machine that has no CVT", function()
      local vehicle = makeVehicle({ transmissionTemperature = -99 })
      assert.is_nil(contribute(vehicle).ads.transmissionTemperatur)
    end)

    it("asks the machine, not the value -- ADS's non-reading drifts off its own sentinel", function()
      -- Seen in game at -80: once the thermal smoothing has touched the field it is no longer the
      -- -99 it was initialised to, so `> -99` let a bar onto machines with no transmission oil at
      -- all. ADS's own syncBlinkingWarning tests `> -90` for the same reason.
      local vehicle = makeVehicle({ transmissionTemperature = -80 })
      assert.is_nil(contribute(vehicle).ads.transmissionTemperatur)
    end)

    it("still says nothing when a machine has a CVT but no reading yet", function()
      local vehicle = makeVehicle({ cvt = true, transmissionTemperature = -99 })
      assert.is_nil(contribute(vehicle).ads.transmissionTemperatur)
    end)
  end)

  describe("lamps", function()
    it("reports every lamp a modern machine has, dark by default", function()
      local lamps = contribute(makeVehicle({ year = 2020 })).ads.lamps
      assert.same(
        { engine = "OFF", warning = "OFF", brakes = "OFF", battery = "OFF", coolant = "OFF", service = "OFF" },
        lamps
      )
    end)

    it("gives an old machine only the lamps of its age", function()
      -- 1975: past the battery and coolant lamps (1950) and the service one (1970), short of brakes
      -- (1980) and engine/warning (1990).
      local lamps = contribute(makeVehicle({ year = 1975 })).ads.lamps
      assert.same({ battery = "OFF", coolant = "OFF", service = "OFF" }, lamps)
    end)

    it("takes the year gate from ADS's own HUD table rather than a copy of it", function()
      -- The one part of the lamp behaviour that IS a reachable table, so a rebalance in ADS is
      -- followed rather than silently diverged from.
      stubMod({
        indicators = {
          engine = hudIndicator("engine", 1960),
          brakes = hudIndicator("brakes", 2030),
        },
      })
      VDT.AdvancedDamageSystem.reset()
      local lamps = contribute(makeVehicle({ year = 1975 })).ads.lamps
      assert.same({ engine = "OFF" }, lamps)
    end)

    it("reports no lamps at all when ADS's HUD has not been built", function()
      -- Which lamps a machine has is ADS's answer to give. With its table out of reach there is no
      -- second-guessing it from a mirrored copy of the years: the band simply stays empty.
      stubMod({ noHud = true })
      VDT.AdvancedDamageSystem.reset()
      local ads = contribute(makeVehicle({ year = 1975, systemVoltageV = 13.8 })).ads
      assert.is_nil(ads.lamps)
      assert.is_not_nil(ads.electrical, "only the lamps go quiet, not the whole block")
    end)

    it("carries ADS's severity, not just on/off", function()
      local vehicle = makeVehicle({
        activeIndicators = {
          battery = indicator(COLORS.CRITICAL, true, false),
          brakes = indicator(COLORS.WARNING, true, false),
        },
      })
      local lamps = contribute(vehicle).ads.lamps
      assert.equals("CRIT", lamps.battery)
      assert.equals("WARN", lamps.brakes)
    end)

    it("latches a lamp on, so it stays lit after its switchOn stops holding", function()
      local switchOn = true
      local vehicle = makeVehicle({
        activeIndicators = {
          engine = {
            color = COLORS.WARNING,
            isActive = false,
            switchOn = function()
              return switchOn
            end,
            switchOff = function()
              return false
            end,
          },
        },
      })
      assert.equals("WARN", contribute(vehicle).ads.lamps.engine)
      switchOn = false
      assert.equals("WARN", contribute(vehicle).ads.lamps.engine)
    end)

    it("releases the latch when a switchOff condition holds", function()
      local switchOff = false
      local vehicle = makeVehicle({
        activeIndicators = {
          engine = {
            color = COLORS.WARNING,
            isActive = true,
            switchOn = function()
              return false
            end,
            switchOff = function()
              return switchOff
            end,
          },
        },
      })
      assert.equals("WARN", contribute(vehicle).ads.lamps.engine)
      switchOff = true
      assert.equals("OFF", contribute(vehicle).ads.lamps.engine)
    end)

    it("goes dark with the key out, and forgets what was latched", function()
      local vehicle, spec = makeVehicle({
        motorState = 1,
        activeIndicators = { engine = indicator(COLORS.CRITICAL, true, false, true) },
      })
      assert.equals("OFF", contribute(vehicle).ads.lamps.engine)
      -- ... and a lamp whose switchOn no longer holds does not come back lit on restart.
      spec.activeIndicators.engine = indicator(COLORS.CRITICAL, false, false, false)
      vehicle.getMotorState = function()
        return 4
      end
      assert.equals("OFF", contribute(vehicle).ads.lamps.engine)
    end)

    it("lights everything while the starter turns", function()
      for _, cranking in ipairs({ 2, 3 }) do
        local vehicle = makeVehicle({ motorState = cranking })
        local lamps = contribute(vehicle).ads.lamps
        assert.equals("WARN", lamps.engine)
        assert.equals("WARN", lamps.coolant)
        assert.equals("WARN", lamps.service)
      end
    end)

    it("reads the coolant lamp blue while the engine is still cold", function()
      assert.equals("COLD", contribute(makeVehicle({ engineTemperature = 31 })).ads.lamps.coolant)
    end)

    it("reads it blue while only a CVT's transmission is cold", function()
      local vehicle = makeVehicle({ cvt = true, engineTemperature = 88, transmissionTemperature = 30 })
      assert.equals("COLD", contribute(vehicle).ads.lamps.coolant)
    end)

    it("does not read a non-CVT's non-reading as a cold transmission", function()
      -- The same drifted -80: taken at face value it is below every cold threshold there is, so the
      -- lamp would have sat blue for the whole session on most of the fleet.
      local vehicle = makeVehicle({ engineTemperature = 88, transmissionTemperature = -80 })
      assert.equals("OFF", contribute(vehicle).ads.lamps.coolant)
    end)

    it("warns and then goes critical on temperature", function()
      assert.equals("OFF", contribute(makeVehicle({ engineTemperature = 95 })).ads.lamps.coolant)
      assert.equals("WARN", contribute(makeVehicle({ engineTemperature = 104 })).ads.lamps.coolant)
      assert.equals("CRIT", contribute(makeVehicle({ engineTemperature = 115 })).ads.lamps.coolant)
    end)

    it("lets a breakdown's own colour outrank the temperature reading", function()
      local vehicle = makeVehicle({
        engineTemperature = 20, -- cold enough to be blue on its own
        activeIndicators = { coolant = indicator(COLORS.CRITICAL, true, false) },
      })
      assert.equals("CRIT", contribute(vehicle).ads.lamps.coolant)
    end)

    it("lights the service lamp once the interval is past", function()
      assert.equals("OFF", contribute(makeVehicle({ serviceHours = 4, serviceInterval = 5 })).ads.lamps.service)
      assert.equals("WARN", contribute(makeVehicle({ serviceHours = 6, serviceInterval = 5 })).ads.lamps.service)
    end)

    it("survives an indicator whose condition throws", function()
      local vehicle = makeVehicle({
        activeIndicators = {
          engine = {
            color = COLORS.WARNING,
            isActive = false,
            switchOn = function()
              error("ADS renamed something")
            end,
            switchOff = function()
              return false
            end,
          },
        },
      })
      assert.equals("OFF", contribute(vehicle).ads.lamps.engine)
    end)
  end)

  describe("service interval", function()
    it("reports where the machine is in it, in hours", function()
      local service = contribute(makeVehicle({ serviceHours = 3.27, serviceInterval = 5.4 })).ads.service
      assert.equals(3.3, service.hours)
      assert.equals(5.4, service.interval)
    end)

    it("says nothing when ADS reports a degenerate interval", function()
      assert.is_nil(contribute(makeVehicle({ serviceHours = 3, serviceInterval = 0 })).ads.service)
    end)

    it("survives a getter that throws, and keeps the rest of the block", function()
      -- ADS's getters walk the machine's maintenance log; this collector runs inside the
      -- latency-critical telemetry write, so a throw must cost one field and not the tick.
      local vehicle = makeVehicle({ serviceHours = 3, systemVoltageV = 13.8 })
      vehicle.getMaintenanceInterval = function()
        error("malformed maintenance log")
      end
      local ads = contribute(vehicle).ads
      assert.is_nil(ads.service)
      assert.equals(13.8, ads.electrical.systemVoltage)
    end)
  end)

  describe("inspection results", function()
    it("reports what the last inspection found, as percentages", function()
      local vehicle = makeVehicle({
        inspectedCondition = 0.734,
        inspectedService = 0.42,
        inspectedComplete = true,
      })
      local inspected = contribute(vehicle).ads.inspected
      assert.equals(73, inspected.condition)
      assert.equals(42, inspected.service)
      assert.is_true(inspected.complete)
    end)

    it("says nothing about a machine that has never been inspected", function()
      assert.is_nil(contribute(makeVehicle({ inspectedCondition = 0 })).ads.inspected)
    end)

    it("never carries the live hidden values", function()
      local vehicle, spec = makeVehicle({ inspectedCondition = 0.9 })
      spec.conditionLevel = 0.31
      spec.serviceLevel = 0.12
      local ads = contribute(vehicle).ads
      assert.is_nil(ads.condition)
      assert.equals(90, ads.inspected.condition)
    end)
  end)

  describe("pre-shift checks", function()
    it("says nothing about them, however filthy the machine is", function()
      -- Deliberate, and the bands ADS reports them in were not enough to save them: a driver learns
      -- these by getting out and walking round, so a dashboard that printed them would hand over the
      -- walk (see the file header). The values are right there on the spec, and stay there.
      local ads = contribute(makeVehicle({
        radiatorClogging = 0.9,
        airIntakeClogging = 0.9,
        lubricationLevel = 0.05,
      })).ads
      assert.is_nil(ads.checks)
      assert.is_nil(ads.radiator)
      assert.is_nil(ads.airIntake)
      assert.is_nil(ads.lubrication)
    end)
  end)

  describe("engine load", function()
    it("reports the load ADS wears the engine on, with the threshold that goes with it", function()
      local load = contribute(makeVehicle({ dynamicMotorLoad = 0.72 })).ads.load
      assert.equals(72, load.value)
      assert.equals(85, load.overloadAt)
      assert.equals("%", load.unit)
    end)

    it("does not clip it at 100, because how far over is the whole point", function()
      -- ADS adds a draft term on a field with an implement down, and lets the sum reach 1.15. Its own
      -- HUD clips the readout there; the amount over is what a driver would change their driving for.
      assert.equals(112, contribute(makeVehicle({ dynamicMotorLoad = 1.115 })).ads.load.value)
    end)

    it("takes the threshold from ADS's config, which a player can move", function()
      _G.FS25_AdvancedDamageSystem.ADS_Config.CORE.ENGINE_FACTOR_DATA.MOTOR_OVERLOADED_THRESHOLD = 0.7
      assert.equals(70, contribute(makeVehicle({ dynamicMotorLoad = 0.5 })).ads.load.overloadAt)
    end)

    it("leaves the plain engine load alone, which is a different number and still true", function()
      local model = contribute(makeVehicle({ dynamicMotorLoad = 1.05 }), {
        motor = { temperatur = { value = 20, min = 20, max = 120, unit = "°C" }, load = { value = 91 } },
      })
      assert.equals(91, model.motor.load.value)
      assert.equals(105, model.ads.load.value)
    end)

    it("says nothing when ADS has not computed one", function()
      assert.is_nil(contribute(makeVehicle({})).ads.load)
    end)
  end)

  describe("electrical", function()
    it("reports the voltage the machine's electrics see", function()
      local electrical = contribute(makeVehicle({ systemVoltageV = 13.84 })).ads.electrical
      assert.equals(13.8, electrical.systemVoltage)
      assert.equals("V", electrical.unit)
    end)
  end)

  describe("fleet stage", function()
    -- The localization the mod does for the app: the fleet rows carry text, not i18n keys, because
    -- the terminal has no access to the game's language files.
    -- ADS's texts live in ITS i18n namespace, not ours, so the stub answers only for keys asked for
    -- with its mod name -- which is what pins the integration to the customEnv lookup.
    before_each(function()
      local function isAdsKey(key, customEnv)
        return customEnv == "FS25_AdvancedDamageSystem" and string.sub(key, 1, 4) == "ads_"
      end
      _G.g_i18n = {
        hasText = function(_, key, customEnv)
          return isAdsKey(key, customEnv)
        end,
        getText = function(_, key, customEnv)
          if not isAdsKey(key, customEnv) then
            return "Missing '" .. key .. "' in l10n_en.xml"
          end
          return "text:" .. key
        end,
      }
    end)

    after_each(function()
      _G.g_i18n = nil
    end)

    ---A machine as the fleet channel meets it: parked, with a maintenance history behind it.
    local function makeFleetVehicle(over)
      over = over or {}
      local vehicle, spec = makeVehicle(over)
      spec.currentState = over.currentState or "ads_spec_state_ready"
      spec.activeBreakdowns = over.breakdowns
      spec.maintenanceLog = over.log
      spec.pendingServicePrice = over.pendingServicePrice
      spec.serviceOptionOne = over.serviceOptionOne
      function vehicle:getLastInspectionDate()
        return over.inspectionDate
      end
      function vehicle:getLastMaintenanceDate()
        return over.maintenanceDate
      end
      function vehicle:getServiceDuration()
        return over.serviceDuration
      end
      function vehicle:getServiceFinishTime()
        return over.finishHour, over.finishInDays
      end
      function vehicle:getServicePrice()
        return over.servicePrice
      end
      return vehicle, spec
    end

    local function fleetRow(vehicle)
      local row = {}
      VDT.AdvancedDamageSystem.contributeFleetVehicle(vehicle, row)
      return row
    end

    it("reports ADS's state as a token rather than its i18n key", function()
      local row = fleetRow(makeFleetVehicle({ currentState = "ads_spec_state_repair" }))
      assert.equals("REPAIR", row.ads.state)
    end)

    it("falls back to the key's own shape when ADS's status table is out of reach", function()
      _G.FS25_AdvancedDamageSystem.AdvancedDamageSystem = nil
      assert.equals("OVERHAUL", fleetRow(makeFleetVehicle({ currentState = "ads_spec_state_overhaul" })).ads.state)
    end)

    it("reports a state neither of those resolves as UNKNOWN rather than as ready", function()
      -- A state a later ADS adds. The record is still worth having -- the breakdowns and the service
      -- hours are read the same way -- so the row keeps it; only the state itself is unnameable.
      local row = fleetRow(makeFleetVehicle({ currentState = "ads_spec_state_of_the_art_calibration" }))
      assert.equals("UNKNOWN", row.ads.state)
    end)

    it("contributes nothing to an implement or an excluded machine", function()
      assert.is_nil(fleetRow({}).ads)
      assert.is_nil(fleetRow(makeFleetVehicle({ excluded = true })).ads)
      -- The fleet row is the one that would look plausible if the gate missed: ADS's onLoad leaves
      -- `currentState` at READY, so an excluded machine would file a maintenance record saying it is
      -- ready for work.
      assert.is_nil(fleetRow(makeFleetVehicle({ excluded = true, noExclusionMethod = true })).ads)
    end)

    it("carries the inspection record and the service interval, the same blocks the cluster has", function()
      local row = fleetRow(makeFleetVehicle({
        inspectedCondition = 0.62,
        inspectedComplete = true,
        inspectedService = 0.4,
        serviceHours = 41.25,
        serviceInterval = 60,
      }))
      assert.equals(62, row.ads.inspected.condition)
      assert.is_true(row.ads.inspected.complete)
      assert.equals(40, row.ads.inspected.service)
      assert.equals(41.3, row.ads.service.hours)
      assert.equals(60, row.ads.service.interval)
    end)

    it("carries the two log dates as the game counts them", function()
      local row = fleetRow(makeFleetVehicle({
        inspectionDate = { year = 2, month = 5, day = 11 },
        maintenanceDate = { year = 1, month = 12 },
      }))
      assert.same({ year = 2, month = 5, day = 11 }, row.ads.lastInspection)
      -- A date without a day is still a date: the app reads these in months.
      assert.same({ year = 1, month = 12, day = 1 }, row.ads.lastMaintenance)
    end)

    it("says nothing about a machine that has never been in", function()
      local row = fleetRow(makeFleetVehicle({}))
      assert.is_nil(row.ads.lastInspection)
      assert.is_nil(row.ads.lastMaintenance)
      assert.is_nil(row.ads.breakdowns)
      assert.is_nil(row.ads.maintenanceCost)
      assert.is_nil(row.ads.workshop)
    end)

    it("lists only the breakdowns the player has found", function()
      local row = fleetRow(makeFleetVehicle({
        breakdowns = {
          ENGINE_OIL_LEAK = { stage = 2, isVisible = true, isActive = true },
          BRAKE_WEAR = { stage = 1, isVisible = false, isActive = true },
        },
      }))
      assert.equals(1, #row.ads.breakdowns)
      local breakdown = row.ads.breakdowns[1]
      assert.equals("ENGINE_OIL_LEAK", breakdown.id)
      assert.equals(2, breakdown.stage)
      assert.equals("text:ads_part_engine", breakdown.part)
      assert.equals("text:ads_severity_major", breakdown.severity)
      assert.equals("text:ads_desc_dripping", breakdown.description)
    end)

    it("never hints at a hidden breakdown, not even as a count", function()
      local row = fleetRow(makeFleetVehicle({
        breakdowns = { ENGINE_OIL_LEAK = { stage = 1, isVisible = false, isActive = true } },
      }))
      assert.is_nil(row.ads.breakdowns)
    end)

    it("names the system when a breakdown carries no part of its own", function()
      local row = fleetRow(makeFleetVehicle({
        breakdowns = { BRAKE_WEAR = { stage = 1, isVisible = true, isActive = true } },
      }))
      assert.equals("text:ads_spec_system_chassis", row.ads.breakdowns[1].part)
    end)

    it("says what was done to a suspended breakdown rather than what stage it is at", function()
      local row = fleetRow(makeFleetVehicle({
        breakdowns = { ENGINE_OIL_LEAK = { stage = 2, isVisible = true, isActive = false, source = 3 } },
      }))
      assert.equals("text:ads_breakdowns_quick_fix_stage", row.ads.breakdowns[1].severity)
      assert.equals("text:ads_breakdowns_temporarily_repaired_description", row.ads.breakdowns[1].description)
    end)

    it("orders the breakdowns, because pairs() does not", function()
      -- An unsorted list would reshuffle between writes and push a changed document every tick.
      local row = fleetRow(makeFleetVehicle({
        breakdowns = {
          ENGINE_OIL_LEAK = { stage = 1, isVisible = true, isActive = true },
          BRAKE_WEAR = { stage = 1, isVisible = true, isActive = true },
        },
      }))
      assert.equals("BRAKE_WEAR", row.ads.breakdowns[1].id)
      assert.equals("ENGINE_OIL_LEAK", row.ads.breakdowns[2].id)
    end)

    it("reports the workshop only while the machine is in one", function()
      local ready = fleetRow(makeFleetVehicle({ serviceDuration = 4, finishHour = 16.5 }))
      assert.is_nil(ready.ads.workshop)

      local row = fleetRow(makeFleetVehicle({
        currentState = "ads_spec_state_maintenance",
        serviceDuration = 4.26,
        finishHour = 16.53,
        finishInDays = 1,
        pendingServicePrice = 1450.7,
      }))
      assert.equals(4.3, row.ads.workshop.remaining)
      assert.equals(16.5, row.ads.workshop.finishHour)
      assert.equals(1, row.ads.workshop.finishInDays)
      assert.equals(1450, row.ads.workshop.price)
    end)

    it("asks ADS what the service costs when no price is pending yet", function()
      local row = fleetRow(makeFleetVehicle({
        currentState = "ads_spec_state_inspection",
        servicePrice = 320,
      }))
      assert.equals(320, row.ads.workshop.price)
    end)

    it("totals what the machine has cost in maintenance", function()
      local row = fleetRow(makeFleetVehicle({
        log = { { price = 1200.4 }, { price = 800 }, { notAPrice = true } },
      }))
      assert.equals(2000, row.ads.maintenanceCost)
    end)

    it("leaks none of what ADS hides behind its workshop diagnostic", function()
      local vehicle, spec = makeFleetVehicle({ inspectedCondition = 0.5 })
      spec.conditionLevel = 0.31
      spec.serviceLevel = 0.22
      spec.systems = { engine = { condition = 0.4, stress = 0.9 } }
      spec.radiatorClogging = 0.8
      spec.lubricationLevel = 0.2
      local encoded = Json.encode(fleetRow(vehicle))
      assert.is_nil(string.find(encoded, "conditionLevel", 1, true))
      assert.is_nil(string.find(encoded, "serviceLevel", 1, true))
      assert.is_nil(string.find(encoded, "stress", 1, true))
      assert.is_nil(string.find(encoded, "clogging", 1, true))
      assert.is_nil(string.find(encoded, "lubrication", 1, true))
    end)
  end)

  describe("mod-environment isolation", function()
    it("still reports lit lamps when ADS's colour table is out of reach", function()
      stubMod({ noColours = true })
      VDT.AdvancedDamageSystem.reset()
      local vehicle = makeVehicle({ activeIndicators = { battery = indicator(COLORS.CRITICAL, true, false) } })
      -- The severity degrades to WARN, but the lamp is not lost -- which is the fail-soft contract.
      assert.equals("WARN", contribute(vehicle).ads.lamps.battery)
    end)
  end)
end)
