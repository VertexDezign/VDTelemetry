package net.vertexdezign.vdt.server

import net.vertexdezign.vdt.ClientMessage
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Writes app -> mod commands to `commands.xml` (a sibling of the telemetry file). The mod polls this
 * file via the engine `XMLFile.load` — it can only *read* files that way, since the FS25 Lua sandbox
 * restricts `io.open` to write mode. That's why the command channel is XML while telemetry is JSON.
 *
 * Each command gets a monotonically increasing id; the mod dedups by id and runs only ids greater
 * than the last it executed. We keep a small ring of recent commands in the file so a missed mod
 * poll doesn't drop intermediate commands, and write temp + atomic rename so the mod never reads a
 * torn file. Ids are seeded from the existing file's max id on startup, so a server restart doesn't
 * reissue ids a still-running mod already executed (which it would then ignore).
 */
class CommandWriter(
  private val path: Path,
  private val ringSize: Int = 16,
) {
  private val log = LoggerFactory.getLogger(CommandWriter::class.java)

  private var nextId = seedNextId()

  // Rendered <command .../> elements, oldest first.
  private val ring = ArrayDeque<String>()

  @Synchronized
  fun submit(message: ClientMessage) {
    val id = nextId++
    ring.addLast(render(id, message))
    while (ring.size > ringSize) ring.removeFirst()
    write()
    log.info("Wrote command id={} {}", id, message)
  }

  private fun render(
    id: Int,
    message: ClientMessage,
  ): String =
    when (message) {
      // Tokens are fixed enum values and ids are ints, so no XML escaping is needed here.
      is ClientMessage.SetLight ->
        """<command id="$id" type="setLight" light="${message.light.token}" on="${message.on}"/>"""
      is ClientMessage.SetTurnLight ->
        """<command id="$id" type="setTurnLight" state="${message.state.token}"/>"""
    }

  private fun write() {
    path.parent?.createDirectories()
    val xml =
      buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"no\"?>\n")
        append("<commands>\n")
        for (element in ring) append("    ").append(element).append("\n")
        append("</commands>\n")
      }
    val tmp = path.resolveSibling("${path.fileName}.tmp")
    Files.writeString(tmp, xml)
    Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  }

  private fun seedNextId(): Int =
    try {
      if (!path.exists()) {
        1
      } else {
        val maxId =
          Regex("""id="(\d+)"""")
            .findAll(path.readText())
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull() ?: 0
        maxId + 1
      }
    } catch (e: Exception) {
      log.warn("Could not seed command id from {}; starting at 1", path, e)
      1
    }
}
