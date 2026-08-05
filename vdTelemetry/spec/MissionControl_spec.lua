-- Unit tests for src/command/MissionControl.lua (accept / cancel / collect a contract).
--
-- Run with `busted` from the vdTelemetry/ directory. Load order mirrors VDTelemetry.lua: the control
-- takes its permission + status helpers from MissionExporter (which registers a channel at load, so
-- ExportChannels first, and reads the farm through ProductionExporter) and self-registers into
-- CommandRegistry. We stub the mission manager, the three engine event classes and a client
-- connection, and capture what was sent.
--
-- What is worth pinning down is the refusals: every one of them is the difference between a clear log
-- line and a silent no-op, because the mod has no way to answer the app yet.

if VDT == nil or VDT.CommandRegistry == nil then
  dofile("src/command/CommandRegistry.lua")
end
if VDT.ExportChannels == nil then
  dofile("src/export/ExportChannels.lua")
end
if VDT.ProductionExporter == nil then
  dofile("src/collect/ProductionExporter.lua")
end
if VDT.MapExporter == nil then
  dofile("src/collect/MapExporter.lua")
end
if VDT.MissionExporter == nil then
  dofile("src/collect/MissionExporter.lua")
end
dofile("src/command/MissionControl.lua")

local STATUS = { CREATED = 1, PREPARING = 2, RUNNING = 3, FINISHED = 4, DISMISSED = 5 }

