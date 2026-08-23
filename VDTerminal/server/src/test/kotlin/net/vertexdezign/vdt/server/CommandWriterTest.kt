package net.vertexdezign.vdt.server

import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.ControlTarget
import net.vertexdezign.vdt.CruiseAction
import net.vertexdezign.vdt.InvoiceLineInput
import net.vertexdezign.vdt.OutputMode
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
  fun `writes the precision farming rate commands as absolute values`() {
    val path = Files.createTempDirectory("vdt-cmd").resolve("commands.xml")
    val writer = CommandWriter(path)
    writer.submit(ClientMessage.SetSprayAmountAuto(false))
    // The step is the target, not an increment: a `+` tap sends the value it computed from what it
    // rendered, so a dropped or doubled command settles back rather than drifting the rate.
    writer.submit(ClientMessage.SetSprayAmountStep(4))
    val xml = path.readText()
    assertTrue(xml.contains("""type="setSprayAmountAuto" auto="false""""), xml)
    assertTrue(xml.contains("""type="setSprayAmountStep" step="4""""), xml)
  }

  @Test
  fun `writes the ground-layer subscription as a comma-separated set`() {
    val path = Files.createTempDirectory("vdt-cmd").resolve("commands.xml")
    val writer = CommandWriter(path)
    writer.submit(ClientMessage.SetMapLayers(listOf("crops", "growth")))
    assertTrue(path.readText().contains("type=\"setMapLayers\" ids=\"crops,growth\""), path.readText())

    // The empty set is the "nobody is looking" state, not a missing attribute -- the mod parses it as
    // an explicit "sweep nothing".
    writer.submit(ClientMessage.SetMapLayers(emptyList()))
    assertTrue(path.readText().contains("type=\"setMapLayers\" ids=\"\""), path.readText())
  }

  @Test
  fun `writes crop rotation slot edits with int attributes`() {
    val path = Files.createTempDirectory("vdt-cmd").resolve("commands.xml")
    val writer = CommandWriter(path)
    writer.submit(ClientMessage.SetRotationCrop(rotationIndex = 2, slot = 3, state = 5))
    writer.submit(ClientMessage.SetRotationCatchCrop(rotationIndex = 2, slot = 3, catchCropState = 1))
    writer.submit(ClientMessage.RemoveRotationSlot(rotationIndex = 2))
    val xml = path.readText()
    assertTrue(xml.contains("""type="setRotationCrop" rotationIndex="2" slot="3" state="5""""), xml)
    assertTrue(xml.contains("""type="setRotationCatchCrop" rotationIndex="2" slot="3" catchCropState="1""""), xml)
    assertTrue(xml.contains("""type="removeRotationSlot" rotationIndex="2""""), xml)
  }

  @Test
  fun `writes production control commands`() {
    val path = Files.createTempDirectory("vdt-cmd").resolve("commands.xml")
    val writer = CommandWriter(path)
    writer.submit(ClientMessage.SetProductionEnabled("biogas-1", "mist", enabled = false))
    writer.submit(ClientMessage.SetProductionOutputMode("biogas-1", "FERMENTERMANURE", OutputMode.AUTO_DELIVER))
    val xml = path.readText()
    assertTrue(
      xml.contains("""type="setProductionEnabled" pointId="biogas-1" productionId="mist" enabled="false""""),
      xml,
    )
    assertTrue(
      xml.contains(
        """type="setProductionOutputMode" pointId="biogas-1" fillType="FERMENTERMANURE" mode="autoDeliver"""",
      ),
      xml,
    )
  }

  @Test
  fun `writes an object-storage unload command with escaped title`() {
    val path = Files.createTempDirectory("vdt-cmd").resolve("commands.xml")
    CommandWriter(path).submit(
      ClientMessage.UnloadObjectStorage("barn-1", index = 2, title = "Round bale <Straw>", amount = 10),
    )
    val xml = path.readText()
    assertTrue(
      xml.contains(
        """type="unloadObjectStorage" storageId="barn-1" index="2" title="Round bale &lt;Straw&gt;" amount="10"""",
      ),
      xml,
    )
  }

  @Test
  fun `xml-escapes the rotation name`() {
    val path = Files.createTempDirectory("vdt-cmd").resolve("commands.xml")
    CommandWriter(path).submit(ClientMessage.CreateRotation("""Heavy & "wet" <soil>"""))
    val xml = path.readText()
    assertTrue(xml.contains("""name="Heavy &amp; &quot;wet&quot; &lt;soil&gt;""""), xml)
    assertFalse(xml.contains("Heavy & "), "the raw ampersand must not survive: $xml")
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

  @Test
  fun `writes the id-addressed invoice commands`() {
    val path = Files.createTempDirectory("vdt-cmd").resolve("commands.xml")
    val writer = CommandWriter(path)
    writer.submit(ClientMessage.PayInvoice(7))
    writer.submit(ClientMessage.CancelInvoice(8))
    writer.submit(ClientMessage.ValidateProposal(9))
    writer.submit(ClientMessage.RefuseProposal(10))
    val xml = path.readText()
    assertTrue(xml.contains("""type="payInvoice" invoiceId="7""""), xml)
    assertTrue(xml.contains("""type="cancelInvoice" invoiceId="8""""), xml)
    assertTrue(xml.contains("""type="validateProposal" invoiceId="9""""), xml)
    assertTrue(xml.contains("""type="refuseProposal" invoiceId="10""""), xml)
  }

  @Test
  fun `writes createInvoice as an element with child lines`() {
    val path = Files.createTempDirectory("vdt-cmd").resolve("commands.xml")
    // The only command in the channel that carries a list, and so the only one that is not
    // self-closing. The mod reads the children off its own key.
    CommandWriter(path).submit(
      ClientMessage.CreateInvoice(
        farmId = 2,
        lines =
          listOf(
            InvoiceLineInput(workTypeId = 2, quantity = 3.5, fieldId = 12, note = "north field"),
            InvoiceLineInput(workTypeId = 44, quantity = 2.0, price = 1320.0, discount = 0.1),
          ),
      ),
    )
    val xml = path.readText()
    assertTrue(xml.contains("""type="createInvoice" farmId="2" proposal="false">"""), xml)
    assertTrue(xml.contains("""<line workTypeId="2" quantity="3.5" fieldId="12" note="north field"/>"""), xml)
    assertTrue(xml.contains("""<line workTypeId="44" quantity="2" price="1320" discount="0.1"/>"""), xml)
    assertTrue(xml.contains("</command>"), xml)
  }

  @Test
  fun `omits the optional line attributes rather than sending zeros`() {
    val path = Files.createTempDirectory("vdt-cmd").resolve("commands.xml")
    // An absent price means "use the catalogue's", which is not the same as a price of 0 — the mod
    // reads the difference, so the writer must not flatten it.
    CommandWriter(path).submit(
      ClientMessage.CreateInvoice(farmId = 3, lines = listOf(InvoiceLineInput(workTypeId = 2, quantity = 1.0))),
    )
    val xml = path.readText()
    assertTrue(xml.contains("""<line workTypeId="2" quantity="1"/>"""), xml)
  }

  @Test
  fun `writes a large litre quantity as plain digits`() {
    val path = Files.createTempDirectory("vdt-cmd").resolve("commands.xml")
    // Double.toString() would render this as 1.0E7, which the engine's XML float reader would not
    // read back as ten million litres.
    CommandWriter(path).submit(
      ClientMessage.CreateInvoice(farmId = 2, lines = listOf(InvoiceLineInput(workTypeId = 53, quantity = 1.0e7))),
    )
    val xml = path.readText()
    assertTrue(xml.contains("""quantity="10000000""""), xml)
    assertFalse(xml.contains("E7"), "scientific notation must not reach the mod: $xml")
  }

  @Test
  fun `xml-escapes an invoice line note`() {
    val path = Files.createTempDirectory("vdt-cmd").resolve("commands.xml")
    // The one user-typed value on the whole command.
    CommandWriter(path).submit(
      ClientMessage.CreateInvoice(
        farmId = 2,
        lines = listOf(InvoiceLineInput(workTypeId = 2, quantity = 1.0, note = """A & B <tag> "q"""")),
      ),
    )
    val xml = path.readText()
    assertTrue(xml.contains("""note="A &amp; B &lt;tag&gt; &quot;q&quot;""""), xml)
    assertFalse(xml.contains("A & B"), "the raw ampersand must not survive: $xml")
  }

  @Test
  fun `seeds the next id past a createInvoice without tripping over its line attributes`() {
    val path = Files.createTempDirectory("vdt-cmd").resolve("commands.xml")
    CommandWriter(path).submit(
      ClientMessage.CreateInvoice(
        farmId = 2,
        // fieldId/workTypeId end in `Id="..."` with a capital I, so the id-seeding regex cannot
        // mistake one for a command id -- which would otherwise jump the counter to a field number.
        lines = listOf(InvoiceLineInput(workTypeId = 99, quantity = 1.0, fieldId = 4242)),
      ),
    )
    // A fresh writer over the same file re-seeds from the highest command id, which is 1.
    CommandWriter(path).submit(ClientMessage.PayInvoice(1))
    val xml = path.readText()
    assertTrue(xml.contains("""<command id="2" type="payInvoice""""), xml)
  }

  @Test
  fun `writes the machine commands with the attributes the mod parses`() {
    // The attribute names are the contract with the mod's own parse, and a typo fails *silently*
    // there: `xml:getString(key.."#target")` on a misspelled attribute returns nil, the resolver
    // finds nothing, and the command is dropped without a word.
    val path = Files.createTempDirectory("vdt-cmd").resolve("commands.xml")
    val writer = CommandWriter(path)
    writer.submit(ClientMessage.SetPipeState(ControlTarget.BACK, state = 2))
    writer.submit(ClientMessage.SetCoverState(ControlTarget.BACK, state = 0))
    writer.submit(ClientMessage.SetTipSide(ControlTarget.BACK, side = 3))
    writer.submit(ClientMessage.SetDischarging(ControlTarget.VEHICLE, on = true))
    val xml = path.readText()
    assertTrue(xml.contains("""type="setPipeState" target="back" state="2""""), xml)
    assertTrue(xml.contains("""type="setCoverState" target="back" state="0""""), xml)
    assertTrue(xml.contains("""type="setTipSide" target="back" side="3""""), xml)
    assertTrue(xml.contains("""type="setDischarging" target="vehicle" on="true""""), xml)
  }
}
