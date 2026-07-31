package net.vertexdezign.vdt.app.panels

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The map's transform, now that every overlay shares one copy of it. What is worth pinning down is
 * that it still agrees with the hand-rolled `norm * side * scale + offset` each draw site used to
 * carry, that it inverts, and that auto-centering really does put the tracked point in the middle at
 * any zoom — the property the whole panel's pan/zoom behaviour rests on.
 */
class MapProjectionTest {
  private fun assertOffsetEquals(expected: Offset, actual: Offset, tolerance: Float = 1e-3f) {
    assertTrue(
      (actual.x - expected.x) * (actual.x - expected.x) + (actual.y - expected.y) * (actual.y - expected.y) <=
        tolerance * tolerance,
      "expected $expected but was $actual",
    )
  }

  @Test
  fun projectsTheSameWayTheDrawSitesUsedTo() {
    val projection = MapProjection(side = 800f, scale = 2.5f, offset = Offset(-120f, 40f))
    // The literal formula the tap hit-test, the player marker and the overlay canvas each had.
    val norm = Offset(0.25f, 0.75f)
    val expected = Offset(norm.x * 800f * 2.5f - 120f, norm.y * 800f * 2.5f + 40f)
    assertOffsetEquals(expected, projection.toScreen(norm))
    assertOffsetEquals(expected, projection.toScreen(norm.x, norm.y))
    assertEquals(2000f, projection.factor)
  }

  @Test
  fun toNormInvertsToScreen() {
    val projection = MapProjection(side = 512f, scale = 0.75f, offset = Offset(33f, -17f))
    val norm = Offset(0.1f, 0.9f)
    assertOffsetEquals(norm, projection.toNorm(projection.toScreen(norm)))
  }

  @Test
  fun centeringPutsThePointInTheMiddleAtAnyZoom() {
    val norm = Offset(0.3f, 0.62f)
    for (scale in listOf(0.25f, 1f, 4f, 16f)) {
      val projection = MapProjection(600f, scale, MapProjection.centeredOn(norm, 600f, scale))
      assertOffsetEquals(Offset(300f, 300f), projection.toScreen(norm))
    }
  }

  @Test
  fun zoomingKeepsTheFocalPointPinned() {
    val projection = MapProjection(400f, 1f, Offset(10f, -5f))
    val focal = Offset(120f, 260f)
    // The point under the fingers before the zoom...
    val normUnderFocal = projection.toNorm(focal)
    val zoomed = MapProjection(400f, 3.2f, zoomedOffset(projection.offset, focal, 1f, 3.2f))
    // ...is still under them after it.
    assertOffsetEquals(focal, zoomed.toScreen(normUnderFocal))
  }

  @Test
  fun courseUpTurnsTheMapAboutTheVehicleAnchor() {
    // The vehicle sits two thirds down; the map turns about it, so its own screen position is the one
    // thing rotation must not move — otherwise the machine slides around its own display.
    val anchor = Offset(300f, 396f)
    val vehicle = Offset(0.4f, 0.7f)
    val projection =
      MapProjection(
        side = 600f,
        scale = 4f,
        offset = MapProjection.anchoredAt(vehicle, anchor, 600f, 4f),
        rotationDeg = -137f,
        pivot = anchor,
      )
    assertOffsetEquals(anchor, projection.toScreen(vehicle), tolerance = 0.05f)
  }

  @Test
  fun aQuarterTurnPutsWhatWasEastAtTheTopOfTheScreen() {
    // Heading 90° (east) means rotationDeg -90: a point due east of the vehicle — +x, screen right —
    // has to end up straight above it.
    val anchor = Offset(100f, 100f)
    val projection = MapProjection(200f, 1f, Offset.Zero, rotationDeg = -90f, pivot = anchor)
    assertOffsetEquals(Offset(100f, 60f), projection.rotate(Offset(140f, 100f)))
  }

  @Test
  fun toNormStillInvertsToScreenWhenTurned() {
    val projection = MapProjection(512f, 2.5f, Offset(-40f, 90f), rotationDeg = 213f, pivot = Offset(256f, 338f))
    val norm = Offset(0.62f, 0.18f)
    // This is what the tap hit-test relies on: a finger on a rotated map still lands on the field
    // whose label is under it.
    assertOffsetEquals(norm, projection.toNorm(projection.toScreen(norm)), tolerance = 1e-4f)
  }

  @Test
  fun aDragMovesTheMapAlongTheFinger() {
    // Pan is a direction, not a place: the pivot must not enter into it, and on a turned map the
    // delta has to come back through the rotation before it reaches the (unturned) offset.
    val projection = MapProjection(400f, 1f, Offset.Zero, rotationDeg = 90f, pivot = Offset(200f, 260f))
    val finger = Offset(10f, 0f)
    val unrotated = projection.unrotateVector(finger)
    assertOffsetEquals(Offset(0f, -10f), unrotated)
    // Feeding that back through the projection reproduces the finger's own movement on screen.
    val before = projection.toScreen(0.5f, 0.5f)
    val after = projection.copy(offset = projection.offset + unrotated).toScreen(0.5f, 0.5f)
    assertOffsetEquals(finger, after - before)
  }

  @Test
  fun northUpIsUntouched() {
    // The default has to be bit-identical to the pre-rotation behaviour, since every existing page
    // is on it.
    val projection = MapProjection(300f, 2f, Offset(11f, -7f))
    val point = Offset(120f, 240f)
    assertOffsetEquals(point, projection.rotate(point))
    assertOffsetEquals(point, projection.unrotate(point))
    assertOffsetEquals(Offset(5f, 9f), projection.unrotateVector(Offset(5f, 9f)))
  }

  @Test
  fun visibilityAllowsTheMarginButNotBeyondIt() {
    val projection = MapProjection(300f, 1f, Offset.Zero)
    assertTrue(projection.isVisible(Offset(150f, 150f), margin = 80f))
    // Off the box but within the slack: a marker anchored here still draws part of itself.
    assertTrue(projection.isVisible(Offset(-79f, 379f), margin = 80f))
    assertFalse(projection.isVisible(Offset(-81f, 150f), margin = 80f))
    assertFalse(projection.isVisible(Offset(150f, 381f), margin = 80f))
  }
}
