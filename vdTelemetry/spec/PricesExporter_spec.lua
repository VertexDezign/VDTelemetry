-- Unit tests for the prices export channel (src/collect/PricesExporter.lua).
--
-- Run with `busted` from the vdTelemetry/ directory. The collector reads FS globals
-- (g_currentMission.storageSystem / economyManager / g_fillTypeManager / SellingStation), so the
-- tests stub just enough of those to drive collect() offline. ExportChannels must exist first -- the
-- collector calls register() at load time -- and MapExporter/ProductionExporter supply the
-- normalization and id helpers it reuses.

if VDT == nil or VDT.ExportChannels == nil then
  dofile("src/export/ExportChannels.lua")
end
if VDT.MapExporter == nil then
  dofile("src/collect/MapExporter.lua")
end
if VDT.Farm == nil then
  dofile("src/utils/Farm.lua")
end
if VDT.ProductionExporter == nil then
  dofile("src/collect/ProductionExporter.lua")
end
if VDT.PricesExporter == nil then
  dofile("src/collect/PricesExporter.lua")
end

-- Fill-type table keyed by index; getFillTypeByIndex mimics g_fillTypeManager. `economy.history` is
-- the twelve-period running average the game keeps -- the curve the export publishes.
local FILL_TYPES = {
  [1] = {
    index = 1,
    name = "WHEAT",
    title = "Wheat",
    pricePerLiter = 0.9,
    showOnPriceTable = true,
    economy = { history = { 0.88, 0.91, 1.01, 1.08, 1.12, 0.96, 0.78, 0.7, 0.72, 0.8, 0.85, 0.87 } },
  },
  [2] = {
    index = 2,
    name = "DIESEL",
    title = "Diesel",
    pricePerLiter = 1.85,
    showOnPriceTable = false,
    economy = { history = {} },
  },
  [3] = {
    index = 3,
    name = "FERTILIZER",
    title = "Fertilizer",
    pricePerLiter = 1.2,
    showOnPriceTable = true,
    economy = { history = {} },
  },
}

-- fillTypePriceInfo bit positions, as the game defines them.
local BIT_FALLING, BIT_CLIMBING, BIT_GREAT_DEMAND = 1, 2, 5
local function mask(...)
  local value = 0
  for _, bit in ipairs({ ... }) do
    value = value + 2 ^ bit
  end
  return value
end

local function makeSellingStation(opts)
  local station = {
    isSellingPoint = true,
    owningPlaceable = opts.placeable,
    acceptedFillTypes = opts.accepted or {},
    fillTypePrices = opts.prices or {},
    _info = opts.info or {},
    _name = opts.name,
    isTrainStation = opts.isTrainStation,
    hideFromPricesMenu = opts.hidden,
  }
  function station:getName()
    return self._name
  end
  function station:getEffectiveFillTypePrice(fillType)
    return self.fillTypePrices[fillType]
  end
  function station:getCurrentPricingTrend(fillType)
    return self._info[fillType]
  end
  return station
end

local function makeBuyingStation(opts)
  local station = {
    fillTypePricesScale = opts.scales or {},
    owningPlaceable = opts.placeable,
    supportedFillTypes = opts.supported or {},
    _prices = opts.prices or {},
    _name = opts.name,
  }
  function station:getName()
    return self._name
  end
  function station:getEffectiveFillTypePrice(fillType)
    return self._prices[fillType]
  end
  return station
end

local function makePalletStation(name, pallets)
  return {
    uniqueId = "pallets-1",
    spec_palletBuyingStation = { pallets = pallets },
    getName = function()
      return name
    end,
  }
end

