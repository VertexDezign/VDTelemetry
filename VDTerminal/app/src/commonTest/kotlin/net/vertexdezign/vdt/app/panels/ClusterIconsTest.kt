package net.vertexdezign.vdt.app.panels

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The glyphs are hand-written path data, and a typo in one is invisible until a driver notices a
 * lamp that never comes on. These assert the two things that can go wrong silently.
 *
 * The list is written out rather than derived, so it is also the inventory: a glyph missing from it
 * is a glyph nothing checks. The last four are drawn ahead of the maintenance mod that will feed
 * them and are deliberately not [Telltale]s yet.
 */
class ClusterIconsTest {
  private val glyphs: List<Pair<String, ImageVector>> =
    listOf(
      "turnLeft" to ClusterIcons.TurnLeft,
      "turnRight" to ClusterIcons.TurnRight,
      "hazard" to ClusterIcons.Hazard,
      "highBeam" to ClusterIcons.HighBeam,
      "lowBeam" to ClusterIcons.LowBeam,
      "workFront" to ClusterIcons.WorkFront,
      "workRear" to ClusterIcons.WorkRear,
      "beacon" to ClusterIcons.Beacon,
      "parkingBrake" to ClusterIcons.ParkingBrake,
      "diffLockFront" to ClusterIcons.DiffLockFront,
      "diffLockRear" to ClusterIcons.DiffLockRear,
      "awd" to ClusterIcons.Awd,
      "engineWarning" to ClusterIcons.EngineWarning,
      "driveForward" to ClusterIcons.DriveForward,
      "driveReverse" to ClusterIcons.DriveReverse,
      "battery" to ClusterIcons.Battery,
      "temperature" to ClusterIcons.Temperature,
      "generalWarning" to ClusterIcons.GeneralWarning,
      "brakeSystem" to ClusterIcons.BrakeSystem,
      "service" to ClusterIcons.Service,
    )

  private fun paths(node: VectorNode): List<VectorPath> = when (node) {
    is VectorPath -> listOf(node)
    is VectorGroup -> node.flatMap { paths(it) }
  }

  @Test
  fun everyGlyphParsesToRealGeometry() {
    for ((name, icon) in glyphs) {
      val paths = paths(icon.root)
      assertTrue(paths.isNotEmpty(), "$name has no path at all")
      for (path in paths) assertTrue(path.pathData.isNotEmpty(), "$name has a path that parsed to nothing")
    }
  }

  @Test
  fun noTwoGlyphsAreTheSameDrawing() {
    // Two lamps drawn the same are two lamps you cannot tell apart — which is what the front/rear
    // pairs used to be when they borrowed one Material icon between them.
    assertEquals(glyphs.size, glyphs.map { it.second }.distinct().size, "two glyphs draw the same shape")
  }

  @Test
  fun everyLampInTheBandHasOneOfTheseGlyphs() {
    val drawn = glyphs.map { it.second }.toSet()
    for (lamp in Telltale.entries) {
      assertTrue(lamp.icon in drawn, "${lamp.key} draws a glyph the inventory doesn't cover")
    }
  }
}
