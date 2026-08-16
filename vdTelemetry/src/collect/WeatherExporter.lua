-- Weather export channel: the forecast, written to weather.json -- current conditions, twelve
-- two-hourly steps ahead, and six days out. This is the bottom half of the game's own Anbaukalender
-- (gui/InGameMenuCalendarFrame), and it reads the same three calls that frame does:
-- forecast:getCurrentWeather(), :getHourlyForecast(hoursFromNow) and :getDailyForecast(daysFromToday).
--
-- Distinct from the telemetry channel's `environment.weather`, which carries only the live
-- min/max/current temperature at the 100 ms tick. A forecast is eighteen entries of structure and
-- changes on the hour; putting it on the live tick is exactly what the channel registry exists to
-- avoid.
--
-- Base-game state only, so it lives in collect/, not integrations/. NOT farmScoped: it rains on
-- every farm equally.
--
-- Event-driven, subscribed to HOUR_CHANGED and DAY_CHANGED -- the same two the game's own frame
-- reloads on, so our readout moves exactly when the menu's does. At default timescale an in-game
-- hour is about a real minute, which makes this a ~1/min rewrite of a ~1 kB file.
--
-- Temperatures go through g_i18n:getTemperature and the file names the resulting unit, so a player
-- on Fahrenheit gets Fahrenheit here and the app never converts. Wind ships twice: windSpeed in m/s
-- (the honest measurement) and windBeaufort (what the menu prints, via ValueMapper).
--
-- windDirection is the game's RAW angle in degrees, deliberately not put through
-- ValueMapper.headingFromYRotation. The current wind's angle is derived from a y-rotation, but each
-- forecast entry's comes from `variation.wind.windAngle` in the weather XML -- two different sources
-- that only happen to share a unit. Forcing both through one compass convention would silently make
-- one of them wrong. The game draws its arrow at `windDirection + 180`; consumers wanting to match
-- the menu must do the same.
--
-- Every engine read is pcall-guarded (fail-soft house rule); on a multiplayer client in particular,
-- whether the forecast items are replicated at all is unproven (see FUTURE.md).
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.WeatherExporter = {}

VDT.WeatherExporter.CHANNEL = "weather"
VDT.WeatherExporter.FILE_NAME = "weather.json"
-- Own version, evolving independently of VDTelemetry.VERSION and the shared Kotlin WeatherForecastData.
VDT.WeatherExporter.VERSION = 1

-- The game's own two list lengths: twelve hourly cells at a two-hour step (so a full day ahead), and
-- six daily cells. Matching them keeps our strip and the menu's showing the same horizon.
VDT.WeatherExporter.HOURLY_STEPS = 12
VDT.WeatherExporter.HOURLY_STEP_HOURS = 2
VDT.WeatherExporter.DAILY_STEPS = 6

VDT.WeatherExporter.subscribed = false

local MS_PER_HOUR = 60 * 60 * 1000

-- WeatherType ids -> the names we export. The enum lives in the engine's
-- environment/weather/WeatherType.lua; mirrored here rather than read from the global so a missing
-- WeatherType table degrades to "UNKNOWN" instead of throwing.
local WEATHER_TYPES = {
  [1] = "SUN",
  [2] = "PARTIALLY_CLOUDY",
  [3] = "CLOUDY",
  [4] = "RAIN",
  [5] = "SNOW",
  [6] = "HAIL",
  [7] = "TWISTER",
  [8] = "THUNDER",
}

---A WeatherType id as its exported name.
---@param forecastType number|nil the engine's WeatherType id
---@return string one of the WeatherType names, or "UNKNOWN"
function VDT.WeatherExporter.mapWeatherType(forecastType)
  return WEATHER_TYPES[forecastType] or "UNKNOWN"
end

-- A temperature in the player's unit, rounded to whole degrees the way the menu shows it. Guarded
-- because g_i18n is absent in the specs and briefly during load.
local function temperature(celsius)
  if type(celsius) ~= "number" then
    return 0
  end
  local value = celsius
  if g_i18n ~= nil then
    local ok, converted = pcall(g_i18n.getTemperature, g_i18n, celsius)
    if ok and type(converted) == "number" then
      value = converted
    end
  end
  return math.floor(value + 0.5)
end

local function num(value)
  return type(value) == "number" and value or 0
end

local function windDirection(degrees)
  -- Lua's % is non-negative for a positive divisor, so a negative angle wraps rather than staying
  -- negative. The engine's own angles are already snapped to 45 degrees, but a mod's need not be.
  return math.floor(num(degrees) + 0.5) % 360
end

