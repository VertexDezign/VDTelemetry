-- Unit tests for the crop calendar export channel (src/collect/CropCalendarExporter.lua): the pure
-- period/season helpers plus collect() against a stubbed fruit type manager. Whether the real
-- FruitTypeDesc still looks like these stubs is what the in-game smoke test covers.
--
-- Run with `busted` from the vdTelemetry/ directory. The exporter self-registers a channel at load,
-- so ExportChannels loads first (only if not already loaded, so we don't reset a registry another
-- spec populated).

if VDT == nil or VDT.ExportChannels == nil then
  dofile("src/export/ExportChannels.lua")
end
if VDT.CropCalendarExporter == nil then
  dofile("src/collect/CropCalendarExporter.lua")
end

-- A stubbed FruitTypeDesc. `plant`/`harvest` are the sets of periods the predicates say yes to; both
-- take (growthMode, period) exactly as the engine's do.
local function makeFruit(opts)
  local plant = {}
  for _, p in ipairs(opts.plant or {}) do
    plant[p] = true
  end
  local harvest = {}
  for _, p in ipairs(opts.harvest or {}) do
    harvest[p] = true
  end
  return {
    index = opts.index,
    name = opts.name,
    shownOnMap = opts.shownOnMap ~= false,
    getIsCatchCrop = function()
      return opts.catchCrop == true
    end,
    getIsPlantableInPeriod = function(_, growthMode, period)
      if growthMode ~= 1 then
        return true
      end
      return plant[period] == true
    end,
    getIsHarvestableInPeriod = function(_, growthMode, period)
      if growthMode ~= 1 then
        return true
      end
      return harvest[period] == true
    end,
  }
end

-- `titles` maps a fruit index -> its fill type title, mirroring the real
-- getFillTypeByFruitTypeIndex(idx).title chain.
local function installWorld(fruits, titles, opts)
  opts = opts or {}
  _G.g_fruitTypeManager = {
    getFruitTypes = function()
      return fruits
    end,
    getFillTypeByFruitTypeIndex = function(_, index)
      local title = titles[index]
      return title and { title = title } or nil
    end,
  }
  _G.g_currentMission = {
    missionInfo = { growthMode = opts.growthMode or 1 },
    environment = {
      currentPeriod = opts.period or 6,
      currentDayInPeriod = opts.dayInPeriod or 2,
      daysPerPeriod = opts.daysPerPeriod or 3,
      currentYear = opts.year or 1,
    },
  }
end

after_each(function()
  _G.g_currentMission = nil
  _G.g_fruitTypeManager = nil
  _G.g_i18n = nil
end)

describe("CropCalendarExporter.seasonForPeriod", function()
  it("groups the twelve periods three to a season", function()
    assert.are.equal("SPRING", VDT.CropCalendarExporter.seasonForPeriod(1))
    assert.are.equal("SPRING", VDT.CropCalendarExporter.seasonForPeriod(3))
    assert.are.equal("SUMMER", VDT.CropCalendarExporter.seasonForPeriod(4))
    assert.are.equal("AUTUMN", VDT.CropCalendarExporter.seasonForPeriod(7))
    assert.are.equal("WINTER", VDT.CropCalendarExporter.seasonForPeriod(10))
    assert.are.equal("WINTER", VDT.CropCalendarExporter.seasonForPeriod(12))
  end)
end)

describe("CropCalendarExporter.growthMode", function()
  it("maps the engine ids to their names", function()
    installWorld({}, {}, { growthMode = 2 })
    local mode, name = VDT.CropCalendarExporter.growthMode()
    assert.are.equal(2, mode)
    assert.are.equal("DAILY", name)
  end)

  it("falls back to SEASONAL when missionInfo is unreadable", function()
    _G.g_currentMission = nil
    local mode, name = VDT.CropCalendarExporter.growthMode()
    assert.are.equal(1, mode)
    assert.are.equal("SEASONAL", name)
  end)

  it("falls back to SEASONAL for an unknown id", function()
    installWorld({}, {}, { growthMode = 99 })
    local _, name = VDT.CropCalendarExporter.growthMode()
    assert.are.equal("SEASONAL", name)
  end)
end)

