package net.vertexdezign.vdt.app.panels

import net.vertexdezign.vdt.ControlTarget
import net.vertexdezign.vdt.model.Implement
import net.vertexdezign.vdt.model.Mixer
import net.vertexdezign.vdt.model.Schema
import net.vertexdezign.vdt.model.SchemaJoint
import net.vertexdezign.vdt.model.Selection
import net.vertexdezign.vdt.model.Vehicle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A joint at the parent's edge — `x = 1` is what every machine in every capture exports. */
private fun joint(invertX: Boolean = false, lift: Float = 0f) =
  SchemaJoint(x = 1f, y = 0f, rotation = 0f, invertX = invertX, liftedOffsetX = 0f, liftedOffsetY = lift)

private fun schema(vararg joints: SchemaJoint) = Schema(name = "IMPLEMENT", attacherJoint = joints.toList())

private fun tractor(vararg implements: Implement, joints: Array<SchemaJoint> = arrayOf(joint())) = Vehicle(
  name = "Tractor",
  schema = Schema(name = "VEHICLE", attacherJoint = joints.toList()),
  implement = implements.toList(),
)

/** A linear chain of [depth] implements, each hitched to the one before it. */
private fun chain(depth: Int): Implement {
  var tail = Implement(name = "L$depth", schema = schema(), jointDescIndex = 1)
  for (level in depth - 1 downTo 1) {
    tail = Implement(name = "L$level", schema = schema(joint()), jointDescIndex = 1, implement = listOf(tail))
  }
  return tail
}

/**
 * The rig diagram's arithmetic, away from Compose.
 *
 * This is the failure mode the whole layout has: a wrong offset does not throw, it draws a plausible
 * rig with a machine on the wrong end. The assertions are deliberately *relative* — behind, in front,
 * higher — rather than absolute coordinates, because the box size is a calibration constant that may
 * be retuned, and a test that pins it would fail for a change that is not a bug.
 */
class RigSchemaTest {
  @Test
  fun aRigWithoutASchemaIsStillDrawn() {
    // `schemaOverlay` is only assigned when a machine's XML declares it, so a modded vehicle can
    // legitimately have none — and the game then draws no diagram at all, because it has no
    // silhouette to draw. We have one generic box either way, so the machines are still placed:
    // ahead or behind by their position, which is the same fact RigSlotPanel works from.
    val rig =
      Vehicle(
        name = "Tractor",
        implement =
        listOf(
          Implement(name = "Plough", position = "BACK"),
          Implement(name = "Weight", position = "FRONT"),
        ),
      )
    val nodes = layoutRig(rig)
    assertEquals(listOf("Tractor", "Plough", "Weight"), nodes.map { it.machine.name })

    val root = nodes.first { it.isRoot }
    assertTrue(nodes.first { it.machine.name == "Plough" }.x < root.x, "BACK goes behind")
    assertTrue(nodes.first { it.machine.name == "Weight" }.x > root.x, "FRONT goes ahead")
  }

  @Test
  fun aNestedMachineWithNoPositionFallsInBehind() {
    // Only top-level implements carry FRONT/BACK; deeper ones report nothing, and a hitch chain runs
    // backwards.
    val rig =
      Vehicle(
        name = "Tractor",
        implement =
        listOf(
          Implement(name = "Dolly", position = "BACK", implement = listOf(Implement(name = "Trailer"))),
        ),
      )
    val nodes = layoutRig(rig)
    val dolly = nodes.first { it.machine.name == "Dolly" }
    assertTrue(nodes.first { it.machine.name == "Trailer" }.x < dolly.x)
  }

  @Test
  fun aTractorAloneIsOneNode() {
    val nodes = layoutRig(tractor())
    assertEquals(1, nodes.size)
    assertEquals(RIG_ROOT_ID, nodes[0].id)
    assertTrue(nodes[0].isRoot)
  }

  @Test
  fun aRearImplementSitsBehindTheTractorAndAFrontOneAhead() {
    // The two arms of the baseX branch, and the only thing on the diagram a driver would notice
    // immediately if it were backwards. invertX is what the game uses to tell the ends apart: the
    // Puma's five joints in the dribble-bar capture are three unmirrored and two mirrored.
    val rig =
      tractor(
        Implement(name = "Plough", schema = schema(), jointDescIndex = 1),
        Implement(name = "Weight", schema = schema(), jointDescIndex = 2),
        joints = arrayOf(joint(invertX = false), joint(invertX = true)),
      )
    val nodes = layoutRig(rig)
    val root = nodes.first { it.isRoot }
    val plough = nodes.first { it.machine.name == "Plough" }
    val weight = nodes.first { it.machine.name == "Weight" }

    assertTrue(plough.x < root.x, "an unmirrored joint hangs the implement behind the tractor")
    assertTrue(weight.x > root.x, "a mirrored joint hangs it in front")
  }

