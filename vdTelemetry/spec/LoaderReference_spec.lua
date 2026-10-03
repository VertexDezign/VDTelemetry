-- Unit tests for the loader "set level" path (issue #169): src/store/LoaderReferences.lua and
-- src/command/LoaderControl.lua, plus the `reference` the LoaderTool aspect reads back.
--
-- Run with `busted` from the vdTelemetry/ directory. The engine's XMLFile is replaced by an in-memory
-- one: a "file" is a flat map of attribute paths to values, so a save followed by a load really goes
-- through the same paths the game's would.
--
-- What is worth pinning:
--   * the key is the config file with the mods directory taken off, so it means the same tool on any
--     machine -- and two tools of one mod with the same file NAME stay apart;
--   * a reference survives a reload, and a store with a foreign version is ignored, not half-read;
--   * the command records what the tool reads WHEN THE TAP ARRIVES, and drops it when the tool has
--     left the loader in between;
--   * clearing is a real removal from the file, not a zero.

if ValueMapper == nil then
  dofile("src/mapper/ValueMapper.lua")
end
if VDT == nil or VDT.CommandRegistry == nil then
  dofile("src/command/CommandRegistry.lua")
end
for name, file in pairs({
  SelectionControl = "src/command/SelectionControl.lua",
  LoaderReferences = "src/store/LoaderReferences.lua",
  LoaderTool = "src/collect/aspects/LoaderTool.lua",
  LoaderControl = "src/command/LoaderControl.lua",
}) do
  if VDT == nil or VDT[name] == nil then
    dofile(file)
  end
end

local debugger = { debug = function() end, warn = function() end, error = function() end }

local MODS = "C:/Users/me/Documents/My Games/FarmingSimulator2025/mods/"
local PATH = "modSettings/FS25_vdTelemetry/loaderReferences.xml"

-- The in-memory disk: path -> { [attributePath] = value }.
local disk

local function xmlFile(path, values)
  local f = { path = path, values = values }
  local function get(_, key, default)
    local v = f.values[key]
    if v == nil then
      return default
    end
    return v
  end
  local function set(_, key, value)
    f.values[key] = value
  end
  f.getInt, f.getString, f.getFloat, f.getBool = get, get, get, get
  f.setInt, f.setString, f.setFloat, f.setBool = set, set, set, set
  function f.iterate(_, base, fn)
    local i = 0
    while f.values[string.format("%s(%d)#key", base, i)] ~= nil do
      fn(i + 1, string.format("%s(%d)", base, i))
      i = i + 1
    end
  end
  function f.save()
    disk[f.path] = f.values
  end
  function f.delete() end
  return f
end

local function stubEngine()
  disk = {}
  rawset(_G, "MathUtil", {
    round = function(v, decimals)
      local mult = 10 ^ (decimals or 0)
      return math.floor(v * mult + 0.5) / mult
    end,
  })
  rawset(_G, "Utils", {
    removeModDirectory = function(file)
      if file:sub(1, #MODS) == MODS then
        return file:sub(#MODS + 1)
      end
      return file
    end,
  })
  rawset(_G, "XMLFile", {
    loadIfExists = function(_, path)
      if disk[path] == nil then
        return nil
      end
      local copy = {}
      for k, v in pairs(disk[path]) do
        copy[k] = v
      end
      return xmlFile(path, copy)
    end,
    create = function(_, path)
      return xmlFile(path, {})
    end,
  })
  -- What LoaderTool needs to read a tool: a front-loader joint, a level root, the ground 0.4 m below.
  rawset(_G, "AttacherJoints", { jointTypeNameToInt = { frontloader = 7, implement = 1 } })
  rawset(_G, "CollisionFlag", { TERRAIN = 1, STATIC_OBJECT = 2, VEHICLE = 4, DYNAMIC_OBJECT = 8 })
  rawset(_G, "localDirectionToWorld", function()
    return 1, 0, 0
  end)
  rawset(_G, "localToWorld", function()
    return 0, 2, 0
  end)
  rawset(_G, "raycastAll", function(_, _, _, _, dirY, _, _, callbackName, target)
    if dirY < 0 then
      target[callbackName](target, 1, 0, 0, 0, 0.4)
    end
  end)
  VDT.LoaderReferences.load(PATH, debugger)
end

local function unstub()
  for _, name in ipairs({
    "MathUtil",
    "Utils",
    "XMLFile",
    "AttacherJoints",
    "CollisionFlag",
    "localDirectionToWorld",
    "localToWorld",
    "raycastAll",
  }) do
    rawset(_G, name, nil)
  end
end

---Tractor -> loader -> shovel, the way the captures have it. Returns the tractor and the shovel.
local function rig(shovelFile, jointName)
  local shovel = {
    rootNode = 100,
    vehicleNodes = { [100] = {} },
    configFileName = shovelFile or "data/vehicles/albutt/shovel/shovel.xml",
    spec_attachable = {},
    getActiveInputAttacherJoint = function()
      return { jointType = AttacherJoints.jointTypeNameToInt[jointName or "frontloader"] }
    end,
  }
  local loader = { spec_attacherJoints = { attachedImplements = { { object = shovel } } } }
  local tractor = { spec_attacherJoints = { attachedImplements = { { object = loader } } } }
  shovel.rootVehicle = {
    getChildVehicles = function()
      return { tractor, loader, shovel }
    end,
  }
  return tractor, shovel
end

describe("VDT.LoaderReferences", function()
  before_each(stubEngine)
  after_each(unstub)

  it("keys a base-game tool by its data path and a mod tool by its path inside the mod", function()
    assert.are.equal(
      "data/vehicles/a/shovel.xml",
      VDT.LoaderReferences.keyOf({ configFileName = "data/vehicles/a/shovel.xml" })
    )
    assert.are.equal(
      "FS25_Pack/shovels/shovel.xml",
      VDT.LoaderReferences.keyOf({ configFileName = MODS .. "FS25_Pack/shovels/shovel.xml" })
    )
    -- Tool Inclination Helper would key both of these "FS25_Pack|shovel" and give them one zero.
    assert.are_not.equal(
      VDT.LoaderReferences.keyOf({ configFileName = MODS .. "FS25_Pack/forks/shovel.xml" }),
      VDT.LoaderReferences.keyOf({ configFileName = MODS .. "FS25_Pack/shovels/shovel.xml" })
    )
    assert.is_nil(VDT.LoaderReferences.keyOf({}))
  end)

  it("keeps a reference across a reload", function()
    VDT.LoaderReferences.set("a.xml", { pitch = 1.5, distance = 0.02 }, debugger)
    VDT.LoaderReferences.set("b.xml", { pitch = -2 }, debugger)
    VDT.LoaderReferences.load(PATH, debugger)
    assert.are.same({ pitch = 1.5, distance = 0.02 }, VDT.LoaderReferences.get("a.xml"))
    assert.are.same({ pitch = -2 }, VDT.LoaderReferences.get("b.xml"))
  end)

  it("removes a cleared reference from the file", function()
    VDT.LoaderReferences.set("a.xml", { pitch = 1.5 }, debugger)
    VDT.LoaderReferences.clear("a.xml", debugger)
    VDT.LoaderReferences.load(PATH, debugger)
    assert.is_nil(VDT.LoaderReferences.get("a.xml"))
  end)

  it("ignores a store of another version rather than reading half of it", function()
    disk[PATH] = {
      ["loaderReferences#version"] = 99,
      ["loaderReferences.tool(0)#key"] = "a.xml",
      ["loaderReferences.tool(0)#pitch"] = 3,
    }
    VDT.LoaderReferences.load(PATH, debugger)
    assert.is_nil(VDT.LoaderReferences.get("a.xml"))
  end)

  it("starts empty when there is no file yet", function()
    assert.is_nil(VDT.LoaderReferences.get("a.xml"))
    assert.is_nil(VDT.LoaderReferences.get(nil))
  end)
end)

describe("VDT.LoaderControl", function()
  before_each(stubEngine)
  after_each(unstub)

  local function run(tractor, node, on)
    local handler = VDT.CommandRegistry.get("setLoaderReference")
    handler.execute(tractor, { node = node, on = on }, debugger)
  end

  it("records what the tool reads when the tap arrives, and the export carries it beside the raw values", function()
    local tractor, shovel = rig()
    run(tractor, "0/0/0", true)
    local reading = VDT.LoaderTool.collect(shovel)
    assert.are.same({ pitch = 0, distance = 0.4 }, reading.reference)
    -- Raw stays raw: the reference is never subtracted mod-side.
    assert.are.equal(0, reading.pitch)
    assert.are.equal(0.4, reading.distance)
  end)

  it("gives every copy of the same model the same zero", function()
    local tractor = rig("data/vehicles/albutt/shovel/shovel.xml")
    run(tractor, "0/0/0", true)
    local _, another = rig("data/vehicles/albutt/shovel/shovel.xml")
    assert.is_not_nil(VDT.LoaderTool.collect(another).reference)
    local _, different = rig("data/vehicles/albutt/fork/fork.xml")
    assert.is_nil(VDT.LoaderTool.collect(different).reference)
  end)

  it("forgets the reference on off", function()
    local tractor, shovel = rig()
    run(tractor, "0/0/0", true)
    run(tractor, "0/0/0", false)
    assert.is_nil(VDT.LoaderTool.collect(shovel).reference)
    assert.is_nil(VDT.LoaderReferences.get("data/vehicles/albutt/shovel/shovel.xml"))
  end)

  it("records nothing for a machine that is not on a loader's tool joint any more", function()
    -- Re-hitched to a three-point linkage between the export and the tap.
    local tractor = rig(nil, "implement")
    run(tractor, "0/0/0", true)
    assert.is_nil(VDT.LoaderReferences.get("data/vehicles/albutt/shovel/shovel.xml"))
  end)

  it("records nothing when the path names nothing", function()
    local tractor = rig()
    run(tractor, "0/3", true)
    assert.is_nil(VDT.LoaderReferences.get("data/vehicles/albutt/shovel/shovel.xml"))
  end)

  it("parses node and on off the command element", function()
    local values = { ["c#node"] = "0/0/0", ["c#on"] = true }
    local xml = {
      getString = function(_, k)
        return values[k]
      end,
      getBool = function(_, k, d)
        if values[k] == nil then
          return d
        end
        return values[k]
      end,
    }
    assert.are.same({ node = "0/0/0", on = true }, VDT.CommandRegistry.get("setLoaderReference").parse(xml, "c"))
  end)
end)
