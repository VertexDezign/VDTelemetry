-- Unit tests for the pure-Lua JSON encoder (src/utils/Json.lua).
--
-- Run with `busted` from the vdTelemetry/ directory (see .github/workflows/ci.yml). Json.lua sets a
-- global `Json` when executed and has no engine dependency, so we just load it directly. Not part of
-- the shipped mod (never source()'d).

dofile("src/utils/Json.lua")

describe("Json.encode", function()
  describe("strings", function()
    it("quotes plain strings", function()
      assert.are.equal([=["hello"]=], Json.encode("hello"))
    end)

    it("escapes quotes and backslashes", function()
      -- input string is: a"b\c
      assert.are.equal([=["a\"b\\c"]=], Json.encode('a"b\\c'))
    end)

    it("escapes the named control characters", function()
      assert.are.equal([=["\n\t\r"]=], Json.encode("\n\t\r"))
    end)

    it("escapes other control characters as unicode escapes", function()
      assert.are.equal([=["\u0001"]=], Json.encode(string.char(1)))
    end)
  end)

  describe("numbers", function()
    it("emits integer-valued numbers without a decimal point", function()
      assert.are.equal("5", Json.encode(5))
      assert.are.equal("0", Json.encode(0))
      assert.are.equal("-42", Json.encode(-42))
    end)

    it("emits fractional numbers", function()
      assert.are.equal("7.73", Json.encode(7.73))
      assert.are.equal("0.5", Json.encode(0.5))
    end)

    it("emits NaN and infinities as null (JSON has no such literals)", function()
      assert.are.equal("null", Json.encode(0 / 0))
      assert.are.equal("null", Json.encode(math.huge))
      assert.are.equal("null", Json.encode(-math.huge))
    end)
  end)

  describe("booleans and nil", function()
    it("emits booleans", function()
      assert.are.equal("true", Json.encode(true))
      assert.are.equal("false", Json.encode(false))
    end)

    it("emits nil as null", function()
      assert.are.equal("null", Json.encode(nil))
    end)
  end)

  describe("arrays", function()
    it("emits a sequential table as an array", function()
      assert.are.equal("[1,2,3]", Json.encode({ 1, 2, 3 }))
    end)

    it("escapes string elements", function()
      assert.are.equal([=[["a","b"]]=], Json.encode({ "a", "b" }))
    end)

    it("nests arrays", function()
      assert.are.equal("[[1,2],[3]]", Json.encode({ { 1, 2 }, { 3 } }))
    end)
  end)

  describe("objects (minified)", function()
    it("emits a single-key object", function()
      assert.are.equal([=[{"a":1}]=], Json.encode({ a = 1 }))
    end)

    it("emits an empty table as an object", function()
      assert.are.equal("{}", Json.encode({}))
    end)

    it("nests objects", function()
      assert.are.equal([=[{"a":{"b":2}}]=], Json.encode({ a = { b = 2 } }))
    end)

    it("emits every key of a multi-key object (minified order is hash-dependent)", function()
      local out = Json.encode({ a = 1, b = 2 })
      assert.is_truthy(out:find([=["a":1]=], 1, true))
      assert.is_truthy(out:find([=["b":2]=], 1, true))
      assert.are.equal("{", out:sub(1, 1))
      assert.are.equal("}", out:sub(-1))
    end)
  end)

  describe("pretty mode (indented, keys sorted)", function()
    it("indents and sorts object keys alphabetically", function()
      assert.are.equal(
        [=[{
  "a": 1,
  "b": 2
}]=],
        Json.encode({ b = 2, a = 1 }, true)
      )
    end)

    it("indents arrays", function()
      assert.are.equal(
        [=[[
  1,
  2
]]=],
        Json.encode({ 1, 2 }, true)
      )
    end)

    it("keeps an empty object compact", function()
      assert.are.equal("{}", Json.encode({}, true))
    end)

    it("produces deterministic, diff-stable output for a model-shaped payload", function()
      local model = {
        version = "1",
        motor = { state = "ON", rpm = { value = 850, min = 0, max = 2200 } },
      }
      assert.are.equal(
        [=[{
  "motor": {
    "rpm": {
      "max": 2200,
      "min": 0,
      "value": 850
    },
    "state": "ON"
  },
  "version": "1"
}]=],
        Json.encode(model, true)
      )
    end)
  end)
end)
