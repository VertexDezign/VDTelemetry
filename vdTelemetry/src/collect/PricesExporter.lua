-- Prices export channel: the map's price board, written to prices.json on its OWN interval -- what
-- every station pays for each fill type, what the ones that sell to you charge, and the twelve-month
-- price curve behind each commodity. It is the data the game's own "Prices" table draws
-- (InGameMenuStatisticsFrame), collected once instead of read station by station in a menu.
--
-- NOT farm-scoped, and deliberately holds NO fill levels. A price is the same number for every farm,
-- and what the local farm actually owns is already exported by the storage / husbandry / production
-- channels -- valuing stock against this board is the app's join, not a second stock walk (issue
-- #112; the overview that consumes it is #118).
--
-- Reads only base-game state (g_currentMission.storageSystem + economyManager), so it lives in
-- collect/, not integrations/. Every engine read is pcall-guarded (fail-soft house rule): a station
-- that throws is dropped, a bad row skipped, and writeDirty()'s pcall contains the rest.
--
-- MULTIPLAYER. Everything here works on a client, because the game already syncs it: a
-- SellingStation writes its EFFECTIVE price and its trend bits into the client's fillTypePrices /
-- fillTypePriceInfo every 30 s, and getEffectiveFillTypePrice simply returns that stored value when
-- it is not the server (it recomputes the seasonal factor only server-side). Great demands arrive by
-- GreatDemandsEvent on the in-game hour. The channel's 30 s cadence is chosen to match that sync
-- interval -- a client cannot be fresher than the game makes it, and the server has nothing slower
-- to report.
--
-- PRICE UNIT: per 1000 litres, difficulty multiplier included -- the unit the game prints (its menu
-- multiplies the per-litre figure by 1000). Pallet prices are per whole pallet. See PricesModel.lua.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.PricesExporter = {}

VDT.PricesExporter.CHANNEL = "prices"
VDT.PricesExporter.FILE_NAME = "prices.json"
-- Own version, evolving independently of VDTelemetry.VERSION and the shared Kotlin PricesData.
VDT.PricesExporter.VERSION = 1
-- Write cadence in ms. Matches SellingStation.priceSyncTimerDuration (30 s), the interval the game
-- itself refreshes a multiplayer client's prices on -- see the MULTIPLAYER note above.
VDT.PricesExporter.INTERVAL_MS = 30000

-- Bit positions inside SellingStation's fillTypePriceInfo mask. Taken from the game's own constants
-- when they are reachable, with the shipped values as the fallback so a spec (and a Giants rename)
-- still gets sane tokens rather than an error.
local FALLBACK_BITS = { falling = 1, climbing = 2, greatDemand = 5 }

local function priceInfoBit(name)
  local station = SellingStation
  if type(station) == "table" then
    local key = name == "falling" and "PRICE_FALLING"
      or (name == "climbing" and "PRICE_CLIMBING" or "PRICE_GREAT_DEMAND")
    if type(station[key]) == "number" then
      return station[key]
    end
  end
  return FALLBACK_BITS[name]
end

-- Bit test without bit32: the mask is a small non-negative integer, so integer division is enough
-- (and keeps the module runnable under plain Lua in the specs).
local function isBitSet(mask, bit)
  if type(mask) ~= "number" or mask < 0 then
    return false
  end
  return math.floor(mask / 2 ^ bit) % 2 == 1
end

local function num(v)
  return type(v) == "number" and v or 0
end

-- Currency rounded to two decimals -- enough for the cheapest fill types (water is single digits per
-- 1000 l) without carrying float noise into the json.
local function round2(v)
  return math.floor(num(v) * 100 + 0.5) / 100
end

-- Per-litre price -> the exported per-1000-litre figure.
local function per1000(v)
  return round2(num(v) * 1000)
end

