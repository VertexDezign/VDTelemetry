package net.vertexdezign.vdt

import net.vertexdezign.vdt.model.Implement
import net.vertexdezign.vdt.model.LoaderCylinderRole
import net.vertexdezign.vdt.model.LoaderJoint
import net.vertexdezign.vdt.model.Vehicle
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The loader aspects (mod version 30, issue #169), against the captures in
 * `examples/json/telemetry/vanilla/loader/`: a front loader with a shovel (on the ground, then raised
 * and tipped) and with a pallet fork (on the ground, then over a pallet), a wheel loader, a
 * telehandler and a skid steer — all base game, singleplayer.
 *
 * Three rounds of capture. The first had no `loaderCylinders` at all (the mod called
 * `getMovingToolState` as a vehicle method, and it is not one). The second had them running backwards —
 * 0.99 for an arm on the ground — which is the engine's own direction. The front-loader and telehandler
 * files are the third, taken after the mod turned lift and tilt round.
 */
class LoaderModelTest {
  private fun capture(name: String): Vehicle {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
      val candidate = File(dir, "examples/json/telemetry/vanilla/loader/$name.json")
      if (candidate.exists()) return VdtParser.parseJson(candidate.readText()).vehicle!!
      dir = dir.parentFile
    }
    error("Could not locate loader capture $name from ${File(".").absolutePath}")
  }

  private fun Vehicle.tools(): List<Implement> {
    val out = mutableListOf<Implement>()
    fun walk(list: List<Implement>) {
      list.forEach {
        if (it.loaderTool != null) out += it
        walk(it.implement)
      }
    }
    walk(implement)
    return out
  }

  private fun toolOf(name: String) = capture(name).tools().single()

  @Test
  fun theReadingIsOnTheToolAndNotOnTheLoaderItHangsFrom() {
    // Tractor -> loader -> shovel. The loader hangs on the tractor by `attachableFrontloader`, which is
    // a loader joint too, and must not be mistaken for the tool's.
    val rig = capture("frontLoader_shovel_ground")
    assertNull(rig.loaderTool)
    val loader = rig.implement.single()
    assertNull(loader.loaderTool)
    assertEquals("Albutt Universalschaufel", loader.implement.single().name)
    assertEquals(listOf("Albutt Universalschaufel"), rig.tools().map { it.name })
  }

  @Test
  fun everyLoaderKindIsFoundByItsJointAndNotByItsTypeName() {
    // The skid steer's vehicle type is `teleHandler` and the wheel loader's is `tractor`: a switch on
    // the type name would have named both wrong. The joint does not.
    assertEquals(LoaderJoint.FRONTLOADER, toolOf("frontLoader_shovel_ground").loaderTool!!.joint)
    assertEquals(LoaderJoint.FRONTLOADER, toolOf("frontLoader_palletFork_ground").loaderTool!!.joint)
    assertEquals(LoaderJoint.WHEEL_LOADER, toolOf("wheelLoader").loaderTool!!.joint)
    assertEquals(LoaderJoint.TELEHANDLER, toolOf("telehandler").loaderTool!!.joint)
    assertEquals("teleHandler", capture("skidSteer").type)
    assertEquals(LoaderJoint.SKID_STEER, toolOf("skidSteer").loaderTool!!.joint)
  }

  @Test
  fun aShovelFlatOnTheGroundReadsNearLevelAndNearZero() {
    // The raw root-node angle happens to land within a degree of flat on this shovel. That is a fact
    // about this model, not a rule: the reference exists because nothing guarantees it.
    val t = toolOf("frontLoader_shovel_ground").loaderTool!!
    assertEquals(-0.69f, t.pitch)
    assertEquals(0.001f, t.distance)
  }

  @Test
  fun aRaisedAndTippedShovelReadsNoseDownAndHigh() {
    val t = toolOf("frontLoader_shovel_raisedTipped").loaderTool!!
    assertEquals(-66.65f, t.pitch)
    assertEquals(2.439f, t.distance)
  }

  @Test
  fun aTelehandlerForkPointingIntoTheSkyReadsSteeplyNoseUpAndHigh() {
    // Full lift and full extension, the fork following the boom. 10.1 m is past the 10 m reach the mod
    // first copied from Tool Inclination Helper, under which this capture had no distance at all.
    val t = toolOf("telehandler").loaderTool!!
    assertEquals(65.65f, t.pitch)
    assertEquals(10.146f, t.distance)
  }

  private fun Vehicle.travel(role: LoaderCylinderRole): Float {
    // A telehandler's cylinders are its own; a front loader's are on the loader hitched to the tractor.
    val cylinders = loaderCylinders.ifEmpty { implement.single().loaderCylinders }
    return cylinders.single { it.role == role }.travel
  }

  @Test
  fun liftReadsOneAtTheTopAndTiltZeroTippedOut() {
    // The engine's own 0..1 runs the other way on all three captured loaders; the mod turns it round.
    val ground = capture("frontLoader_shovel_ground")
    assertEquals(0.009f, ground.travel(LoaderCylinderRole.LIFT))
    val tipped = capture("frontLoader_shovel_raisedTipped")
    assertEquals(1f, tipped.travel(LoaderCylinderRole.LIFT))
    assertEquals(0f, tipped.travel(LoaderCylinderRole.TILT))
    // The level shovel sits well toward curled-back, because "level" is relative to an arm on the
    // ground: tilt travel is not an angle against the horizon, which is what loaderTool is for.
    assertEquals(0.708f, ground.travel(LoaderCylinderRole.TILT))

    val tele = capture("telehandler")
    assertEquals(0.998f, tele.travel(LoaderCylinderRole.LIFT))
    assertEquals(1f, tele.travel(LoaderCylinderRole.TELESCOPE))
  }

  @Test
  fun theForkOverAPalletReadsTheGapAboveThePallet() {
    // The same fork, on the ground and then lifted over a pallet. 10.8 cm is the gap to the pallet, with
    // the arm at 43% of its lift: a ray that went past the pallet to the ground would read far more.
    assertEquals(0.004f, toolOf("frontLoader_palletFork_ground").loaderTool!!.distance)
    assertEquals(0.429f, capture("frontLoader_palletFork_overPallet").travel(LoaderCylinderRole.LIFT))
    assertEquals(0.108f, toolOf("frontLoader_palletFork_overPallet").loaderTool!!.distance)
  }

  @Test
  fun theSelectedMachineIsTheToolInEveryCapture() {
    // What the ISOBUS panel's auto-pick follows: in all seven the game has the tool selected, not the
    // loader or the tractor, so the loader screen opens on the node that carries the reading.
    listOf(
      "frontLoader_shovel_ground",
      "frontLoader_shovel_raisedTipped",
      "frontLoader_palletFork_ground",
      "frontLoader_palletFork_overPallet",
      "wheelLoader",
      "telehandler",
      "skidSteer",
    ).forEach { assertEquals(true, toolOf(it).selection?.selected, it) }
  }

  @Test
  fun theCylindersAreOnWhateverOwnsThem() {
    // A front loader's lift and tilt are on the loader, not the tractor; a telehandler's are on the
    // machine itself, with the boom's telescope between them. A pallet fork brings a cylinder of its own.
    val frontRig = capture("frontLoader_palletFork_ground")
    assertTrue(frontRig.loaderCylinders.isEmpty())
    val loader = frontRig.implement.single()
    assertEquals(listOf(LoaderCylinderRole.LIFT, LoaderCylinderRole.TILT), loader.loaderCylinders.map { it.role })
    assertEquals(listOf("AXIS_FRONTLOADER_TOOL2"), loader.implement.single().loaderCylinders.map { it.axis })

    val tele = capture("telehandler")
    assertEquals(
      listOf(LoaderCylinderRole.LIFT, LoaderCylinderRole.TELESCOPE, LoaderCylinderRole.TILT),
      tele.loaderCylinders.map { it.role },
    )
  }

  @Test
  fun noCaptureHasAReferenceSoNoneHasALevel() {
    // Taken before "set level" existed. Without a reference there is no inclination to show -- a raw
    // root-node angle must never stand in for one.
    val shovel = toolOf("frontLoader_shovel_ground").loaderTool!!
    assertNull(shovel.reference)
    assertNull(shovel.inclination)
    assertNull(shovel.height)
  }

  @Test
  fun aReferenceIsSubtractedFromBothReadings() {
    val tool =
      VdtParser.parseJson(
        """{"version":"30","vehicle":{"name":"x","loaderTool":{"joint":"FRONTLOADER","pitch":-48.5,""" +
          """"distance":2.3,"reference":{"pitch":1.5,"distance":0.3}}}}""",
      ).vehicle!!.loaderTool!!
    assertEquals(-50f, tool.inclination)
    assertEquals(2f, tool.height!!, 1e-4f)
  }

  @Test
  fun aReferenceTakenOverNothingZeroesTheAngleOnly() {
    val tool =
      VdtParser.parseJson(
        """{"version":"30","vehicle":{"name":"x","loaderTool":{"pitch":2,"distance":1.2,"reference":{"pitch":2}}}}""",
      ).vehicle!!.loaderTool!!
    assertEquals(0f, tool.inclination)
    assertEquals(1.2f, tool.height)
  }

  @Test
  fun anUnknownJointOrRoleDoesNotLoseTheReading() {
    val v =
      VdtParser.parseJson(
        """{"version":"30","vehicle":{"name":"x","loaderTool":{"joint":"HOVERCRAFT","pitch":1},""" +
          """"loaderCylinders":[{"role":"WINCH","axis":"AXIS_FRONTLOADER_X","travel":0.5}]}}""",
      ).vehicle!!
    val tool = v.loaderTool!!
    assertNull(tool.joint)
    assertEquals(1f, tool.pitch)
    assertEquals(LoaderCylinderRole.AUX, v.loaderCylinders.single().role)
  }
}
