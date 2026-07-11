-- Unit tests for the FS25_CropRotation collector (src/integrations/CropRotation.lua).
--
-- Run with `busted` from the vdTelemetry/ directory. CropRotation.lua self-registers an export
-- channel at load, so we load ExportChannels first (only if not already loaded, so we don't reset a
-- registry another spec populated). The collector reads the mod through its env global
-- `FS25_CropRotation` (mod-environment isolation) plus the shared `g_localPlayer`, both stubbed here.

if VDT == nil or VDT.ExportChannels == nil then
  dofile("src/export/ExportChannels.lua")
end
dofile("src/integrations/CropRotation.lua")

-- A stub FS25_CropRotation env: a planner with the given cropRotations plus a g_cropRotation whose
-- possible-states catalogs resolve names for the fixtures below.
local function installMod(cropRotations)
  rawset(_G, "FS25_CropRotation", {
    g_cropRotationPlanner = { cropRotations = cropRotations },
    g_cropRotation = {
      getPossibleCropStates = function()
        return {
          { cropIndex = 0, name = "Fallow" },
          { cropIndex = 3, name = "Wheat" },
          { cropIndex = 5, name = "Canola", ignoreInPlanner = false },
        }
      end,
      getPossibleCatchCropStates = function()
        return {
          { cropIndex = 0, name = "Without catch crop" },
          { cropIndex = 2, name = "Oilseed Radish" },
        }
      end,
    },
  })
end

before_each(function()
  rawset(_G, "FS25_CropRotation", nil)
  rawset(_G, "g_localPlayer", { farmId = 1 })
  VDT.CropRotation.subscribed = nil
end)

describe("CropRotation channel registration", function()
  it("registers cropRotation.json and is unavailable when the mod is absent", function()
    local hasFile = false
    for _, name in ipairs(VDT.ExportChannels.fileNames()) do
      if name == "cropRotation.json" then
        hasFile = true
      end
    end
    -- Registered regardless; availability is false with no FS25_CropRotation env.
    assert.is_true(hasFile)
    assert.are.equal(false, VDT.CropRotation.isAvailable())
  end)
end)

describe("CropRotation.collect", function()
  it("returns nil when the mod isn't installed (skips the write)", function()
    assert.is_nil(VDT.CropRotation.collect())
  end)

  it("resolves crop + catch-crop display names inline", function()
    installMod({
      {
        index = 1,
        name = "Heavy Soil",
        farmId = 1,
        rotations = {
          { state = 3, catchCropState = 0 },
          { state = 5, catchCropState = 2 },
          { state = 0, catchCropState = 0 },
        },
      },
    })

    local model = VDT.CropRotation.collect()
    assert.are.equal("1", model.version)
    assert.are.equal(1, #model.rotations)

    local plan = model.rotations[1]
    assert.are.equal(1, plan.index)
    assert.are.equal("Heavy Soil", plan.name)
    assert.are.equal(3, #plan.sequence)

    assert.are.same(
      { state = 3, crop = "Wheat", catchCropState = 0, catchCrop = "Without catch crop" },
      plan.sequence[1]
    )
    assert.are.same({ state = 5, crop = "Canola", catchCropState = 2, catchCrop = "Oilseed Radish" }, plan.sequence[2])
    assert.are.same(
      { state = 0, crop = "Fallow", catchCropState = 0, catchCrop = "Without catch crop" },
      plan.sequence[3]
    )
  end)

  it("scopes to the local player's farm", function()
    installMod({
      { index = 1, name = "Mine", farmId = 1, rotations = { { state = 3, catchCropState = 0 } } },
      { index = 2, name = "Theirs", farmId = 2, rotations = { { state = 3, catchCropState = 0 } } },
    })

    local model = VDT.CropRotation.collect()
    assert.are.equal(1, #model.rotations)
    assert.are.equal("Mine", model.rotations[1].name)
  end)

  it("includes all farms when the local player's farm can't be resolved", function()
    rawset(_G, "g_localPlayer", nil)
    installMod({
      { index = 1, name = "A", farmId = 1, rotations = { { state = 3, catchCropState = 0 } } },
      { index = 2, name = "B", farmId = 2, rotations = { { state = 3, catchCropState = 0 } } },
    })

    assert.are.equal(2, #VDT.CropRotation.collect().rotations)
  end)

  it("omits empty rotation lists so the encoder doesn't emit {}", function()
    installMod({})
    local model = VDT.CropRotation.collect()
    assert.is_nil(model.rotations)
  end)

  it("survives a mod that renamed getPossibleCropStates (names fall back to empty)", function()
    installMod({ { index = 1, name = "X", farmId = 1, rotations = { { state = 3, catchCropState = 0 } } } })
    rawget(_G, "FS25_CropRotation").g_cropRotation.getPossibleCropStates = nil

    local model = VDT.CropRotation.collect()
    assert.are.equal("", model.rotations[1].sequence[1].crop)
  end)
end)