describe("CropCalendarExporter.collectPeriods", function()
  it("uses the game's own localized labels", function()
    _G.g_i18n = {
      formatPeriod = function(_, period, useShort)
        assert.is_true(useShort)
        return "P" .. period
      end,
    }
    local periods = VDT.CropCalendarExporter.collectPeriods()
    assert.are.equal(12, #periods)
    assert.are.equal(1, periods[1].period)
    assert.are.equal("P1", periods[1].label)
    assert.are.equal("SPRING", periods[1].season)
    assert.are.equal("P12", periods[12].label)
    assert.are.equal("WINTER", periods[12].season)
  end)

  it("falls back to the period number when i18n cannot answer", function()
    local periods = VDT.CropCalendarExporter.collectPeriods()
    assert.are.equal("1", periods[1].label)
    assert.are.equal("12", periods[12].label)
  end)
end)

describe("CropCalendarExporter.tick", function()
  local marked
  local realMarkDirty
  local debugger = {
    info = function() end,
  }

  before_each(function()
    marked = 0
    realMarkDirty = VDT.ExportChannels.markDirty
    VDT.ExportChannels.markDirty = function()
      marked = marked + 1
    end
    VDT.CropCalendarExporter.resetWatch()
    _G.MessageType = { DAY_CHANGED = 1, PERIOD_LENGTH_CHANGED = 2 }
    _G.g_messageCenter = { subscribe = function() end }
  end)

  after_each(function()
    VDT.ExportChannels.markDirty = realMarkDirty
    VDT.CropCalendarExporter.resetWatch()
    _G.MessageType = nil
    _G.g_messageCenter = nil
  end)

  it("subscribes once and queues the first write", function()
    installWorld({ makeFruit({ index = 1, name = "WHEAT" }) }, { [1] = "Wheat" })

    VDT.CropCalendarExporter.tick(debugger, 16)
    assert.are.equal(1, marked)
    assert.is_true(VDT.CropCalendarExporter.subscribed)

    -- a second tick inside the poll window neither re-subscribes nor re-queues
    VDT.CropCalendarExporter.tick(debugger, 16)
    assert.are.equal(1, marked)
  end)

  it("waits for the fruit types before subscribing", function()
    installWorld({}, {})

    VDT.CropCalendarExporter.tick(debugger, 16)

    assert.is_false(VDT.CropCalendarExporter.subscribed)
    assert.are.equal(0, marked)
  end)

  it("queues a rewrite when the growth mode changes", function()
    -- The mode decides what every period in the file means, and the game publishes no message for it,
    -- so the channel polls. Without this a switch to Daily left the wrong calendar up until midnight.
    installWorld({ makeFruit({ index = 1, name = "WHEAT" }) }, { [1] = "Wheat" }, { growthMode = 1 })
    VDT.CropCalendarExporter.tick(debugger, 16)
    VDT.CropCalendarExporter.collect() -- the write the subscribe queued; records the mode it used
    marked = 0

    -- still seasonal after a full poll window -> nothing queued
    VDT.CropCalendarExporter.tick(debugger, VDT.CropCalendarExporter.GROWTH_MODE_POLL_MS)
    assert.are.equal(0, marked)

    _G.g_currentMission.missionInfo.growthMode = 2
    VDT.CropCalendarExporter.tick(debugger, VDT.CropCalendarExporter.GROWTH_MODE_POLL_MS)
    assert.are.equal(1, marked)
  end)

  it("keeps queueing until a document actually goes out with the new mode", function()
    -- The watch compares against what was WRITTEN, not what was last seen, so a skipped write retries.
    installWorld({ makeFruit({ index = 1, name = "WHEAT" }) }, { [1] = "Wheat" }, { growthMode = 1 })
    VDT.CropCalendarExporter.tick(debugger, 16)
    VDT.CropCalendarExporter.collect()
    _G.g_currentMission.missionInfo.growthMode = 3
    marked = 0

    VDT.CropCalendarExporter.tick(debugger, VDT.CropCalendarExporter.GROWTH_MODE_POLL_MS)
    VDT.CropCalendarExporter.tick(debugger, VDT.CropCalendarExporter.GROWTH_MODE_POLL_MS)
    assert.are.equal(2, marked)

    VDT.CropCalendarExporter.collect()
    VDT.CropCalendarExporter.tick(debugger, VDT.CropCalendarExporter.GROWTH_MODE_POLL_MS)
    assert.are.equal(2, marked)
  end)

  it("does not poll faster than GROWTH_MODE_POLL_MS", function()
    installWorld({ makeFruit({ index = 1, name = "WHEAT" }) }, { [1] = "Wheat" }, { growthMode = 1 })
    VDT.CropCalendarExporter.tick(debugger, 16)
    VDT.CropCalendarExporter.collect()
    _G.g_currentMission.missionInfo.growthMode = 2
    marked = 0

    -- a frame's worth of dt is nowhere near the window
    VDT.CropCalendarExporter.tick(debugger, 16)
    assert.are.equal(0, marked)
  end)
end)

describe("CropCalendarExporter.isAvailable", function()
  it("waits for the fruit table to be populated, not just to exist", function()
    -- The channel writes once per in-game day, so an empty first write would sit on disk for a whole
    -- day; being unavailable until the map's fruits load is what prevents it.
    installWorld({}, {})
    assert.is_false(VDT.CropCalendarExporter.isAvailable())

    installWorld({ makeFruit({ index = 1, name = "WHEAT" }) }, { [1] = "Wheat" })
    assert.is_true(VDT.CropCalendarExporter.isAvailable())
  end)

  it("is false before the mission exists", function()
    _G.g_currentMission = nil
    _G.g_fruitTypeManager = nil
    assert.is_false(VDT.CropCalendarExporter.isAvailable())
  end)
end)

describe("CropCalendarExporter.collect", function()
  it("collects the shownOnMap crops with their sow and harvest periods", function()
    installWorld({
      makeFruit({ index = 1, name = "WHEAT", plant = { 9, 10 }, harvest = { 4, 5 } }),
      makeFruit({ index = 2, name = "OAT", plant = { 1, 2 }, harvest = { 5, 6 } }),
    }, { [1] = "Wheat", [2] = "Oat" })

    local model = VDT.CropCalendarExporter.collect()

    assert.are.equal("1", model.version)
    assert.are.equal("SEASONAL", model.growthMode)
    assert.are.equal(2, #model.crops)
    -- sorted by display name, the way the game's own frame sorts them: Oat before Wheat
    assert.are.equal("Oat", model.crops[1].name)
    assert.are.equal("OAT", model.crops[1].id)
    assert.are.same({ 1, 2 }, model.crops[1].plant)
    assert.are.same({ 5, 6 }, model.crops[1].harvest)
    assert.are.equal("Wheat", model.crops[2].name)
    assert.are.same({ 9, 10 }, model.crops[2].plant)
  end)

  it("keeps a wrapped sow range as two runs of periods", function()
    -- the screenshot's Ackergras: sows March..October and again in February
    installWorld({
      makeFruit({ index = 1, name = "MEADOW", plant = { 1, 2, 3, 4, 5, 6, 7, 8, 12 } }),
    }, { [1] = "Meadow" })

    local model = VDT.CropCalendarExporter.collect()

    assert.are.same({ 1, 2, 3, 4, 5, 6, 7, 8, 12 }, model.crops[1].plant)
  end)

  it("skips fruits the map does not show", function()
    installWorld({
      makeFruit({ index = 1, name = "WHEAT", plant = { 9 } }),
      makeFruit({ index = 2, name = "BUSH", shownOnMap = false, plant = { 1 } }),
    }, { [1] = "Wheat", [2] = "Bush" })

    local model = VDT.CropCalendarExporter.collect()

    assert.are.equal(1, #model.crops)
    assert.are.equal("Wheat", model.crops[1].name)
  end)

  it("skips a fruit whose fill type has no title", function()
    installWorld({
      makeFruit({ index = 1, name = "WHEAT", plant = { 9 } }),
      makeFruit({ index = 2, name = "BROKEN", plant = { 1 } }),
    }, { [1] = "Wheat" })

    local model = VDT.CropCalendarExporter.collect()

    assert.are.equal(1, #model.crops)
  end)

  it("omits empty period lists rather than exporting {}", function()
    installWorld({
      makeFruit({ index = 1, name = "POPLAR", plant = { 3 } }),
    }, { [1] = "Poplar" })

    local model = VDT.CropCalendarExporter.collect()

    assert.are.same({ 3 }, model.crops[1].plant)
    assert.is_nil(model.crops[1].harvest)
    -- a non-catch crop omits the flag entirely
    assert.is_nil(model.crops[1].catchCrop)
  end)

  it("flags a catch crop", function()
    installWorld({
      makeFruit({ index = 1, name = "COVER", catchCrop = true, plant = { 8 } }),
    }, { [1] = "Cover Crop" })

    local model = VDT.CropCalendarExporter.collect()

    assert.is_true(model.crops[1].catchCrop)
  end)

  it("reports every period plantable outside seasonal growth, and says which mode it is", function()
    installWorld({
      makeFruit({ index = 1, name = "WHEAT", plant = { 9 }, harvest = { 4 } }),
    }, { [1] = "Wheat" }, { growthMode = 2 })

    local model = VDT.CropCalendarExporter.collect()

    assert.are.equal("DAILY", model.growthMode)
    assert.are.equal(12, #model.crops[1].plant)
    assert.are.equal(12, #model.crops[1].harvest)
  end)

  it("carries the today marker", function()
    installWorld(
      { makeFruit({ index = 1, name = "WHEAT", plant = { 9 } }) },
      { [1] = "Wheat" },
      { period = 6, dayInPeriod = 2, daysPerPeriod = 3, year = 4 }
    )

    local model = VDT.CropCalendarExporter.collect()

    assert.are.equal(6, model.today.period)
    assert.are.equal(2, model.today.dayInPeriod)
    assert.are.equal(3, model.today.daysPerPeriod)
    assert.are.equal(4, model.today.year)
  end)

  it("floors daysPerPeriod at 1 so the marker never divides by zero", function()
    installWorld({ makeFruit({ index = 1, name = "WHEAT" }) }, { [1] = "Wheat" }, { daysPerPeriod = 0 })

    local model = VDT.CropCalendarExporter.collect()

    assert.are.equal(1, model.today.daysPerPeriod)
  end)

  it("omits an entirely empty crop list rather than exporting {}", function()
    -- Reachable only when every fruit is filtered out (none shownOnMap), since an empty fruit table
    -- makes the channel unavailable in the first place.
    installWorld({ makeFruit({ index = 1, name = "BUSH", shownOnMap = false }) }, { [1] = "Bush" })

    assert.is_nil(VDT.CropCalendarExporter.collect().crops)
  end)

  it("returns nil when the fruit types aren't up yet", function()
    _G.g_currentMission = nil
    _G.g_fruitTypeManager = nil

    assert.is_nil(VDT.CropCalendarExporter.collect())
  end)

  it("survives a fruit whose period predicates throw", function()
    -- getIsPlantableInPeriod indexes growthDataSeasonal.periods[...] with no nil check of its own, so
    -- on a map without seasonal growth data it throws rather than answering false
    local fruit = makeFruit({ index = 1, name = "WHEAT", harvest = { 4 } })
    fruit.getIsPlantableInPeriod = function()
      error("no seasonal growth data")
    end
    installWorld({ fruit }, { [1] = "Wheat" })

    local model = VDT.CropCalendarExporter.collect()

    assert.are.equal(1, #model.crops)
    assert.is_nil(model.crops[1].plant)
    assert.are.same({ 4 }, model.crops[1].harvest)
  end)
end)