  @Test
  fun aJointShortOfTheParentsEdgeMountsTheChildOverIt() {
    // `x = 1` hangs a child flush against the parent; anything less overlaps it. A front loader's
    // attacher reports 0.8 — the only non-1.0 joint in any capture — which is what puts its box a
    // fifth of the way over the tractor's instead of beside it. The diagram draws that overlap on
    // purpose; what stops two identical rectangles reading as one dented box is the halo, not the
    // placement.
    fun rigWithJointAt(jointX: Float) = layoutRig(
      tractor(
        Implement(name = "Loader", position = "FRONT", schema = schema(), jointDescIndex = 1),
        joints = arrayOf(SchemaJoint(x = jointX, invertX = true)),
      ),
    )

    val root = rigWithJointAt(1f).first { it.isRoot }
    // `x = 1` is where every other capture's joints sit: flush against the parent's far edge.
    val flush = rigWithJointAt(1f).first { !it.isRoot }
    val mounted = rigWithJointAt(0.8f).first { !it.isRoot }

    assertTrue(flush.x > root.x, "mirrored, so the child grows forward from the parent")
    // Stated against the flush case rather than against the box width, which is a private calibration
    // this test has no business knowing.
    assertTrue(mounted.x < flush.x, "0.8 starts the child short of the parent's far edge")
    assertTrue(mounted.x > root.x, "but still forward of it, so the two boxes cross rather than swap")
  }

  @Test
  fun theJointIndexIsOneBased() {
    // Lua's indexing, carried across the wire unchanged. Off by one here and every implement on a
    // multi-joint tractor lands on the wrong end -- silently, because index 1 exists too.
    val rig =
      tractor(
        Implement(name = "Front weight", schema = schema(), jointDescIndex = 2),
        joints = arrayOf(joint(invertX = false), joint(invertX = true)),
      )
    val weight = layoutRig(rig).first { !it.isRoot }
    assertTrue(weight.x > layoutRig(rig).first { it.isRoot }.x, "index 2 must select the mirrored joint")
  }

  @Test
  fun anImplementWhoseJointTheParentDoesNotHaveIsDropped() {
    val rig = tractor(Implement(name = "Ghost", schema = schema(), jointDescIndex = 9))
    assertEquals(listOf(RIG_ROOT_ID), layoutRig(rig).map { it.id })
  }

  @Test
  fun aMachineWithNoSchemaKeepsItsSubtree() {
    // Where the game drops such an object and everything behind it, we keep both: the box is the same
    // box regardless, and dropping would orphan machines that did declare a schema. The child is
    // placed by position, since a schema-less parent names no attachment points.
    val rig =
      tractor(
        Implement(
          name = "Unknown",
          position = "BACK",
          jointDescIndex = 1,
          implement = listOf(Implement(name = "Behind it", schema = schema(), jointDescIndex = 1)),
        ),
      )
    assertEquals(listOf("Tractor", "Unknown", "Behind it"), layoutRig(rig).map { it.machine.name })
  }

  @Test
  fun mirroringComposesAsXorDownTheChain() {
    // A mirrored joint below a mirrored joint comes back the right way round. Getting this wrong
    // draws a chain that folds back over itself, which reads as a shorter rig rather than as an error.
    val rig =
      tractor(
        Implement(
          name = "Dolly",
          schema = schema(joint(invertX = true)),
          jointDescIndex = 1,
          implement = listOf(Implement(name = "Trailer", schema = schema(), jointDescIndex = 1)),
        ),
        joints = arrayOf(joint(invertX = true)),
      )
    val nodes = layoutRig(rig)
    assertTrue(nodes.first { it.machine.name == "Dolly" }.invertX)
    assertTrue(!nodes.first { it.machine.name == "Trailer" }.invertX, "true XOR true is false")
  }

