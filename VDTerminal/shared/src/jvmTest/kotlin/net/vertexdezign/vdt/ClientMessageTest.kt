package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

/**
 * The [ClientMessage.SetCruiseControl] finiteness invariant. Enforced in the type's `init` block so a
 * non-finite speed can neither be constructed nor decoded off the wire — the server writer relies on
 * that instead of screening messages itself.
 */
class ClientMessageTest {
  @Test
  fun `a finite cruise speed is accepted`() {
    assertEquals(15.5f, ClientMessage.SetCruiseControl(CruiseAction.SET_SPEED, speed = 15.5f).speed)
  }

  @Test
  fun `a null speed is accepted (the non-setSpeed actions carry none)`() {
    assertEquals(null, ClientMessage.SetCruiseControl(CruiseAction.ENABLE).speed)
  }

  @Test
  fun `constructing a non-finite cruise speed is rejected`() {
    for (speed in listOf(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN)) {
      assertFailsWith<IllegalArgumentException> {
        ClientMessage.SetCruiseControl(CruiseAction.SET_SPEED, speed = speed)
      }
    }
  }

  @Test
  fun `task write commands round-trip through the wire`() {
    val json = Json { encodeDefaults = true }
    val messages =
      listOf(
        ClientMessage.CompleteTask("group-1", "task-1"),
        ClientMessage.DeleteTask("group-1", "task-2"),
        ClientMessage.CreateTask(
          "group-1",
          TaskInput(detail = "Plow", priority = 2, effort = 3, recurMode = 1, month = 5),
        ),
        ClientMessage.EditTask("group-1", "task-3", TaskInput(detail = "Sow", recurMode = 4, n = 2, month = 3)),
      )
    for (message in messages) {
      val encoded = json.encodeToString(ClientMessage.serializer(), message)
      assertEquals(message, json.decodeFromString(ClientMessage.serializer(), encoded))
    }
  }

  @Test
  fun `a task input outside its documented ranges is rejected`() {
    // These reach TaskListControl.buildStandardTask, where `month` drives the period / nextN maths —
    // an out-of-range value would build a silently malformed task rather than fail. `n` stays
    // unbounded on purpose: the mod's own wizard offers 24 and 36 on top of 1..12.
    assertFailsWith<IllegalArgumentException> { TaskInput(priority = 11) }
    assertFailsWith<IllegalArgumentException> { TaskInput(effort = 0) }
    assertFailsWith<IllegalArgumentException> { TaskInput(recurMode = 5) }
    assertFailsWith<IllegalArgumentException> { TaskInput(month = 13) }
    assertFailsWith<IllegalArgumentException> { TaskInput(n = 0) }
    assertEquals(36, TaskInput(recurMode = 3, n = 36).n)
  }

  @Test
  fun `decoding a task input outside its ranges fails instead of building a malformed task`() {
    assertFails {
      Json.decodeFromString(
        ClientMessage.serializer(),
        """{"type":"createTask","groupId":"g","task":{"detail":"x","month":0}}""",
      )
    }
  }

  @Test
  fun `decoding an overflowing speed fails instead of yielding infinity`() {
    // `1e400` is valid JSON but overflows Float to +Infinity. The decode has to run the init-block
    // require (a hostile client can't smuggle a non-finite speed through), so this must throw rather
    // than silently produce a message with speed == Infinity.
    assertFails {
      Json.decodeFromString(
        ClientMessage.serializer(),
        """{"type":"setCruiseControl","action":"setSpeed","speed":1e400}""",
      )
    }
  }
}
