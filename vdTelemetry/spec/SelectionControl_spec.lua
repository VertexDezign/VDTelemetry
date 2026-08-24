-- Unit tests for src/command/SelectionControl.lua (the write side of the selection aspect).
--
-- Run with `busted` from the vdTelemetry/ directory. The control file self-registers into
-- VDT.CommandRegistry at load (dofile runs in the real _G), so we load CommandRegistry first -- but
-- only if it isn't already loaded, so we don't reset the registry another spec populated.
--
-- Two things are worth testing away from a game and are exactly the two that fail quietly:
--
--   * the node walk. A wrong index does not throw -- it selects a DIFFERENT machine on the same rig,
--     which looks like the command working.
--   * the selectability gate. setSelectedVehicle does not refuse an ineligible machine; it selects
--     the first eligible one instead, so a missing gate here moves the selection somewhere the
--     driver never tapped.

if VDT == nil or VDT.CommandRegistry == nil then
  dofile("src/command/CommandRegistry.lua")
end
dofile("src/command/SelectionControl.lua")

local debugger = { debug = function() end, warn = function() end }

-- A machine: selectable by default, records what the root was asked to select.
local function machine(name, opts)
  opts = opts or {}
  local m = {
    name = name,
    isSelected = opts.selected == true,
    canBeSelected = opts.canBeSelected ~= false,
    blockSelection = opts.blockSelection == true,
  }
  function m:getIsSelected()
    return self.isSelected
  end
  function m:getCanBeSelected()
    return self.canBeSelected
  end
  function m:getBlockSelection()
    return self.blockSelection
  end
  if opts.controlGroupMapping ~= nil then
    m.spec_cylindered = { controlGroupMapping = opts.controlGroupMapping }
  end
  return m
end

-- Hitch `implements` onto `parent`, in the order VehicleExporter walks them.
local function attach(parent, implements)
  local attached = {}
  for _, object in ipairs(implements) do
    table.insert(attached, { object = object })
  end
  parent.spec_attacherJoints = { attachedImplements = attached }
  return parent
end

-- A rig whose root records every setSelectedVehicle call. Every object on it shares that root, which
-- is what the engine does (rootVehicle is set on the whole chain).
local function rig(root, ...)
  root.calls = {}
  function root:setSelectedVehicle(vehicle, subSelectionIndex)
    table.insert(self.calls, { vehicle = vehicle, subSelectionIndex = subSelectionIndex })
  end
  local function claim(object)
    object.rootVehicle = root
    local spec = object.spec_attacherJoints
    if spec ~= nil then
      for _, attached in ipairs(spec.attachedImplements) do
        claim(attached.object)
      end
    end
  end
  claim(root)
  return root
end

describe("SelectionControl.resolve", function()
  it("resolves the root to the controlled vehicle", function()
    local tractor = machine("Tractor")
    assert.are.equal(tractor, VDT.SelectionControl.resolve(tractor, "0", debugger))
  end)

  it("walks attachedImplements, 0-based on the wire", function()
    -- The one that fails silently: index 1 exists too, so an off-by-one selects the wrong machine.
    local front = machine("Weight")
    local back = machine("Plough")
    local tractor = attach(machine("Tractor"), { front, back })

    assert.are.equal(front, VDT.SelectionControl.resolve(tractor, "0/0", debugger))
    assert.are.equal(back, VDT.SelectionControl.resolve(tractor, "0/1", debugger))
  end)

  it("reaches down the hitch chain", function()
    local bar = machine("Dribble bar")
    local barrel = attach(machine("Barrel"), { bar })
    local tractor = attach(machine("Tractor"), { barrel })

    assert.are.equal(bar, VDT.SelectionControl.resolve(tractor, "0/0/0", debugger))
  end)

  it("returns nil when the rig no longer has that node", function()
    -- Unhitched between the app drawing the diagram and the command arriving.
    local tractor = attach(machine("Tractor"), { machine("Plough") })
    assert.is_nil(VDT.SelectionControl.resolve(tractor, "0/1", debugger))
    assert.is_nil(VDT.SelectionControl.resolve(machine("Tractor"), "0/0", debugger))
  end)

  it("refuses a path that is not rooted at the vehicle", function()
    local tractor = attach(machine("Tractor"), { machine("Plough") })
    assert.is_nil(VDT.SelectionControl.resolve(tractor, "1/0", debugger))
    assert.is_nil(VDT.SelectionControl.resolve(tractor, "0/x", debugger))
    assert.is_nil(VDT.SelectionControl.resolve(tractor, "", debugger))
  end)
end)

