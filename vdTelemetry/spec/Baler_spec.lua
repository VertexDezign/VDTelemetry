-- Unit tests for the baler and bale-wrapper aspects (src/collect/aspects/Baler.lua, BaleWrapper.lua)
-- and their controls (src/command/BalerControl.lua).
--
-- Run with `busted` from the vdTelemetry/ directory. The objects are built from the engine fields the
-- collectors read, named as in Baler.lua / BaleWrapper.lua.
--
-- What is worth testing:
--   * `fillUnit` is a position in the EXPORTED list, not the engine's index -- the two differ on any
--     self-propelled baler, whose diesel comes first and is not exported.
--   * `unload` follows the game's own decision tree, including its one surprise (a finished bale
--     wins over an unfinished one) and the platform branch that ignores auto-drop.
--   * the drop command acts only while the engine still offers the action the app saw, because the
--     engine's handler toggles the door.

if Set == nil then
  dofile("src/utils/Set.lua")
end
if ValueMapper == nil then
  dofile("src/mapper/ValueMapper.lua")
end
if VDT == nil or VDT.FillUnit == nil then
  dofile("src/collect/aspects/FillUnit.lua")
end
if VDT.CommandRegistry == nil then
  dofile("src/command/CommandRegistry.lua")
end
if VDT.TargetResolver == nil then
  dofile("src/command/TargetResolver.lua")
end
dofile("src/collect/aspects/Baler.lua")
dofile("src/collect/aspects/BaleWrapper.lua")
dofile("src/command/BalerControl.lua")

local debugger = { debug = function() end, warn = function() end }

local FILL_TYPES = {
  [1] = { name = "UNKNOWN" },
  [2] = { name = "DIESEL" },
  [3] = { name = "GRASS_WINDROW" },
  [4] = { name = "BALE_NET" },
}

local function merge(base, over)
  for k, v in pairs(over or {}) do
    base[k] = v
  end
  return base
end

---A towed round baler with a chamber and a net unit. `over` replaces spec_baler fields.
local function roundBaler(over, objectOver)
  local spec = merge({
    isRoundBaler = true,
    hasUnloadingAnimation = true,
    allowsBaleUnloading = false,
    fillUnitIndex = 1,
    unloadingState = 1,
    bales = {},
    baleTypes = {
      { isRoundBale = true, diameter = 1.25, width = 1.2 },
      { isRoundBale = true, diameter = 1.5, width = 1.2 },
    },
    currentBaleTypeIndex = 1,
    preSelectedBaleTypeIndex = 1,
    automaticDrop = false,
    toggleableAutomaticDrop = true,
    lastAreaBiggerZero = true,
    buffer = {},
  }, over)
  local object = {
    spec_baler = spec,
    spec_fillUnit = { fillUnits = { { fillType = 3 }, { fillType = 4 } } },
    calls = {},
    powered = true,
    unfinished = false,
    getIsPowered = function(self)
      return self.powered, "start the motor"
    end,
    isUnloadingAllowed = function()
      return true
    end,
    getCanUnloadUnfinishedBale = function(self)
      return self.unfinished
    end,
    handleUnloadingBaleEvent = function(self)
      table.insert(self.calls, "handleUnloadingBaleEvent")
    end,
    dropBaleFromPlatform = function(self, wait)
      table.insert(self.calls, "dropBaleFromPlatform:" .. tostring(wait))
    end,
    setBaleTypeIndex = function(self, index)
      table.insert(self.calls, "setBaleTypeIndex:" .. index)
    end,
    setBalerAutomaticDrop = function(self, on)
      table.insert(self.calls, "setBalerAutomaticDrop:" .. tostring(on))
    end,
  }
  return merge(object, objectOver)
end

---A square baler: a channel with an animation curve, no door, three sizes-worth of nothing.
local function squareBaler(over)
  return roundBaler(merge({
    isRoundBaler = false,
    hasUnloadingAnimation = false,
    baleAnimCurve = {},
    baleTypes = { { isRoundBale = false, width = 1.2, height = 0.9, length = 2.4 } },
    automaticDrop = true,
    toggleableAutomaticDrop = false,
  }, over))
end