---The economic-difficulty factor the game folds into every price (EconomyManager.PRICE_MULTIPLIER
---indexed by the save's economicDifficulty). 1 when it can't be read, which is also its "hard"
---value -- the honest fallback, since an invented multiplier would silently rescale the whole board.
---@return number
function VDT.PricesExporter.priceMultiplier()
  if EconomyManager == nil or type(EconomyManager.getPriceMultiplier) ~= "function" then
    return 1
  end
  local ok, value = pcall(EconomyManager.getPriceMultiplier)
  if ok and type(value) == "number" and value > 0 then
    return value
  end
  return 1
end

---Wire token for a station's pricing trend, from the fillTypePriceInfo bitmask. Great demand is a
---bit in the same mask but is NOT a trend -- it rides on the row as its own field, so a commodity in
---great demand still reports whether it is climbing or falling.
---@param mask number|nil SellingStation:getCurrentPricingTrend(fillType)
---@return string "climbing"|"falling"|"steady"
function VDT.PricesExporter.trendToken(mask)
  if isBitSet(mask, priceInfoBit("climbing")) then
    return "climbing"
  end
  if isBitSet(mask, priceInfoBit("falling")) then
    return "falling"
  end
  return "steady"
end

-- The running great demands, indexed station -> fillTypeIndex -> { multiplier, hoursLeft }. Read off
-- economyManager.greatDemands (max three, and synced to clients by GreatDemandsEvent). demandDuration
-- is decremented once per in-game hour while the demand runs, so it IS the hours remaining.
local function collectDemands()
  local index = {}
  local manager = g_currentMission ~= nil and g_currentMission.economyManager or nil
  local demands = manager ~= nil and manager.greatDemands or nil
  if type(demands) ~= "table" then
    return index
  end
  for _, demand in pairs(demands) do
    if type(demand) == "table" and demand.isValid and demand.isRunning and demand.sellStation ~= nil then
      local byFillType = index[demand.sellStation]
      if byFillType == nil then
        byFillType = {}
        index[demand.sellStation] = byFillType
      end
      byFillType[demand.fillTypeIndex] = {
        multiplier = round2(demand.demandMultiplier),
        hoursLeft = math.max(math.floor(num(demand.demandDuration)), 0),
      }
    end
  end
  return index
end

-- A station is a selling point if it says so. isSellingPoint is set in SellingStation.new, so it
-- covers every subclass a map or mod defines; the isa() test is the belt-and-braces path for a
-- station that reached us some other way.
local function isSellingStation(station)
  if type(station) ~= "table" then
    return false
  end
  if station.isSellingPoint == true then
    return true
  end
  if type(station.isa) == "function" and SellingStation ~= nil then
    local ok, result = pcall(station.isa, station, SellingStation)
    return ok and result == true
  end
  return false
end

-- Same duck-typing for the buy side: fillTypePricesScale is built in BuyingStation:load and is what
-- its getEffectiveFillTypePrice reads, so a station that has it can be priced.
local function isBuyingStation(station)
  if type(station) ~= "table" then
    return false
  end
  if type(station.fillTypePricesScale) == "table" then
    return true
  end
  if type(station.isa) == "function" and BuyingStation ~= nil then
    local ok, result = pcall(station.isa, station, BuyingStation)
    return ok and result == true
  end
  return false
end

-- A station's display name, falling back to its placeable's. Both are pcall'd: getName() on a
-- selling station reaches through to the placeable, which a half-deleted one may no longer have.
local function stationName(station, placeable)
  if station ~= nil and type(station.getName) == "function" then
    local ok, name = pcall(station.getName, station)
    if ok and type(name) == "string" and name ~= "" then
      return name
    end
  end
  if placeable ~= nil and type(placeable.getName) == "function" then
    local ok, name = pcall(placeable.getName, placeable)
    if ok and type(name) == "string" and name ~= "" then
      return name
    end
  end
  return nil
end

-- The station's marker in the same normalized [0,1] frame as map.json, so the app can put it on the
-- overlay it already draws. The placeable's first hotspot is what the game's own prices list uses
-- for its distance column; the root node is the fallback for a placeable that has no hotspot.
---@return number|nil posX, number|nil posZ
local function stationPos(placeable, sizeX, sizeZ)
  if placeable == nil or sizeX == nil or sizeZ == nil then
    return nil, nil
  end
  if type(placeable.getHotspot) == "function" then
    local okHotspot, hotspot = pcall(placeable.getHotspot, placeable, 1)
    if okHotspot and hotspot ~= nil and type(hotspot.getWorldPosition) == "function" then
      local okPos, worldX, worldZ = pcall(hotspot.getWorldPosition, hotspot)
      if okPos and type(worldX) == "number" and type(worldZ) == "number" then
        return VDT.MapExporter.normalizeCoord(worldX, sizeX), VDT.MapExporter.normalizeCoord(worldZ, sizeZ)
      end
    end
  end
  if type(placeable.rootNode) == "number" then
    local okNode, worldX, _, worldZ = pcall(getWorldTranslation, placeable.rootNode)
    if okNode and type(worldX) == "number" and type(worldZ) == "number" then
      return VDT.MapExporter.normalizeCoord(worldX, sizeX), VDT.MapExporter.normalizeCoord(worldZ, sizeZ)
    end
  end
  return nil, nil
end

-- Sort a row list by fill type name: pairs() over the game's fill-type sets has no order, and a file
-- whose rows shuffle every write would make every diff (and every app animation) meaningless.
local function sortRows(rows)
  table.sort(rows, function(a, b)
    return a.type < b.type
  end)
  return rows
end

-- The catalogue entry for one fill type: the commodity's own properties, listed once no matter how
-- many stations name it. months[] is fillType.economy.history -- the running per-period average the
-- game's own fluctuation graph plots, seeded from the seasonal factors -- NOT a static curve.
---@param fillType table a FillTypeDesc
---@param multiplier number the economic-difficulty factor
---@return PricesFillTypeModel
local function fillTypeRow(fillType, multiplier)
  ---@type PricesFillTypeModel
  local row = {
    type = fillType.name,
    title = type(fillType.title) == "string" and fillType.title or fillType.name,
    basePrice = per1000(num(fillType.pricePerLiter) * multiplier),
    showOnPriceTable = fillType.showOnPriceTable == true or nil,
  }

  if g_fruitTypeManager ~= nil and type(g_fruitTypeManager.getFruitTypeIndexByFillTypeIndex) == "function" then
    local ok, fruitIndex =
      pcall(g_fruitTypeManager.getFruitTypeIndexByFillTypeIndex, g_fruitTypeManager, fillType.index)
    row.isCrop = (ok and fruitIndex ~= nil) or nil
  end

  local history = type(fillType.economy) == "table" and fillType.economy.history or nil
  if type(history) == "table" then
    local months, bestMonth, bestPrice = {}, nil, nil
    for period = 1, 12 do
      local price = per1000(num(history[period]) * multiplier)
      months[period] = price
      if bestPrice == nil or price > bestPrice then
        bestMonth, bestPrice = period, price
      end
    end
    -- Only publish the curve when the game actually filled it in; an all-zero history is a fill type
    -- with no economy, and a "best month" picked out of twelve zeroes is a lie the app would print.
    if bestPrice ~= nil and bestPrice > 0 then
      row.months, row.bestMonth, row.bestPrice = months, bestMonth, bestPrice
    end
  end

  return row
end

-- Channel is available once the storage system exists (map loaded) -- that is where every station
-- registers itself, on the server and on a client alike.
function VDT.PricesExporter.isAvailable()
  return g_currentMission ~= nil and g_currentMission.storageSystem ~= nil and g_fillTypeManager ~= nil
end

---Build the prices model, or nil when the storage system isn't up yet (skips the write).
---@return PricesModel|nil
function VDT.PricesExporter.collect()
  if not VDT.PricesExporter.isAvailable() then
    return nil
  end

  local system = g_currentMission.storageSystem
  local multiplier = VDT.PricesExporter.priceMultiplier()
  local sizeX, sizeZ = VDT.MapExporter.resolveWorldSize()
  local demands = collectDemands()

  local stations = {} -- in discovery order; sorted by name at the end
  local byKey = {} -- owning placeable (or the station itself) -> its entry, so one placeable is one row
  local usedFillTypes = {} -- fillTypeIndex -> true, for the catalogue

  -- One row per PLACEABLE, not per station: a shop that both buys grain and sells seed is two
  -- stations on one building, and the game's own table shows it once with a sell and a buy column.
  local function entryFor(placeable, station, name)
    local key = placeable or station
    local entry = byKey[key]
    if entry == nil then
      entry = {
        id = VDT.ProductionExporter.placeableId(placeable, "station" .. (#stations + 1)),
        name = name or "Station",
      }
      entry.posX, entry.posZ = stationPos(placeable, sizeX, sizeZ)
      byKey[key] = entry
      stations[#stations + 1] = entry
    elseif entry.name == "Station" and name ~= nil then
      entry.name = name
    end
    if station ~= nil then
      entry.isTrainStation = station.isTrainStation == true or entry.isTrainStation
      entry.isPalletStation = station.isPalletStation == true or entry.isPalletStation
    end
    return entry
  end

  local function markUsed(fillTypeIndex)
    usedFillTypes[fillTypeIndex] = true
  end

  -- Rows are APPENDED, never assigned: grouping per placeable is our choice, and a placeable type is
  -- just a list of specializations, so a map is free to build one out of two selling-station (or two
  -- buying-station) components. The storage system then hands us both, pointing at one
  -- owningPlaceable, and the second one's rows have to join the first's rather than replace them --
  -- which also means the sort has to wait until every pass below has contributed.
  local function appendRows(entry, field, rows)
    local existing = entry[field]
    if existing == nil then
      entry[field] = rows
      return
    end
    for _, row in ipairs(rows) do
      existing[#existing + 1] = row
    end
  end

  -- Sell side: every selling station the prices menu would list. hideFromPricesMenu is the map's own
  -- opt-out (mission-only tip triggers set it) and is honoured here for the same reason the game
  -- honours it -- those are not places a player can choose to sell at.
  local okUnloading, unloadingStations = pcall(system.getUnloadingStations, system)
  if okUnloading and type(unloadingStations) == "table" then
    for _, station in pairs(unloadingStations) do
      if isSellingStation(station) and station.hideFromPricesMenu ~= true then
        local placeable = station.owningPlaceable
        local rows = {}
        local stationDemands = demands[station] or {}
        for fillTypeIndex in pairs(station.acceptedFillTypes or {}) do
          local fillType = g_fillTypeManager:getFillTypeByIndex(fillTypeIndex)
          -- Guard the price lookup rather than let it fail: getEffectiveFillTypePrice logs and prints
          -- a callstack for a fill type it has no entry for, and this runs every 30 s forever.
          local prices = station.fillTypePrices
          if fillType ~= nil and type(prices) == "table" and prices[fillTypeIndex] ~= nil then
            local okPrice, price = pcall(station.getEffectiveFillTypePrice, station, fillTypeIndex)
            if okPrice and type(price) == "number" then
              local okTrend, trend = pcall(station.getCurrentPricingTrend, station, fillTypeIndex)
              ---@type PricesSellModel
              local row = {
                type = fillType.name,
                price = per1000(price),
                trend = VDT.PricesExporter.trendToken(okTrend and trend or nil),
              }
              local demand = stationDemands[fillTypeIndex]
              if demand ~= nil or isBitSet(okTrend and trend or nil, priceInfoBit("greatDemand")) then
                row.greatDemand = true
                if demand ~= nil then
                  row.demandMultiplier = demand.multiplier
                  row.demandHoursLeft = demand.hoursLeft
                end
              end
              rows[#rows + 1] = row
              markUsed(fillTypeIndex)
            end
          end
        end
        if #rows > 0 then
          appendRows(entryFor(placeable, station, stationName(station, placeable)), "sell", rows)
        end
      end
    end
  end

  -- Buy side by the litre: fuel, seed, fertilizer, lime, water. Its price has no dynamics -- it is
  -- the fill type's base price times the station's scale times the difficulty multiplier -- so there
  -- is no trend to report and none is invented.
  local okLoading, loadingStations = pcall(system.getLoadingStations, system)
  if okLoading and type(loadingStations) == "table" then
    for _, station in pairs(loadingStations) do
      if isBuyingStation(station) then
        local placeable = station.owningPlaceable
        local rows = {}
        for fillTypeIndex in pairs(station.supportedFillTypes or {}) do
          local fillType = g_fillTypeManager:getFillTypeByIndex(fillTypeIndex)
          if fillType ~= nil then
            local okPrice, price = pcall(station.getEffectiveFillTypePrice, station, fillTypeIndex)
            if okPrice and type(price) == "number" and price > 0 then
              rows[#rows + 1] = { type = fillType.name, price = per1000(price) }
              markUsed(fillTypeIndex)
            end
          end
        end
        if #rows > 0 then
          appendRows(entryFor(placeable, station, stationName(station, placeable)), "buy", rows)
        end
      end
    end
  end

  -- Buy side by the pallet: the counter shops. Their price is for a WHOLE pallet and already carries
  -- the difficulty multiplier (the spec bakes it in at load), so it is passed through unscaled.
  local okPallets, palletStations = pcall(system.getPalletBuyingStations, system)
  if okPallets and type(palletStations) == "table" then
    for _, placeable in pairs(palletStations) do
      local spec = type(placeable) == "table" and placeable.spec_palletBuyingStation or nil
      local pallets = spec ~= nil and spec.pallets or nil
      if type(pallets) == "table" then
        local rows = {}
        for _, pallet in ipairs(pallets) do
          local fillType = g_fillTypeManager:getFillTypeByIndex(pallet.fillTypeIndex)
          if fillType ~= nil and type(pallet.price) == "number" and pallet.price > 0 then
            rows[#rows + 1] = { type = fillType.name, price = round2(pallet.price) }
            markUsed(pallet.fillTypeIndex)
          end
        end
        if #rows > 0 then
          local entry = entryFor(placeable, nil, stationName(nil, placeable))
          appendRows(entry, "pallets", rows)
          entry.isPalletStation = true
        end
      end
    end
  end

  -- Sorted here rather than in the passes above, because a station's list is only complete once all
  -- three have run (see appendRows).
  for _, entry in ipairs(stations) do
    for _, field in ipairs({ "sell", "buy", "pallets" }) do
      if entry[field] ~= nil then
        sortRows(entry[field])
      end
    end
  end

  -- The catalogue: one entry per fill type any row above named, so every row joins to exactly one.
  local fillTypes = {}
  for fillTypeIndex in pairs(usedFillTypes) do
    local fillType = g_fillTypeManager:getFillTypeByIndex(fillTypeIndex)
    if fillType ~= nil and type(fillType.name) == "string" then
      fillTypes[#fillTypes + 1] = fillTypeRow(fillType, multiplier)
    end
  end
  sortRows(fillTypes)

  table.sort(stations, function(a, b)
    if a.name == b.name then
      return a.id < b.id
    end
    return a.name < b.name
  end)

  local environment = g_currentMission.environment
  local period = type(environment) == "table" and environment.currentPeriod or nil

  return {
    version = tostring(VDT.PricesExporter.VERSION),
    period = type(period) == "number" and math.floor(period) or nil,
    priceMultiplier = round2(multiplier),
    -- omit empty arrays: the Json encoder emits {} for an empty table, so a nil keeps the key absent
    -- and the Kotlin model falls back to emptyList() (see MapExporter / ProductionExporter).
    fillTypes = #fillTypes > 0 and fillTypes or nil,
    stations = #stations > 0 and stations or nil,
  }
end

-- Self-register the channel (see ExportChannels). Interval-driven and NOT farmScoped: the board reads
-- the same for every farm, so a farm switch changes nothing in this file.
VDT.ExportChannels.register({
  name = VDT.PricesExporter.CHANNEL,
  fileName = VDT.PricesExporter.FILE_NAME,
  isAvailable = VDT.PricesExporter.isAvailable,
  collect = VDT.PricesExporter.collect,
  intervalMs = VDT.PricesExporter.INTERVAL_MS,
})