describe("SelectionControl.setSelected", function()
  it("selects the machine the node names, through the root", function()
    local plough = machine("Plough")
    local tractor = rig(attach(machine("Tractor"), { plough }))

    VDT.SelectionControl.setSelected(tractor, "0/0", nil, debugger)
    assert.are.equal(1, #tractor.calls)
    assert.are.equal(plough, tractor.calls[1].vehicle)
  end)

  it("names the first control group when landing on a machine that was not selected", function()
    -- What the game's own cycling key does when it steps onto a new object.
    local plough = machine("Plough")
    local tractor = rig(attach(machine("Tractor"), { plough }))

    VDT.SelectionControl.setSelected(tractor, "0/0", nil, debugger)
    assert.are.equal(1, tractor.calls[1].subSelectionIndex)
  end)

  it("leaves the group alone when the machine is already selected", function()
    -- A bare tap on the machine the driver is already on must not knock its control group back to
    -- the first one; nil tells setSelectedObject to keep what it has (and clamp it).
    local loader = machine("Loader", { selected = true })
    local tractor = rig(attach(machine("Tractor"), { loader }))

    VDT.SelectionControl.setSelected(tractor, "0/0", nil, debugger)
    assert.is_nil(tractor.calls[1].subSelectionIndex)
  end)

  it("translates a control group into its sub-selection index", function()
    -- The two are different numbers: controlGroupMapping is subSelectionIndex -> group index, and
    -- only the groups whose tools are active are in it. Passing the group index straight through
    -- would select the wrong group whenever one is inactive.
    local loader = machine("Loader", { controlGroupMapping = { [1] = 1, [2] = 3 } })
    local tractor = rig(attach(machine("Tractor"), { loader }))

    VDT.SelectionControl.setSelected(tractor, "0/0", 3, debugger)
    assert.are.equal(2, tractor.calls[1].subSelectionIndex)
  end)

  it("still selects the machine when the named group has gone inactive", function()
    local loader = machine("Loader", { controlGroupMapping = { [1] = 1 } })
    local tractor = rig(attach(machine("Tractor"), { loader }))

    VDT.SelectionControl.setSelected(tractor, "0/0", 3, debugger)
    assert.are.equal(loader, tractor.calls[1].vehicle)
    assert.are.equal(1, tractor.calls[1].subSelectionIndex)
  end)

  it("does nothing at all when the machine cannot be selected", function()
    -- The whole reason `selection.selectable` exists: setSelectedVehicle would walk selectableObjects
    -- and select the FIRST eligible machine instead, so not gating here moves the selection to a
    -- machine the driver did not tap.
    local trailer = machine("Trailer", { canBeSelected = false })
    local tractor = rig(attach(machine("Tractor"), { trailer }))
    VDT.SelectionControl.setSelected(tractor, "0/0", nil, debugger)
    assert.are.same({}, tractor.calls)

    local blocked = machine("Bundle", { blockSelection = true })
    local other = rig(attach(machine("Tractor"), { blocked }))
    VDT.SelectionControl.setSelected(other, "0/0", nil, debugger)
    assert.are.same({}, other.calls)
  end)

  it("does nothing when the node names nothing", function()
    local tractor = rig(attach(machine("Tractor"), { machine("Plough") }))
    VDT.SelectionControl.setSelected(tractor, "0/7", nil, debugger)
    assert.are.same({}, tractor.calls)
  end)
end)
