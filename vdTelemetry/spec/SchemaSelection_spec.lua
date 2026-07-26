-- Unit tests for the schema and selection aspect collectors
-- (src/collect/aspects/{Schema,Selection}.lua).
--
-- Run with `busted` from the vdTelemetry/ directory. Both are pure reads of engine state with no
-- globals involved, so the objects below are plain tables shaped like the engine's.
--
-- Together these are what a rig diagram needs: which silhouette each object draws as, where its
-- children hang off it, and which node the player is currently driving. The one piece that is NOT
-- here is jointDescIndex — it lives on the parent's attacher-joint entry, so VehicleExporter sets it
-- while walking the tree.

if VDT == nil or VDT.Schema == nil then
  dofile("src/collect/aspects/Schema.lua")
end
if VDT.Selection == nil then
  dofile("src/collect/aspects/Selection.lua")
end

local function joint(x, y, rotation, invertX)
  return { x = x, y = y, rotation = rotation, invertX = invertX, liftedOffsetX = 0, liftedOffsetY = 5 }
end

local function withSchema(name, joints)
  return {
    schemaOverlay = {
      schemaName = name,
      offsetX = 0.25,
      offsetY = 0.5,
      invisibleBorderLeft = 0.05,
      invisibleBorderRight = 0.05,
      attacherJoints = joints,
    },
  }
end

-- An object that reports a selection state, optionally with Cylindered control groups.
local function selectable(isSelected, groupNames, currentGroup)
  local object = {
    getIsSelected = function()
      return isSelected
    end,
  }
  if groupNames ~= nil then
    object.spec_cylindered = { controlGroupNames = groupNames, currentControlGroupIndex = currentGroup }
  end
  return object
end

describe("Schema.collect", function()
  it("returns nil when the object defines no schema overlay", function()
    assert.is_nil(VDT.Schema.collect({}))
  end)

  it("reports the silhouette name, offsets and borders", function()
    local s = VDT.Schema.collect(withSchema("HARVESTER", nil))
    assert.are.equal("HARVESTER", s.name)
    assert.are.equal(0.25, s.offsetX)
    assert.are.equal(0.5, s.offsetY)
    assert.are.equal(0.05, s.borderLeft)
    assert.are.equal(0.05, s.borderRight)
  end)

  it("omits attacherJoint entirely when the object has none", function()
    -- The engine leaves attacherJoints nil until addAttacherJoint is called, so a plain implement
    -- has no list at all -- and a nil key stays out of the JSON.
    assert.is_nil(VDT.Schema.collect(withSchema("IMPLEMENT", nil)).attacherJoint)
  end)

  it("copies each attacher joint verbatim, in order", function()
    local s = VDT.Schema.collect(withSchema("VEHICLE", { joint(0.1, 0.2, 0, false), joint(0.9, 0.3, 1.5, true) }))
    assert.are.equal(2, #s.attacherJoint)

    assert.are.equal(0.1, s.attacherJoint[1].x)
    assert.are.equal(0.2, s.attacherJoint[1].y)
    assert.are.equal(0, s.attacherJoint[1].rotation)
    assert.is_false(s.attacherJoint[1].invertX)

    assert.are.equal(0.9, s.attacherJoint[2].x)
    assert.are.equal(1.5, s.attacherJoint[2].rotation)
    assert.is_true(s.attacherJoint[2].invertX)
    assert.are.equal(0, s.attacherJoint[2].liftedOffsetX)
    assert.are.equal(5, s.attacherJoint[2].liftedOffsetY)
  end)
end)

describe("Selection.collect", function()
  it("returns nil when the object cannot report a selection state", function()
    assert.is_nil(VDT.Selection.collect({}))
  end)

  it("mirrors the engine's selected flag", function()
    assert.is_true(VDT.Selection.collect(selectable(true)).selected)
    assert.is_false(VDT.Selection.collect(selectable(false)).selected)
  end)

  it("omits controlGroup when the object has no cylindered groups", function()
    assert.is_nil(VDT.Selection.collect(selectable(true)).controlGroup)
    -- spec_cylindered present but with no named groups is still nothing to cycle.
    assert.is_nil(VDT.Selection.collect(selectable(true, {}, 0)).controlGroup)
  end)

  it("resolves the current control group to its name", function()
    local g = VDT.Selection.collect(selectable(true, { "Kran", "Greifer" }, 2)).controlGroup
    assert.are.equal(2, g.current)
    assert.are.equal("Greifer", g.name)
    assert.are.same({ "Kran", "Greifer" }, g.names)
  end)

  it("leaves the name absent when no group is active", function()
    -- currentControlGroupIndex is 0 while the object is not active for input.
    local g = VDT.Selection.collect(selectable(false, { "Kran", "Greifer" }, 0)).controlGroup
    assert.are.equal(0, g.current)
    assert.is_nil(g.name)
    assert.are.equal(2, #g.names)
  end)
end)
