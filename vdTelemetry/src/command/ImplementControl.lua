-- Executes lower/fold/activate commands from the app -> mod back-channel, for the controlled
-- vehicle, its front/back attached implements, and whatever machine the game has selected. The
-- inverse of the VDT.Lowered / VDT.Foldable / VDT.TurnOn aspect collectors: it maps an absolute
-- target ("front lowered", "vehicle folded", "selected turned on") onto an action. Absolute (not
-- toggle) for the same reason as LightControl -- the file channel is lossy/async, so an idempotent
-- set-to-state is self-correcting where a dropped or doubled toggle would desync.
--
-- Every target routes through FS25_additionalInputs (a hard dependency), which owns the spec-aware
-- logic: attacher-joint lowering, fold-to-middle, requiresPower, the whole implement chain. We do
-- NOT reimplement any of that here -- doing so per spec was fragile (e.g. a self-propelled foldable
-- like the Krone BigM reports "lowered" via the Foldable fold-middle state, not Attachable, so a
-- hand-rolled setLoweredAll no-ops on it). additionalInputs exposes, on the vehicle:
--   vdAI<Action><Address>(on)   Action in {Lower,Fold,Activate}, Address in {Vehicle,Front,Back,Selected}
-- each taking the absolute on/off state.
--
-- `Selected` is the fourth address and the reason this file did not have to grow a fallback for
-- issue #120. A machine hitched behind another one has no hitch position of its own, so the three
-- positional functions cannot reach it -- and hand-rolling a selection-addressed lower here would
-- have walked straight back into the BigM scar above. It went into additionalInputs instead
-- (1.2.0.0), which is where the spec-aware logic already lives and where the fix stays in one place;
-- "act on the selected machine" is not a VDTelemetry-specific idea. That is a request granted, not
-- the standing rule bent: we still call only vdAI functions that exist, and we still do not add ones
-- only this mod would use. VDTelemetry.VD_AI.REQUIRED_MIN_MINOR_VERSION is what holds the mod to a
-- version that has them.
--
-- The addresses are NOT interchangeable where they overlap, which is why the app keeps choosing
-- between them (see controlTargetOf in the terminal): vdAILowerVehicle lowers the tractor alone,
-- while vdAILowerSelected lowers the selected machine AND cascades into everything hitched to it.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.ImplementControl = {}

-- target token -> FS25_additionalInputs function name per action.
local AI_FUNC = {
  vehicle = { lower = "vdAILowerVehicle", fold = "vdAIFoldVehicle", activate = "vdAIActivateVehicle" },
  front = { lower = "vdAILowerFront", fold = "vdAIFoldFront", activate = "vdAIActivateFront" },
  back = { lower = "vdAILowerBack", fold = "vdAIFoldBack", activate = "vdAIActivateBack" },
  selected = { lower = "vdAILowerSelected", fold = "vdAIFoldSelected", activate = "vdAIActivateSelected" },
}

-- Route an action for a target through FS25_additionalInputs' vdAI* function.
local function callAi(vehicle, target, action, on, debugger)
  local funcs = AI_FUNC[target]
  if funcs == nil then
    debugger:warn("implement control: unknown target '%s'", tostring(target))
    return
  end
  local name = funcs[action]
  if vehicle[name] == nil then
    -- additionalInputs is a hard dependency, so this should not happen; warn rather than error.
    debugger:warn("implement control: %s missing (FS25_additionalInputs not loaded?)", name)
    return
  end
  vehicle[name](vehicle, on)
  debugger:debug("%s(%s)", name, tostring(on))
end

---Lower (on=true) or raise the target.
---@param vehicle Vehicle
---@param target string vehicle|front|back|selected
---@param on boolean
---@param debugger GrisuDebug
function VDT.ImplementControl.setLowered(vehicle, target, on, debugger)
  callAi(vehicle, target, "lower", on, debugger)
end

---Fold (on=true, transport) or unfold the target.
---@param vehicle Vehicle
---@param target string vehicle|front|back|selected
---@param on boolean
---@param debugger GrisuDebug
function VDT.ImplementControl.setFolded(vehicle, target, on, debugger)
  callAi(vehicle, target, "fold", on, debugger)
end

---Turn the target on (on=true) or off.
---@param vehicle Vehicle
---@param target string vehicle|front|back|selected
---@param on boolean
---@param debugger GrisuDebug
function VDT.ImplementControl.setActivated(vehicle, target, on, debugger)
  callAi(vehicle, target, "activate", on, debugger)
end

-- Command handlers (see CommandRegistry). All three share the {target, on} payload.
local function parseTargetOn(xml, key)
  return {
    target = xml:getString(key .. "#target"),
    on = xml:getBool(key .. "#on", false),
  }
end

VDT.CommandRegistry.register("setLowered", {
  parse = parseTargetOn,
  execute = function(vehicle, params, debugger)
    VDT.ImplementControl.setLowered(vehicle, params.target, params.on, debugger)
  end,
})

VDT.CommandRegistry.register("setFolded", {
  parse = parseTargetOn,
  execute = function(vehicle, params, debugger)
    VDT.ImplementControl.setFolded(vehicle, params.target, params.on, debugger)
  end,
})

VDT.CommandRegistry.register("setActivated", {
  parse = parseTargetOn,
  execute = function(vehicle, params, debugger)
    VDT.ImplementControl.setActivated(vehicle, params.target, params.on, debugger)
  end,
})
