-- Unit tests for the integration stage runner (src/integrations/registry.lua).
--
-- Run with `busted` from the vdTelemetry/ directory. The registry names its integrations and resolves
-- each under VDT.* at call time, so these specs install fake modules under those names rather than
-- sourcing the real integrations -- what is under test is the runner's containment, not any one mod.

local NAMES = { "EnhancedVehicle", "PrecisionFarming", "AdvancedDamageSystem", "CombineXP" }

describe("VDT.Integrations.run", function()
  local saved
  local savedTelemetry
  local logged

  before_each(function()
    -- Sourced first: it creates VDT on the real global table, where busted's insulated spec env would
    -- otherwise shadow it with a table of its own.
    dofile("src/integrations/registry.lua")
    saved = {}
    for _, name in ipairs(NAMES) do
      saved[name] = VDT[name]
      VDT[name] = {}
    end
    VDT.Integrations.resetReported()
    logged = {}
    savedTelemetry = _G.g_vdTelemetry
    _G.g_vdTelemetry = {
      debugger = {
        error = function(_, message)
          logged[#logged + 1] = message
        end,
      },
    }
  end)

  after_each(function()
    for _, name in ipairs(NAMES) do
      VDT[name] = saved[name]
    end
    _G.g_vdTelemetry = savedTelemetry
  end)

  it("runs every integration that implements the stage, in list order", function()
    local order = {}
    VDT.EnhancedVehicle.contributeObject = function()
      order[#order + 1] = "EnhancedVehicle"
    end
    VDT.CombineXP.contributeObject = function()
      order[#order + 1] = "CombineXP"
    end
    VDT.AdvancedDamageSystem.contributeFleetVehicle = function()
      order[#order + 1] = "wrong stage"
    end
    VDT.Integrations.run("contributeObject", {}, {})
    assert.are.same({ "EnhancedVehicle", "CombineXP" }, order)
  end)

  it("skips a name with no module behind it", function()
    VDT.PrecisionFarming = nil
    assert.has_no.errors(function()
      VDT.Integrations.run("contributeObject", {}, {})
    end)
  end)

  -- The failure this guards: a third-party mod renames something, its hook throws, and -- uncontained --
  -- vdTelemetry.json stops being written on every tick while the later integrations never run.
  it("contains a throwing hook, keeps what it wrote, and still runs the ones after it", function()
    VDT.EnhancedVehicle.contributeObject = function(_, model)
      model.ev = true
      error("renamed upstream")
    end
    VDT.CombineXP.contributeObject = function(_, model)
      model.cxp = true
    end
    local model = {}
    assert.has_no.errors(function()
      VDT.Integrations.run("contributeObject", {}, model)
    end)
    assert.is_true(model.ev)
    assert.is_true(model.cxp)
  end)

  it("logs a failing hook once, by integration and stage", function()
    VDT.CombineXP.contributeObject = function()
      error("renamed upstream")
    end
    for _ = 1, 5 do
      VDT.Integrations.run("contributeObject", {}, {})
    end
    assert.are.equal(1, #logged)
    assert.truthy(logged[1]:find("integration CombineXP: contributeObject failed", 1, true))
    assert.truthy(logged[1]:find("renamed upstream", 1, true))
  end)

  it("logs the same integration failing at a different stage separately", function()
    VDT.AdvancedDamageSystem.contributeObject = function()
      error("a")
    end
    VDT.AdvancedDamageSystem.contributeFleetVehicle = function()
      error("b")
    end
    VDT.Integrations.run("contributeObject", {}, {})
    VDT.Integrations.run("contributeFleetVehicle", {}, {})
    assert.are.equal(2, #logged)
  end)

  it("keeps running a hook after it failed once (a failure can be one machine's)", function()
    local calls = 0
    VDT.EnhancedVehicle.contributeObject = function(subject)
      calls = calls + 1
      if subject.bad then
        error("this machine only")
      end
    end
    VDT.Integrations.run("contributeObject", { bad = true }, {})
    VDT.Integrations.run("contributeObject", {}, {})
    assert.are.equal(2, calls)
  end)
end)
