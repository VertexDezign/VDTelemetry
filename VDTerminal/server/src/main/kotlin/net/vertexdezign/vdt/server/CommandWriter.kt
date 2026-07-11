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
 * torn file.
 *
 * Id lifetime tracks the file's lifetime. The mod deletes `commands.xml` at the start of every game
 * session (and zeroes its watermark); when [submit] then finds the file gone, it treats that as a
 * new session — resetting the id counter to 1 and dropping the stale ring (whose high ids would
 * otherwise replay against the mod's freshly-zeroed watermark). This also keeps ids from growing
 * without bound across sessions. On *server* startup (not per write) ids are seeded from the
 * existing file's max, so a server restart mid-session doesn't reissue ids the mod already ran.
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
    // The mod deletes commands.xml at game start; if it's gone, this is a new session — restart ids
    // from 1 and drop the stale ring so its old high ids can't replay against the mod's zeroed
    // watermark. Also what keeps ids from growing without bound across sessions.
    if (!path.exists()) {
      nextId = 1
      ring.clear()
      log.info("Command file gone (new session) — resetting ids")
    }
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
      // Tokens are fixed enum values and ids are ints, so the fixed-shape commands below need no
      // escaping. The TaskList commands do — they carry user-typed ids/detail — so those attribute
      // values go through esc() (the mod reads via XMLFile.load, which unescapes on its side).
      is ClientMessage.SetLight -> {
        """<command id="$id" type="setLight" light="${message.light.token}" on="${message.on}"/>"""
      }

      is ClientMessage.SetTurnLight -> {
        """<command id="$id" type="setTurnLight" state="${message.state.token}"/>"""
      }

      is ClientMessage.SetLowered -> {
        """<command id="$id" type="setLowered" target="${message.target.token}" on="${message.on}"/>"""
      }

      is ClientMessage.SetFolded -> {
        """<command id="$id" type="setFolded" target="${message.target.token}" on="${message.on}"/>"""
      }

      is ClientMessage.SetActivated -> {
        """<command id="$id" type="setActivated" target="${message.target.token}" on="${message.on}"/>"""
      }

      is ClientMessage.SetMotorState -> {
        """<command id="$id" type="setMotorState" on="${message.on}"/>"""
      }

      is ClientMessage.SetCruiseControl -> {
        val speedAttr = message.speed?.let { " speed=\"$it\"" } ?: ""
        """<command id="$id" type="setCruiseControl" action="${message.action.token}"$speedAttr/>"""
      }

      is ClientMessage.SetGpsLinesVisible -> {
        """<command id="$id" type="setGpsLinesVisible" on="${message.on}"/>"""
      }

      is ClientMessage.CompleteTask -> {
        """<command id="$id" type="completeTask" groupId="${esc(message.groupId)}" taskId="${esc(message.taskId)}"/>"""
      }

      is ClientMessage.DeleteTask -> {
        """<command id="$id" type="deleteTask" groupId="${esc(message.groupId)}" taskId="${esc(message.taskId)}"/>"""
      }

      is ClientMessage.CreateTask -> {
        """<command id="$id" type="createTask" groupId="${esc(message.groupId)}"${taskAttrs(message.task)}/>"""
      }

      is ClientMessage.EditTask -> {
        """<command id="$id" type="editTask" groupId="${esc(
          message.groupId,
        )}" taskId="${esc(message.taskId)}"${taskAttrs(message.task)}/>"""
      }

      // CropRotation writes. Only createRotation carries user text (name → escaped); the rest are ints.
      is ClientMessage.SetRotationCrop -> {
        """<command id="$id" type="setRotationCrop" rotationIndex="${message.rotationIndex}" slot="${message.slot}" state="${message.state}"/>"""
      }

      is ClientMessage.SetRotationCatchCrop -> {
        """<command id="$id" type="setRotationCatchCrop" rotationIndex="${message.rotationIndex}" slot="${message.slot}" catchCropState="${message.catchCropState}"/>"""
      }

      is ClientMessage.AddRotationSlot -> {
        """<command id="$id" type="addRotationSlot" rotationIndex="${message.rotationIndex}"/>"""
      }

      is ClientMessage.RemoveRotationSlot -> {
        """<command id="$id" type="removeRotationSlot" rotationIndex="${message.rotationIndex}"/>"""
      }

      is ClientMessage.CreateRotation -> {
        """<command id="$id" type="createRotation" name="${esc(message.name)}"/>"""
      }

      is ClientMessage.DeleteRotation -> {
        """<command id="$id" type="deleteRotation" rotationIndex="${message.rotationIndex}"/>"""
      }
    }

  /** The shared `TaskInput` attributes for createTask / editTask (detail is user text → escaped). */
  private fun taskAttrs(task: net.vertexdezign.vdt.TaskInput): String =
    """ detail="${esc(task.detail)}" priority="${task.priority}" effort="${task.effort}"""" +
      """ recurMode="${task.recurMode}" n="${task.n}" month="${task.month}""""

  /** XML-escape an attribute value. `&` first so the entities it introduces aren't re-escaped. */
  private fun esc(value: String): String =
    value
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&apos;")

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