local function wrapper(over, objectOver)
  local round = { animTime = 5000, currentTime = 0 }
  local spec = merge({
    roundBaleWrapper = round,
    squareBaleWrapper = { animTime = 5000, currentTime = 0 },
    currentWrapper = round,
    baleWrapperState = 0,
    automaticDrop = false,
    toggleableAutomaticDrop = true,
    showInvalidBaleWarning = false,
  }, over)
  local object = {
    spec_baleWrapper = spec,
    calls = {},
    powered = true,
    getIsPowered = function(self)
      return self.powered
    end,
    setBaleWrapperAutomaticDrop = function(self, on)
      table.insert(self.calls, "setBaleWrapperAutomaticDrop:" .. tostring(on))
    end,
  }
  return merge(object, objectOver)
end

describe("Baler", function()
  before_each(function()
    _G.g_fillTypeManager = {
      getFillTypeByIndex = function(_, index)
        return FILL_TYPES[index] or FILL_TYPES[1]
      end,
    }
  end)

  describe("collect", function()
    it("is nil on anything that is not a baler", function()
      assert.is_nil(VDT.Baler.collect({}))
    end)

    it("describes a round baler", function()
      local m = VDT.Baler.collect(roundBaler())
      assert.is_true(m.round)
      assert.are.equal(1, m.fillUnit)
      assert.is_true(m.working)
      assert.is_true(m.powered)
      assert.are.equal("CLOSED", m.door)
      assert.is_nil(m.bales)
      assert.are.same({ { diameter = 1.25, width = 1.2 }, { diameter = 1.5, width = 1.2 } }, m.baleTypes)
      assert.are.equal(1, m.baleType)
      assert.is_nil(m.nextBaleType)
      assert.are.same({ on = false, canToggle = true }, m.autoDrop)
      assert.is_nil(m.platform)
      assert.is_nil(m.buffer)
    end)

    it("points at the chamber's EXPORTED position, past an unexported diesel tank", function()
      local b = roundBaler({ fillUnitIndex = 2 })
      b.spec_fillUnit.fillUnits = { { fillType = 2 }, { fillType = 3 }, { fillType = 4 } }
      b.spec_motorized = { propellantFillUnitIndices = { 1 } }
      assert.are.equal(1, VDT.Baler.collect(b).fillUnit)
    end)

    it("leaves fillUnit out when the chamber is hidden from the info HUD", function()
      local b = roundBaler()
      b.spec_fillUnit.fillUnits[1].showOnInfoHud = false
      assert.is_nil(VDT.Baler.collect(b).fillUnit)
    end)

    it("finds the net through the Consumable spec, even with no fill type on its unit", function()
      -- The captured GOEWEIL VARIO-Master exports its net roll with no fill type at all.
      local b = roundBaler()
      b.spec_fillUnit.fillUnits[2] = { fillType = 1 }
      b.spec_consumable = { typesByName = { BALE_NET = { fillUnitIndex = 2 } } }
      assert.are.equal(2, VDT.Baler.collect(b).consumable)
    end)

    it("falls back to the other consumable name, and to nothing", function()
      local b = squareBaler()
      b.spec_consumable = { typesByName = { BALE_NET = { fillUnitIndex = 2 } } }
      assert.are.equal(2, VDT.Baler.collect(b).consumable)
      assert.is_nil(VDT.Baler.collect(squareBaler()).consumable)
    end)

    it("reports a size chosen for after the current bale", function()
      local m = VDT.Baler.collect(roundBaler({ preSelectedBaleTypeIndex = 2 }))
      assert.are.equal(1, m.baleType)
      assert.are.equal(2, m.nextBaleType)
    end)

    it("reports each finished square bale's place in the channel, clamped", function()
      local m = VDT.Baler.collect(squareBaler({ bales = { { time = 0.4 }, { time = 1.02 } } }))
      assert.is_false(m.round)
      assert.is_nil(m.door)
      assert.are.same({ { position = 0.4 }, { position = 1 } }, m.bales)
      assert.are.same({ { width = 1.2, height = 0.9, length = 2.4 } }, m.baleTypes)
    end)

    it("gives a round baler's waiting bale no position", function()
      local m = VDT.Baler.collect(roundBaler({ bales = { { time = 0 } } }))
      assert.are.same({ {} }, m.bales)
    end)

    it("reads a platform baler's auto-drop off the platform", function()
      local m = VDT.Baler.collect(squareBaler({
        hasPlatform = true,
        platformReadyToDrop = true,
        automaticDrop = true,
        platformAutomaticDrop = false,
      }))
      assert.are.same({ ready = true }, m.platform)
      assert.is_false(m.autoDrop.on)
    end)

    it("points at a bale collector's count", function()
      local b = squareBaler()
      b.spec_fillUnit.fillUnits[3] = { fillType = 3 }
      b.spec_baleLoader = { fillUnitIndex = 3 }
      assert.are.same({ fillUnit = 3 }, VDT.Baler.collect(b).collector)
      assert.is_nil(VDT.Baler.collect(squareBaler()).collector)
    end)

    it("describes a non-stop baler's buffer", function()
      local b = roundBaler({ nonStopBaling = true, buffer = { fillUnitIndex = 3, unloadingStarted = true } })
      b.spec_fillUnit.fillUnits[3] = { fillType = 3 }
      assert.are.same({ fillUnit = 3, overloading = true }, VDT.Baler.collect(b).buffer)
    end)
  end)

  describe("unloadAction", function()
    it("offers nothing on a closed chamber with nothing to drop", function()
      assert.is_nil(VDT.Baler.unloadAction(roundBaler()))
    end)

    it("offers UNLOAD on a finished bale", function()
      assert.are.equal("UNLOAD", VDT.Baler.unloadAction(roundBaler({ bales = { {} } })))
    end)

    it("prefers the finished bale over an unfinished one, as the game's text does", function()
      local b = roundBaler({ bales = { {} } }, { unfinished = true })
      assert.are.equal("UNLOAD", VDT.Baler.unloadAction(b))
    end)

    it("offers UNLOAD_UNFINISHED when the machine allows it", function()
      assert.are.equal("UNLOAD_UNFINISHED", VDT.Baler.unloadAction(roundBaler(nil, { unfinished = true })))
    end)

    it("offers CLOSE on an open door", function()
      assert.are.equal("CLOSE", VDT.Baler.unloadAction(roundBaler({ unloadingState = 3 })))
    end)

    it("offers nothing while the door moves", function()
      assert.is_nil(VDT.Baler.unloadAction(roundBaler({ unloadingState = 2, bales = { {} } })))
    end)

    it("offers nothing with auto-drop on", function()
      assert.is_nil(VDT.Baler.unloadAction(roundBaler({ automaticDrop = true, bales = { {} } })))
    end)

    it("offers nothing unpowered", function()
      assert.is_nil(VDT.Baler.unloadAction(roundBaler({ bales = { {} } }, { powered = false })))
    end)

    it("offers nothing when the wrapper behind cannot take the bale", function()
      local b = roundBaler({ bales = { {} } }, {
        isUnloadingAllowed = function()
          return false
        end,
      })
      assert.is_nil(VDT.Baler.unloadAction(b))
    end)

    it("offers DROP_PLATFORM whenever a bale waits on the platform", function()
      local b = squareBaler({ hasPlatform = true, platformReadyToDrop = true })
      assert.are.equal("DROP_PLATFORM", VDT.Baler.unloadAction(b))
    end)

    it("offers an unfinished bale on a platform baler even with auto-drop on", function()
      local b = squareBaler({ hasPlatform = true, allowsBaleUnloading = true }, nil)
      b.unfinished = true
      assert.are.equal("UNLOAD_UNFINISHED", VDT.Baler.unloadAction(b))
    end)
  end)
end)

