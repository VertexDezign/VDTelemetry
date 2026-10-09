package net.vertexdezign.vdt.app.panels

import net.vertexdezign.vdt.model.FillUnit
import net.vertexdezign.vdt.model.FillUnits
import net.vertexdezign.vdt.model.Implement
import net.vertexdezign.vdt.model.LoaderCylinder
import net.vertexdezign.vdt.model.LoaderCylinderRole
import net.vertexdezign.vdt.model.LoaderJoint
import net.vertexdezign.vdt.model.LoaderReference
import net.vertexdezign.vdt.model.LoaderTool
import net.vertexdezign.vdt.model.LoaderToolKind
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
  fun aClampIsNamedByItsIconAndAnUnnamedToolCylinderIsNot() {
    // The Albutt muck grab's top-hold and a pallet fork's tine spread are both TOOL2; only the icon
    // the author gave the control tells them apart.
    val clamp = LoaderCylinder(LoaderCylinderRole.AUX, "AXIS_FRONTLOADER_TOOL2", 0.6f, icon = "GRABBER_OPEN_CLOSE")
    val spread =
      LoaderCylinder(LoaderCylinderRole.AUX, "AXIS_FRONTLOADER_TOOL2", 0.3f, icon = "WORKING_WIDTH_TRANSLATE_X")
    assertTrue(clamp.isClamp)
    assertFalse(spread.isClamp)
    assertEquals("Clamp", cylinderLabel(clamp))
    assertEquals("Tool 2", cylinderLabel(spread))
  }

  @Test
  fun aShovelGetsATopArmOnlyWhenItHasAClamp() {
    val clamp = LoaderCylinder(LoaderCylinderRole.AUX, "AXIS_FRONTLOADER_TOOL2", 0.6f, icon = "TOOL_OPEN_CLOSE")
    fun rigWith(cylinder: LoaderCylinder) = loaderRigOf(
      listOf(
        null to Implement(name = "623R", loaderCylinders = listOf(lift, tilt)).isoBus(),
        null to Implement(
          name = "Muck grab",
          loaderTool = LoaderTool(LoaderJoint.FRONTLOADER, kind = LoaderToolKind.SHOVEL),
          loaderCylinders = listOf(cylinder),
        ).isoBus(),
      ),
    )!!
    assertEquals(0.6f, rigWith(clamp).clamp?.travel)
    // A high-tip bucket's TOOL2 carries no open/close icon, so it is no clamp and draws none.
    assertNull(rigWith(clamp.copy(icon = null)).clamp)

    val frame = glyphFrame(200f, 200f, false)
    val tip = armTip(frame, 0.5f, 0f)
    assertEquals(1, toolStrokes(frame, tip, 0f, LoaderToolKind.SHOVEL).size)
    assertEquals(2, toolStrokes(frame, tip, 0f, LoaderToolKind.SHOVEL, clamp = 0.6f).size)
  }

  @Test
  fun aHighTipTurnsTheBucketPastItsFrameAndIsNoClamp() {
    // Paladin on a Kubota SVL (issue #175): the root node is the frame, so its pitch misses the tip.
    val tip =
      LoaderCylinder(LoaderCylinderRole.TIP, "AXIS_FRONTLOADER_TOOL2", 0.588f, icon = "TOOL_OPEN_CLOSE", angle = -53f)
    val rig = loaderRigOf(
      listOf(
        null to Implement(
          name = "Paladin",
          loaderTool = LoaderTool(LoaderJoint.SKID_STEER, pitch = -1.6f, kind = LoaderToolKind.SHOVEL),
          loaderCylinders = listOf(tip),
        ).isoBus(),
      ),
    )!!
    assertNull(rig.clamp)
    assertEquals(-54.6f, rig.inclination!!, 0.001f)
    assertEquals("High tip", cylinderLabel(tip))
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

  @Test
  fun noPoseTakesTheSideViewOutOfItsBox() {
    // The bug this guards: a fixed pivot and scale drew a raised arm's bucket over the panel header and
    // the control-group chips. Every corner of the lift, telescope and tool angle, on a wide, a tall and
    // a square canvas, must land inside it.
    for ((w, h) in listOf(400f to 120f, 120f to 400f, 200f to 200f)) {
      for (telescopic in listOf(false, true)) {
        val frame = glyphFrame(w, h, telescopic)
        assertTrue(frame.ground <= h, "ground at ${frame.ground} below a $h canvas")
        for (lift in listOf(0f, 0.5f, 1f)) {
          for (telescope in if (telescopic) listOf(0f, 1f) else listOf(0f)) {
            val tip = armTip(frame, lift, telescope)
            for (angle in listOf(-90f, -45f, 0f, 45f, 90f)) {
              for (kind in LoaderToolKind.entries + null) {
                for (open in listOf(0f, 1f)) {
                  val drawn = toolStrokes(frame, tip, angle, kind, open, clamp = open).flatten() +
                    shovelFill(frame, tip, angle, 1f).orEmpty() + tip + frame.pivot
                  drawn.forEach { p ->
                    val at = "$kind open $open, lift $lift, telescope $telescope, angle $angle on $w x $h"
                    assertTrue(p.x in 0f..w && p.y in 0f..h, "($p) out of the box at $at")
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  @Test
  fun aRaisedArmIsAboveALoweredOneAndANoseUpBucketPointsUp() {
    val frame = glyphFrame(300f, 200f, telescopic = false)
    assertTrue(armTip(frame, 1f, 0f).y < armTip(frame, 0f, 0f).y)
    val tip = armTip(frame, 0.5f, 0f)
    // Facing left: the cutting edge is to the left of the hinge when level, and above it nose-up.
    val edge = { angle: Float -> toolStrokes(frame, tip, angle, LoaderToolKind.SHOVEL)[0][0] }
    assertTrue(edge(0f).x < tip.x)
    assertTrue(edge(30f).y < tip.y)
  }

  @Test
  fun aShovelsFillRisesWithItsLoadAndAForkHasNone() {
    val frame = glyphFrame(300f, 200f, telescopic = false)
    val tip = armTip(frame, 0f, 0f)
    assertNull(shovelFill(frame, tip, 0f, 0f))
    // Level bucket: the fill's top edge is higher (smaller y) the fuller the bucket is.
    val half = shovelFill(frame, tip, 0f, 0.5f)!!
    val full = shovelFill(frame, tip, 0f, 1f)!!
    assertTrue(full[2].y < half[2].y)

    val shovel = Implement(
      name = "Shovel",
      loaderTool = LoaderTool(kind = LoaderToolKind.SHOVEL),
      fillUnits =
      FillUnits(
        listOf(FillUnit(value = 640f, type = "WHEAT", title = "Wheat", capacity = 1000, fillLevelPercentage = 64)),
      ),
    )
    val rig = loaderRigOf(listOf(null to shovel.isoBus()))!!
    assertEquals(64, rig.load?.fillLevelPercentage)
    val fork = Implement(name = "Fork", loaderTool = LoaderTool(kind = LoaderToolKind.FORK))
    assertNull(loaderRigOf(listOf(null to fork.isoBus()))!!.load)
  }
}
