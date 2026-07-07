-- Command back-channel (app -> mod), read side.
--
-- The terminal server writes commands into modSettings/<modName>/commands/commands.xml (a sibling
-- of the telemetry/ folder). The mod polls that file on the telemetry timer cadence and dispatches
-- commands it hasn't seen yet.
--
-- Why XML and not JSON: the FS25 Lua sandbox restricts io.open to WRITE mode ('w') only, so the mod
-- cannot read a file itself. The engine's XMLFile.load is the only file reader available (it's how
-- the mod reads its own settings). So telemetry stays JSON (write-only via io.open) but the command
-- channel must be XML. Each <command> carries an `id` + `type` envelope plus type-specific attributes
-- that the owning control parses (see CommandRegistry). File shape (written by the server):
--   <commands>
--     <command id="1" type="setLight" light="beacon" on="true"/>
--     <command id="2" type="setCruiseControl" action="setSpeed" speed="15"/>
--   </commands>
--
-- Dedup: each command carries a monotonic `id`; the mod tracks the highest id it has handled and
-- only dispatches ids greater than it. The server may rewrite the whole file each poll and keeps a
-- small ring of recent commands, so a missed poll doesn't drop intermediates -- filtering by id is
-- all the mod needs. A torn read (server mid-write) makes XMLFile.load fail -> skip, retry next
-- tick (the server writes temp + atomic rename, so this shouldn't happen in practice).
-- Namespaced under VDT.* (see aspects/TurnOn.lua).

VDT = VDT or {}
VDT.CommandChannel = {}

VDT.CommandChannel.FILE_NAME = "commands.xml"

---Filter a raw command list down to those newer than lastCommandId, sorted ascending by id.
---Pure (no engine calls) so it's unit-testable offline; poll() feeds it the envelopes it read.
---@param commands table[] each at least { id = number }
---@param lastCommandId number
---@return table[] pending commands to dispatch, ascending by id
function VDT.CommandChannel.selectNew(commands, lastCommandId)
  local pending = {}
  for _, cmd in ipairs(commands) do
    if type(cmd) == "table" and type(cmd.id) == "number" and cmd.id > lastCommandId then
      pending[#pending + 1] = cmd
    end
  end
  -- The file's element order is whatever the server wrote; sorting guarantees we dispatch (and
  -- advance lastCommandId) strictly in id order.
  table.sort(pending, function(a, b)
    return a.id < b.id
  end)
  return pending
end

---Poll the command file and dispatch every command newer than lastCommandId, in id order.
---Reads only the envelope (id + type) itself; the per-command payload is parsed by the control that
---owns the type, looked up in `registry`. So this stays free of command schemas -- a new command
---type is added by registering it, with no change here (see CommandRegistry).
---@param filePath string absolute path to commands.xml
---@param lastCommandId number highest command id already handled
---@param registry table command registry with get(type) -> { parse, execute } | nil
---@param handler fun(cmd: table) called once per new, known command (ascending by id) with
---  { id, type, params, execute }; params is what the control's parse() returned
---@param debugger GrisuDebug
---@return number newLastCommandId the highest id handled after this poll
function VDT.CommandChannel.poll(filePath, lastCommandId, registry, handler, debugger)
  -- loadIfExists returns nil when the file is absent OR unparsable (torn read); both mean "nothing
  -- to do this tick", so no separate fileExists guard is needed.
  local xml = XMLFile.loadIfExists("vdtCommands", filePath)
  if xml == nil then
    return lastCommandId
  end

  -- Envelope only (id + type). `key` stays valid for attribute reads until xml:delete() below, so we
  -- keep it to hand to each command's parse() after id-filtering.
  local entries = {}
  xml:iterate("commands.command", function(_, key)
    local id = xml:getInt(key .. "#id")
    if id ~= nil then
      entries[#entries + 1] = { id = id, type = xml:getString(key .. "#type"), key = key }
    end
  end)

  local pending = VDT.CommandChannel.selectNew(entries, lastCommandId)
  if #pending > 0 then
    -- Only when there's actually something new — the ring file is re-read every poll, so an
    -- unconditional log here would fire at the full 100 ms cadence forever.
    debugger:debug("command file: %d new command(s) (lastId=%d)", #pending, lastCommandId)
  end

  local newLast = lastCommandId
  for _, entry in ipairs(pending) do
    local cmdHandler = registry.get(entry.type)
    if cmdHandler == nil then
      debugger:warn("unknown command type: %s", tostring(entry.type))
    else
      -- delegate payload parsing to the control that owns this type
      local params = cmdHandler.parse(xml, entry.key)
      handler({ id = entry.id, type = entry.type, params = params, execute = cmdHandler.execute })
    end
    -- advance past every pending id, including unknown ones, so we don't re-warn each poll
    if entry.id > newLast then
      newLast = entry.id
    end
  end

  xml:delete()
  return newLast
end