describe("BaleWrapper.collect", function()
  it("is nil on anything that is not a wrapper", function()
    assert.is_nil(VDT.BaleWrapper.collect({}))
  end)

  it("describes an empty round wrapper", function()
    assert.are.same({
      state = "EMPTY",
      round = true,
      autoDrop = { on = false, canToggle = true },
      canDrop = false,
      unsupportedBale = false,
    }, VDT.BaleWrapper.collect(wrapper()))
  end)

  it("reports the wrap's progress while wrapping", function()
    local w = wrapper({ baleWrapperState = 3 })
    w.spec_baleWrapper.currentWrapper.currentTime = 2000
    local m = VDT.BaleWrapper.collect(w)
    assert.are.equal("WRAPPING", m.state)
    assert.are.equal(0.4, m.progress)
    assert.is_false(m.canDrop)
  end)

  it("holds a wrapped bale at full progress, droppable", function()
    local m = VDT.BaleWrapper.collect(wrapper({ baleWrapperState = 4 }))
    assert.are.equal("WRAPPED", m.state)
    assert.are.equal(1, m.progress)
    assert.is_true(m.canDrop)
  end)

  it("finds the wrap film", function()
    local w = wrapper()
    w.spec_fillUnit = { fillUnits = { { fillType = 4 } } }
    w.spec_consumable = { typesByName = { BALE_WRAP = { fillUnitIndex = 1 } } }
    assert.are.equal(1, VDT.BaleWrapper.collect(w).consumable)
  end)

  it("tells a square wrapper", function()
    local w = wrapper()
    w.spec_baleWrapper.currentWrapper = w.spec_baleWrapper.squareBaleWrapper
    assert.is_false(VDT.BaleWrapper.collect(w).round)
  end)

  it("cannot drop with auto-drop on or unpowered", function()
    assert.is_false(VDT.BaleWrapper.canDrop(wrapper({ baleWrapperState = 4, automaticDrop = true })))
    assert.is_false(VDT.BaleWrapper.canDrop(wrapper({ baleWrapperState = 4 }, { powered = false })))
  end)
end)

