-- Unit tests for the shared Precision Farming detection (src/integrations/PrecisionFarming.lua):
-- isActive() reads the shared g_modIsLoaded table keyed by the mod name, matching the game's own gate.
--
-- Run with `busted` from the vdTelemetry/ directory.

dofile("src/integrations/PrecisionFarming.lua")

describe("PrecisionFarming.isActive", function()
  after_each(function()
    rawset(_G, "g_modIsLoaded", nil)
  end)

  it("is false when g_modIsLoaded is absent", function()
    assert.is_false(VDT.PrecisionFarming.isActive())
  end)

  it("is true only when FS25_precisionFarming is loaded", function()
    rawset(_G, "g_modIsLoaded", { FS25_precisionFarming = true })
    assert.is_true(VDT.PrecisionFarming.isActive())
  end)

  it("is false when a different mod is loaded", function()
    rawset(_G, "g_modIsLoaded", { FS25_SomeOtherMod = true })
    assert.is_false(VDT.PrecisionFarming.isActive())
  end)
end)
