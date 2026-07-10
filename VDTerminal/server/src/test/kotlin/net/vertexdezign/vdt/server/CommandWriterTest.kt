package net.vertexdezign.vdt.server

import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.CruiseAction
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
