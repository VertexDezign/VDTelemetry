-- Command back-channel (app -> mod), read side. Proof of concept.
--
-- The terminal server writes commands into modSettings/<modName>/commands/commands.xml (a sibling
-- of the telemetry/ folder). The mod polls that file on the telemetry timer cadence and dispatches
-- commands it hasn't seen yet.
--
-- Why XML and not JSON: the FS25 Lua sandbox restricts io.open to WRITE mode ('w') only, so the mod
-- cannot read a file itself. The engine's XMLFile.load is the only file reader available (it's how
-- the mod reads its own settings). So telemetry stays JSON (write-only via io.open) but the command
-- channel must be XML. File shape (written by the server; for the PoC by dev/write-command.sh):
--   <commands>
--     <command id="1" type="toggleLights"/>
--     <command id="2" type="setCruiseSpeed" value="15"/>
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
---Pure (no engine calls) so it's unit-testable offline; poll() feeds it what it read from XML.
---@param commands table[] each { id = number, type = string, value = string|nil }
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
---@param filePath string absolute path to commands.xml
---@param lastCommandId number highest command id already handled
---@param handler fun(cmd: table) called once per new command, ascending by id
---@param debugger GrisuDebug
---@return number newLastCommandId the highest id handled after this poll
function VDT.CommandChannel.poll(filePath, lastCommandId, handler, debugger)
  -- loadIfExists returns nil when the file is absent OR unparsable (torn read); both mean "nothing
  -- to do this tick", so no separate fileExists guard is needed.
  local xml = XMLFile.loadIfExists("vdtCommands", filePath)
  if xml == nil then
    return lastCommandId
  end

  -- Read the command attributes here (this is the one place that touches XML). The set is the union
  -- of every command type's schema; a given command only reads the fields it needs in onCommand.
  local commands = {}
  xml:iterate("commands.command", function(_, key)
    local id = xml:getInt(key .. "#id")
    if id ~= nil then
      commands[#commands + 1] = {
        id = id,
        type = xml:getString(key .. "#type"),
        -- setLight: which light + absolute on/off
        light = xml:getString(key .. "#light"),
        on = xml:getBool(key .. "#on", false),
        -- setTurnLight: absolute turn-light state (off/left/right/hazard)
        state = xml:getString(key .. "#state"),
      }
    end
  end)
  xml:delete()

  local pending = VDT.CommandChannel.selectNew(commands, lastCommandId)
  if #pending > 0 then
    -- Only when there's actually something new — the ring file is re-read every poll, so an
    -- unconditional log here would fire at the full 100 ms cadence forever.
    debugger:debug("command file: %d new command(s) (lastId=%d)", #pending, lastCommandId)
  end

  local newLast = lastCommandId
  for _, cmd in ipairs(pending) do
    handler(cmd)
    if cmd.id > newLast then
      newLast = cmd.id
    end
  end

  return newLast
end
