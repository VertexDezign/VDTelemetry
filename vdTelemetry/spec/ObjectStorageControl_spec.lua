-- Unit tests for src/command/ObjectStorageControl.lua (object-storage unload command).
--
-- Run with `busted` from the vdTelemetry/ directory. Load order mirrors VDTelemetry.lua: the control
-- takes its own-farm + id helpers from ProductionExporter (which registers a channel at load, so
-- ExportChannels first) and self-registers into CommandRegistry. We stub an object-storage placeable,
-- the local player, the unload event class and a client/server connection, and capture the sent event.

if VDT == nil or VDT.CommandRegistry == nil then
  dofile("src/command/CommandRegistry.lua")
end
if VDT.ExportChannels == nil then
  dofile("src/export/ExportChannels.lua")
end
if VDT.ProductionExporter == nil then
  dofile("src/collect/ProductionExporter.lua")
end
dofile("src/command/ObjectStorageControl.lua")

local debugger = { warn = function() end, debug = function() end }

local sent -- captured { placeable, index, amount } from the last unload event

local function makeObjectStorage(owner, uniqueId, groups, maxUnload)
  local objectInfos = {}
  for _, g in ipairs(groups) do
    objectInfos[#objectInfos + 1] = {
      numObjects = g.count,
      objects = {
        {
          getDialogText = function()
            return g.title
          end,
        },
      },
    }
  end
  return {
    uniqueId = uniqueId,
    spec_objectStorage = { objectInfos = objectInfos, maxUnloadAmount = maxUnload or 25 },
    getOwnerFarmId = function()
      return owner
    end,
    updateDirtyObjectStorageObjectInfos = function() end,
  }
end

local function installWorld(placeables, farmId)
  sent = nil
  _G.g_localPlayer = farmId ~= nil and { farmId = farmId } or nil
  _G.g_currentMission = { placeableSystem = { placeables = placeables or {} } }
  _G.PlaceableObjectStorageUnloadEvent = {
    new = function(placeable, index, amount)
      return { placeable = placeable, index = index, amount = amount }
    end,
  }
  _G.g_client = {
    getServerConnection = function()
      return {
        sendEvent = function(_, event)
          sent = event
        end,
      }
    end,
  }
end

local function run(params)
  VDT.CommandRegistry.get("unloadObjectStorage").execute(nil, params, debugger)
end

after_each(function()
  _G.g_currentMission = nil
  _G.g_localPlayer = nil
  _G.PlaceableObjectStorageUnloadEvent = nil
  _G.g_client = nil
end)

describe("unloadObjectStorage", function()
  it("sends an unload event with the resolved index and amount", function()
    local os = makeObjectStorage(1, "barn-1", { { title = "Round bale (Straw)", count = 20 } }, 25)
    installWorld({ os }, 1)
    run({ storageId = "barn-1", index = 1, title = "Round bale (Straw)", amount = 10 })
    assert.is_not_nil(sent)
    assert.are.equal(os, sent.placeable)
    assert.are.equal(1, sent.index)
    assert.are.equal(10, sent.amount)
  end)

  it("clamps the amount to the stored count", function()
    local os = makeObjectStorage(1, "barn-1", { { title = "Bale", count = 3 } }, 25)
    installWorld({ os }, 1)
    run({ storageId = "barn-1", index = 1, title = "Bale", amount = 10 })
    assert.are.equal(3, sent.amount)
  end)

  it("clamps the amount to maxUnloadAmount", function()
    local os = makeObjectStorage(1, "barn-1", { { title = "Bale", count = 100 } }, 25)
    installWorld({ os }, 1)
    run({ storageId = "barn-1", index = 1, title = "Bale", amount = 100 })
    assert.are.equal(25, sent.amount)
  end)

  it("re-resolves the group by title when the index has shifted", function()
    local os = makeObjectStorage(1, "barn-1", {
      { title = "Straw", count = 5 },
      { title = "Hay", count = 8 },
    }, 25)
    installWorld({ os }, 1)
    -- the app sent index 1 for Hay, but Hay is now at index 2
    run({ storageId = "barn-1", index = 1, title = "Hay", amount = 4 })
    assert.are.equal(2, sent.index)
    assert.are.equal(4, sent.amount)
  end)

  it("ignores a storage owned by another farm", function()
    local os = makeObjectStorage(2, "barn-2", { { title = "Bale", count = 5 } }, 25)
    installWorld({ os }, 1)
    run({ storageId = "barn-2", index = 1, title = "Bale", amount = 2 })
    assert.is_nil(sent)
  end)

  it("does nothing when the group is empty", function()
    local os = makeObjectStorage(1, "barn-1", { { title = "Bale", count = 0 } }, 25)
    installWorld({ os }, 1)
    run({ storageId = "barn-1", index = 1, title = "Bale", amount = 5 })
    assert.is_nil(sent)
  end)

  it("ignores an unknown storage id", function()
    local os = makeObjectStorage(1, "barn-1", { { title = "Bale", count = 5 } }, 25)
    installWorld({ os }, 1)
    run({ storageId = "nope", index = 1, title = "Bale", amount = 2 })
    assert.is_nil(sent)
  end)
end)
