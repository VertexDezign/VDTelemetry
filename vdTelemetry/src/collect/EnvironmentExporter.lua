-- Collects the environment subtree of the telemetry model.
--
-- Pure extraction: reads game state and returns a plain EnvironmentModel table. It never touches a
-- serializer, so the same fragment feeds JSON today and could feed anything later. Behaviour mirrors
-- the original VDTelemetry:populateXMLFromEnvironment so the emitted values stay identical.

EnvironmentExporter = {}

---@param pda PDA|nil the resolved map PDA file, or nil when the map has none
---@return EnvironmentModel
function EnvironmentExporter.collect(pda)
  local environment = g_currentMission.environment

  ---@type EnvironmentModel
  local model = {
    -- initial year is 2024 (game release), stored as year 1 -> 2023 + currentYear
    date = string.format("%02d.%02d.%04d", environment.currentDayInPeriod,
      ValueMapper.mapPeriodToMonth(environment.currentPeriod), 2023 + environment.currentYear),
    -- current time, fixed 24h format
    time = string.format("%02d:%02d", environment.currentHour, environment.currentMinute),
  }

  -- weather
  local weather = environment.weather
  local minTemperatureInC, maxTemperatureInC = weather:getCurrentMinMaxTemperatures()
  local currentTemperatureInC = weather.forecast:getCurrentWeather()
  model.weather = {
    temperature = {
      min = MathUtil.round(g_i18n:getTemperature(minTemperatureInC), 0),
      max = MathUtil.round(g_i18n:getTemperature(maxTemperatureInC), 0),
      current = MathUtil.round(g_i18n:getTemperature(currentTemperatureInC.temperature), 0),
      unit = "°C",
    },
  }

  -- pda / player position: the player position is always present; filename/width/height only when
  -- the map actually ships a PDA image.
  local ingameMap = g_currentMission.hud.ingameMap
  ---@type PdaModel
  local pdaModel = {
    player = {
      posX = ingameMap.normalizedPlayerPosX,
      posZ = ingameMap.normalizedPlayerPosZ,
    },
  }
  if pda ~= nil then
    pdaModel.filename = pda.filename
    pdaModel.width = pda.width
    pdaModel.height = pda.height
  end
  model.pda = pdaModel

  return model
end
