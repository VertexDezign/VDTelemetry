-- Unit tests for the Variable Tire Pressure integration (src/integrations/VariableTirePressure.lua) and
-- its write side (src/command/TirePressureControl.lua).
--
-- Run with `busted` from the vdTelemetry/ directory. The machines are shaped like the mod's own
-- (1.0.0.13): a `spec_variableTirePressure` alias set in its onLoad, the dashboard getters it registers
-- on the vehicle type, and its specialization class in the type's `specializationsByName` under the
-- mod-qualified name, which is where the control finds the key handler.

if ValueMapper == nil then
  dofile("src/mapper/ValueMapper.lua")
end
if VDT == nil or VDT.CommandRegistry == nil then
  dofile("src/command/CommandRegistry.lua")
end
dofile("src/integrations/VariableTirePressure.lua")
dofile("src/command/TirePressureControl.lua")

-- The pressures are rounded through ValueMapper.mapFloat, which needs the engine's MathUtil (stubbed
-- the same way GpsCourse_spec does).
rawset(_G, "MathUtil", {
  round = function(value, decimals)
    local mult = 10 ^ (decimals or 0)
    return math.floor(value * mult + 0.5) / mult
  end,
})

local debugger = { debug = function() end, warn = function() end }

local MODE_CODES = { ROAD = 0, FIELD = 1, MIXED_FRONT_ROAD = 2, MIXED_FRONT_FIELD = 3 }

