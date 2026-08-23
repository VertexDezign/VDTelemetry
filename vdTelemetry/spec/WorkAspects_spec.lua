-- Unit tests for the §4 aspect collectors: discharge, tipping, harvest, work mode / width and the
-- bale counter (src/collect/aspects/{Discharge,Tipping,Harvest,Work,BaleCounter}.lua).
--
-- Run with `busted` from the vdTelemetry/ directory. All five are field reads of their spec table, so
-- the objects below are plain tables shaped like the engine's; only the work-width collector calls
-- back into the object (getVariableWorkWidth), which is stubbed.

if ValueMapper == nil then
  dofile("src/mapper/ValueMapper.lua")
end
for name, file in pairs({
  Discharge = "src/collect/aspects/Discharge.lua",
  Tipping = "src/collect/aspects/Tipping.lua",
  Harvest = "src/collect/aspects/Harvest.lua",
  Work = "src/collect/aspects/Work.lua",
  BaleCounter = "src/collect/aspects/BaleCounter.lua",
}) do
  if VDT == nil or VDT[name] == nil then
    dofile(file)
  end
end

describe("Discharge.collect", function()
  local function dischargeable(state, node, allowed)
    return {
      spec_dischargeable = {
        currentDischargeState = state,
        currentDischargeNode = node,
        isDischargeAllowed = allowed ~= false,
      },
    }
  end

  it("returns nil when the object cannot discharge", function()
    assert.is_nil(VDT.Discharge.collect({}))
  end)

  it("maps the three discharge states", function()
    assert.are.equal("OFF", VDT.Discharge.collect(dischargeable(0)).state)
    assert.are.equal("OBJECT", VDT.Discharge.collect(dischargeable(1)).state)
    assert.are.equal("GROUND", VDT.Discharge.collect(dischargeable(2)).state)
  end)

  it("reports the active node without a failure reason when nothing is wrong", function()
    local d = VDT.Discharge.collect(dischargeable(2, {
      index = 1,
      fillUnitIndex = 2,
      dischargeObject = nil,
      dischargeHitTerrain = true,
    }))
    assert.are.equal(1, d.nodeIndex)
    assert.are.equal(2, d.fillUnitIndex)
    assert.is_false(d.hasObject)
    assert.is_true(d.hitTerrain)
    assert.is_nil(d.reason)
  end)

  it("maps the engine's blocked-discharge reason", function()
    local d = VDT.Discharge.collect(dischargeable(0, { index = 1, dischargeFailedReason = 2 }))
    assert.are.equal("NO_FREE_CAPACITY", d.reason)
    assert.are.equal("NO_ACCESS_LAND", VDT.Discharge.collect(dischargeable(0, { dischargeFailedReason = 6 })).reason)
  end)

  it("carries the isDischargeAllowed latch", function()
    assert.is_true(VDT.Discharge.collect(dischargeable(0)).allowed)
    assert.is_false(VDT.Discharge.collect(dischargeable(0, nil, false)).allowed)
  end)

  it("omits the node fields when no discharge node is active", function()
    local d = VDT.Discharge.collect(dischargeable(0))
    assert.is_nil(d.nodeIndex)
    assert.is_nil(d.hasObject)
  end)
end)

describe("Tipping.collect", function()
  local function trailer(tipState, current, preferred, count, sides)
    return {
      spec_trailer = {
        tipState = tipState,
        currentTipSideIndex = current,
        preferedTipSideIndex = preferred,
        tipSideCount = count,
        tipSides = sides,
      },
    }
  end

  it("returns nil when the object does not tip", function()
    assert.is_nil(VDT.Tipping.collect({}))
  end)

  it("maps the four tip states", function()
    assert.are.equal("CLOSED", VDT.Tipping.collect(trailer(0)).state)
    assert.are.equal("OPENING", VDT.Tipping.collect(trailer(1)).state)
    assert.are.equal("OPEN", VDT.Tipping.collect(trailer(2)).state)
    assert.are.equal("CLOSING", VDT.Tipping.collect(trailer(3)).state)
  end)

  it("keeps the current and preferred sides apart", function()
    -- Not tipping yet: no side is in use, but one is already chosen for next time.
    local idle = VDT.Tipping.collect(trailer(0, nil, 3, 3))
    assert.is_nil(idle.side)
    assert.are.equal(3, idle.preferredSide)
    assert.are.equal(3, idle.count)

    local tipping = VDT.Tipping.collect(trailer(2, 2, 3, 3))
    assert.are.equal(2, tipping.side)
  end)

  it("names the sides, index-aligned with side and preferredSide", function()
    -- Trailer:loadTipSide has already run the XML's #name through g_i18n:convertText with the
    -- machine's own customEnvironment, so these arrive localized and there is nothing to look up.
    local sides = { { name = "Links" }, { name = "Rechts" }, { name = "Hinten" } }
    local model = VDT.Tipping.collect(trailer(2, 2, 3, 3, sides))
    assert.are.same({ "Links", "Rechts", "Hinten" }, model.sides)
    assert.are.equal("Rechts", model.sides[model.side])
    assert.are.equal("Hinten", model.sides[model.preferredSide])
  end)

  it("keeps the list aligned rather than skipping an unnamed side", function()
    -- loadTipSide rejects a side whose #name does not resolve, so this should not happen; if it ever
    -- does, dropping the entry would relabel every side after it instead of merely missing one.
    local sides = { { name = "Links" }, {}, { name = "Hinten" } }
    local model = VDT.Tipping.collect(trailer(0, nil, 3, 3, sides))
    assert.are.same({ "Links", "", "Hinten" }, model.sides)
  end)

  it("leaves the names out on a trailer whose sides the game never named", function()
    assert.is_nil(VDT.Tipping.collect(trailer(0, nil, 1, 1)).sides)
  end)
end)

