-- Unit tests for the weather export channel (src/collect/WeatherExporter.lua): the type mapping and
-- collect() against a stubbed WeatherForecast. Whether the real forecast still looks like this stub
-- -- on a multiplayer client above all -- is what the in-game smoke test covers.
--
-- Run with `busted` from the vdTelemetry/ directory. The exporter self-registers a channel at load
-- and uses ValueMapper for the Beaufort conversion, so both dependencies load first.

if VDT == nil or VDT.ExportChannels == nil then
  dofile("src/export/ExportChannels.lua")
end
if ValueMapper == nil then
  dofile("src/mapper/ValueMapper.lua")
end
if VDT.WeatherExporter == nil then
  dofile("src/collect/WeatherExporter.lua")
end

local MS_PER_HOUR = 60 * 60 * 1000

-- A stubbed forecast. `hourly` is keyed by hoursFromNow (so a missing key returns nil, the engine's
-- own answer when no forecast item covers that time) and `daily` by daysFromToday.
local function installWorld(opts)
  opts = opts or {}
  _G.g_currentMission = {
    environment = {
      currentPeriod = opts.period or 6,
      currentDayInPeriod = opts.dayInPeriod or 1,
      -- the forecast counts in monotonic days; these two turn one back into a calendar position
      getPeriodFromDay = function(_, day)
        return (opts.period or 6) + math.floor(day / 3)
      end,
      getDayInPeriodFromDay = function(_, day)
        return day % 3 + 1
      end,
      weather = {
        forecast = {
          getCurrentWeather = function()
            return opts.current
          end,
          getHourlyForecast = function(_, hoursFromNow)
            return (opts.hourly or {})[hoursFromNow]
          end,
          getDailyForecast = function(_, daysFromToday)
            return (opts.daily or {})[daysFromToday]
          end,
        },
      },
    },
  }
end

after_each(function()
  _G.g_currentMission = nil
  _G.g_i18n = nil
end)

describe("WeatherExporter.mapWeatherType", function()
  it("maps the engine's WeatherType ids to their names", function()
    assert.are.equal("SUN", VDT.WeatherExporter.mapWeatherType(1))
    assert.are.equal("PARTIALLY_CLOUDY", VDT.WeatherExporter.mapWeatherType(2))
    assert.are.equal("RAIN", VDT.WeatherExporter.mapWeatherType(4))
    assert.are.equal("THUNDER", VDT.WeatherExporter.mapWeatherType(8))
  end)

  it("degrades an unknown or missing id to UNKNOWN", function()
    assert.are.equal("UNKNOWN", VDT.WeatherExporter.mapWeatherType(99))
    assert.are.equal("UNKNOWN", VDT.WeatherExporter.mapWeatherType(nil))
  end)
end)

describe("ValueMapper.windSpeedToBeaufort", function()
  it("matches the game's own conversion", function()
    -- floor((ceil(mps) / 0.836) ^ (2/3)) -- the menu's formula, rounding the speed up first
    assert.are.equal(0, ValueMapper.windSpeedToBeaufort(0))
    assert.are.equal(1, ValueMapper.windSpeedToBeaufort(0.4))
    assert.are.equal(1, ValueMapper.windSpeedToBeaufort(1))
    assert.are.equal(2, ValueMapper.windSpeedToBeaufort(4))
    assert.are.equal(5, ValueMapper.windSpeedToBeaufort(10))
  end)

  it("passes nil through", function()
    assert.is_nil(ValueMapper.windSpeedToBeaufort(nil))
  end)
end)

describe("WeatherExporter.temperatureUnit", function()
  it("asks g_i18n, so a Fahrenheit player is labelled Fahrenheit", function()
    _G.g_i18n = {
      getTemperatureUnit = function()
        return "°F"
      end,
    }
    assert.are.equal("°F", VDT.WeatherExporter.temperatureUnit())
  end)

  it("falls back to Celsius when i18n cannot answer", function()
    assert.are.equal("°C", VDT.WeatherExporter.temperatureUnit())
  end)
end)

