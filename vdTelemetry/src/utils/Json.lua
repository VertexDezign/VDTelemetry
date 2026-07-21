-- Minimal JSON encoder (pure Lua, no engine dependency). The serialize step of the mod's
-- collect -> model -> Json.encode pipeline: a model table goes straight to a JSON string.
--
-- Encoding rules (kept deliberately small):
--   * string  -> quoted, with control chars / " / \ escaped (others as \uXXXX)
--   * number  -> integer-valued numbers without a decimal point, else %.14g;
--                NaN / +-Infinity become null (JSON has no such literals)
--   * boolean -> true / false
--   * table   -> array if it has sequential integer keys (1..#t), otherwise an object
--   * nil / anything else -> null
--
-- Two output modes:
--   * minified (default) -- compact, keys in Lua hash order. For production / the wire.
--   * pretty             -- indented AND object keys sorted alphabetically, so the output is
--                           deterministic and diff-stable even as optional keys appear/disappear.
--                           For watching the file live during development.
-- JSON objects are unordered, so sorting is purely cosmetic and the consumer (kotlinx) ignores it.
--
-- Shape of the implementation, driven by the heavy channels (mapLayers writes ~1.5 MB of hex rows):
--   * every encoder appends its pieces to one flat buffer that is table.concat'd once at the end,
--     instead of returning a string per level. Concatenating per level re-copies the whole payload
--     at every nesting depth (row -> rows array -> layer -> layers -> root).
--   * the escape scan is memoized for long strings -- see CLEAN_MIN_LEN below.
--   * pretty and minified are separate walkers so the hot (minified) one carries no indent
--     bookkeeping and no per-value mode check.

Json = {}

local INDENT = "  "

-- Library functions used per value; upvalues instead of global/table lookups in the walk.
local concat = table.concat
local sort = table.sort
local format = string.format
local find = string.find
local gsub = string.gsub
local byte = string.byte
local rep = string.rep
local floor = math.floor
local abs = math.abs
local huge = math.huge
local type = type
local pairs = pairs
local tostring = tostring

local ESCAPES = {
  ['"'] = '\\"',
  ["\\"] = "\\\\",
  ["\b"] = "\\b",
  ["\f"] = "\\f",
  ["\n"] = "\\n",
  ["\r"] = "\\r",
  ["\t"] = "\\t",
}

local function escapeChar(c)
  return ESCAPES[c] or format("\\u%04x", byte(c))
end

-- Memo of strings already known to need no escaping. Lua's pattern matcher runs at roughly 10 ns per
-- character, so the scan -- not the tree walk -- dominates a mapLayers encode: 1.5 MB of hex rows is
-- ~15 ms of scanning per write, every write, for a verdict that is almost always the same one as last
-- time. A table lookup on an already-interned string is O(1) (Lua caches the hash in the string
-- itself), so memoizing the verdict turns that 15 ms into well under 1 ms.
--
-- The memo survives a full sweep rebuilding its row tables: Lua interns strings, so a row whose
-- content did not change encodes to the *same* string object and hits the memo. Only genuinely
-- changed rows pay the scan.
--
-- Short strings (names, ids, enum labels) are scanned every time instead: a 20-character scan costs
-- ~0.2 us, and memoizing them would fill the memo with entries that never pay for themselves.
local CLEAN_MIN_LEN = 24
local CLEAN_MAX = 4096
local clean = {}
local cleanCount = 0

---Append the JSON form of a string as buffer pieces (quote, body, quote -- the body goes in as-is when
---it needs no escaping, so a clean string is never copied).
---@param s string
---@param out string[] output buffer
---@param n number pieces written so far
---@return number n
local function pushString(s, out, n)
  local safe
  if #s >= CLEAN_MIN_LEN then
    safe = clean[s]
    if safe == nil then
      safe = find(s, '[%c"\\]') == nil
      -- Bounded: drop the whole memo rather than grow without limit on a map whose rows keep changing.
      if cleanCount >= CLEAN_MAX then
        clean = {}
        cleanCount = 0
      end
      clean[s] = safe
      cleanCount = cleanCount + 1
    end
  else
    safe = find(s, '[%c"\\]') == nil
  end
  out[n + 1] = '"'
  out[n + 2] = safe and s or gsub(s, '[%c"\\]', escapeChar)
  out[n + 3] = '"'
  return n + 3
end

local function encodeString(s)
  if find(s, '[%c"\\]') == nil then
    return '"' .. s .. '"'
  end
  return '"' .. gsub(s, '[%c"\\]', escapeChar) .. '"'
end

local function encodeNumber(n)
  if n ~= n or n == huge or n == -huge then
    return "null"
  end
  if floor(n) == n and abs(n) < 1e15 then
    return format("%.0f", n)
  end
  return format("%.14g", n)
end

-- Encoded object keys ('"name":'), cached: the model's key set is small and fixed, but the same keys
-- are re-encoded on every write of every channel. Minified only -- pretty sorts and spaces its keys.
local keys = {}

local function encodeKey(k)
  local encoded = keys[k]
  if encoded == nil then
    encoded = encodeString(type(k) == "string" and k or tostring(k)) .. ":"
    keys[k] = encoded
  end
  return encoded
end

-- forward declarations so the walkers can recurse through themselves
local encodeMinified
local encodePretty

---@param v any value to encode
---@param out string[] output buffer
---@param n number pieces written so far
---@return number n
encodeMinified = function(v, out, n)
  local t = type(v)
  if t == "string" then
    return pushString(v, out, n)
  elseif t == "number" then
    n = n + 1
    out[n] = encodeNumber(v)
  elseif t == "table" then
    local len = #v
    if len > 0 then
      n = n + 1
      out[n] = "["
      n = encodeMinified(v[1], out, n)
      for i = 2, len do
        n = n + 1
        out[n] = ","
        n = encodeMinified(v[i], out, n)
      end
      n = n + 1
      out[n] = "]"
    else
      n = n + 1
      out[n] = "{"
      local first = true
      for k, value in pairs(v) do
        if first then
          first = false
        else
          n = n + 1
          out[n] = ","
        end
        n = n + 1
        out[n] = encodeKey(k)
        n = encodeMinified(value, out, n)
      end
      n = n + 1
      out[n] = "}"
    end
  elseif t == "boolean" then
    n = n + 1
    out[n] = v and "true" or "false"
  else
    n = n + 1
    out[n] = "null"
  end
  return n
end

---@param v any value to encode
---@param out string[] output buffer
---@param n number pieces written so far
---@param level number current nesting depth, for indentation
---@return number n
encodePretty = function(v, out, n, level)
  local t = type(v)
  if t == "string" then
    return pushString(v, out, n)
  elseif t == "number" then
    n = n + 1
    out[n] = encodeNumber(v)
  elseif t == "table" then
    local len = #v
    local child = rep(INDENT, level + 1)
    local close = rep(INDENT, level)
    if len > 0 then
      n = n + 1
      out[n] = "[\n"
      for i = 1, len do
        if i > 1 then
          n = n + 1
          out[n] = ",\n"
        end
        n = n + 1
        out[n] = child
        n = encodePretty(v[i], out, n, level + 1)
      end
      n = n + 1
      out[n] = "\n" .. close .. "]"
    else
      local sorted, count = {}, 0
      for k in pairs(v) do
        count = count + 1
        sorted[count] = k
      end
      if count == 0 then
        n = n + 1
        out[n] = "{}"
        return n
      end
      sort(sorted, function(a, b)
        return tostring(a) < tostring(b)
      end)
      n = n + 1
      out[n] = "{\n"
      for i = 1, count do
        local k = sorted[i]
        if i > 1 then
          n = n + 1
          out[n] = ",\n"
        end
        n = n + 1
        out[n] = child
        n = n + 1
        out[n] = encodeString(tostring(k)) .. ": "
        n = encodePretty(v[k], out, n, level + 1)
      end
      n = n + 1
      out[n] = "\n" .. close .. "}"
    end
  elseif t == "boolean" then
    n = n + 1
    out[n] = v and "true" or "false"
  else
    n = n + 1
    out[n] = "null"
  end
  return n
end

---Encode a Lua value as a JSON string.
---@param value any
---@param pretty boolean? when true, indent output and sort object keys (deterministic, diff-stable). Default minified.
---@return string
function Json.encode(value, pretty)
  local out = {}
  local n
  if pretty == true then
    n = encodePretty(value, out, 0, 0)
  else
    n = encodeMinified(value, out, 0)
  end
  return concat(out, "", 1, n)
end