  @Test
  fun aRaisedImplementIsNudgedClearOfTheMachineTowingIt() {
    // How the diagram shows raised vs lowered at all. `liftedOffsetY` is a pixel value -- the
    // engine's default is 5 -- which is why it is the one number in the schema that is not a fraction.
    fun rig(lowered: Boolean?) = tractor(
      Implement(name = "Plough", schema = schema(), jointDescIndex = 1, lowered = lowered),
      joints = arrayOf(joint(lift = 5f)),
    )

    val raised = layoutRig(rig(false)).first { !it.isRoot }
    val down = layoutRig(rig(true)).first { !it.isRoot }
    assertTrue(raised.y > down.y, "schema y points up, so the raised implement sits higher")

    // A machine with no lowered state at all sits LEVEL with what is towing it -- a trailer on a ball
    // hitch is not hovering. The game asks getIsLowered(true), so no opinion reads as lowered, and
    // treating null as raised put every trailer on the rig in the air.
    assertEquals(down.y, layoutRig(rig(null)).first { !it.isRoot }.y)
  }

  @Test
  fun raisingAnImplementMovesNothingButTheImplement() {
    // The band measures itself against restY + liftHeadroom, and neither moves with the raise. Fit it
    // to the drawn positions instead and the bounding box grows the moment a plough comes up, which
    // re-centres AND re-scales the whole diagram: the tractor slides down and every box shrinks for a
    // change that happened behind it.
    fun rig(lowered: Boolean) = tractor(
      Implement(name = "Plough", schema = schema(), jointDescIndex = 1, lowered = lowered),
      joints = arrayOf(joint(lift = 5f)),
    )

    val up = layoutRig(rig(false))
    val down = layoutRig(rig(true))

    for (side in listOf(up, down)) {
      val root = side.first { it.isRoot }
      assertEquals(0f, root.restY)
      assertEquals(0f, root.liftHeadroom)
    }
    // The implement's own anchor is identical either way; only where it is drawn differs.
    val plough = { nodes: List<RigNode> -> nodes.first { !it.isRoot } }
    assertEquals(plough(down).restY, plough(up).restY)
    assertEquals(plough(down).liftHeadroom, plough(up).liftHeadroom)
    assertTrue(plough(up).liftHeadroom > 0f, "the room a raise needs is reserved whether or not taken")
    assertTrue(plough(up).y > plough(down).y)
  }

  @Test
  fun theHeadroomAccumulatesDownAChain() {
    // A machine behind a raised one is raised with it, so the room reserved has to cover the whole
    // path rather than one hop.
    val rig =
      tractor(
        Implement(
          name = "Barrel",
          schema = schema(joint(lift = 5f)),
          jointDescIndex = 1,
          implement = listOf(Implement(name = "Dribble bar", schema = schema(), jointDescIndex = 1)),
        ),
        joints = arrayOf(joint(lift = 5f)),
      )
    val nodes = layoutRig(rig)
    val barrel = nodes.first { it.machine.name == "Barrel" }
    val bar = nodes.first { it.machine.name == "Dribble bar" }
    assertEquals(barrel.liftHeadroom * 2, bar.liftHeadroom)
  }

  @Test
  fun theChainStopsSixImplementsDown() {
    // InputHelpDisplay.MAX_SCHEMA_COLLECTION_DEPTH is 5 and the walk starts at 1 on the root's own
    // implements, so six levels are drawn and the seventh is not. Matching the game exactly means our
    // diagram and its diagram give up in the same place.
    val nodes = layoutRig(tractor(chain(8)))
    assertEquals(7, nodes.size, "the root plus six levels")
    assertEquals("L6", nodes.last().machine.name)
  }

  @Test
  fun idsAreAPathSoASelectionSurvivesTheRigChanging() {
    val rig =
      tractor(
        Implement(
          name = "Barrel",
          schema = schema(joint()),
          jointDescIndex = 1,
          implement = listOf(Implement(name = "Dribble bar", schema = schema(), jointDescIndex = 1)),
        ),
      )
    assertEquals(listOf("0", "0/0", "0/0/0"), layoutRig(rig).map { it.id })
  }

  @Test
  fun theDiagramStartsOnWhateverTheGameHasSelected() {
    // The engine mirrors its selection onto every object, and in the dribble-bar capture the selected
    // one is the machine at the *end* of the chain -- exactly the node RigSlotPanel cannot address.
    val rig =
      tractor(
        Implement(
          name = "Barrel",
          schema = schema(joint()),
          jointDescIndex = 1,
          implement =
          listOf(
            Implement(
              name = "Dribble bar",
              schema = schema(),
              jointDescIndex = 1,
              selection = Selection(selected = true),
            ),
          ),
        ),
      )
    assertEquals("Dribble bar", selectedRigNode(layoutRig(rig))?.machine?.name)
  }