---A machine with the mod working on it. `o` overrides: mode (a MODE_CODES key), bar, target, road,
---field, split (axles apart, with front/rear { bar, road }), enabled, implements, isServer.
local function machine(o)
  o = o or {}
  local mode = o.mode or "ROAD"
  local spec = {
    isConfigEnabled = o.enabled ~= false,
    isRoadMode = mode == "ROAD" or mode == "MIXED_FRONT_ROAD",
    tractorAxleSplitActive = o.split ~= nil,
  }
  local implements = {}
  for _, child in ipairs(o.implements or {}) do
    implements[#implements + 1] = { object = child }
  end
  local object = {
    spec_variableTirePressure = spec,
    isServer = o.isServer,
    set = {},
    vtpGetDashboardMode = function()
      return MODE_CODES[mode]
    end,
    vtpGetDashboardPressureBar = function()
      return o.bar or 2.0
    end,
    vtpGetDashboardPressureTarget = function()
      return o.target or o.bar or 2.0
    end,
    vtpGetRoadPressure = function()
      return o.road or 2.0
    end,
    vtpGetFieldPressure = function()
      return o.field or 0.8
    end,
    getAttachedImplements = function()
      return implements
    end,
  }
  if o.split ~= nil then
    object.vtpGetDashboardPressureBarFront = function()
      return o.split.front.bar
    end
    object.vtpGetDashboardPressureBarRear = function()
      return o.split.rear.bar
    end
    object.vtpGetDashboardModeFront = function()
      return o.split.front.road and 0 or 1
    end
    object.vtpGetDashboardModeRear = function()
      return o.split.rear.road and 0 or 1
    end
  end
  function object:vtpSetRoadMode(isRoad)
    self.set[#self.set + 1] = isRoad
  end
  return object
end

---The class as the vehicle type holds it, recording the key presses its handler receives.
local function withClass(vehicle, folder)
  local presses = {}
  vehicle.specializationsByName = {
    wheels = {},
    [(folder or "FS25_VariableTirePressure") .. ".variableTirePressure"] = {
      actionEventTogglePressure = function(self, action, inputValue, callbackState)
        assert.are.equal(vehicle, self)
        assert.are.equal(InputAction.CALLBACK_STATE_START, callbackState)
        presses[#presses + 1] = action
      end,
    },
  }
  return presses
end

describe("VariableTirePressure.contributeObject", function()
  it("exports the pressure, its target, the mode and the setpoints", function()
    local model = {}
    VDT.VariableTirePressure.contributeObject(machine({ mode = "FIELD", bar = 1.234, target = 0.8 }), model)
    assert.are.same({ mode = "FIELD", bar = 1.23, targetBar = 0.8, roadBar = 2.0, fieldBar = 0.8 }, model.tirePressure)
  end)

  it("puts the higher setpoint on road, as the mod's own HUD does", function()
    local model = {}
    VDT.VariableTirePressure.contributeObject(machine({ road = 0.6, field = 1.8 }), model)
    assert.are.equal(1.8, model.tirePressure.roadBar)
    assert.are.equal(0.6, model.tirePressure.fieldBar)
  end)

  it("exports each axle only while they run apart", function()
    local model = {}
    VDT.VariableTirePressure.contributeObject(machine(), model)
    assert.is_nil(model.tirePressure.front)

    local split = { front = { bar = 2.0, road = true }, rear = { bar = 0.9, road = false } }
    VDT.VariableTirePressure.contributeObject(machine({ mode = "MIXED_FRONT_ROAD", bar = 1.45, split = split }), model)
    assert.are.equal("MIXED", model.tirePressure.mode)
    assert.are.same({ bar = 2.0, mode = "ROAD" }, model.tirePressure.front)
    assert.are.same({ bar = 0.9, mode = "FIELD" }, model.tirePressure.rear)
  end)

  it("skips a machine whose wheels were not bought with the system", function()
    local model = {}
    VDT.VariableTirePressure.contributeObject(machine({ enabled = false }), model)
    assert.is_nil(model.tirePressure)
  end)

  it("skips a machine without the mod, and one whose getters are missing", function()
    local model = {}
    VDT.VariableTirePressure.contributeObject({}, model)
    assert.is_nil(model.tirePressure)

    VDT.VariableTirePressure.contributeObject({ spec_variableTirePressure = { isConfigEnabled = true } }, model)
    assert.is_nil(model.tirePressure)
  end)
end)

describe("TirePressureControl.setTirePressure", function()
  setup(function()
    rawset(_G, "InputAction", { CALLBACK_STATE_START = 1 })
  end)

  it("presses the tractor key only when the tractor is not already there", function()
    local tractor = machine({ mode = "ROAD" })
    local presses = withClass(tractor)
    VDT.TirePressureControl.setTirePressure(tractor, "vehicle", true, debugger)
    assert.are.same({}, presses)

    VDT.TirePressureControl.setTirePressure(tractor, "vehicle", false, debugger)
    assert.are.same({ "VTP_SET_TIRE_PRESSURE_TRACTOR" }, presses)
  end)

  it("presses the tractor key on axles that disagree, whichever way was asked", function()
    local split = { front = { bar = 2.0, road = true }, rear = { bar = 0.9, road = false } }
    local tractor = machine({ mode = "MIXED_FRONT_ROAD", split = split })
    local presses = withClass(tractor)
    VDT.TirePressureControl.setTirePressure(tractor, "vehicle", true, debugger)
    assert.are.same({ "VTP_SET_TIRE_PRESSURE_TRACTOR" }, presses)
  end)

  it("toggles the implements from the first one, depth-first", function()
    local behind = machine({ mode = "FIELD" })
    local trailer = machine({ mode = "ROAD", implements = { behind } })
    local tractor = machine({ enabled = false, implements = { trailer } })
    local presses = withClass(tractor)

    VDT.TirePressureControl.setTirePressure(tractor, "implements", false, debugger)
    assert.are.same({ "VTP_SET_TIRE_PRESSURE_TOOLS" }, presses)
  end)

  it("sets straggling implements directly on the server, where the key would turn them all", function()
    local behind = machine({ mode = "FIELD" })
    local trailer = machine({ mode = "ROAD", implements = { behind } })
    local tractor = machine({ implements = { trailer }, isServer = true })
    local presses = withClass(tractor)

    VDT.TirePressureControl.setTirePressure(tractor, "implements", true, debugger)
    assert.are.same({}, presses)
    assert.are.same({}, trailer.set)
    assert.are.same({ true }, behind.set)
  end)

  it("leaves straggling implements alone on a multiplayer client", function()
    local behind = machine({ mode = "FIELD" })
    local trailer = machine({ mode = "ROAD", implements = { behind } })
    local tractor = machine({ implements = { trailer }, isServer = false })
    local presses = withClass(tractor)

    VDT.TirePressureControl.setTirePressure(tractor, "implements", true, debugger)
    assert.are.same({}, presses)
    assert.are.same({}, behind.set)
  end)

  it("brings a whole rig that disagrees to one mode, a key per part that needs it", function()
    local trailer = machine({ mode = "FIELD" })
    local tractor = machine({ mode = "ROAD", implements = { trailer } })
    local presses = withClass(tractor)

    VDT.TirePressureControl.setTirePressure(tractor, "all", true, debugger)
    assert.are.same({ "VTP_SET_TIRE_PRESSURE_TOOLS" }, presses)

    presses = withClass(tractor)
    VDT.TirePressureControl.setTirePressure(tractor, "all", false, debugger)
    assert.are.same({ "VTP_SET_TIRE_PRESSURE_TRACTOR" }, presses)
  end)

  it("finds the class under whatever folder the mod was installed as", function()
    local tractor = machine({ mode = "ROAD" })
    local presses = withClass(tractor, "FS25_VariableTirePressure_update")
    VDT.TirePressureControl.setTirePressure(tractor, "vehicle", false, debugger)
    assert.are.same({ "VTP_SET_TIRE_PRESSURE_TRACTOR" }, presses)
  end)

  it("ignores a vehicle without the mod, a bad scope and a missing mode", function()
    VDT.TirePressureControl.setTirePressure(machine(), "vehicle", false, debugger)

    local tractor = machine({ mode = "ROAD" })
    local presses = withClass(tractor)
    VDT.TirePressureControl.setTirePressure(tractor, "front", false, debugger)
    VDT.TirePressureControl.setTirePressure(tractor, "vehicle", nil, debugger)
    assert.are.same({}, presses)
  end)

  it("parses the scope and the absolute mode off the command", function()
    local handler = VDT.CommandRegistry.get("setTirePressure")
    local xml = {
      getString = function(_, key)
        assert.are.equal("c#scope", key)
        return "all"
      end,
      getBool = function(_, key)
        assert.are.equal("c#road", key)
        return false
      end,
    }
    assert.are.same({ scope = "all", road = false }, handler.parse(xml, "c"))
  end)
end)
