-- Minimal JSON encoder (pure Lua, no engine dependency).
--
-- Seeded during the JSON spike (see ROADMAP / restructure-design.md). Intended to grow into the
-- serialize step once the mod switches its on-disk format from XML to JSON.
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

Json = {}

local INDENT = '  '

local ESCAPES = {
  ['"'] = '\\"',
  ['\\'] = '\\\\',
  ['\b'] = '\\b',
  ['\f'] = '\\f',
  ['\n'] = '\\n',
  ['\r'] = '\\r',
  ['\t'] = '\\t',
}

local function escapeChar(c)
  return ESCAPES[c] or string.format('\\u%04x', string.byte(c))
end

local function encodeString(s)
  return '"' .. string.gsub(s, '[%c"\\]', escapeChar) .. '"'
end

local function encodeNumber(n)
  if n ~= n or n == math.huge or n == -math.huge then
    return 'null'
  end
  if math.floor(n) == n and math.abs(n) < 1e15 then
    return string.format('%.0f', n)
  end
  return string.format('%.14g', n)
end

-- forward declaration so the container encoders can recurse through it
local encodeValue

local function encodeArray(t, n, pretty, level)
  local parts = {}
  for i = 1, n do
    parts[i] = encodeValue(t[i], pretty, level + 1)
  end
  if not pretty then
    return '[' .. table.concat(parts, ',') .. ']'
  end
  local child = string.rep(INDENT, level + 1)
  for i = 1, n do
    parts[i] = child .. parts[i]
  end
  return '[\n' .. table.concat(parts, ',\n') .. '\n' .. string.rep(INDENT, level) .. ']'
end

local function encodeObject(t, pretty, level)
  local keys = {}
  for k in pairs(t) do
    keys[#keys + 1] = k
  end
  if #keys == 0 then
    return '{}'
  end
  if pretty then
    table.sort(keys, function(a, b) return tostring(a) < tostring(b) end)
  end

  local colon = pretty and ': ' or ':'
  local parts = {}
  for i = 1, #keys do
    local k = keys[i]
    parts[i] = encodeString(tostring(k)) .. colon .. encodeValue(t[k], pretty, level + 1)
  end
  if not pretty then
    return '{' .. table.concat(parts, ',') .. '}'
  end
  local child = string.rep(INDENT, level + 1)
  for i = 1, #parts do
    parts[i] = child .. parts[i]
  end
  return '{\n' .. table.concat(parts, ',\n') .. '\n' .. string.rep(INDENT, level) .. '}'
end

encodeValue = function(v, pretty, level)
  local t = type(v)
  if t == 'string' then
    return encodeString(v)
  elseif t == 'number' then
    return encodeNumber(v)
  elseif t == 'boolean' then
    return v and 'true' or 'false'
  elseif t == 'table' then
    local n = #v
    if n > 0 then
      return encodeArray(v, n, pretty, level)
    end
    return encodeObject(v, pretty, level)
  else
    return 'null'
  end
end

---Encode a Lua value as a JSON string.
---@param value any
---@param pretty boolean? when true, indent output and sort object keys (deterministic, diff-stable). Default minified.
---@return string
function Json.encode(value, pretty)
  return encodeValue(value, pretty == true, 0)
end