  @Test
  fun withNothingSelectedTheDiagramStartsOnTheMachineThatHasSomethingToShow() {
    // The auto-pick from before the diagram existed. A rig the game has not reported a selection for
    // should still open on the mixer rather than on the tractor towing it.
    val rig =
      tractor(
        Implement(name = "Mixer", position = "BACK", schema = schema(), jointDescIndex = 1, mixer = Mixer()),
      )
    assertEquals("Mixer", selectedRigNode(layoutRig(rig))?.machine?.name)
  }

  @Test
  fun withNothingSelectedAndNothingToShowTheDiagramStartsOnTheTractor() {
    assertEquals("Tractor", selectedRigNode(layoutRig(tractor()))?.machine?.name)
  }

  // -------------------------------------------------------------------------
  // Addressing: which node a command can actually name
  // -------------------------------------------------------------------------

  @Test
  fun theTractorAndItsOwnFrontAndRearAreAddressable() {
    val rig =
      tractor(
        Implement(name = "Plough", position = "BACK", schema = schema(), jointDescIndex = 1),
        Implement(name = "Weight", position = "FRONT", schema = schema(), jointDescIndex = 2),
        joints = arrayOf(joint(invertX = false), joint(invertX = true)),
      )
    val nodes = layoutRig(rig)
    assertEquals(ControlTarget.VEHICLE, controlTargetOf(nodes.first { it.isRoot }))
    assertEquals(ControlTarget.BACK, controlTargetOf(nodes.first { it.machine.name == "Plough" }))
    assertEquals(ControlTarget.FRONT, controlTargetOf(nodes.first { it.machine.name == "Weight" }))
  }

  @Test
  fun aMachineDeeperInTheChainIsNotAddressable() {
    // The dribble-bar rig: the Bomech is hitched behind the Kaweco, so the only thing on the
    // tractor's rear attacher is the Kaweco. The panel shows the Bomech and refuses to command it.
    val rig =
      tractor(
        Implement(
          name = "Barrel",
          position = "BACK",
          schema = schema(joint()),
          jointDescIndex = 1,
          implement = listOf(Implement(name = "Dribble bar", schema = schema(), jointDescIndex = 1)),
        ),
      )
    val nodes = layoutRig(rig)
    assertEquals(ControlTarget.BACK, controlTargetOf(nodes.first { it.machine.name == "Barrel" }))
    assertNull(controlTargetOf(nodes.first { it.machine.name == "Dribble bar" }))
  }

  @Test
  fun aNestedRearImplementIsNotTheTractorsRear() {
    // The one that would be silently wrong: `position` is BACK on both, but the deeper one is the
    // *dolly's* rear. vdAI's vdAI*Back moves whatever is on the tractor, so addressing this as BACK
    // would fold, raise or switch off a different machine than the one on screen.
    val rig =
      tractor(
        Implement(
          name = "Dolly",
          position = "BACK",
          schema = schema(joint()),
          jointDescIndex = 1,
          implement = listOf(Implement(name = "Trailer", position = "BACK", schema = schema(), jointDescIndex = 1)),
        ),
      )
    assertNull(controlTargetOf(layoutRig(rig).first { it.machine.name == "Trailer" }))
  }

  @Test
  fun anImplementOnNeitherAttacherIsNotAddressable() {
    // Real captures do this: the Bomech's `position` is the empty string, not FRONT or BACK.
    val rig = tractor(Implement(name = "Odd", position = "", schema = schema(), jointDescIndex = 1))
    assertNull(controlTargetOf(layoutRig(rig).first { !it.isRoot }))
  }

  @Test
  fun aSilhouetteWithNoInvisibleBorderGetsTheEnginesOwnDefault() {
    // The borders are what keeps a three-machine chain from drawing as one long bar: the game's
    // silhouettes carry the padding inside the artwork, ours has to inset the box by the same share.
    // A machine that declares none must still get a gap, so the fallback is the engine's own 0.05.
    val bare = layoutRig(tractor()).first()
    assertEquals(0.05f, bare.borderLeft)
    assertEquals(0.05f, bare.borderRight)

    val declared =
      layoutRig(
        Vehicle(
          name = "Tractor",
          schema = Schema(name = "VEHICLE", borderLeft = 0.2f, borderRight = 0.1f),
        ),
      ).first()
    assertEquals(0.2f, declared.borderLeft)
    assertEquals(0.1f, declared.borderRight)
  }
}
