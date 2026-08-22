-- Collects the environment subtree of the telemetry model. Namespaced under VDT.* (see
-- aspects/TurnOn.lua). Pure extraction: reads game state and returns a plain EnvironmentModel table.

VDT = VDT or {}
VDT.EnvironmentExporter = {}

---@param pda PDA|nil the resolved map PDA file, or nil when the map has none
---@return EnvironmentModel
function VDT.EnvironmentExporter.collect(pda)
  local environment = g_currentMission.environment

  ---@type EnvironmentModel
  local model = {
    -- Both formats live in ValueMapper: the finance channel stamps its notification log with the same
    -- date/time, and the two must not drift apart.
    date = ValueMapper.formatGameDate(environment),
    time = ValueMapper.formatGameTime(environment),
  }

  -- weather. Only the temperature: the forecast is its own channel on its own cadence
  -- (src/collect/WeatherExporter.lua), and this stays here because it is the live half. Today's
  -- min/max exist nowhere else -- weather.json's outlook starts tomorrow -- and the header reads
  -- this at the tick, where the weather channel may be switched off entirely.
  --
  -- getCurrentTemperature() rather than forecast:getCurrentWeather().temperature, which is what it
  -- returns anyway: the forecast call also walks forecastItems for a weather type and derives a wind
  -- angle, and three of its four fields were being dropped here 10x a second.
  local weather = environment.weather
  local minTemperatureInC, maxTemperatureInC = weather:getCurrentMinMaxTemperatures()
  -- getTemperature converts to the player's chosen unit, so the unit label has to come from g_i18n
  -- too: hardcoding "°C" here reported Fahrenheit values under a Celsius label for anyone who had
  -- switched. Same pairing as the weather channel, and the same rounding -- MathUtil.round(x, 0) is
  -- math.floor(x + 0.5), so the header and the calendar's "now" cell cannot print different degrees.
  model.weather = {
    temperature = {
      min = MathUtil.round(g_i18n:getTemperature(minTemperatureInC), 0),
      max = MathUtil.round(g_i18n:getTemperature(maxTemperatureInC), 0),
      current = MathUtil.round(g_i18n:getTemperature(weather:getCurrentTemperature()), 0),
      unit = g_i18n:getTemperatureUnit(false),
    },
  }

  -- pda / player position: the player position is always present; filename/width/height only when
  -- the map actually ships a PDA image.
  --
  -- The heading rides along with the position: the HUD map refreshes playerRotation (the local
  -- player's yaw, radians) in the same updatePlayerPosition() pass that produces the normalized
  -- coordinates, and it already accounts for the top-down camera. It is only unset before that
  -- first pass, hence the `or 0`.
  local ingameMap = g_currentMission.hud.ingameMap
  ---@type PdaModel
  local pdaModel = {
    player = {
      posX = ingameMap.normalizedPlayerPosX,
      posZ = ingameMap.normalizedPlayerPosZ,
      heading = math.floor(ValueMapper.calculatePlayerHeading(ingameMap.playerRotation or 0)),
      headingUnit = "°",
    },
  }
  -- The local player's farm, so the app can tell own fields/POIs from other farms' (map.json only
  -- carries ownerFarmId). 0 is the spectator farm = "no farm" -> omitted (see CropRotation.lua).
  if g_localPlayer ~= nil and type(g_localPlayer.farmId) == "number" and g_localPlayer.farmId > 0 then
    pdaModel.player.farmId = g_localPlayer.farmId
  end
  if pda ~= nil then
    pdaModel.filename = pda.filename
    pdaModel.width = pda.width
    pdaModel.height = pda.height
  end
  model.pda = pdaModel

  -- extension point for optional environment integrations (e.g. a weather/seasons mod). None today;
  -- the seam exists so environment extensions don't require touching this collector later.
  VDT.Integrations.run("contributeEnvironment", environment, model)

  return model
end
