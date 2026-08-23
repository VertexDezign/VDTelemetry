-- Unit tests for src/collect/aspects/Lowered.lua.
--
-- Run with `busted` from the vdTelemetry/ directory.
--
-- This aspect is the one that cannot gate on a spec, because base Vehicle registers `getIsLowered` on
-- every machine and hard-returns false. Everything here is about telling that default apart from a
-- real answer, so the stubs mimic the two ways the engine hands one back: the base function itself
-- (compared by identity) and an override that returns the caller's default unchanged.

dofile("src/collect/aspects/Lowered.lua")

-- Stand-in for the base game's global, whose body is `return false` regardless of the default.
--
-- Assigned through _G on purpose: busted gives each spec file its own global environment, and the
-- collector was loaded with dofile into the real one -- so a plain `Vehicle = ...` here would be
-- invisible to the code under test and the identity check would silently never fire. In the game the
-- question does not arise: the mod's files are source()d into the same environment the engine's own
-- bare-global specialization classes live in.
local BASE_GET_IS_LOWERED = function()
  return false
end
_G.Vehicle = { getIsLowered = BASE_GET_IS_LOWERED }

describe("VDT.Lowered", function()
  it("reports nothing for an object with no getIsLowered at all", function()
    assert.is_nil(VDT.Lowered.collect({}))
  end)

  it("reports nothing for a machine that never overrode the base function", function()
    -- A tractor. The engine answers `false` because that is Vehicle:getIsLowered's whole body, not
    -- because anything on the machine is raised -- and a terminal that believed it offered a raise
    -- control for a vehicle with nothing to raise.
    assert.is_nil(VDT.Lowered.collect({ getIsLowered = BASE_GET_IS_LOWERED }))
  end)

  it("reports nothing when the override hands the caller's default straight back", function()
    -- Attachable on an attacher joint that neither allowsLowering nor isDefaultLowered: it returns
    -- `defaultIsLowered` unchanged. Asking twice with opposite defaults is what catches it.
    local implement = {
      getIsLowered = function(_, default)
        return default
      end,
    }
    assert.is_nil(VDT.Lowered.collect(implement))
  end)

  it("reports a real lowered state", function()
    local plough = {
      getIsLowered = function()
        return true
      end,
    }
    assert.is_true(VDT.Lowered.collect(plough))
  end)

  it("reports a real raised state", function()
    -- The case that must survive: `false` from an override is a fact, where `false` from the base is
    -- not. Only the identity test separates them, since both agree across the two calls.
    local plough = {
      getIsLowered = function()
        return false
      end,
    }
    assert.is_false(VDT.Lowered.collect(plough))
  end)

  it("reports nothing when the override answers nil", function()
    local odd = {
      getIsLowered = function()
        return nil
      end,
    }
    assert.is_nil(VDT.Lowered.collect(odd))
  end)
end)
