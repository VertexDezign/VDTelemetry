-- Ground-layer export channel: grid-samples the field/soil state across the whole map (the FieldState
-- per-world-position read pattern) and writes mapLayers.json -- three raster planes (crops planted,
-- growth state, soil condition) the server renders into translucent PNG overlays. Classification and
-- colors replicate the game's own MapOverlayGenerator (gui/base/MapOverlayGenerator.lua) so the
-- dashboard overlay matches the in-game map overlay exactly; the constants and transition rules below
-- are transcribed from that class (and from FruitTypeDesc's getIsGrowing/getIsPreparable/etc., which
-- MapOverlayGenerator itself reimplements inline).
--
-- Sampling is spread over many frames (CELLS_PER_FRAME per tick) rather than done in one pass, since a
-- full GRID_SIZE^2 sweep is tens of thousands of engine density-map reads. tick() runs every frame
-- (ExportChannels.tick has no pcall around channel ticks), so the sweep batch is pcall'd here -- an
-- engine hiccup mid-sweep aborts that sweep and enters the pause phase, rather than killing the whole
-- mod's update loop.
--
-- Wire format: three one-byte-per-cell planes, rows as right-trimmed hex strings (see encodeRow).
-- Legends list only values actually seen during the sweep, so the file (and the app's PNG fetch) never
-- carries color/label data for fruit types or states that don't exist on this map.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.MapLayers = {}

VDT.MapLayers.CHANNEL = "mapLayers"
VDT.MapLayers.FILE_NAME = "mapLayers.json"
-- Own version, evolving independently of VDTelemetry.VERSION and the shared Kotlin MapLayersData.
VDT.MapLayers.VERSION = 1

-- The game's own overlay base resolution (MapOverlayGenerator.OVERLAY_RESOLUTION.FOLIAGE_STATE) --
-- fixed, not terrain-scaled, so a bigger map just means bigger cells.
VDT.MapLayers.GRID_SIZE = 512
-- ~256 frames per full sweep at this budget (512*512/1024), roughly 4-9s depending on frame rate.
VDT.MapLayers.CELLS_PER_FRAME = 1024
-- Idle time between completed sweeps.
VDT.MapLayers.SWEEP_PAUSE_MS = 60000

-- Mutable state, on the table so specs can reset it (see MapVehicles.timerMs). pauseMs starts already
-- past the threshold so the first sweep begins on the first available tick, not after an initial wait.
VDT.MapLayers.sweep = nil -- in-progress sweep context, nil when idle/paused
VDT.MapLayers.pauseMs = VDT.MapLayers.SWEEP_PAUSE_MS
VDT.MapLayers.model = nil -- last completed sweep's model; collect() returns this

-- Wire values for the "growth" plane. These are our own wire vocabulary, not
-- MapOverlayGenerator.GROWTH_STATE_INDEX (that enum keys the game's own UI filter checkboxes and has
-- no bearing on how we encode a cell).
local GROWTH_NONE = 0
local GROWTH_CULTIVATED = 1
local GROWTH_STUBBLE_TILLAGE = 2
local GROWTH_SEEDBED = 3
local GROWTH_PLOWED = 4
local GROWTH_GRADIENT_BASE = 9 -- + gradient index (1..#GROWTH_GRADIENT_COLORS) => 10..17
local GROWTH_TOPPING = 20
local GROWTH_HARVEST = 21
local GROWTH_CUT = 22
local GROWTH_WITHERED = 23

-- Wire values for the "soil" plane.
local SOIL_NONE = 0
local SOIL_WEED_BASE = 1 -- + weed color group index (1-based) => 1..9
local SOIL_STONE_BASE = 10 -- + stone color group index (1-based) => 10..19
local SOIL_NEEDS_PLOWING = 20
local SOIL_NEEDS_LIME = 21
local SOIL_FERTILIZED_BASE = 30 -- + spray level (1..maxSprayLevel) => 31..

-- Non-colorblind color constants transcribed from MapOverlayGenerator.lua (linear RGB, the [false]
-- variant -- see the plan's Context for the exact source lines this was read from). Converted to sRGB
-- hex on use via VDT.MapExporter.linearToSrgbHex, same as every other color this mod exports.
local GROWTH_GRADIENT_COLORS = {
  { 0.227, 0.5711, 0.0176 },
  { 0.1683, 0.4678, 0.0152 },
  { 0.1221, 0.3813, 0.013 },
  { 0.0823, 0.3006, 0.011 },
  { 0.0529, 0.2346, 0.0091 },
  { 0.0296, 0.1746, 0.0075 },
  { 0.0144, 0.1248, 0.006 },
  { 0.0048, 0.0844, 0.0048 },
}
local COLOR_HARVEST_READY = { 0.7758, 0.3095, 0.013 }
local COLOR_CUT = { 0.2647, 0.1038, 0.358 }
local COLOR_WITHERED = { 0.1441, 0.0452, 0.0123 }
local COLOR_TOPPING = { 0.7011, 0.0452, 0.0123 }
local COLOR_CULTIVATED = { 0.0967, 0.3758, 0.7084 }
local COLOR_STUBBLE_TILLAGE = { 0.1967, 0.4758, 0.3084 }
local COLOR_SEEDBED = { 0.0815, 0.6584, 0.4198 }
local COLOR_PLOWED = { 0.0908, 0.0467, 0.0865 }
local COLOR_NEEDS_PLOWING = { 0.6172, 0.051, 0.051 }
local COLOR_NEEDS_LIME = { 0.0815, 0.6584, 0.4198 }
local FERTILIZED_COLORS = {
  { 0.0595, 0.2086, 0.8227 },
  { 0.0091, 0.0931, 0.5841 },
  { 0.0018, 0.0382, 0.2961 },
}

-- Fixed-value legend entries: l10n key (mirrors MapOverlayGenerator.L10N_SYMBOL), an English fallback
-- for when g_i18n can't resolve it, and the color. Gradient/fertilized/weed/stone entries are built
-- separately since their color (and, for weed/stone, label) depends on sweep context.
local GROWTH_LABELS = {
  [GROWTH_CULTIVATED] = { key = "ui_growthMapCultivated", fallback = "Cultivated", color = COLOR_CULTIVATED },
  [GROWTH_STUBBLE_TILLAGE] = {
    key = "ui_growthMapStubbleTillage",
    fallback = "Stubble tillage",
    color = COLOR_STUBBLE_TILLAGE,
  },
  [GROWTH_SEEDBED] = { key = "ui_growthMapSeedbed", fallback = "Seedbed", color = COLOR_SEEDBED },
  [GROWTH_PLOWED] = { key = "ui_growthMapPlowed", fallback = "Plowed", color = COLOR_PLOWED },
  [GROWTH_TOPPING] = {
    key = "ui_growthMapReadyToPrepareForHarvest",
    fallback = "Ready to prepare",
    color = COLOR_TOPPING,
  },
  [GROWTH_HARVEST] = { key = "ui_growthMapReadyToHarvest", fallback = "Ready to harvest", color = COLOR_HARVEST_READY },
  [GROWTH_CUT] = { key = "ui_growthMapCut", fallback = "Harvested", color = COLOR_CUT },
  [GROWTH_WITHERED] = { key = "ui_growthMapWithered", fallback = "Withered", color = COLOR_WITHERED },
}
local SOIL_LABELS = {
  [SOIL_NEEDS_PLOWING] = { key = "ui_growthMapNeedsPlowing", fallback = "Needs plowing", color = COLOR_NEEDS_PLOWING },
  [SOIL_NEEDS_LIME] = { key = "ui_growthMapNeedsLime", fallback = "Needs lime", color = COLOR_NEEDS_LIME },
}

---sRGB hex for one of this module's linear-RGB triplet constants.
---@param color number[] {r, g, b}
---@return string
local function hex(color)
  return VDT.MapExporter.linearToSrgbHex(color[1], color[2], color[3])
end

---Localized text for an l10n key, or fallback when g_i18n is unavailable / doesn't know the key
---(getText echoes unknown keys back verbatim, which is treated the same as "unavailable").
---@param key string
---@param fallback string
---@return string
local function l10nText(key, fallback)
  if g_i18n ~= nil then
    local ok, text = pcall(g_i18n.getText, g_i18n, key)
    if ok and type(text) == "string" and text ~= "" and text ~= key then
      return text
    end
  end
  return fallback
end

-- Precomputed 2-char hex for every byte value, for encodeRow. Values are clamped into a byte (wraps
-- past 255) -- extremely unlikely to matter in practice (crops would need 256+ shownOnMap fruit types
-- on one map) but a wraparound is a wrong color, never a crash.
local HEX = {}
for i = 0, 255 do
  HEX[i] = string.format("%02x", i)
end

---Encode a row buffer (1-based, values 0..255) as a right-trimmed hex string; an all-zero row encodes
---as "" (the decoder zero-pads short/missing rows).
---@param buf number[]
---@param n number
---@return string
function VDT.MapLayers.encodeRow(buf, n)
  local last = 0
  for i = 1, n do
    if buf[i] ~= 0 then
      last = i
    end
  end
  if last == 0 then
    return ""
  end
  local parts = {}
  for i = 1, last do
    parts[i] = HEX[buf[i] % 256]
  end
  return table.concat(parts)
end

---Color groups for a weed/stone system: state -> 1-based group index, and group index -> color
---({r,g,b,a} table, not a Color object -- these come straight from XML VECTOR_4 parsing). nil when the
---system doesn't expose a states/color table (unavailable / malformed data).
---@param system table weedSystem or stoneSystem
---@return table<number, number>|nil stateToGroup
---@return table<number, number[]>|nil groupColors
local function buildColorGroups(system)
  local ok, mapColor = pcall(system.getColors, system)
  if not ok or type(mapColor) ~= "table" then
    return nil, nil
  end
  local stateToGroup = {}
  local groupColors = {}
  for k, data in ipairs(mapColor) do
    groupColors[k] = data.color
    if type(data.states) == "table" then
      for _, state in ipairs(data.states) do
        stateToGroup[state] = k
      end
    end
  end
  return stateToGroup, groupColors
end

---Growth-state bucket for one fruit cell, per MapOverlayGenerator:buildGrowthStateMapOverlay's
---per-state color assignment (gui/base/MapOverlayGenerator.lua:92-160): later writes overwrite earlier
---ones for the same density-state number, and by construction the growing/topping/harvest ranges are
---mutually exclusive, so a single first-match-wins pass here is faithful as long as witheredState /
---harvestTransitions don't numerically collide with those ranges -- true for real fruit data, not
---provable from static source alone. nil when the state isn't in any bucket (caller falls back to
---ground-type classification, matching "no shown fruit" behavior).
---@param desc table fruitTypeDesc
---@param growthState number
---@return number|nil
local function classifyGrowthFromFruit(desc, growthState)
  if type(desc.harvestTransitions) == "table" then
    for _, cutState in pairs(desc.harvestTransitions) do
      if cutState == growthState then
        return GROWTH_CUT
      end
    end
  end
  if desc.witheredState ~= nil and desc.witheredState == growthState then
    return GROWTH_WITHERED
  end
  if
    desc.minHarvestingGrowthState >= 0
    and growthState >= desc.minHarvestingGrowthState
    and growthState <= desc.maxHarvestingGrowthState
  then
    return GROWTH_HARVEST
  end
  if
    desc.minPreparingGrowthState >= 0
    and growthState >= desc.minPreparingGrowthState
    and growthState <= desc.maxPreparingGrowthState
  then
    return GROWTH_TOPPING
  end
  local maxGrowing = desc.minHarvestingGrowthState - 1
  if desc.minPreparingGrowthState >= 0 then
    maxGrowing = math.min(maxGrowing, desc.minPreparingGrowthState - 1)
  end
  if maxGrowing > 0 and growthState >= 1 and growthState <= maxGrowing then
    local index = math.max(math.floor(#GROWTH_GRADIENT_COLORS / maxGrowing * growthState), 1)
    return GROWTH_GRADIENT_BASE + index
  end
  return nil
end

---Growth-state fallback when no shown fruit applies at this cell: ground-type-only classification
---(MapOverlayGenerator's CULTIVATED/PLOWED/STUBBLE_TILLAGE/SEEDBED paint calls, which run unconditional
---of any fruit).
---@param ctx table sweep context
---@param groundTypeValue number raw FieldDensityMap.GROUND_TYPE value
---@return number
local function classifyGrowthFromGround(ctx, groundTypeValue)
  if groundTypeValue == ctx.cultivatedValue then
    return GROWTH_CULTIVATED
  end
  if groundTypeValue == ctx.plowedValue then
    return GROWTH_PLOWED
  end
  if groundTypeValue == ctx.stubbleTillageValue then
    return GROWTH_STUBBLE_TILLAGE
  end
  if groundTypeValue == ctx.seedbedValue or groundTypeValue == ctx.rolledSeedbedValue then
    return GROWTH_SEEDBED
  end
  return GROWTH_NONE
end

---Soil-state bucket for one cell, priority high->low: weeds > stones > needs-plowing > needs-lime >
---fertilized (MapOverlayGenerator:buildSoilStateMapOverlay's gating, lines 161-227). Weed/stone use the
---raw density STATE (not the "level" FieldState reads) since that's what the game's color groups key
---on.
---@param ctx table sweep context
---@param x number world x
---@param z number world z
---@param groundTypeValue number raw FieldDensityMap.GROUND_TYPE value
---@return number
local function classifySoil(ctx, x, z, groundTypeValue)
  local onField = groundTypeValue ~= nil and groundTypeValue ~= 0

  if ctx.weedAvailable then
    local ok, state = pcall(ctx.weedSystem.getWeedStateAtWorldPos, ctx.weedSystem, x, z)
    if ok and type(state) == "number" then
      local group = ctx.weedStateToGroup[state]
      if group ~= nil then
        return SOIL_WEED_BASE + group - 1
      end
    end
  end

  if ctx.stoneAvailable then
    local ok, state = pcall(ctx.stoneSystem.getStoneStateAtWorldPos, ctx.stoneSystem, x, z)
    if ok and type(state) == "number" then
      local group = ctx.stoneStateToGroup[state]
      if group ~= nil then
        return SOIL_STONE_BASE + group - 1
      end
    end
  end

  if onField and ctx.plowingRequiredEnabled then
    local plowLevel = ctx.fieldGroundSystem:getValueAtWorldPos(FieldDensityMap.PLOW_LEVEL, x, 0, z)
    if plowLevel == 0 then
      return SOIL_NEEDS_PLOWING
    end
  end

  if onField and ctx.limeRequired then
    local limeLevel = ctx.fieldGroundSystem:getValueAtWorldPos(FieldDensityMap.LIME_LEVEL, x, 0, z)
    if limeLevel == 0 then
      return SOIL_NEEDS_LIME
    end
  end

  if ctx.maxSprayLevel > 0 then
    local sprayLevel = ctx.fieldGroundSystem:getValueAtWorldPos(FieldDensityMap.SPRAY_LEVEL, x, 0, z)
    if type(sprayLevel) == "number" and sprayLevel >= 1 then
      return SOIL_FERTILIZED_BASE + sprayLevel
    end
  end

  return SOIL_NONE
end

---Fruit label for the crops legend: the fill type's title (matches MapOverlayGenerator's
---getDisplayCropTypes, minus its extra fruitType->fillTypeIndex->fillType hop -- FruitTypeDesc already
---holds the fillType directly), falling back to the fruit's internal name.
---@param desc table fruitTypeDesc
---@return string
local function fruitLabel(desc)
  local ok, title = pcall(function()
    return desc.fillType ~= nil and desc.fillType.title or nil
  end)
  if ok and type(title) == "string" and title ~= "" then
    return title
  end
  return desc.name or "?"
end

---Fruit color for the crops legend: defaultMapColor is a Color object (unlike this module's own
---{r,g,b} constants), so it needs :unpack() rather than index access.
---@param desc table fruitTypeDesc
---@return string|nil
local function fruitColorHex(desc)
  local ok, r, g, b = pcall(function()
    return desc.defaultMapColor:unpack()
  end)
  if ok and type(r) == "number" then
    return VDT.MapExporter.linearToSrgbHex(r, g, b)
  end
  return nil
end

---Legend entry for a growth wire value: the gradient range shares one label/color-per-step, everything
---else is a fixed lookup.
---@param value number
---@return table
local function growthLegendEntry(value)
  if value > GROWTH_GRADIENT_BASE and value <= GROWTH_GRADIENT_BASE + #GROWTH_GRADIENT_COLORS then
    local index = value - GROWTH_GRADIENT_BASE
    return { v = value, label = l10nText("ui_growthMapGrowing", "Growing"), color = hex(GROWTH_GRADIENT_COLORS[index]) }
  end
  local entry = GROWTH_LABELS[value]
  if entry == nil then
    return { v = value, label = "?" }
  end
  return { v = value, label = l10nText(entry.key, entry.fallback), color = hex(entry.color) }
end

---Legend entry for a soil wire value: weed/stone groups take their label from the system's title
---(suffixed with the group number -- the game itself only has one description per system, not per
---severity), fertilized levels share one label, everything else is a fixed lookup.
---@param ctx table sweep context
---@param value number
---@return table
local function soilLegendEntry(ctx, value)
  if value >= SOIL_WEED_BASE and value < SOIL_STONE_BASE then
    local group = value - SOIL_WEED_BASE + 1
    local color = ctx.weedGroupColors and ctx.weedGroupColors[group]
    return {
      v = value,
      label = ctx.weedTitle .. " " .. group,
      color = color and VDT.MapExporter.linearToSrgbHex(color[1], color[2], color[3]) or nil,
    }
  end
  if value >= SOIL_STONE_BASE and value < SOIL_NEEDS_PLOWING then
    local group = value - SOIL_STONE_BASE + 1
    local color = ctx.stoneGroupColors and ctx.stoneGroupColors[group]
    return {
      v = value,
      label = ctx.stoneTitle .. " " .. group,
      color = color and VDT.MapExporter.linearToSrgbHex(color[1], color[2], color[3]) or nil,
    }
  end
  if value >= SOIL_FERTILIZED_BASE then
    local level = value - SOIL_FERTILIZED_BASE
    local color = FERTILIZED_COLORS[math.min(level, #FERTILIZED_COLORS)]
    return { v = value, label = l10nText("ui_growthMapFertilized", "Fertilized"), color = hex(color) }
  end
  local entry = SOIL_LABELS[value]
  if entry == nil then
    return { v = value, label = "?" }
  end
  return { v = value, label = l10nText(entry.key, entry.fallback), color = hex(entry.color) }
end

---Classify one world cell into (crops, growth, soil) wire values, and record any newly-seen legend
---entries into ctx's seen-maps. Fruit lookups are guarded: an unavailable/nil dataPlaneId or manager
---degrades to ground-type-only classification, never an error.
---@param ctx table sweep context
---@param x number world x
---@param z number world z
---@return number cropsV, number growthV, number soilV
function VDT.MapLayers.classifyCell(ctx, x, z)
  local groundTypeValue = ctx.fieldGroundSystem:getValueAtWorldPos(FieldDensityMap.GROUND_TYPE, x, 0, z)

  local cropsV = 0
  local growthV = nil
  local desc = nil
  if ctx.dataPlaneId ~= nil then
    local densityTypeIndex = getDensityTypeIndexAtWorldPos(ctx.dataPlaneId, x, 0, z)
    desc = g_fruitTypeManager:getFruitTypeByDensityTypeIndex(densityTypeIndex)
  end
  if desc ~= nil and desc.shownOnMap then
    cropsV = desc.index or 0
    local state = getDensityStatesAtWorldPos(ctx.dataPlaneId, x, 0, z)
    local growthState = desc:getGrowthStateByDensityState(state)
    growthV = classifyGrowthFromFruit(desc, growthState)
  end
  if growthV == nil then
    growthV = classifyGrowthFromGround(ctx, groundTypeValue)
  end

  local soilV = classifySoil(ctx, x, z, groundTypeValue)

  if cropsV ~= 0 and ctx.cropsSeen[cropsV] == nil then
    ctx.cropsSeen[cropsV] = { v = cropsV, label = fruitLabel(desc), color = fruitColorHex(desc) }
  end
  if growthV ~= GROWTH_NONE and ctx.growthSeen[growthV] == nil then
    ctx.growthSeen[growthV] = growthLegendEntry(growthV)
  end
  if soilV ~= SOIL_NONE and ctx.soilSeen[soilV] == nil then
    ctx.soilSeen[soilV] = soilLegendEntry(ctx, soilV)
  end

  return cropsV, growthV, soilV
end

VDT.MapLayers.isAvailable = function()
  return g_currentMission ~= nil
    and g_currentMission.isMissionStarted == true
    and g_currentMission.fieldGroundSystem ~= nil
    and g_fruitTypeManager ~= nil
end

---Snapshot everything a sweep needs once, up front: world size, ground-type values, weed/stone
---availability + color groups, spray-level cap. nil when the world size can't be resolved yet (the
---caller retries next tick).
---@return table|nil
local function startSweep()
  local sizeX, sizeZ = VDT.MapExporter.resolveWorldSize()
  if sizeX == nil then
    return nil
  end

  local mission = g_currentMission
  local fieldGroundSystem = mission.fieldGroundSystem
  local missionInfo = mission.missionInfo or {}

  local weedSystem = mission.weedSystem
  local weedAvailable = weedSystem ~= nil and missionInfo.weedsEnabled == true
  if weedAvailable then
    local ok, hasWeed = pcall(weedSystem.getMapHasWeed, weedSystem)
    weedAvailable = ok and hasWeed == true
  end
  local weedStateToGroup, weedGroupColors
  local weedTitle = "Weeds"
  if weedAvailable then
    weedStateToGroup, weedGroupColors = buildColorGroups(weedSystem)
    weedAvailable = weedStateToGroup ~= nil
    local ok, title = pcall(weedSystem.getTitle, weedSystem)
    if ok and type(title) == "string" and title ~= "" then
      weedTitle = title
    end
  end

  local stoneSystem = mission.stoneSystem
  local stoneAvailable = stoneSystem ~= nil and missionInfo.stonesEnabled == true
  if stoneAvailable then
    local ok, hasStones = pcall(stoneSystem.getMapHasStones, stoneSystem)
    stoneAvailable = ok and hasStones == true
  end
  local stoneStateToGroup, stoneGroupColors
  local stoneTitle = "Stones"
  if stoneAvailable then
    stoneStateToGroup, stoneGroupColors = buildColorGroups(stoneSystem)
    stoneAvailable = stoneStateToGroup ~= nil
    local ok, title = pcall(stoneSystem.getTitle, stoneSystem)
    if ok and type(title) == "string" and title ~= "" then
      stoneTitle = title
    end
  end

  local maxSprayLevel = 0
  local ok, value = pcall(fieldGroundSystem.getMaxValue, fieldGroundSystem, FieldDensityMap.SPRAY_LEVEL)
  if ok and type(value) == "number" then
    maxSprayLevel = value
  end

  return {
    sizeX = sizeX,
    sizeZ = sizeZ,
    cellSize = sizeX / VDT.MapLayers.GRID_SIZE,
    row = 0,
    col = 0,
    dataPlaneId = g_fruitTypeManager:getDefaultDataPlaneId(),
    fieldGroundSystem = fieldGroundSystem,
    cultivatedValue = FieldGroundType.getValueByType(FieldGroundType.CULTIVATED),
    plowedValue = FieldGroundType.getValueByType(FieldGroundType.PLOWED),
    stubbleTillageValue = FieldGroundType.getValueByType(FieldGroundType.STUBBLE_TILLAGE),
    seedbedValue = FieldGroundType.getValueByType(FieldGroundType.SEEDBED),
    rolledSeedbedValue = FieldGroundType.getValueByType(FieldGroundType.ROLLED_SEEDBED),
    plowingRequiredEnabled = missionInfo.plowingRequiredEnabled == true,
    limeRequired = missionInfo.limeRequired == true,
    maxSprayLevel = maxSprayLevel,
    weedSystem = weedSystem,
    weedAvailable = weedAvailable,
    weedStateToGroup = weedStateToGroup,
    weedGroupColors = weedGroupColors,
    weedTitle = weedTitle,
    stoneSystem = stoneSystem,
    stoneAvailable = stoneAvailable,
    stoneStateToGroup = stoneStateToGroup,
    stoneGroupColors = stoneGroupColors,
    stoneTitle = stoneTitle,
    cropsBuf = {},
    growthBuf = {},
    soilBuf = {},
    cropsRows = {},
    growthRows = {},
    soilRows = {},
    cropsSeen = {},
    growthSeen = {},
    soilSeen = {},
  }
end

---Run up to `budget` cells of the current sweep, encoding each row as it completes.
---@param ctx table sweep context
---@param budget number
---@return boolean done sweep has covered every row
local function runBatch(ctx, budget)
  local gridSize = VDT.MapLayers.GRID_SIZE
  local halfX = ctx.sizeX / 2
  local halfZ = ctx.sizeZ / 2
  local row, col = ctx.row, ctx.col

  for _ = 1, budget do
    if row >= gridSize then
      break
    end

    local worldX = -halfX + (col + 0.5) * ctx.cellSize
    local worldZ = -halfZ + (row + 0.5) * ctx.cellSize
    local cropsV, growthV, soilV = VDT.MapLayers.classifyCell(ctx, worldX, worldZ)
    ctx.cropsBuf[col + 1] = cropsV
    ctx.growthBuf[col + 1] = growthV
    ctx.soilBuf[col + 1] = soilV

    col = col + 1
    if col >= gridSize then
      ctx.cropsRows[row + 1] = VDT.MapLayers.encodeRow(ctx.cropsBuf, gridSize)
      ctx.growthRows[row + 1] = VDT.MapLayers.encodeRow(ctx.growthBuf, gridSize)
      ctx.soilRows[row + 1] = VDT.MapLayers.encodeRow(ctx.soilBuf, gridSize)
      col = 0
      row = row + 1
    end
  end

  ctx.row, ctx.col = row, col
  return row >= gridSize
end

---Sort a seen-map (value -> legend entry) into a legend list by v, or nil when empty (Json.lua can't
---distinguish {} from an empty array -- see MapExporter.lua's collect()).
---@param seen table<number, table>
---@return table[]|nil
local function toLegend(seen)
  local list = {}
  for _, entry in pairs(seen) do
    list[#list + 1] = entry
  end
  table.sort(list, function(a, b)
    return a.v < b.v
  end)
  return #list > 0 and list or nil
end

local function finishSweep(ctx)
  VDT.MapLayers.model = {
    version = tostring(VDT.MapLayers.VERSION),
    terrainSize = g_currentMission.terrainSize or ctx.sizeX,
    gridSize = VDT.MapLayers.GRID_SIZE,
    layers = {
      { id = "crops", legend = toLegend(ctx.cropsSeen), rows = ctx.cropsRows },
      { id = "growth", legend = toLegend(ctx.growthSeen), rows = ctx.growthRows },
      { id = "soil", legend = toLegend(ctx.soilSeen), rows = ctx.soilRows },
    },
  }
  VDT.ExportChannels.markDirty(VDT.MapLayers.CHANNEL)
end

---Advance the current sweep (or start one after the pause), one CELLS_PER_FRAME batch per tick. Gated
---on export being enabled -- tick still runs while export is off (ExportChannels.tick has no such
---gate), so this bails early rather than burning CPU on a sweep whose result is never written.
---@param debugger GrisuDebug
---@param dt number? frame delta in ms
function VDT.MapLayers.tick(debugger, dt)
  if type(dt) ~= "number" then
    return
  end
  if g_vdTelemetry == nil or g_vdTelemetry.exportEnabled ~= true then
    return
  end
  if not VDT.MapLayers.isAvailable() then
    return
  end

  if VDT.MapLayers.sweep == nil then
    if VDT.MapLayers.pauseMs < VDT.MapLayers.SWEEP_PAUSE_MS then
      VDT.MapLayers.pauseMs = VDT.MapLayers.pauseMs + dt
      return
    end
    VDT.MapLayers.sweep = startSweep()
    if VDT.MapLayers.sweep == nil then
      return -- world size not resolvable yet; retry next frame
    end
  end

  local ok, doneOrErr = pcall(runBatch, VDT.MapLayers.sweep, VDT.MapLayers.CELLS_PER_FRAME)
  if not ok then
    debugger:error("mapLayers channel: sweep batch failed (%s)", tostring(doneOrErr))
    VDT.MapLayers.sweep = nil
    VDT.MapLayers.pauseMs = 0
    return
  end
  if doneOrErr then
    finishSweep(VDT.MapLayers.sweep)
    VDT.MapLayers.sweep = nil
    VDT.MapLayers.pauseMs = 0
  end
end

---@return table|nil the last completed sweep's model, nil before the first sweep finishes
function VDT.MapLayers.collect()
  return VDT.MapLayers.model
end

-- Self-register the channel (see ExportChannels).
VDT.ExportChannels.register({
  name = VDT.MapLayers.CHANNEL,
  fileName = VDT.MapLayers.FILE_NAME,
  isAvailable = VDT.MapLayers.isAvailable,
  collect = VDT.MapLayers.collect,
  tick = VDT.MapLayers.tick,
})
