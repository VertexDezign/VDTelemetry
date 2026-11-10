-- Executes the BGA Control write-back command (app -> mod): the six setpoints of a Pumps & Hoses
-- biogas plant run by PnH BGA Control — target temperature, the two feed rates, agitator,
-- recirculation and the separator's throughput. The read side is integrations/PnhBgaControl.lua.
--
-- ONE COMMAND FOR ALL SIX, because that is what the mod's own network event carries
-- (BgaSetSettingsEvent): there is no per-field write, so the app always sends the full set with one
-- value changed, and this side never has to merge.
--
-- WHAT IT MIRRORS is BgaControlDialog:saveCurrentValuesToBga, the path every one of the dialog's
-- buttons ends in:
--   1. write the values onto the root's `bgaControlData` and `bgaTarget{Silage,Liquid}FlowLph`,
--   2. BgaExtensionMain.applyFlowSettingsToCluster — the feed rates onto every part of the plant,
--   3. on the server: mark the recipe dirty and rebuild it on every fermenter,
--   4. BgaSetSettingsEvent.sendEvent — which does NOTHING in single player (it returns unless the
--      mission is multiplayer), broadcasts the new state on an MP host, and sends the settings to the
--      server from an MP client, where the server checks farm access, clamps, applies and re-broadcasts.
-- Steps 1-3 are therefore what makes it happen in single player; skipping them because "the event
-- does it" would leave an SP plant untouched. The separator has its own call
-- (applySeparatorFlowToCluster), made only when its value changes, as the dialog does.
--
-- An MP CLIENT may only write once it has received the server's state (`root.bgaStateReceived`) —
-- the dialog's own guard, and for the same reason: before that the client's copy is defaults, and the
-- event carries all six, so a write would push those defaults over the server's real settings.
--
-- The plant is addressed by its construction id (the root placeable's, see PumpsAndHoses.rootById),
-- own farm only — the same scope the read side exports.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.BgaControl = {}

local function clamp(v, lo, hi)
  return math.max(lo, math.min(hi, v))
end

---The settings to write, clamped to the mod's own limits (BgaSetSettingsEvent's clampSettings and
---clampSettingsForRoot), in the units the mod stores. The app speaks whole percent for the agitator
---and the recirculation; the mod stores fractions.
---@param params table the parsed command
---@param fermenters number the plant's fermenter count, which the feed limits scale with
---@return table { temperature, silage, liquid, agitator, recirculation, separator|nil }
function VDT.BgaControl.clamped(params, fermenters)
  local limits = VDT.PnhBgaControl.LIMITS
  return {
    temperature = clamp(
      math.floor((params.temperature or limits.temperatureMin) + 0.5),
      limits.temperatureMin,
      limits.temperatureMax
    ),
    silage = clamp(math.floor((params.silage or 0) + 0.5), 0, limits.silagePerFermenter * fermenters),
    liquid = clamp(math.floor((params.liquid or 0) + 0.5), 0, limits.liquidPerFermenter * fermenters),
    agitator = clamp((params.agitator or 0) / 100, 0, 1),
    recirculation = clamp((params.recirculation or 0) / 100, 0, limits.recirculationMax),
    separator = params.separator ~= nil and clamp(math.floor(params.separator + 0.5), 0, limits.separatorMax) or nil,
  }
end

VDT.CommandRegistry.register("setBgaSettings", {
  requiresVehicle = false,
  parse = function(xml, key)
    return {
      constructionId = xml:getString(key .. "#constructionId"),
      temperature = xml:getInt(key .. "#temperature"),
      silage = xml:getInt(key .. "#silage"),
      liquid = xml:getInt(key .. "#liquid"),
      agitator = xml:getInt(key .. "#agitator"),
      recirculation = xml:getInt(key .. "#recirculation"),
      separator = xml:getInt(key .. "#separator"),
    }
  end,
  execute = function(_, params, debugger)
    local main, _, _, settingsEvent = VDT.PnhBgaControl.tables()
    if main == nil then
      debugger:warn("setBgaSettings: PnH BGA Control is not installed")
      return
    end
    local farmId = VDT.Farm.ownFarmId()
    local root = VDT.PumpsAndHoses.rootById(params.constructionId, farmId)
    if root == nil then
      debugger:warn("setBgaSettings: no plant %s owned by the local farm", tostring(params.constructionId))
      return
    end
    local data = root.bgaControlData
    if type(data) ~= "table" then
      debugger:warn("setBgaSettings: plant %s has no BGA Control state yet", tostring(params.constructionId))
      return
    end
    if g_server == nil and root.bgaStateReceived ~= true then
      debugger:warn("setBgaSettings: no state from the server yet for %s, refusing", tostring(params.constructionId))
      return
    end

    local s = VDT.BgaControl.clamped(params, VDT.PnhBgaControl.fermenterCount(main, root))

    data.targetTemperature = s.temperature
    data.silageFlow = s.silage
    data.liquidFlow = s.liquid
    data.agitatorIntensity = s.agitator
    data.recirculationRate = s.recirculation
    root.bgaTargetSilageFlowLph = s.silage
    root.bgaTargetLiquidFlowLph = s.liquid
    main.applyFlowSettingsToCluster(root)

    local separator = tonumber(main.getSeparatorFlowLph(root)) or 0
    if s.separator ~= nil and s.separator ~= separator then
      main.applySeparatorFlowToCluster(root, s.separator)
      separator = s.separator
    end

    if g_server ~= nil then
      root.bgaRecipeDirty = true
      for placeable in pairs(main.getAllClusterPlaceables(root)) do
        if main.isFermenterPlaceable(placeable) then
          local pp = main.getProductionPointFromPlaceable(placeable)
          if pp ~= nil then
            main.updateBgaProductionRecipe(pp)
          end
        end
      end
    end

    if settingsEvent ~= nil and settingsEvent.sendEvent ~= nil then
      settingsEvent.sendEvent(root, s.temperature, s.silage, s.liquid, s.agitator, s.recirculation, separator)
    end
    debugger:debug(
      "setBgaSettings %s: %d C, feed %d/%d L/h, agitator %.2f, recirculation %.2f, separator %d L/h",
      tostring(params.constructionId),
      s.temperature,
      s.silage,
      s.liquid,
      s.agitator,
      s.recirculation,
      separator
    )
  end,
})
