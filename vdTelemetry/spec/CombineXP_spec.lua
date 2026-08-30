-- Unit tests for the Combine XP integration (src/integrations/CombineXP.lua).
--
-- Run with `busted` from the vdTelemetry/ directory. The integration is a read of another mod's spec
-- table, so the objects below are shaped like the mod's own (see references/FS25_CombineXP).
--
-- What is worth testing here is everything that is NOT a plain copy:
--   * the spec is reached through the alias the mod sets on the vehicle, with the mod-name-prefixed
--     key as the fallback -- a renamed zip changes the key but not the alias;
--   * the mod divides by measured quantities that can be zero, so NaN reaches these fields and must
--     leave the model absent rather than null (its own HUD does the same check before drawing);
--   * the exported load is engineLoad scaled by the moisture/time multiplier, which is what the mod's
--     HUD prints;
--   * speedLimit is server-only, because the mod never streams it.

if ValueMapper == nil then
  dofile("src/mapper/ValueMapper.lua")
end
if VDT == nil or VDT.CombineXP == nil then
  dofile("src/integrations/CombineXP.lua")
end

local SPEC_KEY = "spec_FS25_CombineXP.xpCombine"
local NAN = 0 / 0

---A machine with the mod's spec on it; `over` replaces limiter fields, `extra` the object's own.
local function xpVehicle(over, extra)
  local limiter = {
    tonPerHour = 24.5,
    yield = 11.25,
    engineLoad = 0.8,
    loadMultiplier = 1,
    highMoisture = false,
  }
  for k, v in pairs(over or {}) do
    limiter[k] = v
  end

  local object = { spec_xpCombine = { mrCombineLimiter = limiter, speedLimit = 7.5 } }
  for k, v in pairs(extra or {}) do
    object[k] = v
  end
  return object
end

local function harvestModel()
  return { harvest = { swathActive = false, filling = true } }
end

describe("CombineXP.contributeObject", function()
  before_each(function()
    rawset(_G, "MathUtil", {
      round = function(v, decimals)
        local mult = 10 ^ (decimals or 0)
        return math.floor(v * mult + 0.5) / mult
      end,
    })
  end)

  it("does nothing when the mod is not installed", function()
    local model = harvestModel()
    VDT.CombineXP.contributeObject({}, model)
    assert.is_nil(model.harvest.combineXp)
  end)

  it("does nothing on a machine that has no harvest aspect", function()
    local model = {}
    VDT.CombineXP.contributeObject(xpVehicle(), model)
    assert.is_nil(model.combineXp)
    assert.is_nil(model.harvest)
  end)

  it("exports the three numbers the mod's own HUD shows", function()
    local model = harvestModel()
    VDT.CombineXP.contributeObject(xpVehicle(), model)
    local xp = model.harvest.combineXp
    assert.are.equal(24.5, xp.throughput)
    assert.are.equal(11.25, xp.yield)
    assert.are.equal(0.8, xp.load)
    assert.is_false(xp.highMoisture)
  end)

  it("scales the load by the moisture/time multiplier, as the HUD does", function()
    local model = harvestModel()
    VDT.CombineXP.contributeObject(xpVehicle({ engineLoad = 0.5, loadMultiplier = 2.4 }), model)
    assert.are.equal(1.2, model.harvest.combineXp.load)
  end)

  it("treats a NaN multiplier as 1 rather than poisoning the load", function()
    local model = harvestModel()
    VDT.CombineXP.contributeObject(xpVehicle({ engineLoad = 0.5, loadMultiplier = NAN }), model)
    assert.are.equal(0.5, model.harvest.combineXp.load)
  end)

  it("leaves a NaN measurement absent instead of exporting null", function()
    local model = harvestModel()
    VDT.CombineXP.contributeObject(xpVehicle({ tonPerHour = NAN, yield = NAN }), model)
    local xp = model.harvest.combineXp
    assert.is_nil(xp.throughput)
    assert.is_nil(xp.yield)
    assert.are.equal(0.8, xp.load)
  end)

  it("exports the limiter's speed on the server and leaves it absent on a client", function()
    local server = harvestModel()
    VDT.CombineXP.contributeObject(xpVehicle(nil, { isServer = true }), server)
    assert.are.equal(7.5, server.harvest.combineXp.speedLimit)

    local client = harvestModel()
    VDT.CombineXP.contributeObject(xpVehicle(), client)
    assert.is_nil(client.harvest.combineXp.speedLimit)
  end)

  it("finds the spec under the mod-name-prefixed key when the alias is missing", function()
    local object = xpVehicle()
    object[SPEC_KEY] = object.spec_xpCombine
    object.spec_xpCombine = nil

    local model = harvestModel()
    VDT.CombineXP.contributeObject(object, model)
    assert.are.equal(24.5, model.harvest.combineXp.throughput)
  end)
end)
