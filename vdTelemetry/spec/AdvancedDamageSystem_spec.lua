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
-- and that nothing exact leaks out of the values ADS hides.

if ValueMapper == nil then
  dofile("src/mapper/ValueMapper.lua")
end
if VDT == nil or VDT.AdvancedDamageSystem == nil then
  dofile("src/integrations/AdvancedDamageSystem.lua")
end

-- ADS's four indicator colours are compared by table identity, so the stub must hand out the *same*
-- tables the integration will see -- which is exactly how ADS keys its own COLOR_PRIORITY.
local COLORS = {
  DEFAULT = { 1, 1, 1, 0.03 },
  COOL = { 0.0097, 0.4287, 0.6445, 1 },
  WARNING = { 1, 0.4287, 0.0006, 1 },
  CRITICAL = { 0.8069, 0.0097, 0.0097, 1 },
}

---One entry of ADS_Hud's indicator table: the id it drives the lamp by, and the production year a
---machine must beat to have that lamp at all.
local function hudIndicator(name, year)
  return { name = name, year = year, icon = {} }
end

local function stubMod(over)
  over = over or {}
  _G.FS25_AdvancedDamageSystem = {
    ADS_Breakdowns = { COLORS = COLORS },
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
local function makeVehicle(over)
  over = over or {}
  local spec = {
    isExcludedVehicle = over.excluded or false,
    year = over.year or 2020,
    engineTemperature = over.engineTemperature or 88,
    transmissionTemperature = over.transmissionTemperature or -99,
    systemVoltageV = over.systemVoltageV,
    radiatorClogging = over.radiatorClogging,
    airIntakeClogging = over.airIntakeClogging,
    lubricationLevel = over.lubricationLevel,
    isVehicleNeedBlowOut = over.needBlowOut,
    isVehicleNeedLubricate = over.needLubricate,
    activeIndicators = over.activeIndicators or {},
  }
  local vehicle = { spec_AdvancedDamageSystem = spec }
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

    it("falls back to the mirrored years when ADS's HUD has not been built", function()
      stubMod({ noHud = true })
      VDT.AdvancedDamageSystem.reset()
      local lamps = contribute(makeVehicle({ year = 1975 })).ads.lamps
      assert.same({ battery = "OFF", coolant = "OFF", service = "OFF" }, lamps)
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
    it("reports clogging in ADS's own inspection bands", function()
      local checks = contribute(makeVehicle({ radiatorClogging = 0.62, airIntakeClogging = 0.9 })).ads.checks
      assert.equals("HEAVY", checks.radiator)
      assert.equals("CRITICAL", checks.airIntake)
    end)

    it("calls a barely dirty machine clean, as the inspection does", function()
      local checks = contribute(makeVehicle({ radiatorClogging = 0.05, airIntakeClogging = 0.2 })).ads.checks
      assert.equals("OK", checks.radiator)
      assert.equals("SLIGHT", checks.airIntake)
    end)

    it("reports lubrication downwards, since there it is the low end that is bad", function()
      assert.equals("OK", contribute(makeVehicle({ lubricationLevel = 0.95 })).ads.checks.lubrication)
      assert.equals("DRY", contribute(makeVehicle({ lubricationLevel = 0.5 })).ads.checks.lubrication)
      assert.equals("CRITICAL", contribute(makeVehicle({ lubricationLevel = 0.1 })).ads.checks.lubrication)
    end)

    it("leaves out a chore the machine does not need", function()
      local checks = contribute(makeVehicle({ needLubricate = false, radiatorClogging = 0.5 })).ads.checks
      assert.equals("DIRTY", checks.radiator)
      assert.is_nil(checks.lubrication)
    end)

    it("says nothing at all about a machine that needs neither", function()
      assert.is_nil(contribute(makeVehicle({ needLubricate = false, needBlowOut = false })).ads.checks)
    end)
  end)

  describe("electrical", function()
    it("reports the voltage the machine's electrics see", function()
      local electrical = contribute(makeVehicle({ systemVoltageV = 13.84 })).ads.electrical
      assert.equals(13.8, electrical.systemVoltage)
      assert.equals("V", electrical.unit)
    end)
  end)

  describe("mod-environment isolation", function()
    it("still reports lit lamps when ADS's colour table is out of reach", function()
      _G.FS25_AdvancedDamageSystem = nil
      local vehicle = makeVehicle({ activeIndicators = { battery = indicator(COLORS.CRITICAL, true, false) } })
      -- The severity degrades to WARN, but the lamp is not lost -- which is the fail-soft contract.
      assert.equals("WARN", contribute(vehicle).ads.lamps.battery)
    end)
  end)
end)
