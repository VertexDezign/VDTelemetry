-- Unit tests for src/collect/aspects/Lowered.lua.
--
-- Run with `busted` from the vdTelemetry/ directory.
--
-- This aspect is the one that cannot gate on a single spec: base Vehicle registers `getIsLowered` on
-- every machine and hard-returns false, so the job is telling that default apart from an answer. The
-- stubs below stand in for each of the three specializations that overwrite it, including the two
-- that hand the call onward.

dofile("src/collect/aspects/Lowered.lua")

--- A machine whose getIsLowered behaves like base Vehicle's: constant false, default ignored.
local function baseAnswer()
  return false
end

--- Attachable's two arms: `moveDown` when the joint allows lowering, else the caller's default.
local function attachable(moveDown)
  return {
    spec_attachable = {},
    getIsLowered = function(_, default)
      if moveDown == nil then
        return default
      end
      return moveDown
    end,
  }
end

describe("VDT.Lowered", function()
  it("reports nothing for an object with no getIsLowered at all", function()
    assert.is_nil(VDT.Lowered.collect({}))
  end)

  it("reports nothing for a machine no lowering specialization touched", function()
    -- A tractor. The engine answers `false` because that is Vehicle:getIsLowered's whole body, not
    -- because anything on it is raised -- and a terminal that believed it offered a raise control for
    -- a machine with nothing to raise.
    assert.is_nil(VDT.Lowered.collect({ getIsLowered = baseAnswer }))
  end)

  it("reports nothing for a self-propelled foldable without fold-middle", function()
    -- The case the first fix missed. Foldable overwrites getIsLowered but, with no fold-middle
    -- configured, hands the call to superFunc -- which on a machine that is not Attachable is base
    -- Vehicle. Its `false` looks exactly like a real one, because it ignores the default it is given,
    -- so nothing about the returned VALUE can catch this. Only the configuration can.
    local harvester = { spec_foldable = { foldMiddleAnimTime = nil }, getIsLowered = baseAnswer }
    assert.is_nil(VDT.Lowered.collect(harvester))
  end)

  it("reports the state of a foldable that folds to middle", function()
    -- A Krone BigM: it reports lowering through fold-middle and not through Attachable at all.
    local bigM = {
      spec_foldable = { foldMiddleAnimTime = 0.5, foldMiddleInputButton = "IMPLEMENT_EXTRA2" },
      getIsLowered = function()
        return true
      end,
    }
    assert.is_true(VDT.Lowered.collect(bigM))
  end)

  it("reports nothing when an attacher joint hands the caller's default back", function()
    -- A trailer on a hitch that neither allowsLowering nor isDefaultLowered. Asking twice with
    -- opposite defaults is what catches it, without this collector reading jointDesc itself.
    assert.is_nil(VDT.Lowered.collect(attachable(nil)))
  end)

  it("reports a raised implement, which is a fact rather than a default", function()
    -- The one that must survive: `false` from a hitch that does lower is real, where `false` from the
    -- base is not, and both agree across the two probes.
    assert.is_false(VDT.Lowered.collect(attachable(false)))
  end)

  it("reports a lowered implement", function()
    assert.is_true(VDT.Lowered.collect(attachable(true)))
  end)

  it("sees through a foldable stacked on top of an attachable", function()
    -- Foldable defers to Attachable, which defers to the default: the probe follows the whole chain,
    -- where a per-spec flag check would have stopped at the Foldable and reported a state.
    local foldingTrailer = {
      spec_foldable = { foldMiddleAnimTime = nil },
      spec_attachable = {},
      getIsLowered = function(_, default)
        return default
      end,
    }
    assert.is_nil(VDT.Lowered.collect(foldingTrailer))
  end)

  it("reports a pickup's own state", function()
    local forageWagon = {
      spec_pickup = {},
      getIsLowered = function()
        return true
      end,
    }
    assert.is_true(VDT.Lowered.collect(forageWagon))
  end)
end)
