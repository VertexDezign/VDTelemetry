-- Unit tests for src/command/CropRotationControl.lua (FS25_CropRotation write-back commands).
--
-- Run with `busted` from the vdTelemetry/ directory. The control self-registers its command types
-- into VDT.CommandRegistry at load, so we load CommandRegistry first (only if not already loaded, so
-- we don't reset a registry another spec populated). The handlers reach the planner through the mod's
-- env global FS25_CropRotation.g_cropRotationPlanner; we stub a planner that records the wrapper calls
-- it receives, plus g_localPlayer for createRotation.

if VDT == nil or VDT.CommandRegistry == nil then
  dofile("src/command/CommandRegistry.lua")
end
dofile("src/command/CropRotationControl.lua")

local debugger = {
  warn = function() end,
  debug = function() end,
}

-- A planner stub that records each wrapper call, and resolves plans by index from a fixed set.
local function installPlanner(plans)
  local calls = {}
  local pl
  pl = {
    calls = calls,
    getCropRotationWithIndex = function(_, index)
      for _, cr in pairs(plans) do
        if cr.index == index then
          return cr
        end
      end
      return nil
    end,
    updateCropSelection = function(_, cr, slot, state)
      calls[#calls + 1] = { "updateCropSelection", cr.index, slot, state }
    end,
    updateCatchCropSelection = function(_, cr, slot, state)
      calls[#calls + 1] = { "updateCatchCropSelection", cr.index, slot, state }
    end,
    addCropRotationSelection = function(_, cr)
      calls[#calls + 1] = { "addCropRotationSelection", cr.index }
    end,
    removeCropRotationSelection = function(_, cr)
      calls[#calls + 1] = { "removeCropRotationSelection", cr.index }
    end,
    addCropRotation = function(_, name, farmId)
      calls[#calls + 1] = { "addCropRotation", name, farmId }
    end,
    removeCropRotation = function(_, cr)
      calls[#calls + 1] = { "removeCropRotation", cr.index }
    end,
  }
  rawset(_G, "FS25_CropRotation", { g_cropRotationPlanner = pl })
  return calls
end

-- Run a registered command's execute() with the given params (parse is exercised separately).
local function run(cmdType, params)
  VDT.CommandRegistry.get(cmdType).execute(nil, params, debugger)
end

before_each(function()
  rawset(_G, "FS25_CropRotation", nil)
  rawset(_G, "g_localPlayer", { farmId = 1 })
end)

describe("CropRotationControl registration", function()
  it("registers every command with requiresVehicle = false", function()
    for _, cmdType in ipairs({
      "setRotationCrop",
      "setRotationCatchCrop",
      "addRotationSlot",
      "removeRotationSlot",
      "createRotation",
      "deleteRotation",
    }) do
      local handler = VDT.CommandRegistry.get(cmdType)
      assert.is_not_nil(handler)
      assert.are.equal(false, handler.requiresVehicle)
    end
  end)
end)

describe("CropRotationControl execute", function()
  it("drives updateCropSelection / updateCatchCropSelection with the slot + state", function()
    local calls = installPlanner({ { index = 7, rotations = { {}, {}, {} } } })

    run("setRotationCrop", { rotationIndex = 7, slot = 2, state = 5 })
    run("setRotationCatchCrop", { rotationIndex = 7, slot = 3, catchCropState = 1 })

    assert.are.same({ "updateCropSelection", 7, 2, 5 }, calls[1])
    assert.are.same({ "updateCatchCropSelection", 7, 3, 1 }, calls[2])
  end)

  it("ignores a slot edit that points past the plan's slots", function()
    local calls = installPlanner({ { index = 7, rotations = { {} } } })
    run("setRotationCrop", { rotationIndex = 7, slot = 4, state = 5 })
    assert.are.equal(0, #calls)
  end)

  it("adds and removes slots, refusing to drop the last one", function()
    local calls = installPlanner({ { index = 7, rotations = { {}, {} } } })
    run("addRotationSlot", { rotationIndex = 7 })
    run("removeRotationSlot", { rotationIndex = 7 })
    assert.are.same({ "addCropRotationSelection", 7 }, calls[1])
    assert.are.same({ "removeCropRotationSelection", 7 }, calls[2])

    -- With a single slot left, removeRotationSlot is a no-op.
    local single = installPlanner({ { index = 7, rotations = { {} } } })
    run("removeRotationSlot", { rotationIndex = 7 })
    assert.are.equal(0, #single)
  end)

  it("creates a rotation on the local player's farm and deletes by index", function()
    local calls = installPlanner({ { index = 7, rotations = { {} } } })
    run("createRotation", { name = "Heavy Soil" })
    run("deleteRotation", { rotationIndex = 7 })
    assert.are.same({ "addCropRotation", "Heavy Soil", 1 }, calls[1])
    assert.are.same({ "removeCropRotation", 7 }, calls[2])
  end)

  it("skips createRotation when there's no local farm", function()
    local calls = installPlanner({})
    rawset(_G, "g_localPlayer", nil)
    run("createRotation", { name = "X" })
    assert.are.equal(0, #calls)
  end)

  it("no-ops every command when the mod isn't installed", function()
    -- FS25_CropRotation is nil (before_each); nothing should throw.
    assert.has_no.errors(function()
      run("setRotationCrop", { rotationIndex = 1, slot = 1, state = 1 })
      run("addRotationSlot", { rotationIndex = 1 })
      run("createRotation", { name = "X" })
      run("deleteRotation", { rotationIndex = 1 })
    end)
  end)

  it("ignores a command targeting an unknown rotation index", function()
    local calls = installPlanner({ { index = 7, rotations = { {} } } })
    run("deleteRotation", { rotationIndex = 999 })
    assert.are.equal(0, #calls)
  end)
end)