describe("BalerControl", function()
  before_each(function()
    _G.g_fillTypeManager = {
      getFillTypeByIndex = function(_, index)
        return FILL_TYPES[index] or FILL_TYPES[1]
      end,
    }
  end)

  it("resets the session counter when powered", function()
    local b = roundBaler(nil, {
      spec_baleCounter = {},
      doBaleCounterReset = function(self)
        table.insert(self.calls, "reset")
      end,
    })
    VDT.BalerControl.resetBaleCounter(b, "vehicle", debugger)
    assert.are.same({ "reset" }, b.calls)
    b.calls = {}
    b.powered = false
    VDT.BalerControl.resetBaleCounter(b, "vehicle", debugger)
    assert.are.same({}, b.calls)
  end)

  it("sets a bale size by index, and ignores the one already chosen or out of range", function()
    local b = roundBaler()
    VDT.BalerControl.setBaleType(b, "vehicle", 2, debugger)
    VDT.BalerControl.setBaleType(b, "vehicle", 1, debugger)
    VDT.BalerControl.setBaleType(b, "vehicle", 3, debugger)
    assert.are.same({ "setBaleTypeIndex:2" }, b.calls)
  end)

  it("offers no size change on a machine with one size", function()
    local b = squareBaler()
    VDT.BalerControl.setBaleType(b, "vehicle", 1, debugger)
    assert.are.same({}, b.calls)
  end)

  it("sets auto-drop absolutely, on the part named", function()
    local b = roundBaler()
    VDT.BalerControl.setBaleAutoDrop(b, "vehicle", "baler", true, debugger)
    assert.are.same({ "setBalerAutomaticDrop:true" }, b.calls)
    local w = wrapper()
    VDT.BalerControl.setBaleAutoDrop(w, "vehicle", "wrapper", false, debugger)
    assert.are.same({ "setBaleWrapperAutomaticDrop:false" }, w.calls)
  end)

  it("leaves a fixed auto-drop alone", function()
    local b = squareBaler()
    VDT.BalerControl.setBaleAutoDrop(b, "vehicle", "baler", false, debugger)
    assert.are.same({}, b.calls)
  end)

  it("unloads only while the engine still offers that action", function()
    local b = roundBaler({ bales = { {} } })
    VDT.BalerControl.unloadBale(b, "vehicle", "UNLOAD", debugger)
    assert.are.same({ "handleUnloadingBaleEvent" }, b.calls)
    -- The door is open now: a resent UNLOAD must not become a CLOSE.
    b.calls = {}
    b.spec_baler.unloadingState = 3
    b.spec_baler.bales = {}
    VDT.BalerControl.unloadBale(b, "vehicle", "UNLOAD", debugger)
    assert.are.same({}, b.calls)
    VDT.BalerControl.unloadBale(b, "vehicle", "CLOSE", debugger)
    assert.are.same({ "handleUnloadingBaleEvent" }, b.calls)
  end)

  it("tips a platform baler's platform", function()
    local b = squareBaler({ hasPlatform = true, platformReadyToDrop = true })
    VDT.BalerControl.unloadBale(b, "vehicle", "DROP_PLATFORM", debugger)
    assert.are.same({ "dropBaleFromPlatform:false" }, b.calls)
  end)

  it("drops a wrapped bale through the game's own drop key", function()
    local pressed = nil
    _G.BaleWrapper = {
      actionEventEmpty = function(object)
        pressed = object
      end,
    }
    local w = wrapper({ baleWrapperState = 3 })
    VDT.BalerControl.dropWrappedBale(w, "vehicle", debugger)
    assert.is_nil(pressed)
    w.spec_baleWrapper.baleWrapperState = 4
    VDT.BalerControl.dropWrappedBale(w, "vehicle", debugger)
    assert.are.equal(w, pressed)
    _G.BaleWrapper = nil
  end)
end)
