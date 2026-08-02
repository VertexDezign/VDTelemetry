package net.vertexdezign.vdt.app.panels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.vertexdezign.vdt.model.SweptArea
import net.vertexdezign.vdt.model.Vehicle
import net.vertexdezign.vdt.model.WorkSweep

/**
 * How long a swept polygon is drawn locally before the server's mask is trusted to have it.
 *
 * Comfortably more than the server's publish interval plus a fetch and a decode. Holding it too long
 * costs nothing — it is the same green over the same ground — while dropping it too early would blink
 * the freshest strip off the map for the moment before the raster arrives.
 */
private const val TRAIL_HOLD_MS = 6000L

/**
 * The last few seconds of worked ground, drawn by this dashboard from the telemetry it is already
 * receiving.
 *
 * The coverage layer is accumulated on the server, which is what makes it survive a page reload, read
 * the same on a second dashboard and answer to one reset. What it cannot be is *immediate*: the mask
 * is published as a versioned PNG every couple of seconds, so the strip directly behind the machine —
 * the one part a driver is actually watching — is always the part that is missing.
 *
 * So the durable record stays where it is and this fills the gap in front of it: the same
 * [WorkSweep] the server rasterizes, painted straight onto the map until the raster has it.
 *
 * ### The trail must not overlap the raster
 *
 * Both are drawn translucent, so ground carrying both is not the same green twice — it is 0.6 over
 * 0.6, which composites to 0.84 and reads as a distinctly darker band trailing the machine at a fixed
 * distance and fading as the trail expires. [settle] is what prevents it: when a newly published
 * raster reaches the screen, everything swept before that raster was fetched is handed over to it and
 * stops being drawn here. The trail is then exactly the part the raster does not have yet, and the two
 * meet without either a seam or a gap.
 *
 * [TRAIL_HOLD_MS] is only the backstop for when that handover never comes — a failed fetch, a layer
 * the server stopped publishing — so the trail thins out instead of growing without bound.
 *
 * Local to one dashboard and to one visit to the page — a second browser sees the server's raster and
 * nothing more, which is correct: it has no claim to have watched this pass happen.
 */
internal class CoverageTrail {
  private class Entry(val area: SweptArea, val atMs: Long)

  private val sweep = WorkSweep()
  private var entries: List<Entry> = emptyList()

  /** The polygons to draw right now, newest last. Compose state: writing it redraws the overlay. */
  var areas by mutableStateOf<List<SweptArea>>(emptyList())
    private set

  /**
   * Fold one telemetry sample in and drop whatever the server's mask has had time to record.
   *
   * [nowMs] is any monotonic millisecond clock — it is only ever used for differences, and it is the
   * same one [WorkSweep] measures its own staleness guard against.
   */
  fun advance(vehicle: Vehicle?, terrainSize: Float, nowMs: Long) {
    val swept = sweep.advance(vehicle, terrainSize, nowMs)
    val kept = entries.filter { nowMs - it.atMs < TRAIL_HOLD_MS }
    if (swept.isEmpty() && kept.size == entries.size) return // nothing new, nothing expired
    entries = kept + swept.map { Entry(it, nowMs) }
    areas = entries.map { it.area }
  }

  /**
   * Hand everything swept up to [upToMs] over to the raster that has just reached the screen.
   *
   * Called with the moment that raster's fetch began, not the moment it arrived: the version was
   * announced before the fetch, so the server's mask already contained everything swept up to then,
   * and the ground worked *during* the fetch is still this trail's to draw.
   *
   * The handover is a hair conservative — ground swept between the server taking its snapshot and this
   * dashboard hearing about it is dropped without being in the raster. That window is shorter than a
   * telemetry tick, and the next raster covers it. A gap that thin for one publish interval is a far
   * better trade than the alternative: keeping the overlap means a permanently darker band behind the
   * machine, which is what this replaced.
   */
  fun settle(upToMs: Long) {
    val kept = entries.filter { it.atMs > upToMs }
    if (kept.size == entries.size) return
    entries = kept
    areas = entries.map { it.area }
  }

  /** Drop the trail — the coverage layer was cleared, or this map is no longer the one being drawn. */
  fun clear() {
    sweep.forget()
    entries = emptyList()
    areas = emptyList()
  }
}
