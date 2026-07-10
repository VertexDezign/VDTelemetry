package net.vertexdezign.vdt.server

import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.CruiseAction
import net.vertexdezign.vdt.TaskInput
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandWriterTest {
  @Test
  fun `writes a finite cruise speed`() {
    val path = Files.createTempDirectory("vdt-cmd").resolve("commands.xml")
    CommandWriter(path).submit(ClientMessage.SetCruiseControl(CruiseAction.SET_SPEED, speed = 15.5f))
    assertTrue(path.readText().contains("""type="setCruiseControl" action="setSpeed" speed="15.5""""))
  }

  @Test
  fun `speed-less cruise actions are unaffected`() {
    val path = Files.createTempDirectory("vdt-cmd").resolve("commands.xml")
    val writer = CommandWriter(path)
    writer.submit(ClientMessage.SetCruiseControl(CruiseAction.ENABLE))
    writer.submit(ClientMessage.SetCruiseControl(CruiseAction.DISABLE))
    assertEquals(2, Regex("<command ").findAll(path.readText()).count())
  }

  @Test
  fun `writes complete and delete task commands with their ids`() {
    val path = Files.createTempDirectory("vdt-cmd").resolve("commands.xml")
    val writer = CommandWriter(path)
    writer.submit(ClientMessage.CompleteTask("group-1", "task-1"))
    writer.submit(ClientMessage.DeleteTask("group-1", "task-2"))
    val xml = path.readText()
    assertTrue(xml.contains("""type="completeTask" groupId="group-1" taskId="task-1""""), xml)
    assertTrue(xml.contains("""type="deleteTask" groupId="group-1" taskId="task-2""""), xml)
  }

  @Test
  fun `xml-escapes user text in task commands`() {
    val path = Files.createTempDirectory("vdt-cmd").resolve("commands.xml")
    // A detail with all five special chars would otherwise produce a file the mod's XMLFile.load
    // rejects (silently dropping the command).
    CommandWriter(path).submit(ClientMessage.CreateTask("g&1", TaskInput(detail = """A & B <tag> "q" 'x'""")))
    val xml = path.readText()
    assertTrue(xml.contains("""detail="A &amp; B &lt;tag&gt; &quot;q&quot; &apos;x&apos;""""), xml)
    assertTrue(xml.contains("""groupId="g&amp;1""""), xml)
    assertFalse(xml.contains("A & B"), "the raw ampersand must not survive: $xml")
  }
}