local warnings
local debugger = {
  warn = function(_, fmt, ...)
    warnings[#warnings + 1] = string.format(fmt, ...)
  end,
  debug = function() end,
}

local sent -- the last event handed to the server connection

local function contract(over)
  over = over or {}
  local status = over.status or STATUS.CREATED
  return {
    objectId = over.objectId or 42,
    status = status,
    farmId = over.farmId,
    getIsReadyToStart = function()
      return status == STATUS.CREATED
    end,
    getIsInProgress = function()
      return status == STATUS.PREPARING or status == STATUS.RUNNING
    end,
    isSpawnSpaceAvailable = function()
      return over.spawnSpace ~= false
    end,
  }
end

local function installWorld(missions, opts)
  opts = opts or {}
  sent = nil
  warnings = {}
  VDT.MissionControl.subscribed = nil
  _G.MissionStatus = STATUS
  _G.MissionStartState = { OK = 1, LIMIT_REACHED = 2, NO_PERMISSION = 8 }
  _G.Farm = { PERMISSION = { MANAGE_CONTRACTS = "manageContracts" } }
  _G.NetworkUtil = {
    getObjectId = function(object)
      return object.objectId
    end,
  }
  _G.g_localPlayer = opts.farmId ~= false and { farmId = opts.farmId or 1 } or nil
  _G.g_missionManager = {
    missions = missions,
    hasFarmReachedMissionLimit = function()
      return opts.limitReached == true
    end,
  }
  _G.g_currentMission = {
    getHasPlayerPermission = function()
      return opts.canManage ~= false
    end,
  }
  _G.g_messageCenter = { subscribe = function() end }
  _G.MissionStartEvent = {
    new = function(mission, farmId, lease)
      return { kind = "start", mission = mission, farmId = farmId, lease = lease }
    end,
  }
  _G.MissionCancelEvent = {
    new = function(mission)
      return { kind = "cancel", mission = mission }
    end,
  }
  _G.MissionDismissEvent = {
    new = function(mission)
      return { kind = "dismiss", mission = mission }
    end,
  }
  _G.g_client = {
    getServerConnection = function()
      return {
        sendEvent = function(_, event)
          sent = event
        end,
      }
    end,
  }
end

local function run(commandType, params)
  VDT.CommandRegistry.get(commandType).execute(nil, params, debugger)
end

after_each(function()
  for _, name in ipairs({
    "MissionStatus",
    "MissionStartState",
    "Farm",
    "NetworkUtil",
    "g_localPlayer",
    "g_missionManager",
    "g_currentMission",
    "g_messageCenter",
    "MissionStartEvent",
    "MissionCancelEvent",
    "MissionDismissEvent",
    "g_client",
  }) do
    _G[name] = nil
  end
end)

describe("acceptMission", function()
  it("sends the game's own start event for the farm", function()
    local mission = contract({ objectId = 77 })
    installWorld({ mission })

    run("acceptMission", { missionId = 77, lease = false })

    assert.are.equal("start", sent.kind)
    assert.are.equal(mission, sent.mission)
    assert.are.equal(1, sent.farmId)
    assert.is_false(sent.lease)
  end)

  it("passes the lease flag through once there is space for the machines", function()
    installWorld({ contract({ objectId = 77 }) })
    run("acceptMission", { missionId = 77, lease = true })
    assert.is_true(sent.lease)
  end)

  it("refuses to lease with nowhere to put the machines", function()
    -- The in-game screen warns rather than spawning nothing; so do we.
    installWorld({ contract({ objectId = 77, spawnSpace = false }) })
    run("acceptMission", { missionId = 77, lease = true })
    assert.is_nil(sent)
    assert.is_truthy(warnings[1]:find("no free space"))
  end)

  it("refuses a contract that is no longer on offer", function()
    -- Someone else took it, or it timed out, between the app's last update and the click.
    installWorld({ contract({ objectId = 77, status = STATUS.RUNNING, farmId = 2 }) })
    run("acceptMission", { missionId = 77 })
    assert.is_nil(sent)
    assert.is_truthy(warnings[1]:find("no longer on offer"))
  end)

  it("refuses once the farm is at its contract limit", function()
    installWorld({ contract({ objectId = 77 }) }, { limitReached = true })
    run("acceptMission", { missionId = 77 })
    assert.is_nil(sent)
    assert.is_truthy(warnings[1]:find("maximum number"))
  end)

  it("refuses an id that no longer names a contract", function()
    -- Contracts disappear on their own; a stale id must fail to resolve rather than act on whatever
    -- object happens to carry that network id now.
    installWorld({ contract({ objectId = 77 }) })
    run("acceptMission", { missionId = 78 })
    assert.is_nil(sent)
    assert.is_truthy(warnings[1]:find("no contract with id 78"))
  end)

  it("refuses a player without the manage-contracts right", function()
    installWorld({ contract({ objectId = 77 }) }, { canManage = false })
    run("acceptMission", { missionId = 77 })
    assert.is_nil(sent)
    assert.is_truthy(warnings[1]:find("may not manage contracts"))
  end)

  it("refuses without a farm to act for", function()
    installWorld({ contract({ objectId = 77 }) }, { farmId = false })
    run("acceptMission", { missionId = 77 })
    assert.is_nil(sent)
  end)
end)

describe("cancelMission", function()
  it("gives up a running contract of this farm", function()
    local mission = contract({ objectId = 5, status = STATUS.RUNNING, farmId = 1 })
    installWorld({ mission })

    run("cancelMission", { missionId = 5 })

    assert.are.equal("cancel", sent.kind)
    assert.are.equal(mission, sent.mission)
  end)

  it("refuses another farm's contract", function()
    installWorld({ contract({ objectId = 5, status = STATUS.RUNNING, farmId = 2 }) })
    run("cancelMission", { missionId = 5 })
    assert.is_nil(sent)
    assert.is_truthy(warnings[1]:find("another farm"))
  end)

  it("refuses a contract that was never started", function()
    installWorld({ contract({ objectId = 5 }) })
    run("cancelMission", { missionId = 5 })
    assert.is_nil(sent)
    assert.is_truthy(warnings[1]:find("not running"))
  end)
end)

describe("dismissMission", function()
  it("collects a finished contract", function()
    local mission = contract({ objectId = 6, status = STATUS.FINISHED, farmId = 1 })
    installWorld({ mission })

    run("dismissMission", { missionId = 6 })

    assert.are.equal("dismiss", sent.kind)
    assert.are.equal(mission, sent.mission)
  end)

  it("also collects one already marked dismissed", function()
    -- The in-game screen offers its button for both states, and the engine's dismiss is idempotent.
    installWorld({ contract({ objectId = 6, status = STATUS.DISMISSED, farmId = 1 }) })
    run("dismissMission", { missionId = 6 })
    assert.are.equal("dismiss", sent.kind)
  end)

  it("refuses a contract that is still running", function()
    installWorld({ contract({ objectId = 6, status = STATUS.RUNNING, farmId = 1 }) })
    run("dismissMission", { missionId = 6 })
    assert.is_nil(sent)
    assert.is_truthy(warnings[1]:find("has not finished"))
  end)
end)

describe("MissionControl command parsing", function()
  -- The XML the server writes carries ints and a bool; the parse half must read exactly those.
  local xml = {
    getInt = function(_, key)
      return key:find("#missionId") and 91 or nil
    end,
    getBool = function(_, key, default)
      if key:find("#lease") then
        return true
      end
      return default
    end,
  }

  it("reads the contract id and the lease flag", function()
    local params = VDT.CommandRegistry.get("acceptMission").parse(xml, "commands.command(0)")
    assert.are.equal(91, params.missionId)
    assert.is_true(params.lease)

    assert.are.equal(91, VDT.CommandRegistry.get("cancelMission").parse(xml, "commands.command(0)").missionId)
    assert.are.equal(91, VDT.CommandRegistry.get("dismissMission").parse(xml, "commands.command(0)").missionId)
  end)

  it("acts on the farm, not on a vehicle", function()
    -- Nothing here touches the driven vehicle, so the dispatcher must run these on foot too.
    for _, name in ipairs({ "acceptMission", "cancelMission", "dismissMission" }) do
      assert.is_false(VDT.CommandRegistry.get(name).requiresVehicle)
    end
  end)
end)