describe("WeatherExporter.collect", function()
  it("collects current conditions, the hourly strip and the outlook", function()
    local hourly = {}
    for step = 0, 11 do
      hourly[step * 2] = {
        time = (8 + step * 2) % 24 * MS_PER_HOUR,
        temperature = 20 + step,
        windSpeed = 1.44,
        windDirection = 45,
        forecastType = 1,
      }
    end
    local daily = {}
    for offset = 1, 6 do
      daily[offset] = {
        day = offset,
        highTemperature = 30 + offset,
        lowTemperature = 10 + offset,
        forecastType = 4,
        windSpeed = 5.55,
        windDirection = -10,
      }
    end
    installWorld({
      current = { temperature = 27.4, windSpeed = 1.2, windDirection = 45, forecastType = 1 },
      hourly = hourly,
      daily = daily,
    })

    local model = VDT.WeatherExporter.collect()

    assert.are.equal("1", model.version)
    assert.are.equal("°C", model.temperatureUnit)

    assert.are.equal("SUN", model.current.type)
    assert.are.equal(27, model.current.temperature)
    assert.are.equal(1.2, model.current.windSpeed)
    assert.are.equal(1, model.current.windBeaufort)
    assert.are.equal(45, model.current.windDirection)

    assert.are.equal(12, #model.hourly)
    assert.are.equal(8, model.hourly[1].hour)
    assert.are.equal(10, model.hourly[2].hour)
    assert.are.equal(20, model.hourly[1].temperature)
    -- rounded to one decimal: Json.lua would otherwise print 1.4399999999999
    assert.are.equal(1.4, model.hourly[1].windSpeed)
    -- the strip wraps past midnight and stays in order rather than sorting
    assert.are.equal(6, model.hourly[12].hour)

    assert.are.equal(6, #model.daily)
    assert.are.equal("RAIN", model.daily[1].type)
    assert.are.equal(31, model.daily[1].high)
    assert.are.equal(11, model.daily[1].low)
    -- the outlook carries wind too, on the same three fields as the other two lists
    assert.are.equal(5.6, model.daily[1].windSpeed)
    assert.are.equal(3, model.daily[1].windBeaufort)
    -- and through the same negative-angle wrap
    assert.are.equal(350, model.daily[1].windDirection)
  end)

  it("converts every temperature to the player's unit", function()
    _G.g_i18n = {
      getTemperature = function(_, celsius)
        return celsius * 1.8 + 32
      end,
      getTemperatureUnit = function()
        return "°F"
      end,
      formatDayInPeriod = function()
        return "August 1"
      end,
    }
    installWorld({
      current = { temperature = 20, windSpeed = 1, windDirection = 0, forecastType = 1 },
      daily = { [1] = { day = 1, highTemperature = 30, lowTemperature = 10, forecastType = 1 } },
    })

    local model = VDT.WeatherExporter.collect()

    assert.are.equal("°F", model.temperatureUnit)
    assert.are.equal(68, model.current.temperature)
    assert.are.equal(86, model.daily[1].high)
    assert.are.equal(50, model.daily[1].low)
    assert.are.equal("August 1", model.today.label)
  end)

  it("skips an hourly step the forecast has no item for", function()
    installWorld({
      current = { temperature = 20, windSpeed = 1, windDirection = 0, forecastType = 1 },
      -- only the first and third steps resolve; the gap must not end the list
      hourly = {
        [0] = { time = 8 * MS_PER_HOUR, temperature = 20, windSpeed = 1, windDirection = 0, forecastType = 1 },
        [4] = { time = 12 * MS_PER_HOUR, temperature = 24, windSpeed = 1, windDirection = 0, forecastType = 1 },
      },
    })

    local model = VDT.WeatherExporter.collect()

    assert.are.equal(2, #model.hourly)
    assert.are.equal(8, model.hourly[1].hour)
    assert.are.equal(12, model.hourly[2].hour)
  end)

  it("lifts a time that lands a hair below the hour onto it", function()
    installWorld({
      current = { temperature = 20, windSpeed = 1, windDirection = 0, forecastType = 1 },
      hourly = {
        [0] = { time = 8 * MS_PER_HOUR - 0.0001, temperature = 20, windSpeed = 1, windDirection = 0, forecastType = 1 },
      },
    })

    local model = VDT.WeatherExporter.collect()

    assert.are.equal(8, model.hourly[1].hour)
  end)

  it("omits empty lists rather than exporting {}", function()
    installWorld({ current = { temperature = 20, windSpeed = 1, windDirection = 0, forecastType = 1 } })

    local model = VDT.WeatherExporter.collect()

    assert.is_nil(model.hourly)
    assert.is_nil(model.daily)
    assert.is_not_nil(model.current)
  end)

  it("keeps a partial read: current weather without any forecast items", function()
    -- The shape a multiplayer client may well be in, if forecastItems turn out to be server-side only.
    installWorld({ current = { temperature = 20, windSpeed = 1, windDirection = 0, forecastType = 4 } })

    local model = VDT.WeatherExporter.collect()

    assert.are.equal("RAIN", model.current.type)
  end)

  it("keeps the hourly strip when only the current reading throws", function()
    installWorld({
      hourly = {
        [0] = { time = 8 * MS_PER_HOUR, temperature = 20, windSpeed = 1, windDirection = 0, forecastType = 1 },
      },
    })
    _G.g_currentMission.environment.weather.forecast.getCurrentWeather = function()
      error("no current weather on this client")
    end

    local model = VDT.WeatherExporter.collect()

    assert.is_nil(model.current)
    assert.are.equal(1, #model.hourly)
  end)

  it("skips the write entirely when nothing at all is readable", function()
    -- An absent file makes the app wait; a present empty one makes it claim there is no forecast.
    installWorld({})

    assert.is_nil(VDT.WeatherExporter.collect())
  end)

  it("keeps the Beaufort step of a speed just above a whole m/s", function()
    -- windSpeed is rounded to one decimal for the file, but Beaufort is taken from the RAW value:
    -- the game's conversion ceils first, so 2.04 must read as ceil(2.04) = 3 -> Bft 2, where the
    -- rounded-down 2.0 would ceil to 2 -> Bft 1.
    installWorld({ current = { temperature = 20, windSpeed = 2.04, windDirection = 0, forecastType = 1 } })

    local model = VDT.WeatherExporter.collect()

    assert.are.equal(2.0, model.current.windSpeed)
    assert.are.equal(2, model.current.windBeaufort)
    assert.are.equal(1, ValueMapper.windSpeedToBeaufort(2.0))
  end)

  it("wraps a negative wind angle rather than exporting it negative", function()
    installWorld({ current = { temperature = 20, windSpeed = 1, windDirection = -45, forecastType = 1 } })

    assert.are.equal(315, VDT.WeatherExporter.collect().current.windDirection)
  end)

  it("survives non-numeric wind fields", function()
    installWorld({ current = { temperature = 20, windSpeed = "gusty", windDirection = nil, forecastType = 1 } })

    local model = VDT.WeatherExporter.collect()

    assert.are.equal(0, model.current.windSpeed)
    assert.are.equal(0, model.current.windBeaufort)
    assert.are.equal(0, model.current.windDirection)
  end)

  it("returns nil when the weather isn't up yet", function()
    _G.g_currentMission = nil

    assert.is_nil(VDT.WeatherExporter.collect())
  end)
end)
