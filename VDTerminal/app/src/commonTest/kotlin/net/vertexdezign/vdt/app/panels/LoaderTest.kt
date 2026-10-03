package net.vertexdezign.vdt.app.panels

import net.vertexdezign.vdt.model.Implement
import net.vertexdezign.vdt.model.LoaderCylinder
import net.vertexdezign.vdt.model.LoaderCylinderRole
import net.vertexdezign.vdt.model.LoaderJoint
import net.vertexdezign.vdt.model.LoaderReference
import net.vertexdezign.vdt.model.LoaderTool
import net.vertexdezign.vdt.model.Vehicle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The loader screen's logic away from Compose (issue #169): which machines open it, how the rig's
 * halves are joined, and the words the figures are printed in. The rigs are shaped like the captures in
 * `examples/json/telemetry/vanilla/loader/` (which `LoaderModelTest` reads for real).
 */
class LoaderTest {
  private val lift = LoaderCylinder(LoaderCylinderRole.LIFT, "AXIS_FRONTLOADER_ARM", 0.009f)
  private val tilt = LoaderCylinder(LoaderCylinderRole.TILT, "AXIS_FRONTLOADER_TOOL", 0.708f)

  /** John Deere 6M -> 623R -> shovel, as `frontLoader_shovel_ground.json` has it. */
  private fun frontLoader(tool: LoaderTool = LoaderTool(LoaderJoint.FRONTLOADER, pitch = -0.69f, distance = 0.001f)) =
    Vehicle(
      name = "6M",
      implement = listOf(
        Implement(
          name = "623R",
          position = "FRONT",
          loaderCylinders = listOf(lift, tilt),
          implement = listOf(Implement(name = "Shovel", loaderTool = tool)),
        ),
      ),
    )

  @Test
  fun everyPartOfTheLoaderOpensTheScreenAndTheTractorDoesNot() {
    val (tractor, loader, shovel) = rigMachines(frontLoader())
    assertFalse(tractor.hasSection)
    assertTrue(loader.hasSection)
    assertTrue(shovel.hasSection)
  }

  @Test
  fun theRigJoinsTheArmsCylindersToTheToolAndItsPath() {
    val rig = loaderRigOf(rigMachines(frontLoader()).zip(listOf("0", "0/0", "0/0/0")) { m, id -> id to m })!!
    assertEquals("Shovel", rig.tool?.name)
    assertEquals("0/0/0", rig.toolNode)
    assertEquals(listOf(lift, tilt), rig.cylinders)
  }

  @Test
  fun aPinnedTileHasNoPathSoSetLevelHasNothingToAddress() {
    val rig = loaderRigOf(rigMachines(frontLoader()).map { null to it })!!
    assertNull(rig.toolNode)
  }

  @Test
  fun aLoaderWithNoToolIsStillALoader() {
    val bare = Vehicle(name = "6M", implement = listOf(Implement(name = "623R", loaderCylinders = listOf(lift))))
    val rig = loaderRigOf(rigMachines(bare).map { null to it })!!
    assertNull(rig.tool)
    assertEquals(listOf(lift), rig.cylinders)
  }

  @Test
  fun aRigWithoutALoaderHasNone() {
    assertNull(loaderRigOf(rigMachines(Vehicle(name = "Tractor")).map { null to it }))
  }

  @Test
  fun levelIsAWordAndEverythingElseASignedAngle() {
    assertEquals("LEVEL", inclinationLabel(0.4f))
    assertEquals("LEVEL", inclinationLabel(-0.99f))
    assertEquals("+3.2°", inclinationLabel(3.24f))
    // ASCII minus: U+2212 is a tofu box in the wasm build.
    assertEquals("-48.6°", inclinationLabel(-48.57f))
  }

  @Test
  fun heightIsInCentimetresUntilTenMetres() {
    assertEquals("0.00 m", heightLabel(0.001f))
    assertEquals("2.44 m", heightLabel(2.439f))
    assertEquals("-0.01 m", heightLabel(-0.005f))
    // The telehandler at full height: past ten metres a centimetre is noise.
    assertEquals("10.1 m", heightLabel(10.146f))
  }

  @Test
  fun aToolsOwnCylinderIsNamedByItsAxis() {
    assertEquals("Lift", cylinderLabel(lift))
    assertEquals("Tool 2", cylinderLabel(LoaderCylinder(LoaderCylinderRole.AUX, "AXIS_FRONTLOADER_TOOL2", 0.334f)))
  }

  @Test
  fun withoutAReferenceTheRootNodeIsLevelAndTheScreenSaysSo() {
    val rig = loaderRigOf(rigMachines(frontLoader()).map { null to it })!!
    assertEquals(-0.69f, rig.reading?.inclination)
    assertEquals("LEVEL", inclinationLabel(rig.reading!!.inclination))
    assertEquals("on the default level", angleCaption(-0.69f, ownLevel = false))
    assertEquals("nose down, default level", angleCaption(-12f, ownLevel = false))
  }

  @Test
  fun thePlayersLevelReplacesTheDefault() {
    val tool = LoaderTool(pitch = 4.2f, distance = 0.4f, reference = LoaderReference(4.0f, 0.001f))
    val rig = loaderRigOf(rigMachines(frontLoader(tool)).map { null to it })!!
    assertEquals("LEVEL", inclinationLabel(rig.reading!!.inclination))
    assertEquals("on your level", angleCaption(rig.reading!!.inclination, ownLevel = true))
    assertEquals("nose up", angleCaption(6f, ownLevel = true))
  }
}