local function setupWorld(opts)
  opts = opts or {}
  _G.SellingStation =
    { PRICE_FALLING = BIT_FALLING, PRICE_CLIMBING = BIT_CLIMBING, PRICE_GREAT_DEMAND = BIT_GREAT_DEMAND }
  _G.EconomyManager = {
    getPriceMultiplier = function()
      return opts.multiplier or 1
    end,
  }
  _G.g_fillTypeManager = {
    getFillTypeByIndex = function(_, index)
      return FILL_TYPES[index]
    end,
  }
  _G.g_fruitTypeManager = {
    getFruitTypeIndexByFillTypeIndex = function(_, index)
      return index == 1 and 7 or nil
    end,
  }
  _G.g_currentMission = {
    terrainSize = 2048,
    environment = { currentPeriod = opts.period or 6 },
    economyManager = { greatDemands = opts.demands or {} },
    storageSystem = {
      getUnloadingStations = function()
        return opts.unloading or {}
      end,
      getLoadingStations = function()
        return opts.loading or {}
      end,
      getPalletBuyingStations = function()
        return opts.pallets or {}
      end,
    },
  }
end

local function byType(rows)
  local index = {}
  for _, row in ipairs(rows or {}) do
    index[row.type] = row
  end
  return index
end

describe("PricesExporter.trendToken", function()
  before_each(function()
    _G.SellingStation =
      { PRICE_FALLING = BIT_FALLING, PRICE_CLIMBING = BIT_CLIMBING, PRICE_GREAT_DEMAND = BIT_GREAT_DEMAND }
  end)

  after_each(function()
    _G.SellingStation = nil
  end)

  it("reads the game's climbing / falling bits", function()
    assert.are.equal("climbing", VDT.PricesExporter.trendToken(mask(BIT_CLIMBING)))
    assert.are.equal("falling", VDT.PricesExporter.trendToken(mask(BIT_FALLING)))
  end)

  it("is steady when neither bit is set, and for a missing mask", function()
    assert.are.equal("steady", VDT.PricesExporter.trendToken(0))
    assert.are.equal("steady", VDT.PricesExporter.trendToken(nil))
  end)

  it("keeps the trend even while the great-demand bit is set -- they are different questions", function()
    assert.are.equal("falling", VDT.PricesExporter.trendToken(mask(BIT_FALLING, BIT_GREAT_DEMAND)))
  end)
end)