-- The wind fields, shared by the current reading and each hourly step. Every read goes through num()
-- first: this runs inside collect(), not inside the pcall that fetched the forecast, so a non-numeric
-- field here would take the whole channel's write down rather than costing one entry.
local function windOf(info, target)
  local mps = num(info.windSpeed)
  -- Json.lua prints floats with %.14g, so an unrounded m/s reads as 1.3999999999999. One decimal is
  -- well past what a wind readout means.
  target.windSpeed = math.floor(mps * 10 + 0.5) / 10
  -- Beaufort from the RAW speed, not the rounded one: the game's conversion ceils to a whole m/s
  -- first, so a value rounded down across an integer changes the answer -- 2.04 ceils to 3 (Bft 2)
  -- where the rounded 2.0 ceils to 2 (Bft 1).
  target.windBeaufort = ValueMapper.windSpeedToBeaufort(mps) or 0
  target.windDirection = windDirection(info.windDirection)
  return target
end

---The unit every temperature in this file is in. g_i18n owns the °C/°F choice, so the unit is asked
---of it rather than assumed -- the telemetry channel's environment block does the same.
---@return string
function VDT.WeatherExporter.temperatureUnit()
  if g_i18n ~= nil then
    local ok, unit = pcall(g_i18n.getTemperatureUnit, g_i18n, false)
    if ok and type(unit) == "string" and unit ~= "" then
      return unit
    end
  end
  return "°C"
end

---Which day the forecast starts from, with the game's own localized caption ("August 1").
---@param environment table g_currentMission.environment
---@return WeatherDayModel|nil
function VDT.WeatherExporter.collectToday(environment)
  local period = environment.currentPeriod
  local dayInPeriod = environment.currentDayInPeriod
  if type(period) ~= "number" or type(dayInPeriod) ~= "number" then
    return nil
  end
  return {
    label = VDT.WeatherExporter.dayLabel(dayInPeriod, period, false),
    period = period,
    dayInPeriod = dayInPeriod,
  }
end

---The game's own localized day caption. Not derivable app-side: formatDayInPeriod folds in the
---hemisphere's month shift AND drops the day number entirely when a period is one day long. Falls
---back to "<period>/<day>" when i18n cannot answer, which is at least unambiguous.
---@param dayInPeriod number
---@param period number
---@param useShort boolean short form ("Aug 2") vs long ("August 2")
---@return string
function VDT.WeatherExporter.dayLabel(dayInPeriod, period, useShort)
  if g_i18n ~= nil then
    local ok, text = pcall(g_i18n.formatDayInPeriod, g_i18n, dayInPeriod, period, useShort)
    if ok and type(text) == "string" and text ~= "" then
      return text
    end
  end
  return string.format("%d/%d", period, dayInPeriod)
end

---Current conditions.
---@param forecast table the weather forecast (environment.weather.forecast)
---@return WeatherNowModel|nil
function VDT.WeatherExporter.collectCurrent(forecast)
  local ok, info = pcall(forecast.getCurrentWeather, forecast)
  if not ok or type(info) ~= "table" then
    return nil
  end
  return windOf(info, {
    type = VDT.WeatherExporter.mapWeatherType(info.forecastType),
    temperature = temperature(info.temperature),
  })
end

---The hourly strip: HOURLY_STEPS entries HOURLY_STEP_HOURS apart, starting now. The list runs
---forward and wraps past midnight, so it is ordered rather than sorted -- an entry's `hour` alone
---does not say which day it belongs to, and consumers render it in the order given.
---
---A nil step is skipped rather than ending the list: getHourlyForecast returns nil when it finds no
---forecast item covering that time, and a later step may still resolve.
---@param forecast table
---@return WeatherHourModel[]
function VDT.WeatherExporter.collectHourly(forecast)
  local hours = {}
  for step = 0, VDT.WeatherExporter.HOURLY_STEPS - 1 do
    local ok, info = pcall(forecast.getHourlyForecast, forecast, step * VDT.WeatherExporter.HOURLY_STEP_HOURS)
    if ok and type(info) == "table" and type(info.time) == "number" then
      -- The engine's own rounding for this readout: the +0.0001 lifts a time that lands a hair below
      -- a whole hour (floating-point ms) onto it, so 07:59.9997 prints as 08:00 rather than 07:00.
      hours[#hours + 1] = windOf(info, {
        hour = math.floor(info.time / MS_PER_HOUR + 0.0001) % 24,
        type = VDT.WeatherExporter.mapWeatherType(info.forecastType),
        temperature = temperature(info.temperature),
      })
    end
  end
  return hours
end

