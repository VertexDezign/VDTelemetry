-- Executes the productions write-back commands (app -> mod). The inverse of the read-only productions
-- export channel (src/collect/ProductionExporter.lua): switch a production line on/off and change an
-- output's distribution mode by driving the base-game ProductionPoint setters, which each send the
-- appropriate multiplayer event so state stays in sync (ProductionPointProductionStateEvent /
-- ProductionPointOutputModeEvent — the setter's `noEventSend` is left nil, so it broadcasts on the
-- server and forwards to the server from a client). Neither touches a vehicle, so both handlers
-- declare requiresVehicle = false (see CommandRegistry / VDTelemetry:onCommand).
--
-- A production point is addressed by the same exported `id` the read side emits — resolved back to
-- the live point with ProductionExporter.placeableId so the two can't drift. That resolver is also
-- where ownership is enforced: only the local farm's points may be mutated (ProductionExporter
-- .ownFarmId), matching the game's own menu, which shows only owned productions. In multiplayer a
-- command carrying a foreign id would otherwise flip another farm's production; we refuse rather than
-- guess when the farm is unresolved.
--
-- Output mode: only *buffered* outputs (in the point's outputFillTypeIds) have a mode — a direct-sell
-- output isn't stored, so the engine's setter rejects it; we screen for it and log instead.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.ProductionControl = {}

-- token -> ProductionPoint.OUTPUT_MODE value, the inverse of ProductionExporter.outputModeToken
-- (which camelCases the enum key). Built lazily from the live enum so it tracks any renumbering.
local modeValues, modeSource

local function outputModeValue(token, enum)
  enum = enum or (ProductionPoint ~= nil and ProductionPoint.OUTPUT_MODE or nil)
  if enum == nil or token == nil then
    return nil
  end
  if modeValues == nil or modeSource ~= enum then
    modeValues, modeSource = {}, enum
    for key, value in pairs(enum) do
      if type(key) == "string" then
        local lower = string.lower(key)
        modeValues[(string.gsub(lower, "_(%a)", string.upper))] = value
      end
    end
  end
  return modeValues[token]
end

-- Resolve a production point by its exported id, enforcing own-farm ownership. Returns the point or
-- nil (logging the reason) so callers can `if pp == nil then return end`.
local function resolvePoint(pointId, debugger, label)
  if pointId == nil then
    debugger:warn("%s: missing pointId", label)
    return nil
  end
  local manager = g_currentMission ~= nil and g_currentMission.productionChainManager or nil
  if manager == nil then
    debugger:warn("%s: production chain manager not available", label)
    return nil
  end
  local farmId = VDT.ProductionExporter.ownFarmId()
  if farmId == nil then
    debugger:warn("%s: no local farm resolved, refusing to mutate production %s", label, tostring(pointId))
    return nil
  end
  for index, pp in ipairs(manager.productionPoints or {}) do
    local id = VDT.ProductionExporter.placeableId(pp.owningPlaceable, "point" .. index)
    if id == pointId then
      local okOwner, owner = pcall(pp.getOwnerFarmId, pp)
      if not okOwner or owner ~= farmId then
        debugger:warn("%s: production %s is not owned by the local farm -- ignoring", label, tostring(pointId))
        return nil
      end
      return pp
    end
  end
  debugger:warn("%s: no production with id %s", label, tostring(pointId))
  return nil
end

VDT.CommandRegistry.register("setProductionEnabled", {
  requiresVehicle = false,
  parse = function(xml, key)
    return {
      pointId = xml:getString(key .. "#pointId"),
      productionId = xml:getString(key .. "#productionId"),
      enabled = xml:getBool(key .. "#enabled", false),
    }
  end,
  execute = function(_, params, debugger)
    local pp = resolvePoint(params.pointId, debugger, "setProductionEnabled")
    if pp == nil then
      return
    end
    if
      params.productionId == nil
      or pp.productionsIdToObj == nil
      or pp.productionsIdToObj[params.productionId] == nil
    then
      debugger:warn(
        "setProductionEnabled: production %s has no line %s",
        tostring(params.pointId),
        tostring(params.productionId)
      )
      return
    end
    pp:setProductionState(params.productionId, params.enabled == true)
    debugger:debug(
      "setProductionEnabled %s[%s] = %s",
      tostring(params.pointId),
      tostring(params.productionId),
      tostring(params.enabled == true)
    )
  end,
})

VDT.CommandRegistry.register("setProductionOutputMode", {
  requiresVehicle = false,
  parse = function(xml, key)
    return {
      pointId = xml:getString(key .. "#pointId"),
      fillType = xml:getString(key .. "#fillType"),
      mode = xml:getString(key .. "#mode"),
    }
  end,
  execute = function(_, params, debugger)
    local pp = resolvePoint(params.pointId, debugger, "setProductionOutputMode")
    if pp == nil then
      return
    end
    local fillTypeId = params.fillType ~= nil and g_fillTypeManager:getFillTypeIndexByName(params.fillType) or nil
    if fillTypeId == nil then
      debugger:warn("setProductionOutputMode: unknown fillType %s", tostring(params.fillType))
      return
    end
    -- Only buffered outputs carry a mode; a direct-sell output is never stored, so the engine setter
    -- would reject it. Screen here for a clearer log than the engine's printf.
    if pp.outputFillTypeIds == nil or pp.outputFillTypeIds[fillTypeId] == nil then
      debugger:warn(
        "setProductionOutputMode: %s is not a buffered output of production %s -- ignoring",
        tostring(params.fillType),
        tostring(params.pointId)
      )
      return
    end
    local mode = outputModeValue(params.mode)
    if mode == nil then
      debugger:warn("setProductionOutputMode: unknown mode %s", tostring(params.mode))
      return
    end
    pp:setOutputDistributionMode(fillTypeId, mode)
    debugger:debug(
      "setProductionOutputMode %s[%s] = %s",
      tostring(params.pointId),
      tostring(params.fillType),
      tostring(params.mode)
    )
  end,
})
