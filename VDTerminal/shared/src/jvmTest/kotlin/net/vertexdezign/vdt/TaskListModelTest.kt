package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.model.TaskListData
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Decodes the committed `examples/json/taskList` fixtures through the real server path
 * ([VdtParser.parseTaskList]) and asserts the field mapping, the omitted-`groups` case, and a
 * lossless JSON round-trip — the taskList channel's half of the mod↔Kotlin contract.
 */
class TaskListModelTest {
  private val json = Json { encodeDefaults = true }

  private fun example(name: String): String {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
      val candidate = File(dir, "examples/json/taskList/$name")
      if (candidate.exists()) return candidate.readText()
      dir = dir.parentFile
    }
    error("Could not locate examples/json/taskList/$name from ${File(".").absolutePath}")
  }

  private fun assertRoundTrips(data: TaskListData) {
    val encoded = json.encodeToString(TaskListData.serializer(), data)
    val decoded = json.decodeFromString(TaskListData.serializer(), encoded)
    assertEquals(data, decoded, "JSON round-trip should be lossless")
  }

  @Test
  fun parsesBasicTaskList() {
    val data = VdtParser.parseTaskList(example("basic.json"))

    assertEquals("1", data.version)
    assertEquals(2, data.groups.size)

    val fieldwork = data.groups[0]
    assertEquals("Spring Fieldwork", fieldwork.name)
    assertEquals(1, fieldwork.type)
    assertEquals(1, fieldwork.farmId)
    assertEquals(2, fieldwork.tasks.size)

    val fertilize = fieldwork.tasks[0]
    assertEquals("Fertilize north fields", fertilize.detail)
    assertEquals("Fertilize north fields", fertilize.description)
    assertEquals(1, fertilize.type)
    assertEquals(3, fertilize.period)
    assertEquals(2, fertilize.effort)
    assertTrue(fertilize.shouldRecur)
    assertEquals(1, fertilize.recurMode)
    assertTrue(fertilize.active)

    assertTrue(!fieldwork.tasks[1].active, "the fences task is scheduled but not due")

    // Auto husbandry task: empty detail, but a resolved description and its own type/recur.
    val livestock = data.groups[1]
    assertEquals(2, livestock.effortMultiplier)
    val cowFood = livestock.tasks.single()
    assertEquals("", cowFood.detail)
    assertEquals("Cow Barn Fill total", cowFood.description)
    assertEquals(2, cowFood.type)
    assertEquals(2, cowFood.recurMode)
    assertTrue(cowFood.active)

    // 2 active across both groups
    assertEquals(2, data.groups.sumOf { g -> g.tasks.count { it.active } })

    assertRoundTrips(data)
  }

  @Test
  fun parsesEmptyTaskListWithOmittedGroups() {
    // The mod omits an empty `groups` array (the Json encoder can't distinguish [] from {}), so the
    // Kotlin default must fill in. "Installed but no tasks" — distinct from the mod not being present.
    val data = VdtParser.parseTaskList(example("empty.json"))

    assertEquals("1", data.version)
    assertTrue(data.groups.isEmpty())
    assertRoundTrips(data)
  }

  @Test
  fun taskListRidesTheServerMessageDiscriminator() {
    val data = VdtParser.parseTaskList(example("basic.json"))
    val message: ServerMessage = ServerMessage.TaskList(data)
    val encoded = json.encodeToString(ServerMessage.serializer(), message)

    assertTrue(encoded.contains("\"type\":\"taskList\""), "expected the taskList discriminator in $encoded")
    val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
    assertEquals(message, assertNotNull(decoded as? ServerMessage.TaskList))
  }
}
