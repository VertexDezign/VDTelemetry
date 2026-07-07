-- Registry mapping a command `type` -> the handler that parses and runs it. Keeps the command reader
-- (CommandChannel) free of any per-command schema: poll() looks a type up here and delegates the
-- payload parse (and later the execute) to the control that owns it. Adding a command type is then a
-- local change to that control -- no edits to CommandChannel or the dispatch. Controls self-register
-- when their file is sourced (LightControl, ImplementControl, ...), so this must be sourced first.
--
-- A handler is a table with two halves that run at different stages:
--   parse   = function(xml, key) -> params
--       Reads only this command's own attributes off the XML element at `key` (e.g. key.."#light")
--       and returns a plain params table. Runs while the command file is open.
--   execute = function(vehicle, params, debugger)
--       Runs the command against the current vehicle. Runs once the current vehicle is known.
--
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.CommandRegistry = {}

local handlers = {}

---Register the handler for a command type. Last registration wins (types are unique).
---@param commandType string the command's `type` attribute
---@param handler table { parse = fun(xml, key): table, execute = fun(vehicle, params, debugger) }
function VDT.CommandRegistry.register(commandType, handler)
  handlers[commandType] = handler
end

---@param commandType string
---@return table|nil handler the registered handler, or nil when the type is unknown
function VDT.CommandRegistry.get(commandType)
  return handlers[commandType]
end
