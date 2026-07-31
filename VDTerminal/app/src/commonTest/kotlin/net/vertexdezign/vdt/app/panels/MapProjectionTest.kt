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
  fun visibilityAllowsTheMarginButNotBeyondIt() {
    val projection = MapProjection(300f, 1f, Offset.Zero)
    assertTrue(projection.isVisible(Offset(150f, 150f), margin = 80f))
    // Off the box but within the slack: a marker anchored here still draws part of itself.
    assertTrue(projection.isVisible(Offset(-79f, 379f), margin = 80f))
    assertFalse(projection.isVisible(Offset(-81f, 150f), margin = 80f))
    assertFalse(projection.isVisible(Offset(150f, 381f), margin = 80f))
  }
}