describe("Harvest.collect", function()
  it("returns nil when the object is not a combine", function()
    assert.is_nil(VDT.Harvest.collect({}))
  end)

  it("reports swath state and what the machine offers", function()
    local h = VDT.Harvest.collect({
      spec_combine = { isSwathActive = true, swath = { isAvailable = true }, chopper = { isAvailable = false } },
    })
    assert.is_true(h.swathActive)
    assert.is_true(h.swathAvailable)
    assert.is_false(h.chopperAvailable)
  end)

  it("defaults swathActive to false and omits absent capability flags", function()
    local h = VDT.Harvest.collect({ spec_combine = {} })
    assert.is_false(h.swathActive)
    assert.is_nil(h.swathAvailable)
    assert.is_nil(h.chopperAvailable)
  end)
end)

describe("Work.collectMode", function()
  it("returns nil without the spec, or when the spec declares no modes", function()
    assert.is_nil(VDT.Work.collectMode({}))
    -- The spec can be present but inert (stateMax 0), which is not a mode the player can pick.
    assert.is_nil(VDT.Work.collectMode({ spec_workMode = { state = 1, stateMax = 0 } }))
  end)

  it("resolves the current mode to its name", function()
    local m = VDT.Work.collectMode({
      spec_workMode = { state = 2, stateMax = 2, workModes = { { name = "Transport" }, { name = "Arbeit" } } },
    })
    assert.are.equal(2, m.current)
    assert.are.equal(2, m.count)
    assert.are.equal("Arbeit", m.name)
  end)

  it("omits the name when the mode was declared without one", function()
    local m = VDT.Work.collectMode({ spec_workMode = { state = 1, stateMax = 1, workModes = { {} } } })
    assert.are.equal(1, m.current)
    assert.is_nil(m.name)
  end)
end)

describe("Work.collectWidth", function()
  -- The only one of the five that formats through ValueMapper.mapFloat, which needs the engine's
  -- MathUtil (stubbed the same way PrecisionFarming_spec does).
  before_each(function()
    rawset(_G, "MathUtil", {
      round = function(v, decimals)
        local mult = 10 ^ (decimals or 0)
        return math.floor(v * mult + 0.5) / mult
      end,
    })
  end)

  after_each(function()
    rawset(_G, "MathUtil", nil)
  end)

  local function widthObject(left, leftMax, right, rightMax, hasSections)
    return {
      spec_variableWorkWidth = { hasSections = hasSections ~= false },
      getVariableWorkWidth = function(_, isLeft)
        if isLeft then
          return left, leftMax, true
        end
        return right, rightMax, true
      end,
    }
  end

  it("returns nil without the spec, or when the tool has no sections", function()
    assert.is_nil(VDT.Work.collectWidth({}))
    assert.is_nil(VDT.Work.collectWidth(widthObject(1, 1, 1, 1, false)))
  end)

  it("reports each side independently plus the total", function()
    -- The engine measures a side by its section node's local X, so the right-hand one comes back
    -- negative (VariableWorkWidth:onPostLoad) -- adding the two as they arrive totalled 0 on every
    -- tool that has sections. Here the right side is folded in to half width, a normal headland
    -- technique.
    local w = VDT.Work.collectWidth(widthObject(3, 3, -1.5, -3))
    assert.are.equal(3, w.left)
    assert.are.equal(3, w.leftMax)
    assert.are.equal(1.5, w.right)
    assert.are.equal(3, w.rightMax)
    assert.are.equal(4.5, w.total)
    assert.are.equal("m", w.unit)
  end)

  it("counts a side with no sections as nothing, not as its placeholder", function()
    -- A side the tool has no sections on answers `1, 1, false` -- not one meter.
    local w = VDT.Work.collectWidth({
      spec_variableWorkWidth = { hasSections = true },
      getVariableWorkWidth = function(_, isLeft)
        if isLeft then
          return 6, 6, true
        end
        return 1, 1, false
      end,
    })
    assert.are.equal(0, w.right)
    assert.are.equal(0, w.rightMax)
    assert.are.equal(6, w.total)
  end)
end)

describe("BaleCounter.collect", function()
  it("returns nil when the object counts no bales", function()
    assert.is_nil(VDT.BaleCounter.collect({}))
  end)

  it("reports both counters", function()
    local c = VDT.BaleCounter.collect({ spec_baleCounter = { sessionCounter = 12, lifetimeCounter = 480 } })
    assert.are.equal(12, c.session)
    assert.are.equal(480, c.lifetime)
  end)
end)
