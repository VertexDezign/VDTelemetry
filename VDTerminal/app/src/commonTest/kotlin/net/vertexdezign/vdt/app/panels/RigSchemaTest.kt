package net.vertexdezign.vdt.app.panels

import net.vertexdezign.vdt.ControlTarget
import net.vertexdezign.vdt.model.Implement
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
  fun aRigWithoutASchemaDrawsNothing() {
    // Every capture taken before mod version 4 looks like this, and so does any object the engine
    // gave no schemaOverlay. Nothing to hang a diagram off, so there is no diagram.
    assertTrue(layoutRig(Vehicle(name = "Tractor")).isEmpty())
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
  fun aMissingSchemaTakesTheWholeSubtreeWithIt() {
    // The game's own `continue`: an object with no silhouette has no box, so there is nowhere to
    // hang its children -- they go too, rather than being reparented onto the grandparent.
    val rig =
      tractor(
        Implement(
          name = "Unknown",
          jointDescIndex = 1,
          implement = listOf(Implement(name = "Orphan", schema = schema(), jointDescIndex = 1)),
        ),
      )
    assertEquals(listOf(RIG_ROOT_ID), layoutRig(rig).map { it.id })
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

    // Anything that cannot be lowered reports no state at all and takes the nudge, as it does in the
    // game -- `getIsLowered` is simply false there.
    assertEquals(raised.y, layoutRig(rig(null)).first { !it.isRoot }.y)
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
  fun withNothingSelectedTheDiagramStartsOnTheTractor() {
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