---The outlook: DAILY_STEPS days starting tomorrow (the game asks for offsets 1..6, so today is not
---repeated here -- `today` and `current` cover it).
---@param forecast table
---@param environment table g_currentMission.environment
---@return WeatherDailyModel[]
function VDT.WeatherExporter.collectDaily(forecast, environment)
  local days = {}
  for offset = 1, VDT.WeatherExporter.DAILY_STEPS do
    local ok, info = pcall(forecast.getDailyForecast, forecast, offset)
    if ok and type(info) == "table" and type(info.day) == "number" then
      -- The forecast counts in monotonic days; the calendar position of one is the environment's to
      -- work out (it folds in daysPerPeriod and the year wrap).
      local okPeriod, period = pcall(environment.getPeriodFromDay, environment, info.day)
      local okDay, dayInPeriod = pcall(environment.getDayInPeriodFromDay, environment, info.day)
      if okPeriod and okDay and type(period) == "number" and type(dayInPeriod) == "number" then
        days[#days + 1] = {
          label = VDT.WeatherExporter.dayLabel(dayInPeriod, period, true),
          period = period,
          dayInPeriod = dayInPeriod,
          type = VDT.WeatherExporter.mapWeatherType(info.forecastType),
          high = temperature(info.highTemperature),
          low = temperature(info.lowTemperature),
        }
      end
    end
  end
  return days
end

function VDT.WeatherExporter.isAvailable()
  local environment = g_currentMission ~= nil and g_currentMission.environment or nil
  return type(environment) == "table"
    and type(environment.weather) == "table"
    and type(environment.weather.forecast) == "table"
end

---Build the forecast model, or nil when the weather isn't up yet (skips the write).
---@return WeatherForecastModel|nil
function VDT.WeatherExporter.collect()
  if not VDT.WeatherExporter.isAvailable() then
    return nil
  end
  local environment = g_currentMission.environment
  local forecast = environment.weather.forecast

  local current = VDT.WeatherExporter.collectCurrent(forecast)
  local hourly = VDT.WeatherExporter.collectHourly(forecast)
  local daily = VDT.WeatherExporter.collectDaily(forecast, environment)

  -- Nothing readable at all: skip the write rather than publishing an empty forecast. The forecast
  -- object exists from the moment the weather loads, but its items are generated a beat later, and a
  -- file saying "no weather" would sit there until the next hour rolled over. An absent file makes
  -- the app wait; a present empty one makes it claim there is no forecast. A PARTIAL read is kept,
  -- though -- if a multiplayer client turns out to have the current weather but no forecast items,
  -- the "now" block is still worth showing.
  if current == nil and #hourly == 0 and #daily == 0 then
    return nil
  end

  return {
    version = tostring(VDT.WeatherExporter.VERSION),
    temperatureUnit = VDT.WeatherExporter.temperatureUnit(),
    today = VDT.WeatherExporter.collectToday(environment),
    current = current,
    -- omit empty arrays (nil, not {}): an empty Lua table encodes as {} which the Kotlin lists reject
    hourly = #hourly > 0 and hourly or nil,
    daily = #daily > 0 and daily or nil,
  }
end

-- MessageCenter invokes callback(target, ...); target is VDT.WeatherExporter, extras ignored.
function VDT.WeatherExporter.markDirty()
  VDT.ExportChannels.markDirty(VDT.WeatherExporter.CHANNEL)
end

-- Lazy subscribe: wait until the weather is up, then watch the two messages the game's own frame
-- reloads on. The initial markDirty() writes the forecast that was already there on load.
---@param debugger GrisuDebug
function VDT.WeatherExporter.tick(debugger)
  if VDT.WeatherExporter.subscribed or not VDT.WeatherExporter.isAvailable() then
    return
  end
  if MessageType == nil or g_messageCenter == nil then
    return
  end
  for _, message in ipairs({ "HOUR_CHANGED", "DAY_CHANGED" }) do
    if MessageType[message] ~= nil then
      g_messageCenter:subscribe(MessageType[message], VDT.WeatherExporter.markDirty, VDT.WeatherExporter)
    end
  end
  VDT.WeatherExporter.subscribed = true
  VDT.WeatherExporter.markDirty()
  debugger:info("Weather channel active (subscribed to hour/day changes)")
end

-- Self-register the channel (see ExportChannels). Event-driven: no interval, the subscriptions above
-- do the marking. Deliberately NOT farmScoped -- the weather is world state, the same for every farm.
VDT.ExportChannels.register({
  name = VDT.WeatherExporter.CHANNEL,
  fileName = VDT.WeatherExporter.FILE_NAME,
  isAvailable = VDT.WeatherExporter.isAvailable,
  collect = VDT.WeatherExporter.collect,
  tick = VDT.WeatherExporter.tick,
})
