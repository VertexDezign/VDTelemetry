-- Unit tests for src/command/MapLayersControl.lua (the ground-layer subscription command).
--
-- Run with `busted` from the vdTelemetry/ directory. The control self-registers into
-- VDT.CommandRegistry at load, so we load CommandRegistry first -- but only if it isn't already
-- loaded, so we don't reset the registry another spec populated. MapLayersExporter provides
-- setSubscription (and pulls in the channel registry + MapExporter helpers it is built on).

if VDT == nil or VDT.CommandRegistry == nil then
  dofile("src/command/CommandRegistry.lua")
end
if VDT.ExportChannels == nil then
  dofile("src/export/ExportChannels.lua")
end
if VDT.MapExporter == nil then
  dofile("src/collect/MapExporter.lua")
end
if VDT.MapLayers == nil then
  dofile("src/integrations/PrecisionFarming.lua")
  dofile("src/collect/MapLayersExporter.lua")
end
dofile("src/command/MapLayersControl.lua")

local debugger = { debug = function() end, warn = function() end }

describe("MapLayersControl.parseIds", function()
  it("splits a comma-separated list", function()
    assert.are.same({ "crops", "growth" }, VDT.MapLayersControl.parseIds("crops,growth"))
  end)

  it("trims spaces and drops empty entries", function()
    assert.are.same({ "crops", "soil" }, VDT.MapLayersControl.parseIds(" crops , , soil,"))
  end)

  -- The empty set is a real value -- "no dashboard is showing an overlay" -- not a missing attribute,
  -- so it has to parse to an empty list rather than to something the caller treats as "unchanged".
  it("returns nothing for an empty or absent value", function()
    assert.are.same({}, VDT.MapLayersControl.parseIds(""))
    assert.are.same({}, VDT.MapLayersControl.parseIds(nil))
  end)
end)

describe("setMapLayers command", function()
  local function xmlStub(ids)
    return {
      getString = function(_, key, default)
        assert.are.equal("commands.command(0)#ids", key)
        return ids or default
      end,
    }
  end

  before_each(function()
    VDT.MapLayers.subscribedLayers = {}
    VDT.MapLayers.sweep = nil
    VDT.MapLayers.dirty = false
  end)

  after_each(function()
    VDT.MapLayers.subscribedLayers = {}
    VDT.MapLayers.dirty = true
  end)

  it("parses the id list off the command element and subscribes the channel to it", function()
    local handler = VDT.CommandRegistry.get("setMapLayers")
    local params = handler.parse(xmlStub("crops,soil"), "commands.command(0)")
    handler.execute(nil, params, debugger)
    assert.are.same({ crops = true, soil = true }, VDT.MapLayers.subscribedLayers)
    assert.is_true(VDT.MapLayers.dirty) -- a newly wanted plane arms its own resweep
  end)

  it("takes the empty set as 'nothing is being shown' and stops the channel", function()
    VDT.MapLayers.subscribedLayers = { crops = true }
    local handler = VDT.CommandRegistry.get("setMapLayers")
    handler.execute(nil, handler.parse(xmlStub(""), "commands.command(0)"), debugger)
    assert.are.same({}, VDT.MapLayers.subscribedLayers)
    assert.is_false(VDT.MapLayers.dirty) -- nothing to sweep for
  end)

  -- What the terminal displays is not vehicle state, so the dispatcher must run this on foot too
  -- (VDTelemetry:onCommand otherwise drops commands with no current vehicle).
  it("declares requiresVehicle = false so it runs with no current vehicle", function()
    assert.are.equal(false, VDT.CommandRegistry.get("setMapLayers").requiresVehicle)
  end)
end)