describe("PricesExporter.collect", function()
  after_each(function()
    _G.g_currentMission = nil
    _G.g_fillTypeManager = nil
    _G.g_fruitTypeManager = nil
    _G.EconomyManager = nil
    _G.SellingStation = nil
  end)

  it("is unavailable, and collects nothing, before the storage system exists", function()
    assert.is_false(VDT.PricesExporter.isAvailable())
    assert.is_nil(VDT.PricesExporter.collect())
  end)

  it("exports a selling station's prices per 1000 litres with its trend", function()
    setupWorld({
      unloading = {
        makeSellingStation({
          name = "Grain Elevator",
          placeable = { uniqueId = "elevator-1" },
          accepted = { [1] = true },
          prices = { [1] = 1.04355 },
          info = { [1] = mask(BIT_CLIMBING) },
        }),
      },
    })

    local model = VDT.PricesExporter.collect()
    assert.are.equal("1", model.version)
    assert.are.equal(6, model.period)
    assert.are.equal(1, model.priceMultiplier)

    local station = model.stations[1]
    assert.are.equal("elevator-1", station.id)
    assert.are.equal("Grain Elevator", station.name)
    local row = station.sell[1]
    assert.are.equal("WHEAT", row.type)
    -- per-litre 1.04355 -> per 1000 l, rounded to two decimals
    assert.are.equal(1043.55, row.price)
    assert.are.equal("climbing", row.trend)
    assert.is_nil(row.greatDemand)
  end)

  it("folds the difficulty multiplier into the catalogue, since it is already in the station price", function()
    setupWorld({
      multiplier = 1.8,
      unloading = {
        makeSellingStation({
          name = "Elevator",
          placeable = { uniqueId = "e" },
          accepted = { [1] = true },
          prices = { [1] = 1.62 },
        }),
      },
    })

    local model = VDT.PricesExporter.collect()
    assert.are.equal(1.8, model.priceMultiplier)
    local wheat = model.fillTypes[1]
    -- 0.9/l base * 1000 * 1.8, i.e. the reference the station's live price moves around
    assert.are.equal(1620, wheat.basePrice)
    assert.are.equal(2016, wheat.bestPrice) -- 1.12 * 1000 * 1.8
    assert.are.equal(5, wheat.bestMonth)
  end)

  it("publishes the twelve-month curve once per commodity, not once per station", function()
    setupWorld({
      unloading = {
        makeSellingStation({
          name = "A",
          placeable = { uniqueId = "a" },
          accepted = { [1] = true },
          prices = { [1] = 0.9 },
        }),
        makeSellingStation({
          name = "B",
          placeable = { uniqueId = "b" },
          accepted = { [1] = true },
          prices = { [1] = 0.95 },
        }),
      },
    })

    local model = VDT.PricesExporter.collect()
    assert.are.equal(2, #model.stations)
    assert.are.equal(1, #model.fillTypes)
    local wheat = model.fillTypes[1]
    assert.are.equal(12, #wheat.months)
    assert.are.equal(1120, wheat.months[5])
    assert.is_true(wheat.isCrop)
    assert.is_true(wheat.showOnPriceTable)
  end)

  it("omits the curve for a commodity the game keeps no history for", function()
    setupWorld({
      loading = {
        makeBuyingStation({
          name = "Fuel",
          placeable = { uniqueId = "fuel" },
          supported = { [2] = true },
          prices = { [2] = 1.85 },
        }),
      },
    })

    local diesel = VDT.PricesExporter.collect().fillTypes[1]
    assert.are.equal("DIESEL", diesel.type)
    assert.is_nil(diesel.months)
    assert.is_nil(diesel.bestMonth)
    assert.is_nil(diesel.bestPrice)
    assert.is_nil(diesel.showOnPriceTable)
    assert.is_nil(diesel.isCrop)
  end)

  it("marks the running great demand with its premium and the hours it has left", function()
    local station = makeSellingStation({
      name = "Spinnery",
      placeable = { uniqueId = "spinnery" },
      accepted = { [1] = true },
      prices = { [1] = 1.17 },
      info = { [1] = mask(BIT_FALLING, BIT_GREAT_DEMAND) },
    })
    setupWorld({
      unloading = { station },
      demands = {
        {
          isValid = true,
          isRunning = true,
          sellStation = station,
          fillTypeIndex = 1,
          demandMultiplier = 1.3,
          demandDuration = 12,
        },
        -- a demand that is scheduled but has not started must not colour anything yet
        {
          isValid = true,
          isRunning = false,
          sellStation = station,
          fillTypeIndex = 3,
          demandMultiplier = 1.4,
          demandDuration = 18,
        },
      },
    })

    local row = VDT.PricesExporter.collect().stations[1].sell[1]
    assert.is_true(row.greatDemand)
    assert.are.equal(1.3, row.demandMultiplier)
    assert.are.equal(12, row.demandHoursLeft)
    assert.are.equal("falling", row.trend)
  end)

  it("still reports a great demand a client knows only from the synced bit", function()
    -- The bit rides in fillTypePriceInfo, which is synced; economyManager.greatDemands may not have
    -- reached this client yet. The flag is the part the price already reflects, so it is reported
    -- with the premium and countdown simply absent.
    setupWorld({
      unloading = {
        makeSellingStation({
          name = "Spinnery",
          placeable = { uniqueId = "spinnery" },
          accepted = { [1] = true },
          prices = { [1] = 1.17 },
          info = { [1] = mask(BIT_GREAT_DEMAND) },
        }),
      },
    })

    local row = VDT.PricesExporter.collect().stations[1].sell[1]
    assert.is_true(row.greatDemand)
    assert.is_nil(row.demandMultiplier)
    assert.is_nil(row.demandHoursLeft)
  end)

  it("puts one placeable's selling and buying stations on a single row", function()
    local placeable = { uniqueId = "shop-1" }
    setupWorld({
      unloading = {
        makeSellingStation({
          name = "Farm Shop",
          placeable = placeable,
          accepted = { [1] = true },
          prices = { [1] = 0.9 },
        }),
      },
      loading = {
        makeBuyingStation({
          name = "Farm Shop",
          placeable = placeable,
          supported = { [2] = true, [3] = true },
          prices = { [2] = 1.85, [3] = 1.2 },
        }),
      },
    })

    local model = VDT.PricesExporter.collect()
    assert.are.equal(1, #model.stations)
    local station = model.stations[1]
    assert.are.equal(1, #station.sell)
    assert.are.equal(2, #station.buy)
    -- rows are sorted by fill type name, so the file does not churn between writes
    assert.are.equal("DIESEL", station.buy[1].type)
    assert.are.equal("FERTILIZER", station.buy[2].type)
    assert.are.equal(1850, byType(station.buy).DIESEL.price)
  end)

  it("exports a pallet shop's price per pallet, unscaled", function()
    setupWorld({
      pallets = {
        makePalletStation("Pallet Shop", {
          { fillTypeIndex = 3, price = 480, title = "Fertilizer" },
        }),
      },
    })

    local station = VDT.PricesExporter.collect().stations[1]
    assert.is_true(station.isPalletStation)
    assert.are.equal("Pallet Shop", station.name)
    assert.are.equal(480, station.pallets[1].price)
    assert.is_nil(station.sell)
    assert.is_nil(station.buy)
  end)

  it("skips a station the map hides from the prices menu", function()
    setupWorld({
      unloading = {
        makeSellingStation({
          name = "Mission tip trigger",
          placeable = { uniqueId = "hidden" },
          accepted = { [1] = true },
          prices = { [1] = 0.9 },
          hidden = true,
        }),
      },
    })

    assert.is_nil(VDT.PricesExporter.collect().stations)
  end)

  it("skips a fill type the station has no price entry for, instead of asking the game for one", function()
    -- getEffectiveFillTypePrice logs and prints a callstack for an unknown fill type, and this runs
    -- every 30 s forever -- so an accepted type with no price row is dropped silently.
    setupWorld({
      unloading = {
        makeSellingStation({
          name = "Elevator",
          placeable = { uniqueId = "e" },
          accepted = { [1] = true, [3] = true },
          prices = { [1] = 0.9 },
        }),
      },
    })

    local station = VDT.PricesExporter.collect().stations[1]
    assert.are.equal(1, #station.sell)
    assert.are.equal("WHEAT", station.sell[1].type)
  end)

  it("sorts stations by name and carries the marker in map.json's frame", function()
    setupWorld({
      unloading = {
        makeSellingStation({
          name = "Zeta Mill",
          placeable = { uniqueId = "z", rootNode = 1 },
          accepted = { [1] = true },
          prices = { [1] = 0.9 },
        }),
        makeSellingStation({
          name = "Alpha Dairy",
          placeable = {
            uniqueId = "a",
            getHotspot = function()
              return {
                getWorldPosition = function()
                  return 0, 512
                end,
              }
            end,
          },
          accepted = { [1] = true },
          prices = { [1] = 0.95 },
        }),
      },
    })

    local stations = VDT.PricesExporter.collect().stations
    assert.are.equal("Alpha Dairy", stations[1].name)
    assert.are.equal("Zeta Mill", stations[2].name)
    -- world (0, 512) on a 2048 m terrain -> the centre in x, three quarters in z
    assert.are.equal(0.5, stations[1].posX)
    assert.are.equal(0.75, stations[1].posZ)
    -- no hotspot and no engine to translate the root node: the marker is omitted, not guessed
    assert.is_nil(stations[2].posX)
  end)

  it("writes an empty board rather than nothing when the map has no stations", function()
    setupWorld({})
    local model = VDT.PricesExporter.collect()
    assert.is_not_nil(model)
    assert.is_nil(model.stations)
    assert.is_nil(model.fillTypes)
  end)
end)
